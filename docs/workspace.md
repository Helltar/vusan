# Workspace deployment and administration

The workspace shell is a **separate deployment**. `compose.yaml` starts the bot alone; this service is
brought up on its own with `compose.workspace.yaml`, and the bot grows the shell tools only once it can
reach it. It runs commands the model writes and holds the Docker socket in order to do that, so it
belongs on a machine that holds nothing else — a small VM or a cheap VPS is enough. Running it beside
the bot is supported and takes the same two values; [Both on one machine](#both-on-one-machine) covers
what changes and what to budget.

It needs a rootful Linux Docker Engine with cgroup v2 and loop-device support. There is one
implementation — Docker containers with bounded home filesystems — and no gVisor, alternate engine or
runtime to choose. What it does once running is described in
[configuration](configuration.md#workspace).

## Setting it up

On the workspace machine, from a checkout of this repository:

```bash
cp env/workspace.env.example env/workspace.env
sed -i "s/^WORKSPACE_TOKEN=.*/WORKSPACE_TOKEN=$(openssl rand -hex 32)/" env/workspace.env
$EDITOR env/workspace.env          # set WORKSPACE_BIND, and any limit you want to change
docker compose --env-file env/workspace.env -f compose.workspace.yaml up -d
```

`env/workspace.env` is the whole configuration for this machine. Compose reads it twice — for the values
it substitutes into the deployment, and as the container's own environment — which is what `--env-file`
is for; every command below carries it. Export `COMPOSE_ENV_FILES=env/workspace.env` once if you would
rather not repeat it. The bot's file stays on the bot's machine and never comes here.

`WORKSPACE_BIND` is the address the API listens on, and it must be one **only the bot can reach** — a
private network or an encrypted tunnel between the two machines. Compose refuses to start without it
rather than defaulting to every interface. To put both on one machine instead, see
[Both on one machine](#both-on-one-machine) below.

`WORKSPACE_TOKEN` is the shared secret; nothing is generated for you, because the two services no longer
share a filesystem. Put the same value, and the address, in the bot's `env/vusan.env`:

```dotenv
WORKSPACE_URL=http://10.10.10.2:8080
WORKSPACE_TOKEN=<the same secret>
```

and restart the bot with `docker compose up -d`. Removing either line takes the tools away again; the
files stay where they are. A bearer token is not encryption: the private transport has to provide that.

Everything else in that file — limits, networking, retention — has a working default; see the
[tuning table](configuration.md#tuning).

At the host or infrastructure firewall, allow API access only from the bot, and prevent a workspace from
reaching private services through a public address that forwards back into the LAN: the container's own
destination check happens before that external NAT. The service installs its own policy on the machine
regardless, and refuses to serve if it cannot prove that policy is in effect.

To build the image from source instead of pulling it:

```bash
docker build -t vusan-workspace:local ./workspace
WORKSPACE_IMAGE=vusan-workspace:local \
  docker compose --env-file env/workspace.env -f compose.workspace.yaml up -d
```

## Both on one machine

This is the supported way to keep one machine, and the reason it is not the recommendation is worth
stating rather than implying: the controller holds the Docker socket and runs shell that a language model
wrote, so anything escaping a container lands where the bot's Telegram token, its database and whatever
else you host already live. On a machine of its own, that same escape costs you a machine with nothing
on it.

What you keep either way is the network boundary. A workspace cannot reach the host, another workspace,
or any other container on that machine: private ranges and the machine itself are refused, and nothing
can open a connection into a workspace. Neighbouring services are protected from the workspaces over the
network — they are not protected from an escape.

Use the Docker bridge gateway as the bind address. Containers can reach it and nothing outside the host
can; `127.0.0.1` does not work, because a container cannot reach the host's loopback.

```bash
cp env/workspace.env.example env/workspace.env
sed -i "s/^WORKSPACE_BIND=.*/WORKSPACE_BIND=172.17.0.1/;
        s/^WORKSPACE_TOKEN=.*/WORKSPACE_TOKEN=$(openssl rand -hex 32)/" env/workspace.env
docker compose --env-file env/workspace.env -f compose.workspace.yaml up -d
```

The two services keep one environment file each even here, and that is the point: the process holding
the Docker socket never sees the bot's token, its model key or its database path.

Then in `env/vusan.env`, with the same secret:

```dotenv
WORKSPACE_URL=http://172.17.0.1:8080
WORKSPACE_TOKEN=<the same secret>
```

and `docker compose up -d`. These stay two separate Compose projects on one host: they share no network
and no volume, `down` on either leaves the other running, and each is pulled and updated on its own.

Budget the machine explicitly, because the service cannot see what else is on it:

- **Memory and CPU.** The pool sizes itself from *half* the host's RAM and cores, counting nothing that
  the bot or your other containers already use. Lower `WORKSPACE_MEMORY_MB` and `WORKSPACE_MAX_ACTIVE`
  until the remainder is comfortable — a wedged host is a worse outcome than a queued command.
- **Disk.** Each home is preallocated, so `WORKSPACE_MAX_HOME_MB` times the number of people who use the
  workspace has to fit alongside everything else on that filesystem, plus the image and
  `WORKSPACE_MIN_FREE_MB` left untouched. On a small VM, halving the home size is usually the right call,
  and it cannot be changed for homes that already exist.
- **Throughput.** The default write and read caps exist for exactly this case: they keep a workspace
  from stalling the database it shares a disk with. Raise them, or set `none`, only on a machine where
  nothing else matters.

## Removing it

Nothing is installed on the machine and nothing is written to its filesystem: the service is containers,
Docker volumes and two firewall chains that live in the kernel. That makes the cleanup short, and the
order matters only in that the controller should stop first.

```bash
docker compose --env-file env/workspace.env -f compose.workspace.yaml down
docker ps -aq --filter label=com.helltar.vusan.workspace=vusan | xargs -r docker rm -f
docker network rm vusan-workspaces
```

That leaves every home exactly where it was, which is the point: this is also what an update or a host
reboot looks like. **To delete people's files as well**, and only then:

```bash
docker volume ls -q --filter label=com.helltar.vusan.workspace=vusan | xargs -r docker volume rm
```

The firewall chains are kernel state, so a reboot clears them and the next start puts them back. On a
machine that keeps running, they linger — harmlessly, since they name a subnet that no longer exists —
until you remove them:

```bash
sudo iptables -D DOCKER-USER -j WS_VUSAN
sudo iptables -D INPUT -j WS_VUSAN_IN
sudo iptables -F WS_VUSAN && sudo iptables -X WS_VUSAN
sudo iptables -F WS_VUSAN_IN && sudo iptables -X WS_VUSAN_IN
```

Substitute your own `WORKSPACE_NAMESPACE`, uppercased, for `VUSAN` in those names. While the service is
running, do not remove them by hand: they are re-read on a slow cadence and put back, and the removal
would only take effect for as long as it takes the controller to notice.

## Storage and updates

With the default namespace, workspace `u123` uses container `vusan-workspace-u123`. Its files live in
`home.ext4` inside the **`vusan-workspace-u123-disk` backing volume**. The controller automatically
attaches this preallocated image and creates `vusan-workspace-u123-home`, a temporary volume wrapper
that mounts the bounded filesystem at `/work`. The same `u123` is used in private chat and every group.

The user container receives only the mounted home, owned by UID 1000. A short-lived trusted storage
helper receives the backing volume and loop-device access; it never runs user commands. Controller job
metadata and bounded logs live separately in Compose's `vusan-workspace-state` volume. The API secret is
configuration, not state: it lives in `env/workspace.env` here and in the bot's own file there.

**Back up the `-disk` volumes and controller state.** Stop the controller first for a consistent copy;
normal shutdown removes the user containers and mount wrappers and detaches their loop devices. Copy
the backing volumes using ordinary Docker-volume backup tooling, then start the controller again.
Backing volumes survive idle expiry, image replacement and `docker compose down`. Do not use volume
pruning as cleanup: an idle person's backing disk looks like an unused Docker volume.

Update the bot and workspace together because their HTTP contract changes together — on this machine:

```bash
docker compose --env-file env/workspace.env -f compose.workspace.yaml pull
docker compose --env-file env/workspace.env -f compose.workspace.yaml up -d
```

The controller removes its own old containers during startup, marks unfinished jobs interrupted and
resolves the configured image to an immutable image ID for new containers. You do not need a separate
container-removal command when upgrading. Files remain in their volumes; processes do not resume.

Keep `WORKSPACE_NAMESPACE` stable. It is recorded in the state volume and cannot be changed there in
place. Each controller on a Docker host needs a unique namespace and its own state volume. The shipped
Compose files also have fixed service container names, so multiple complete deployments require
distinct names in an override.

## Adding system packages

The workspace user cannot run `sudo`; the root filesystem is read-only and disposable. Dependencies
installed into `/work` survive, but a missing system package belongs in a custom image. For example:

```dockerfile
FROM ghcr.io/helltar/vusan-workspace:latest
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
```

Build it on the workspace machine as `vusan-workspace:custom`, set
`WORKSPACE_IMAGE=vusan-workspace:custom` in `env/workspace.env`, and bring the service up again. Both
the controller and its workspace containers then use it. Rebuild custom images when their base is updated. For the
source-build override, edit `workspace/Dockerfile` and use the source-build command above instead.

Installing Java on the host with `sudo apt install` does not put Java inside a workspace. Do not make
manual changes inside a running user container the source of its dependencies.

## Disk limits and the storage emergency

The defaults enforce limits without host quota configuration:

- **Home capacity** — each person gets a preallocated 4 GiB ext4 disk, including filesystem metadata.
  Its fixed block and inode counts stop multi-file writes, preallocation and inode exhaustion at that
  person's boundary. The backing image is not accessible from a workspace.
- **Per-file limit** — `WORKSPACE_MAX_FILE_MB` additionally caps each file a command writes.
- **Disk I/O** — the workspace loop device is selected automatically. Writes are capped at `50mb`
  (`WORKSPACE_WRITE_BPS`) and reads at `100mb` (`WORKSPACE_READ_BPS`); no host device name is needed.
  Both accept `none`. The cap protects a machine shared with other services; on a machine of its own,
  where the fixed home size already bounds the damage, it mostly gets in the way of ordinary work.
- **Host reserve** — new disks reserve their full size only if `WORKSPACE_MIN_FREE_MB` remains available.
  A separate one-second check watches host free bytes/inodes, blocks commands and uploads on pressure,
  and retries stopping live containers. Slow or failed home measurements cannot delay this check.

A full home can be cleaned with Bash or `deleteWorkspaceFile`. The latter removes an exact file or
recursive directory in a fresh offline container, without shell profiles, and can repair inaccessible
owned directories. Cancel a running command first; background processes are stopped by cleanup.

`resetWorkspace` is the blunt instrument next to it: the home disk is dropped and reformatted, so a
workspace that cannot start — including one left behind by an older, unbounded layout — becomes usable
again in one call. Everything that person kept there is gone; nothing else on the host is affected.

Deleting home files frees space within the fixed disk; it **does not shrink the backing image**.
When the host reserve is exhausted, an administrator must free host storage. Back up an unwanted
person's `-disk` volume before retiring it. The controller never deletes backing disks automatically.
The guard and health check recover once the reserve returns, without a service restart.

Budget disk space for `WORKSPACE_MAX_HOME_MB` times everyone who has used the workspace **within the
retention window**, plus logs and the host reserve: a home goes away by itself once its owner has been
absent for `WORKSPACE_RETAIN_DAYS`, so the number grows with active people rather than with everyone who
ever tried the bot. Snapshots, thin provisioning and other services can consume additional storage.
Changing the configured home size does not resize existing images: stop the controller, back up the
backing volume, and resize the image/filesystem offline with ext4 administration tools before applying
that setting. A size mismatch fails closed, preserving the existing disk.

## Moving from unbounded home volumes

A fresh deployment has nothing to migrate. Existing plain `*-home` volumes cannot silently
remain writable without a capacity bound. The controller preserves them and reports that migration is
required. Before upgrading, export each home using your volume backup tooling and retain the backup.
Stop the old controller and remove only the old volume whose export has been verified. Start the new
controller and initialize that person's empty bounded workspace, then import the files as UID/GID 1000
through the mounted home. Oversized imports fail at the filesystem capacity; reduce the data or choose
a larger capacity before creating the new disk. Never import user files into the raw `-disk` volume.

A person who has nothing worth keeping in an old volume does not need any of this: `resetWorkspace`
discards it and formats a bounded home in its place, from the chat, in one call.

Deploy bot and controller together; the health endpoint reports protocol 5. Old `WORKSPACE_WRITE_DEVICE`
settings are no longer used: the service always throttles its own loop device.

## Moving from per-chat containers

Workspace IDs are now `u<userId>` in every chat. Existing private-chat data must also follow the bounded-volume migration above.
Former `u<userId>_g<chatId>` volumes and job records are retained but no longer opened by the bot. History
and its `(userId, chatId)` key are unchanged.

There is no automatic merge of group files: two chats may have different projects at the same path.
Back up the old volumes, stop the controller, and copy wanted projects into separate subdirectories of
the person's `u<userId>` home, preserving UID/GID `1000:1000`. Review collisions and keep the old copies
until verified. Deploy the bot and controller together; the health endpoint reports protocol 5.

## Moving from the old shared workspace

This rewrite changes the API, job storage and home layout. Deploy the bot and service together; do not
mix the old client with the new service. Remove old isolation, engine, runtime, UID-pool, host-directory
and quota settings from deployment overrides. `WORKSPACE_DISK_WARN_MB` is still only a warning;
`WORKSPACE_MAX_HOME_MB` sets the bounded filesystem capacity.

There is **no automatic import** of the old shared `vusan-workspaces` volume or bind-mounted homes.
They are not deleted by this change. Back them up before deploying. To keep a project, export its
`u<userId>[_g<chatId>]` home from the old store. First run a harmless command through the new bot so its
controller creates and labels the target home volume. Pause the bot while importing through the mounted bounded home as `1000:1000`, never into the raw
backing volume. Keep the controller running during import, since stopping it detaches the filesystem.
Use an empty target home or review collisions first; keep the old copy until the result is verified. The old
UID registry and old command logs are not part of the new controller state.
