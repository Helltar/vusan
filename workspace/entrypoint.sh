#!/bin/bash
# One image, two roles. `supervisor` (the default) installs the egress policy in its own network
# namespace and serves the API; in `shared` mode the workspaces then run beside it as unprivileged
# uids that cannot undo any of it. `workspace` is what a per-workspace container runs in `container`
# mode: the same policy, then nothing but a process to hold the namespace open for `exec`.

set -euo pipefail

fatal() {
  echo "entrypoint: $1" >&2
  exit 1
}

# shellcheck source=netpolicy.sh
source /usr/local/bin/netpolicy.sh

case "${1:-supervisor}" in
  supervisor)
    # only `shared` puts workspaces in this namespace. in `container` mode nothing untrusted runs
    # here, and each workspace container installs the policy for itself.
    if [[ "${WORKSPACE_ISOLATION:-shared}" == "shared" ]]; then
      apply_network_policy 8080
    else
      echo "netpolicy: skipped, workspaces get their own namespace (WORKSPACE_ISOLATION=${WORKSPACE_ISOLATION})"
    fi

    mkdir -p "${WORKSPACE_ROOT:-/work}"
    exec deno run --allow-net --allow-read --allow-write --allow-run --allow-env /app/main.ts
    ;;

  workspace)
    apply_network_policy
    # pid 1 of a workspace container: reaps what a killed command orphans, and exits on a signal
    # so that stopping the container is immediate rather than a ten-second timeout
    trap 'exit 0' TERM INT
    while :; do sleep 86400 & wait $!; done
    ;;

  *)
    fatal "unknown role [$1]"
    ;;
esac
