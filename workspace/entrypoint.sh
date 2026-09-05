#!/bin/bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

case "${1:-supervisor}" in
  supervisor)
    # narrow enough that a controller bug reaches its own state and the docker CLI, and nothing else.
    exec deno run --no-prompt \
      --allow-net=0.0.0.0:8080 \
      --allow-read=/app,/state,/run/workspace-auth,/tmp \
      --allow-write=/state,/run/workspace-auth,/tmp \
      --allow-run=docker \
      --allow-env=WORKSPACE_* \
      --allow-sys=statfs \
      /app/main.ts
    ;;
  workspace)
    /usr/local/bin/netpolicy.sh
    touch /run/workspace-ready
    # only startup can set the firewall; even the namespace's keeper drops every capability.
    exec setpriv --reuid=1000 --regid=1000 --clear-groups \
      --bounding-set=-all --inh-caps=-all --ambient-caps=-all --no-new-privs sleep infinity
    ;;
  *)
    echo "unknown workspace role" >&2
    exit 1
    ;;
esac
