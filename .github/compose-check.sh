#!/bin/bash
# resolves every deployment the repository ships, in both layouts, from throwaway environment files.
# nothing here starts a container. it catches a compose file that no longer resolves — a variable
# renamed out from under a `:?`, a service dropped from an override, a broken interpolation — and
# asserts what the layouts promise: distinct projects, every service configured by its own `.env` on
# one machine too, and no credential reaching the nginx that faces the internet.
#
# run it from the repository root. it writes a `.env` beside each compose file, so it belongs in CI or
# a scratch checkout, not on top of a configured deployment.

set -euo pipefail

for f in .env services/sites/.env; do
    if [ -e "$f" ]; then
        echo "refusing to overwrite an existing environment file: $f" >&2
        exit 1
    fi
done

# the quick start: the bot alone from a checkout, before the site host has a file of its own
cp .env.example .env
if [ "$(docker compose config --services)" != vusan ]; then
    echo "::error::a checkout holding only the bot's .env must start the bot and nothing else"
    exit 1
fi

cp services/sites/.env.example services/sites/.env

# the values the examples deliberately leave blank, because only an operator can supply them
sed -i "s/^SITES_TOKEN=.*/SITES_TOKEN=$(openssl rand -hex 32)/" services/sites/.env
sed -i "s/^SITES_DOMAIN=.*/SITES_DOMAIN=example.com/" services/sites/.env

# a setting only the service's own file carries, which the one-machine layout must still read from there
printf '\n%s\n' "SITES_HOST_DIR=./compose-check" >>services/sites/.env

resolved="$(mktemp -d)"
trap 'rm -rf "$resolved"' EXIT

# the service on a machine of its own: its directory has to stand alone, which is how the docs tell
# an operator to deploy it — copy the directory, nothing else.
docker compose -f compose.yaml config --format json >"$resolved/bot.json"
(cd services/sites && docker compose config --format json) >"$resolved/sites.json"

# and both on one machine, through the profile the compose.beside-bot.yaml file declares
COMPOSE_PROFILES=sites docker compose config --format json >"$resolved/all.json"

python3 - "$resolved" <<'PY'
import json, os, sys

def load(name):
    with open(os.path.join(sys.argv[1], f"{name}.json")) as f:
        return json.load(f)

bot, sites, everything = (load(n) for n in ("bot", "sites", "all"))
services = everything["services"]
errors = []

# the two deployments must not collide on a host that runs both
names = [m["name"] for m in (bot, sites)]
if len(set(names)) != 2:
    errors.append(f"the two deployments must each carry a distinct project name, got: {names}")

# beside the bot a service still takes its settings from its own .env, never from the bot's
if not any(v.get("source", "").endswith("/services/sites/compose-check") for v in services["vusan-sites"]["volumes"]):
    errors.append("on one machine vusan-sites ignored SITES_HOST_DIR in services/sites/.env")

# nginx shares the site service's environment file to read SITES_DOMAIN, so services/sites/compose.yaml
# blanks every secret in it by hand. this keeps that list honest: the container serving the internet
# must hold no credential, whatever gets added to services/sites/.env later.
env = services["vusan-sites-nginx"].get("environment") or {}
leaked = sorted(
    k for k, v in env.items()
    if v and any(word in k.upper() for word in ("TOKEN", "SECRET", "PASSWORD", "KEY", "CREDENTIAL"))
)
if leaked:
    errors.append("these reach the internet-facing nginx and must be blanked in "
                  f"services/sites/compose.yaml: {', '.join(leaked)}")

for error in errors:
    print(f"::error::{error}")
sys.exit(1 if errors else 0)
PY

echo "compose: every layout resolves, the service reads its own .env, nginx holds no credential"
