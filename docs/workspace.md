# Running the workspace on its own machine

The default `compose.yaml` runs the workspace service next to the bot, and that is a reasonable
place for it: the container drops every capability it does not need, mounts nothing from the host,
carries none of the bot's secrets, and installs an egress policy an unprivileged workspace cannot
undo. What it cannot change is that a kernel bug leading out of that container leads onto the host
where `.env` and the database live.

Moving the service to a machine of its own fixes exactly that, and it is the single largest
improvement available here — larger than any hardening applied inside one host. An escape then
lands somewhere holding no bot token, no conversation history, and nothing else of yours.

This is optional. Skip it and everything still works.

## What you need

- A second machine reachable from the bot's. A VM is enough; nothing else should run on it.
- A **private** link between the two. Anything that keeps the API off the public internet works:
  a hypervisor-internal bridge, a cloud provider's private network, or a WireGuard tunnel between
  hosts in different places.
- Docker on the second machine.

## Setting it up

**1. Give both machines an interface on the private link.** On Proxmox this is a Linux bridge with
no physical port and no gateway — `vmbr1`, say — added as a second NIC to both VMs, with static
addresses such as `10.10.10.1` for the bot and `10.10.10.2` for the workspaces. Traffic between
them then never leaves the hypervisor and no tunnel is needed.

**2. Prepare the workspaces directory** on the second machine, ideally on a disk of its own:

```bash
sudo mkdir -p /srv/workspaces
```

Keeping it on a separate disk is what makes the machine disposable. Re-image the host on a
schedule and any persistence someone managed to leave behind — a cron entry, a modified binary —
goes with it, while everyone's files stay.

**3. Start the service there**, with a secret and the private address to listen on:

```bash
WORKSPACE_BIND=10.10.10.2 \
WORKSPACE_TOKEN=$(openssl rand -hex 32) \
docker compose -f compose.workspace.yaml up -d
```

Put both values in a `.env` next to the file so they survive a restart. `WORKSPACE_IMAGE` points
both the supervisor and every workspace container at a build of your own instead of the published
one. `WORKSPACE_DIR` overrides the directory if `/srv/workspaces` is not where the disk is mounted — it is passed to the service
*and* used as the mount path on both sides of the colon, because a workspace container's mount is
resolved by the engine on the host rather than inside the supervisor that asked for it.

This file runs `WORKSPACE_ISOLATION=container`: every workspace gets a container of its own, from
the same image, holding nothing but that workspace's directory. The supervisor never runs a command
itself here — it only starts, execs into, and tears down containers — which is why it can drop
almost every capability while each workspace still gets its own network policy and its own
`WORKSPACE_MEM` / `WORKSPACE_CPUS` / `WORKSPACE_PIDS_LIMIT`.

It reaches the engine through the socket mounted into it. **Reaching that socket is reaching this
host**, which is the whole reason for the separate machine: there is nothing here to take. If the
host runs rootless podman instead, point `WORKSPACE_SOCKET` at its socket — its API is
docker-compatible, so the client baked into the image drives it unchanged, and `WORKSPACE_ENGINE`
only needs changing if you put podman's own CLI in an image of your own — and an escape then lands
on an unprivileged account rather than root. Expect to solve uid mapping first: this design gives
each workspace its own uid on the host, and rootless podman maps container uids into a subuid
range instead. That path is not tested.

Once it works, `WORKSPACE_RUNTIME=runsc` puts every workspace behind gVisor — a userspace kernel
between the workspace and the host's, so an escape needs a bug in gVisor rather than in Linux. Install
`runsc`, register it with the engine (`runsc install`, then restart it), and set the variable. Measured
cost on this workload: a warm command went from 28 ms to 38 ms and an `npm install` plus build was
unchanged, so the overhead is not a reason to avoid it.

**gVisor and `WORKSPACE_NETWORK` interact.** Its network stack has no iptables, so a workspace under
`runsc` cannot install its own egress policy and the container refuses to start. That refusal is
correct, and the answer is not to weaken it: set `WORKSPACE_NETWORK=external` *only* once the machine's
own egress is filtered from outside — the firewall rules below — and the container then knows the job
is done elsewhere.

`kata` gives each workspace a real kernel instead, and needs nested virtualisation on the machine.

**4. Point the bot at it.** In the bot's `.env`:

```dotenv
WORKSPACE_URL=http://10.10.10.2:8080
WORKSPACE_TOKEN=<the same secret>
```

Then remove the `vusan-workspace` service from the bot machine's `compose.yaml`, or simply never
start it: `docker compose up -d vusan`.

## Updating it later

Update both images together. The bot ships the API and the tool descriptions the service answers to,
so a half-updated pair produces failures that read like anything but a version mismatch.

On the machine running the workspaces:

```bash
docker compose -f compose.workspace.yaml pull
docker ps -aq --filter name=vusan-ws- | xargs -r docker rm -f
docker compose -f compose.workspace.yaml up -d
```

**The middle line is the one that is easy to miss.** A workspace container keeps the image it was
started from, and an idle one lives for `WORKSPACE_IDLE_MINUTES` — so without it the supervisor
updates and the workspaces do not, for up to an hour. They are disposable by design: the files are on
the volume and survive being removed, and the next command starts a fresh container.

`WORKSPACE_IMAGE` in that machine's `.env` decides which image is pulled, and is also what every
workspace container is started from, so a tag changes in one place rather than two.

## Locking the machine down

Two rules matter more than the rest, and both belong **outside** the VM. Rules inside it are
removed by anyone who reaches root in it; rules on the hypervisor or the provider's firewall are
not.

- **Block every local range outbound.** `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `169.254.0.0/16` (which is where cloud metadata credentials live), `100.64.0.0/10`. On Proxmox:
  Datacenter → the VM → Firewall → a rule per range, direction `out`, action `DROP`. This also
  stops the workspace machine from reaching the bot machine, while the bot still reaches it —
  replies to the bot's own connections are `ESTABLISHED` and are accepted before these rules.

  Two things on the machine itself would otherwise be caught by those ranges. Give it a **public
  resolver** rather than the one on the local network — its names are not this machine's business
  and a public one needs no exception. And if the private link is inside one of the ranges (a
  `10.10.10.0/24` bridge is inside `10.0.0.0/8`), let that subnet through *before* the drops, or
  the machine cannot answer the bot at all.
- **Expose nothing inbound but the API, and only on the private link.** The compose file already
  binds to `WORKSPACE_BIND` rather than every interface; the firewall should agree.

Keep the kernel on this machine patched aggressively. It is the boundary now, and patching it
cannot break the bot.

## What it still does not change

A container per workspace is a boundary between people, not a boundary against a determined escape.
Every workspace container on this machine is started by the same engine and, unless
`WORKSPACE_RUNTIME` says otherwise, shares the host kernel with the others — so a kernel bug still
leads out of one and onto this machine, which is why nothing valuable may live here and why the
machine is worth re-imaging on a schedule.

Workspace directories are owned by unprivileged uids and mounted one per container, but they all sit
on this host's filesystem: something that escapes onto the host reaches every one of them. Only the
runtime changes that (`runsc`, `kata`), not the arrangement.

`WORKSPACE_ISOLATION=shared` remains available here and is what the bot's own machine runs. It needs
no socket at all, and on a machine that already holds nothing, the gap between the two is much
narrower than it is next to the bot.
