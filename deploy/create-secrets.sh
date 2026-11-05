#!/usr/bin/env bash
# Creates the Docker secrets of the Swarm stack (nfv_*) on the server from the local machine and writes their
# current names into docker-compose.yml, see Docs/deployment.md#secrets.
# Usage (Git Bash, Linux, macOS): bash deploy/create-secrets.sh ADMIN_USER@HOST [SSH_PORT]
# Asks for each secret a value (hidden). Empty input keeps the secret unchanged, a value creates nfv_<name>_v1 or
# the next version (secrets are immutable). Needs an account that may run docker on the server (not "deploy").
set -euo pipefail
fail() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }
[[ $# == 1 || $# == 2 ]] || fail 'Aufruf: bash deploy/create-secrets.sh ADMIN_USER@HOST [SSH_PORT]'
target=$1
port=${2:-22}
[[ $port =~ ^[0-9]+$ ]] || fail 'Ungültiger SSH-Port.'
compose="$(cd "$(dirname "$0")/.." && pwd)/docker-compose.yml"
[[ -f $compose ]] || fail "docker-compose.yml nicht gefunden: $compose"
for cmd in ssh base64 sed; do
    command -v "$cmd" >/dev/null || fail "Benötigtes Programm fehlt: $cmd"
done

# Order and hints of the secrets, must match the secrets section of docker-compose.yml
secrets=(paypal_client_id paypal_client_secret idp_signing_key postgres_password openai_api_key liteapi_api_key)
declare -A hints=(
    [paypal_client_id]='Client ID der PayPal-App'
    [paypal_client_secret]='Secret der PayPal-App'
    [idp_signing_key]='RSA-Key (PKCS#8, Base64 in einer Zeile) oder "generate"; ein neuer Key meldet alle Nutzer und MCP-Clients ab'
    [postgres_password]='Passwort oder "generate"; wirkt nur bei leerem Volume, sonst zusätzlich ALTER USER nfv PASSWORD ...'
    [openai_api_key]='OpenAI API Key, "-" für leer (Funktion aus)'
    [liteapi_api_key]='LiteAPI Key, "-" für leer (Funktion aus)'
)

ssh_cmd=(ssh -p "$port" -o ConnectTimeout=20 "$target")
printf 'Lese vorhandene Secrets von %s ...\n' "$target"
existing=$("${ssh_cmd[@]}" "docker secret ls --format '{{.Name}}'") \
    || fail 'Secrets konnten nicht gelesen werden (SSH-Zugang, Docker-Rechte, Swarm-Manager?).'

# Highest version N of nfv_<name>_vN on the server, 0 if none
latest_version() {
    local line max=0
    while IFS= read -r line; do
        if [[ $line =~ ^nfv_${1}_v([0-9]+)$ ]] && (( 10#${BASH_REMATCH[1]} > max )); then
            max=$((10#${BASH_REMATCH[1]}))
        fi
    done <<< "$existing"
    echo "$max"
}

generate() {
    command -v openssl >/dev/null || fail 'openssl fehlt, Wert bitte selbst eingeben.'
    case $1 in
        idp_signing_key) openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null | grep -v -- ----- | tr -d '\r\n' ;;
        postgres_password) openssl rand -base64 24 | tr -d '\r\n' ;;
        *) fail "Für $1 kann kein Wert erzeugt werden." ;;
    esac
}

declare -A current=()
payload=''
printf '\nLeer lassen = unverändert. Eingabe ist verdeckt.\n\n'
for name in "${secrets[@]}"; do
    version=$(latest_version "$name")
    if (( version > 0 )); then
        current[$name]="nfv_${name}_v${version}"
    elif grep -qx "nfv_${name}" <<< "$existing"; then
        current[$name]="nfv_${name}"
    else
        current[$name]=''
    fi
    printf '%s (aktuell: %s)\n  %s\n' "$name" "${current[$name]:-fehlt}" "${hints[$name]}"
    read -rsp '  Wert: ' value
    printf '\n'
    [[ -z $value ]] && continue
    case $value in
        generate) value=$(generate "$name") ;;
        -) value='' ;;
    esac
    new="nfv_${name}_v$((version + 1))"
    # One line per secret: name and Base64 value, sent on stdin so values never appear in arguments
    payload+="$new $(printf '%s' "$value" | base64 | tr -d '\r\n')"$'\n'
    current[$name]=$new
    printf '  -> wird angelegt: %s\n' "$new"
done

if [[ -n $payload ]]; then
    printf '\nLege Secrets an ...\n'
    remote='set -euo pipefail
while read -r name value; do
    [[ -n $name ]] || continue
    [[ $name =~ ^nfv_[a-z_]+_v[0-9]+$ ]] || { echo "Ungültiger Name: $name" >&2; exit 1; }
    printf %s "$value" | base64 -d | docker secret create "$name" - > /dev/null
    echo "  angelegt: $name"
done'
    "${ssh_cmd[@]}" "bash -c '$remote'" <<< "$payload" || fail 'Anlegen fehlgeschlagen, docker-compose.yml bleibt unverändert.'
fi

# Point the "name:" of each secret in docker-compose.yml to its current version (keeps CRLF line endings)
for name in "${secrets[@]}"; do
    [[ -n ${current[$name]} ]] || continue
    sed -E "s/^([[:space:]]*name: )nfv_${name}(_v[0-9]+)?(\r?)$/\1${current[$name]}\3/" "$compose" > "$compose.tmp"
    mv "$compose.tmp" "$compose"
done

printf '\nAktuelle Secrets (in docker-compose.yml eingetragen):\n'
missing=0
for name in "${secrets[@]}"; do
    if [[ -n ${current[$name]} ]]; then
        printf '  %-22s %s\n' "$name" "${current[$name]}"
    else
        printf '  %-22s FEHLT, der Deploy schlägt fehl\n' "$name"
        missing=1
    fi
done
printf '\nGeänderte docker-compose.yml committen und auf main pushen, der nächste Deploy nutzt die neuen Secrets.\n'
exit "$missing"
