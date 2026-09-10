#!/bin/bash
# Parses every deployment the repository ships, in both topologies, from throwaway environment
# files. Nothing here starts a container; it catches a compose file that no longer resolves — a
# variable renamed out from under a `:?`, a service dropped from an override, a broken interpolation.
#
# Run it from the repository root. It writes env/*.env and .env, so it belongs in CI or a scratch
# checkout, not on top of a configured deployment.

set -euo pipefail

if [ -e .env ] || [ -e env/vusan.env ] || [ -e env/workspace.env ] || [ -e env/sites.env ]; then
    echo "refusing to overwrite an existing environment file" >&2
    exit 1
fi

for name in vusan workspace sites; do
    cp "env/$name.env.example" "env/$name.env"
done
cp .env.example .env

# the values the examples deliberately leave blank, because only an operator can supply them
sed -i "s/^WORKSPACE_TOKEN=.*/WORKSPACE_TOKEN=$(openssl rand -hex 32)/" env/workspace.env
sed -i "s/^SITES_TOKEN=.*/SITES_TOKEN=$(openssl rand -hex 32)/" env/sites.env
sed -i "s/^SITES_DOMAIN=.*/SITES_DOMAIN=example.com/" env/sites.env .env

# one service on a machine of its own: each file has to stand alone
docker compose --env-file env/vusan.env -f compose.yaml config --quiet
docker compose --env-file env/workspace.env -f compose.workspace.yaml config --quiet
docker compose --env-file env/sites.env -f compose.sites.yaml config --quiet

# and everything on one machine, exactly as .env.example drives it
docker compose config --quiet

# the three deployments must not collide on a host that runs more than one of them
names=$(
    for f in compose.yaml compose.workspace.yaml compose.sites.yaml; do
        docker compose -f "$f" config --format json 2>/dev/null | sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' | head -1
    done
)
if [ "$(echo "$names" | sort -u | wc -l)" -ne 3 ]; then
    echo "::error::the three deployment files must each carry a distinct project name, got: $names"
    exit 1
fi

echo "compose: all deployments parse, project names distinct"
