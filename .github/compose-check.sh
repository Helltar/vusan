#!/bin/bash
# Parses every deployment the repository ships, in both topologies, from throwaway environment
# files. Nothing here starts a container; it catches a compose file that no longer resolves — a
# variable renamed out from under a `:?`, a service dropped from an override, a broken interpolation
# — and it fails if a credential reaches the container that faces the internet.
#
# Run it from the repository root. It writes a `.env` beside each compose file, so it belongs in CI or
# a scratch checkout, not on top of a configured deployment.

set -euo pipefail

for f in .env workspace/.env sites/.env; do
    if [ -e "$f" ]; then
        echo "refusing to overwrite an existing environment file: $f" >&2
        exit 1
    fi
    cp "$f.example" "$f"
done

# the values the examples deliberately leave blank, because only an operator can supply them
sed -i "s/^WORKSPACE_TOKEN=.*/WORKSPACE_TOKEN=$(openssl rand -hex 32)/" workspace/.env
sed -i "s/^SITES_TOKEN=.*/SITES_TOKEN=$(openssl rand -hex 32)/" sites/.env
sed -i "s/^SITES_DOMAIN=.*/SITES_DOMAIN=example.com/" sites/.env

# one service on a machine of its own: each directory has to stand alone, which is how the docs
# tell an operator to deploy it — copy the directory, nothing else.
docker compose -f compose.yaml config --quiet
(cd workspace && docker compose config --quiet)
(cd sites && docker compose config --quiet)

# and everything on one machine, through the profiles compose.override.yaml declares
docker compose config --quiet
COMPOSE_PROFILES=workspace,sites docker compose config --quiet

# the three deployments must not collide on a host that runs more than one of them
names="$(docker compose -f compose.yaml config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')
$(cd workspace && docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')
$(cd sites && docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')"
if [ "$(echo "$names" | sort -u | wc -l)" -ne 3 ]; then
    echo "::error::the three deployments must each carry a distinct project name, got: $names"
    exit 1
fi

# nginx shares the site service's environment file to read SITES_DOMAIN, so sites/compose.yaml
# blanks every secret in it by hand. This is what keeps that list honest: the container serving the
# internet must hold no credential, whatever gets added to sites/.env later.
resolved="$(mktemp)"
trap 'rm -f "$resolved"' EXIT
COMPOSE_PROFILES=sites docker compose config --format json >"$resolved"
python3 - "$resolved" <<'PY'
import json, sys

env = json.load(open(sys.argv[1]))["services"]["vusan-sites-nginx"].get("environment") or {}
leaked = sorted(
    k for k, v in env.items()
    if v and any(word in k.upper() for word in ("TOKEN", "SECRET", "PASSWORD", "KEY", "CREDENTIAL"))
)
if leaked:
    print(f"::error::these reach the internet-facing nginx and must be blanked in "
          f"sites/compose.yaml: {', '.join(leaked)}")
    sys.exit(1)
print(f"nginx environment carries no credential ({len(env)} variables checked)")
PY

echo "compose: all deployments parse, project names distinct"
