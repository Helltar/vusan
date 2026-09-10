#!/bin/sh
# Authenticated Origin Pulls is a Cloudflare feature: it demands the client certificate Cloudflare
# presents to origins, and nothing else can produce one. It is the right protection when Cloudflare
# is in front, and an impossible requirement when it is not, so it is a switch rather than a fact.
#
# Written as a file the template includes, because nginx templates hold no conditionals. Runs before
# 20-envsubst-on-templates.sh, from the nginx entrypoint's own /docker-entrypoint.d.
set -e

conf=/etc/nginx/origin-pull.conf
ca=/etc/nginx/certs/origin-pull-ca.pem

# on by default: an existing deployment must never lose this because a variable went unset.
case "${SITES_ORIGIN_PULLS:-on}" in
on)
    if [ ! -r "$ca" ]; then
        echo "SITES_ORIGIN_PULLS is on, but $ca is missing or unreadable." >&2
        echo "Put Cloudflare's Authenticated Origin Pulls CA there, or set SITES_ORIGIN_PULLS=off." >&2
        exit 1
    fi
    cat >"$conf" <<EOF
ssl_client_certificate $ca;
ssl_verify_client on;
EOF
    ;;
off)
    # whatever fronts this origin is now the only thing keeping browsers off it directly, and the
    # bearer token is the only thing authenticating a publish. Both are stated in docs/sites.md.
    echo "# SITES_ORIGIN_PULLS=off: no client certificate is required of callers" >"$conf"
    ;;
*)
    echo "SITES_ORIGIN_PULLS must be 'on' or 'off', got '${SITES_ORIGIN_PULLS}'" >&2
    exit 1
    ;;
esac
