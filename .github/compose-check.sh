#!/bin/bash
# resolves every deployment the repository ships, in both layouts, from throwaway environment files.
# nothing here starts a container. it catches a compose file that no longer resolves — a variable
# renamed out from under a `:?`, a service dropped from an override, a broken interpolation — and
# asserts what the layouts promise: distinct projects, every service configured by its own `.env` on
# one machine too, a workspace controller that shares no network with the site host, and no credential
# reaching the nginx that faces the internet.
#
# run it from the repository root. it writes a `.env` beside each compose file, so it belongs in CI or
# a scratch checkout, not on top of a configured deployment.

set -euo pipefail

for f in .env services/workspace/.env services/sites/.env; do
    if [ -e "$f" ]; then
        echo "refusing to overwrite an existing environment file: $f" >&2
        exit 1
    fi
done

# the quick start: the bot alone from a checkout, before either service has a file of its own
cp .env.example .env
if [ "$(docker compose config --services)" != vusan ]; then
    echo "::error::a checkout holding only the bot's .env must start the bot and nothing else"
    exit 1
fi

cp services/workspace/.env.example services/workspace/.env
cp services/sites/.env.example services/sites/.env

# the values the examples deliberately leave blank, because only an operator can supply them
sed -i "s/^WORKSPACE_TOKEN=.*/WORKSPACE_TOKEN=$(openssl rand -hex 32)/" services/workspace/.env
sed -i "s/^SITES_TOKEN=.*/SITES_TOKEN=$(openssl rand -hex 32)/" services/sites/.env
sed -i "s/^SITES_DOMAIN=.*/SITES_DOMAIN=example.com/" services/sites/.env

# settings only a service's own file carries, which the one-machine layout must still read from there
printf '\n%s\n' "WORKSPACE_IMAGE=compose-check/workspace" >>services/workspace/.env
printf '\n%s\n' "SITES_HOST_DIR=./compose-check" >>services/sites/.env

resolved="$(mktemp -d)"
trap 'rm -rf "$resolved"' EXIT

# each service on a machine of its own: every directory has to stand alone, which is how the docs
# tell an operator to deploy it — copy the directory, nothing else.
docker compose -f compose.yaml config --format json >"$resolved/bot.json"
(cd services/workspace && docker compose config --format json) >"$resolved/workspace.json"
(cd services/sites && docker compose config --format json) >"$resolved/sites.json"

# and everything on one machine, through the profiles the compose.beside-bot.yaml files declare
COMPOSE_PROFILES=workspace,sites docker compose config --format json >"$resolved/all.json"

python3 - "$resolved" <<'PY'
import json, os, sys

def load(name):
    with open(os.path.join(sys.argv[1], f"{name}.json")) as f:
        return json.load(f)

bot, workspace, sites, everything = (load(n) for n in ("bot", "workspace", "sites", "all"))
services = everything["services"]
errors = []

# the three deployments must not collide on a host that runs more than one of them
names = [m["name"] for m in (bot, workspace, sites)]
if len(set(names)) != 3:
    errors.append(f"the three deployments must each carry a distinct project name, got: {names}")

# beside the bot a service still takes its settings from its own .env, never from the bot's
if services["vusan-workspace"]["image"] != "compose-check/workspace":
    errors.append("on one machine vusan-workspace ignored WORKSPACE_IMAGE in services/workspace/.env")
if not any(v.get("source", "").endswith("/services/sites/compose-check") for v in services["vusan-sites"]["volumes"]):
    errors.append("on one machine vusan-sites ignored SITES_HOST_DIR in services/sites/.env")

# the process holding the docker socket publishes nothing beside the bot, and shares no network with
# the containers serving the internet
if services["vusan-workspace"].get("ports"):
    errors.append("on one machine the workspace controller publishes a port")
controller = set(services["vusan-workspace"]["networks"])
for name in ("vusan-sites", "vusan-sites-nginx"):
    if controller & set(services[name]["networks"]):
        errors.append(f"the workspace controller shares a network with {name}")

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

echo "compose: every layout resolves, each service reads its own .env, nginx holds no credential"
