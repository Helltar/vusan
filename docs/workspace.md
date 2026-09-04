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

Put both values in a `.env` next to the file so they survive a restart.

**4. Point the bot at it.** In the bot's `.env`:

```dotenv
WORKSPACE_URL=http://10.10.10.2:8080
WORKSPACE_TOKEN=<the same secret>
```

Then remove the `vusan-workspace` service from the bot machine's `compose.yaml`, or simply never
start it: `docker compose up -d vusan`.

## Locking the machine down

Two rules matter more than the rest, and both belong **outside** the VM. Rules inside it are
removed by anyone who reaches root in it; rules on the hypervisor or the provider's firewall are
not.

- **Block every local range outbound.** `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `169.254.0.0/16` (which is where cloud metadata credentials live), `100.64.0.0/10`. On Proxmox:
  Datacenter → the VM → Firewall → a rule per range, direction `out`, action `DROP`. This also
  stops the workspace machine from reaching the bot machine, while the bot still reaches it.
- **Expose nothing inbound but the API, and only on the private link.** The compose file already
  binds to `WORKSPACE_BIND` rather than every interface; the firewall should agree.

Keep the kernel on this machine patched aggressively. It is the boundary now, and patching it
cannot break the bot.

## What this does not change yet

Workspaces on this machine are still separated by unix user inside one container, not by a
container each. That means people are separated by file permissions rather than namespaces, and
resource limits are shared rather than per person — `/sys/fs/cgroup` is read-only inside a
container, so a per-workspace CPU or memory limit is not something the shared runner can apply. The
process list is shared too: `ps` shows one person what another is running, though not their files,
their environment, or any way to signal them, and hiding it would cost `CAP_SYS_ADMIN`.

A container per workspace (`WORKSPACE_ISOLATION=container`) is the intended next step and is not
built yet; the service refuses to start if it is asked for. Once it exists it belongs here, on
this machine, where spawning containers does not put the bot's host at stake.
