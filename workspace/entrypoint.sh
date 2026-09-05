#!/bin/bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

case "${1:-supervisor}" in
  supervisor)
    exec deno run --no-prompt --allow-net --allow-read --allow-write --allow-run=docker --allow-env --allow-sys=statfs /app/main.ts
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
