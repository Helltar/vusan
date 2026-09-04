#!/bin/bash
# Installs the egress policy in this container's own network namespace and then hands off
# to the supervisor. Runs as root; every workspace afterwards runs as an unprivileged uid
# that cannot undo any of this.
#
# Fails closed on purpose: a workspace with unfiltered egress looks exactly like a working
# one until the day it matters, so a rule that will not install stops the container.

set -euo pipefail

NETWORK="${WORKSPACE_NETWORK:-open}"
PORT=8080

# everything that is not the public internet. ::ffff:0:0/96 has no v4 equivalent here —
# IPv6 is simply not configured for a workspace, which removes that whole class of bypass.
PRIVATE_RANGES=(
  10.0.0.0/8 172.16.0.0/12 192.168.0.0/16
  127.0.0.0/8 169.254.0.0/16 100.64.0.0/10
  192.0.0.0/24 198.18.0.0/15 224.0.0.0/4 240.0.0.0/4 0.0.0.0/8
)

fatal() {
  echo "entrypoint: $1" >&2
  exit 1
}

command -v iptables >/dev/null || fatal "iptables is missing from the image"
iptables -L OUTPUT >/dev/null 2>&1 || fatal "cannot manage iptables — the container needs NET_ADMIN and NET_RAW"

# a reply travelling back to whoever called the API is an OUTBOUND packet to a private
# address, so without this it would be dropped by the ranges below and the service would
# answer nobody. it does not weaken them: the first packet of a workspace's own connection
# to a private address is NEW, never ESTABLISHED, and is still dropped.
iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# the supervisor's own API is reachable over loopback from every workspace process, because
# in shared mode they share this network namespace. only uid 0 may talk to it.
iptables -A OUTPUT -p tcp --dport "$PORT" -m owner ! --uid-owner 0 -j REJECT --reject-with tcp-reset

if [[ "$NETWORK" == "none" ]]; then
  iptables -A OUTPUT ! -o lo -m owner ! --uid-owner 0 -j REJECT
  echo "entrypoint: egress disabled for workspaces (WORKSPACE_NETWORK=none)"
else
  # DNS first, and only to the resolvers this container was given: docker's embedded
  # resolver sits on 127.0.0.11 and podman's inside 10/8, both of which the drops below
  # would otherwise take out along with the rest of the local world.
  resolvers=$(awk '/^nameserver/ { print $2 }' /etc/resolv.conf | grep -v ':' || true)
  [[ -n "$resolvers" ]] || fatal "no IPv4 nameserver in /etc/resolv.conf"
  for ns in $resolvers; do
    iptables -I OUTPUT 1 -d "$ns" -p udp --dport 53 -j ACCEPT
    iptables -I OUTPUT 1 -d "$ns" -p tcp --dport 53 -j ACCEPT
  done

  for range in "${PRIVATE_RANGES[@]}"; do
    iptables -A OUTPUT -d "$range" -j DROP
  done

  # outbound mail is the one abuse with a lasting cost — the address of whoever runs this
  # ends up on blocklists — and no legitimate workspace needs it
  iptables -A OUTPUT -p tcp -m multiport --dports 25,465,587 -j REJECT

  # 53 to anything else would reach a resolver on the local network, whose names are not
  # this workspace's business even though its addresses are already unreachable
  iptables -A OUTPUT -p udp --dport 53 -j REJECT
  iptables -A OUTPUT -p tcp --dport 53 -j REJECT

  echo "entrypoint: egress open, ${#PRIVATE_RANGES[@]} local ranges blocked, resolvers [$(echo $resolvers | tr '\n' ' ')]"
fi

# IPv6 gets no rules of its own because a workspace gets no IPv6; if the runtime handed us
# one anyway, close it rather than leave a path around every rule above
if command -v ip6tables >/dev/null && ip6tables -L OUTPUT >/dev/null 2>&1; then
  ip6tables -A OUTPUT ! -o lo -m owner ! --uid-owner 0 -j REJECT || true
fi

mkdir -p "${WORKSPACE_ROOT:-/work}"

exec deno run \
  --allow-net --allow-read --allow-write --allow-run --allow-env \
  /app/main.ts
