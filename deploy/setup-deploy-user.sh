#!/usr/bin/env bash
# Ubuntu Swarm manager: restricted SSH deployment account.
# Usage: sudo bash setup-deploy-user.sh PUBLIC_KEY_FILE [GHCR_USER TOKEN_FILE]
# Replaces deploy's authorized_keys with ONE key; saves a backup first.
# Docker and OpenSSH must already be installed; does not initialize a swarm.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
fail() { printf 'Fehler: %s\n' "$*" >&2; exit 1; }
if [[ $# == 1 && $1 == --help ]]; then
    sed -n '2,6p' "$0"
    exit 0
fi
[[ $# == 1 || $# == 3 ]] || fail 'Aufruf: sudo bash setup-deploy-user.sh PUBLIC_KEY_FILE [GHCR_USER TOKEN_FILE]'
[[ $EUID == 0 ]] || fail 'Bitte mit sudo oder als root ausführen.'
for cmd in adduser usermod getent docker ssh-keygen sshd systemctl runuser python3 flock; do
    command -v "$cmd" >/dev/null || fail "Benötigtes Programm fehlt: $cmd"
done
exec 9>/run/lock/setup-deploy-user.lock
flock -n 9 || fail 'Eine weitere Einrichtung läuft bereits.'
[[ -r $1 ]] || fail 'Public-Key-Datei nicht lesbar.'
[[ -d /etc/ssh/sshd_config.d ]] || fail 'SSH-Konfigurationsverzeichnis fehlt.'
/usr/sbin/sshd -t
# Require Ubuntu's include so the per-user restrictions actually apply.
python3 - <<'PY'
import shlex
from pathlib import Path
lines = Path('/etc/ssh/sshd_config').read_text().splitlines()
for line in lines:
    parts = shlex.split(line, comments=True)
    if not parts:
        continue
    if parts[0].lower() == 'match':
        break
    if parts[0].lower() == 'include' and '/etc/ssh/sshd_config.d/*.conf' in parts[1:]:
        break
else:
    raise SystemExit('Fehler: globales Include /etc/ssh/sshd_config.d/*.conf fehlt.')
if not parts or parts[0].lower() != 'include':
    raise SystemExit('Fehler: globales SSH-Include fehlt.')
PY
[[ $(docker info --format '{{.Swarm.ControlAvailable}}') == true ]] || fail 'Dieser Docker-Daemon ist kein aktiver Swarm-Manager.'
getent group docker >/dev/null || fail 'Docker-Gruppe fehlt.'
if [[ $# == 3 ]]; then
    [[ -n $2 && -s $3 && -r $3 ]] || fail 'GHCR-Benutzer oder Token-Datei fehlt.'
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Only plain public keys are accepted, never pre-existing key options.
python3 - "$1" "$work/public.pub" <<'PY'
import sys
from pathlib import Path
lines = [s.strip() for s in Path(sys.argv[1]).read_text().splitlines() if s.strip()]
if len(lines) != 1:
    raise SystemExit('Fehler: genau einen öffentlichen SSH-Key angeben.')
p = lines[0].split()
if len(p) < 2 or p[0] not in ('ssh-ed25519', 'ssh-rsa', 'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384', 'ecdsa-sha2-nistp521'):
    raise SystemExit('Fehler: unterstützten Public-Key ohne Optionen angeben.')
Path(sys.argv[2]).write_text(' '.join(p[:2]) + ' deployment\n')
PY
ssh-keygen -lf "$work/public.pub" >/dev/null || fail 'Ungültiger Public-Key.'
if getent passwd deploy >/dev/null; then
    [[ $(id -u deploy) != 0 ]] || fail 'deploy darf nicht UID 0 haben.'
    printf 'Vorhandenen Benutzer deploy konfigurieren.\n'
else
    adduser --disabled-password --gecos '' --shell /bin/bash deploy
fi
home_dir=$(getent passwd deploy | cut -d: -f6)
[[ $home_dir == /home/deploy && -d $home_dir && ! -L $home_dir ]] || fail 'Erwartet wird ein reguläres Home-Verzeichnis /home/deploy.'
usermod -aG docker -s /bin/bash deploy
# Disable Unix password authentication while retaining public-key access.
usermod -L deploy

cat > "$work/deploy-stack" <<'WRAPPER'
#!/bin/bash
set -euo pipefail
export PATH=/usr/bin:/bin
# Never evaluate the supplied SSH command as shell code.
if [[ "${SSH_ORIGINAL_COMMAND:-}" =~ ^deploy-stack\ ([a-zA-Z0-9][a-zA-Z0-9_-]*)$ ]]; then
    stack="${BASH_REMATCH[1]}"
else
    echo 'Usage: deploy-stack STACK_NAME' >&2
    exit 1
fi
# Use the local daemon and deploy's registry credentials explicitly.
unset DOCKER_HOST DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_API_VERSION
export DOCKER_CONFIG=/home/deploy/.docker
exec /usr/bin/docker --host unix:///var/run/docker.sock stack deploy --with-registry-auth -c - "$stack"
WRAPPER
bash -n "$work/deploy-stack"
install -o root -g root -m 755 "$work/deploy-stack" /usr/local/bin/deploy-stack

[[ ! -L $home_dir/.ssh ]] || fail '.ssh darf kein Symlink sein.'
install -d -o deploy -g deploy -m 700 "$home_dir/.ssh"
keys="$home_dir/.ssh/authorized_keys"
[[ ! -L $keys ]] || fail 'authorized_keys darf kein Symlink sein.'
backup_dir=$(mktemp -d /root/deploy-setup-backup.XXXXXX)
[[ ! -e $keys ]] || cp -p "$keys" "$backup_dir/authorized_keys"
{ printf 'restrict,command="/usr/local/bin/deploy-stack" '; cat "$work/public.pub"; } > "$work/authorized_keys"
install -o deploy -g deploy -m 600 "$work/authorized_keys" "$keys"

# ForceCommand also restricts other keys; no interactive SSH or forwarding.
conf=/etc/ssh/sshd_config.d/00-deploy-user.conf
[[ ! -L $conf ]] || fail 'SSH-Konfigurationsdatei darf kein Symlink sein.'
[[ ! -e $conf ]] || cp -p "$conf" "$backup_dir/ssh.conf"
cat > "$work/ssh.conf" <<'CONF'
Match User deploy
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    ForceCommand /usr/local/bin/deploy-stack
    DisableForwarding yes
    PermitTTY no
    PermitUserRC no
Match all
CONF
install -o root -g root -m 644 "$work/ssh.conf" "$conf"
if ! /usr/sbin/sshd -t; then
    if [[ -f $backup_dir/ssh.conf ]]; then
        cp -p "$backup_dir/ssh.conf" "$conf"
    else
        rm -f "$conf"
    fi
    fail 'SSH-Konfiguration ungültig; die Konfigurationsdatei wurde zurückgesetzt.'
fi
# Fail if another configuration overrides the forced command.
effective=$(/usr/sbin/sshd -T -C user=deploy,host=localhost,addr=127.0.0.1)
[[ $effective == *'forcecommand /usr/local/bin/deploy-stack'* ]] || fail 'Eine andere SSH-Konfiguration überschreibt ForceCommand; bitte prüfen.'
systemctl reload ssh
runuser -l deploy -c '/usr/bin/docker --host unix:///var/run/docker.sock info --format "{{.Swarm.ControlAvailable}}"'

if [[ $# == 3 ]]; then
    # Input redirection is performed by root; the token stays out of arguments.
    runuser -u deploy -- env HOME=/home/deploy DOCKER_CONFIG=/home/deploy/.docker \
        /usr/bin/docker login ghcr.io -u "$2" --password-stdin < "$3"
fi
printf '\nEinrichtung abgeschlossen. Backup: %s\n' "$backup_dir"
printf '%s\n' 'Remote-Kommando im Workflow: deploy-stack STACK_NAME' \
    'Bei privaten Images ohne optionalen Login: sudo -iu deploy docker login ghcr.io -u GITHUB_USERNAME' \
    'Bestehende AllowUsers/AllowGroups-Regeln müssen deploy zusätzlich zulassen.' \
    'Docker-Zugriff gewährt praktisch Root-Rechte. Keine zusätzlichen sudo-Rechte wurden vergeben.'
