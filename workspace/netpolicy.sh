#!/bin/bash
set -euo pipefail

# the runtime disables ipv6. refuse to run if that invariant changed.
for setting in /proc/sys/net/ipv6/conf/{all,default}/disable_ipv6; do
  [[ "$(<"$setting")" == 1 ]] || { echo "ipv6 must be disabled" >&2; exit 1; }
done

case "${WORKSPACE_NETWORK:-open}" in
  none)
    # docker also supplies --network=none; preserve only this workspace's loopback.
    iptables -A OUTPUT ! -o lo -j REJECT
    ;;
  open)
    iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    iptables -A OUTPUT -o lo -j ACCEPT
    for range in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 169.254.0.0/16 \
      172.16.0.0/12 192.0.0.0/24 192.168.0.0/16 198.18.0.0/15 224.0.0.0/4 240.0.0.0/4; do
      iptables -A OUTPUT -d "$range" -j REJECT
    done
    iptables -A OUTPUT -p tcp -m multiport --dports 25,465,587 -j REJECT
    for resolver in 1.1.1.1 8.8.8.8; do
      iptables -A OUTPUT -d "$resolver" -p udp --dport 53 -j ACCEPT
      iptables -A OUTPUT -d "$resolver" -p tcp --dport 53 -j ACCEPT
    done
    iptables -A OUTPUT -p udp --dport 53 -j REJECT
    iptables -A OUTPUT -p tcp --dport 53 -j REJECT
    ;;
  *)
    echo "WORKSPACE_NETWORK must be open or none" >&2
    exit 1
    ;;
esac
