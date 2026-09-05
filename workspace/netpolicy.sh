#!/bin/bash
# installs the workspace network policy in the Docker host's own namespace, from a short-lived helper
# container. workspace containers hold no capabilities and never see these rules, so nothing running
# inside one can weaken them — which is the whole reason the policy does not live there any more.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

chain="${1:?missing chain}"
bridge="${2:?missing bridge}"
subnet="${3:?missing subnet}"
mbit="${4:-}"
shift 4 || shift $#
blocked=("$@")

[[ "$chain" =~ ^[A-Z][A-Z0-9_]{2,24}$ ]] || { echo "invalid chain name" >&2; exit 1; }
[[ "$bridge" =~ ^[a-zA-Z0-9_.-]{1,15}$ ]] || { echo "invalid bridge name" >&2; exit 1; }
[[ "$subnet" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/(3[0-2]|[12]?[0-9])$ ]] || { echo "invalid subnet" >&2; exit 1; }
[[ -z "$mbit" || "$mbit" =~ ^[1-9][0-9]{0,4}$ ]] || { echo "invalid bandwidth" >&2; exit 1; }
for range in "${blocked[@]}"; do
  [[ "$range" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(/(3[0-2]|[12]?[0-9]))?$ ]] ||
    { echo "invalid blocked range" >&2; exit 1; }
done
ip link show "$bridge" >/dev/null

# the daemon owns DOCKER-USER, but it is only created once it has published a port. never assume it.
iptables -N DOCKER-USER 2>/dev/null || true
iptables -C FORWARD -j DOCKER-USER 2>/dev/null || iptables -I FORWARD 1 -j DOCKER-USER

# both chains are rebuilt from scratch on every start: a controller restart must not stack duplicates,
# and a half-written policy from an interrupted run must not survive into the next one.
for name in "$chain" "${chain}_IN"; do
  iptables -N "$name" 2>/dev/null || true
  iptables -F "$name"
done
iptables -D DOCKER-USER -j "$chain" 2>/dev/null || true
iptables -I DOCKER-USER 1 -j "$chain"
iptables -D INPUT -j "${chain}_IN" 2>/dev/null || true
iptables -I INPUT 1 -j "${chain}_IN"

# a workspace answers nobody: the controller reaches it through Docker, never over the network.
iptables -A "$chain" -d "$subnet" -m conntrack --ctstate NEW -j DROP
# the machine itself offers a workspace nothing, the controller's own API least of all.
iptables -A "${chain}_IN" -i "$bridge" -j DROP

# private space stays unreachable whatever route the packet would take, including this pool's own
# subnet: with inter-container traffic disabled at the bridge, this is the second lock on the door.
iptables -A "$chain" -s "$subnet" -d "$subnet" -j REJECT
for range in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 169.254.0.0/16 \
  172.16.0.0/12 192.0.0.0/24 192.168.0.0/16 198.18.0.0/15 224.0.0.0/4 240.0.0.0/4 "${blocked[@]}"; do
  iptables -A "$chain" -s "$subnet" -d "$range" -j REJECT
done
iptables -A "$chain" -s "$subnet" -p tcp -m multiport --dports 25,465,587 -j REJECT

# name resolution goes to the resolvers the workspace was handed and nowhere else. Docker's embedded
# server forwards from this namespace, so what this catches is a workspace addressing a resolver itself.
for resolver in 1.1.1.1 8.8.8.8; do
  iptables -A "$chain" -s "$subnet" -d "$resolver" -p udp --dport 53 -j RETURN
  iptables -A "$chain" -s "$subnet" -d "$resolver" -p tcp --dport 53 -j RETURN
done
iptables -A "$chain" -s "$subnet" -p udp --dport 53 -j REJECT
iptables -A "$chain" -s "$subnet" -p tcp --dport 53 -j REJECT

# packet and connection churn are counted per workspace address, not for the pool as a whole, so one
# busy workspace cannot spend everyone else's budget.
iptables -A "$chain" -s "$subnet" -m conntrack --ctstate NEW -m hashlimit \
  --hashlimit-name vusan-ws-new --hashlimit-mode srcip \
  --hashlimit-above 100/second --hashlimit-burst 200 -j REJECT
iptables -A "$chain" -s "$subnet" -m hashlimit \
  --hashlimit-name vusan-ws-rate --hashlimit-mode srcip \
  --hashlimit-above 2000/second --hashlimit-burst 4000 -j DROP

# an operator who asked for a bandwidth cap gets one or gets no workspace: shaping the wrong interface,
# or none, would be a limit that silently is not there. this one is the pool's total, not each
# workspace's share, because the bridge is where they meet.
if [[ -n "$mbit" ]]; then
  tc qdisc replace dev "$bridge" root tbf rate "${mbit}mbit" burst 512kb latency 200ms
  tc qdisc del dev "$bridge" ingress 2>/dev/null || true
  tc qdisc add dev "$bridge" handle ffff: ingress
  tc filter add dev "$bridge" parent ffff: protocol ip prio 1 u32 match u32 0 0 \
    police rate "${mbit}mbit" burst 512kb drop
fi
