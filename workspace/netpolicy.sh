#!/bin/bash
# The egress policy for one network namespace. Sourced by entrypoint.sh in both roles: the
# supervisor's own container in `shared` mode, where the workspaces live beside it, and each
# workspace container in `container` mode.
#
# Fails closed on purpose: a workspace with unfiltered egress looks exactly like a working one
# until the day it matters, so a rule that will not install stops the container.

apply_network_policy() {
  local network="${WORKSPACE_NETWORK:-open}"
  local guard_port="${1:-}"

  # `external` is for a deployment where a layer below this container already filters egress and
  # this one cannot: gVisor's netstack has no iptables, so everything below would stop the
  # container. It is deliberately not automatic — a policy that turns itself off when it cannot be
  # applied is the exact failure this script exists to prevent. Set it only where something outside
  # the VM enforces the same thing, and say where in the deployment's notes.
  if [[ "$network" == "external" ]]; then
    echo "netpolicy: NOT applied here — WORKSPACE_NETWORK=external says egress is filtered outside this container"
    return 0
  fi

  command -v iptables >/dev/null || fatal "iptables is missing from the image"
  iptables -L OUTPUT >/dev/null 2>&1 ||
    fatal "cannot manage iptables — needs NET_ADMIN and NET_RAW, and a runtime that supports them (gVisor does not: see WORKSPACE_NETWORK=external)"

  # everything that is not the public internet. ::ffff:0:0/96 has no v4 equivalent here — IPv6 is
  # simply not configured for a workspace, which removes that whole class of bypass.
  #
  # 127.0.0.0/8 is deliberately NOT here. Loopback is not a route to anywhere: in `container` mode
  # it is the workspace's own namespace, and in `shared` mode the one thing on it worth protecting
  # is the supervisor's port, which the owner rule below covers precisely. Blocking all of it
  # breaks every tool that talks to itself over TCP — puppeteer to the browser it just launched, a
  # dev server being tested, a language server — which is most of the reason to have a browser here.
  local ranges=(
    10.0.0.0/8 172.16.0.0/12 192.168.0.0/16
    169.254.0.0/16 100.64.0.0/10
    192.0.0.0/24 198.18.0.0/15 224.0.0.0/4 240.0.0.0/4 0.0.0.0/8
  )

  # a reply travelling back to whoever called the API is an OUTBOUND packet to a private address,
  # so without this it would be dropped by the ranges below and the service would answer nobody.
  # it does not weaken them: the first packet of a workspace's own connection to a private address
  # is NEW, never ESTABLISHED, and is still dropped.
  iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

  # only in `shared` mode: the supervisor's API is reachable over loopback from every workspace
  # process, because they share this network namespace. a workspace container has no such neighbour.
  if [[ -n "$guard_port" ]]; then
    iptables -A OUTPUT -p tcp --dport "$guard_port" -m owner ! --uid-owner 0 -j REJECT --reject-with tcp-reset
  fi

  if [[ "$network" == "none" ]]; then
    iptables -A OUTPUT ! -o lo -m owner ! --uid-owner 0 -j REJECT
    echo "netpolicy: egress disabled for workspaces (WORKSPACE_NETWORK=none)"
  else
    # DNS first, and only to the resolvers this container was given: docker's embedded resolver
    # sits on 127.0.0.11 and podman's inside 10/8, both of which the drops below would otherwise
    # take out along with the rest of the local world.
    local resolvers
    resolvers=$(awk '/^nameserver/ { print $2 }' /etc/resolv.conf | grep -v ':' || true)
    [[ -n "$resolvers" ]] || fatal "no IPv4 nameserver in /etc/resolv.conf"
    for ns in $resolvers; do
      iptables -I OUTPUT 1 -d "$ns" -p udp --dport 53 -j ACCEPT
      iptables -I OUTPUT 1 -d "$ns" -p tcp --dport 53 -j ACCEPT
    done

    for range in "${ranges[@]}"; do
      iptables -A OUTPUT -d "$range" -j DROP
    done

    # outbound mail is the one abuse with a lasting cost — the address of whoever runs this ends up
    # on blocklists — and no legitimate workspace needs it
    iptables -A OUTPUT -p tcp -m multiport --dports 25,465,587 -j REJECT

    # 53 to anything else would reach a resolver on the local network, whose names are not this
    # workspace's business even though its addresses are already unreachable
    iptables -A OUTPUT -p udp --dport 53 -j REJECT
    iptables -A OUTPUT -p tcp --dport 53 -j REJECT

    echo "netpolicy: egress open, ${#ranges[@]} local ranges blocked, resolvers [$(echo $resolvers | tr '\n' ' ')]"
  fi

  # IPv6 gets no rules of its own because a workspace gets no IPv6; if the runtime handed us one
  # anyway, close it rather than leave a path around every rule above
  if command -v ip6tables >/dev/null && ip6tables -L OUTPUT >/dev/null 2>&1; then
    ip6tables -A OUTPUT ! -o lo -m owner ! --uid-owner 0 -j REJECT || true
  fi
}
