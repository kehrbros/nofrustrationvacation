# TLS terminating reverse proxy of the Swarm stack, built and pushed by CI (build.yml), see Docs/deployment.md#tls-caddy.
# The Caddyfile is baked into the image because "docker stack deploy" from CI cannot read config files of the runner.
FROM caddy:2.10-alpine
COPY Caddyfile /etc/caddy/Caddyfile
