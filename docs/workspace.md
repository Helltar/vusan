# The workspace shell

One persistent Linux home directory **per person, across all chats**. It runs Bash, works on projects,
converts documents and media, installs user-local dependencies and sends the results back; files survive
new messages, `/clear`, container replacement and service restarts. Different people have separate homes,
and the same person uses the same files in private chat and in every group, while conversation history
stays separate per chat.

It is a **separate deployment**, and off until you make it. `compose.yaml` starts the bot alone; this
service comes up on its own with `compose.workspace.yaml`, and the bot grows the shell tools only once it
can reach it. It runs commands the model writes and holds the Docker socket in order to do that, so it
belongs on a machine that holds nothing else — a small VM or a cheap VPS is enough. Running it beside the
bot is supported; [Both on one machine](#both-on-one-machine) covers what changes and what to budget.

It needs a rootful Linux Docker Engine with cgroup v2 and loop-device support. There is one
implementation — Docker containers with bounded home filesystems — and no gVisor, alternate engine or
runtime to choose.

- **Deploying it** — [Setting it up](#setting-it-up) · [Both on one machine](#both-on-one-machine)
- **What it does** — [Files and installed tools](#files-and-installed-tools) ·
  [Commands and processes](#commands-and-processes) · [Network and security](#network-and-security)
- **Running it** — [Limits and tuning](#limits-and-tuning) · [Storage and updates](#storage-and-updates) ·
  [Adding system packages](#adding-system-packages) · [Removing it](#removing-it)

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
[limits and tuning](#limits-and-tuning).

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

## Files and installed tools

Each home is a separate 4 GiB ext4 filesystem mounted at `/work`, also used as `HOME`. Its fixed-size,
preallocated backing image lives in a Docker named volume that user containers never receive. Only that
workspace sees it. Uploaded and replied-to attachments are copied before the first command or file-writing
tool into `inbox/<unique-id>/<filename>`; the tool reports the exact path. Repeated filenames do not
overwrite earlier attachments. Created files stay private until the agent sends them. A request from a
group can use that person's files from private chat; sharing files is intentional, sharing raw chat history is not.

The base image contains Python with pip/venv, Node.js/npm, Git, curl/wget, jq, SQLite, ripgrep,
zip/unzip, Pandoc, FFmpeg, ImageMagick and Chromium with basic fonts. It does not bundle Java, Kotlin,
a C/C++ compiler or a document typesetting suite. Chromium supports checking generated pages and taking
screenshots; its launcher disables the browser's own sandbox inside the isolated workspace container.

The model receives no preinstalled-package inventory. It checks what a task needs, installs dependencies
under the home directory when practical, or reports what is missing. Prefer Python virtual environments
and project-local npm packages; their files persist. There is no `sudo`, and the system image is read-only.
An administrator adds system packages by rebuilding the image, not by installing into a disposable
container; [see the example](workspace.md#adding-system-packages).

## Commands and processes

Every command starts a fresh `bash -lc` at `/work`. Use `cd project && ...` explicitly; shell variables
and the current directory do not carry over. Files such as `~/.profile` can persist environment setup.
There is no terminal or interactive stdin.

A command returns within about ten seconds. If it is still running, the result includes a job ID:
the agent can read its status/output or cancel it. An empty ID in the read tool lists recent commands,
including after `/clear`. Run long builds normally; there is no need to detach them just to avoid an HTTP timeout.

A normal exit leaves background processes alive, whether or not their output was redirected. What
removes them is the **whole container** going away: idle expiry, cancellation, command timeout, or a
workspace that keeps burning CPU while none of its own commands is running. Homes are retained in every
case. That last rule is what bounds a detached loop, which otherwise costs nothing to keep running: a
workspace may spend `WORKSPACE_IDLE_CPU_SECONDS` of processor time unattended before its container is
removed. Work a command is waiting on does not count, and an idle background server spends nothing. An active command is exempt from idle expiry. A controller stop/restart
interrupts commands; it does not resume processes. An abrupt stop is reconciled on the next startup.
When the container pool is full, the least-recently-used container without an active command or file
transfer is removed to make room. Its background processes stop, but files survive. One person occupies
at most one container and one command slot, regardless of how many chats they use.

The controller keeps the last 20 command records per workspace, with at most 8 MiB of combined output
each. A workspace nobody has used for `WORKSPACE_RETAIN_DAYS` is deleted whole, records and home
disk together.
Output is returned in 16 KiB pages after control-code cleanup. Anything beyond the log cap is
discarded and marked as truncated; redirect to a workspace file when the complete output matters.
A single file cannot grow past `WORKSPACE_MAX_FILE_MB`, 4 GiB by default; the writing process is stopped
there. Transfers are limited to 50 MiB per file; sending to chat accepts up to 10 files and 50 MiB total per call.
File-transfer paths must be relative and must not contain symlinks; Bash can copy a linked file first.
`deleteWorkspaceFile` removes one exact file or directory, including inaccessible owned directories. It
stops background processes and uses a fresh offline container without loading shell profiles. A running
command must be cancelled first. A final symlink can be deleted, but parent symlinks and the workspace
root are refused. This operation remains available when ordinary commands have been paused.
`resetWorkspace` is the other recovery: it throws the whole home away and replaces it with an empty one.
It is what to reach for when a workspace is too full, too broken or too tangled to repair file by file —
including a home that can no longer start at all. Everything in it goes, permanently, and a new disk is
formatted on the next command; other people's workspaces are untouched. Because it drops the disk rather
than walking it, a home holding a million files is emptied as quickly as an empty one, and it stays
available when storage pressure has paused everything else.

## Network and security

Each workspace has its own filesystem mounts, process table, network namespace and resource limits.
Commands run as UID 1000 with an empty capability set — not merely unused capabilities, none at all —
alongside Docker's default seccomp profile, `no-new-privileges` and a read-only root filesystem. Nothing
in a workspace container is privileged at any point, including its first instant. They receive neither application secrets nor the
Docker socket. Only the trusted controller gets that socket — **control of the controller means control
of the Docker host**. A short-lived storage helper has `SYS_ADMIN`, `MKNOD` and access to loop devices to
prepare or detach the fixed home filesystem. It has no network, runs only a fixed image-owned script,
and sees the backing image rather than user paths. User containers never receive those privileges,
loop devices, the backing image or a host bind mount.

In the default `open` network policy, public internet access allows downloads and package installs.
The pool has a Docker network of its own, and the policy for it lives **on the machine**, not inside the
containers: a destination-IP firewall blocks private/local ranges, cloud metadata, CGNAT, the machine
itself and outbound SMTP (25/465/587). IPv6 is disabled and DNS is restricted to public resolvers. The
workspace's **own loopback** remains available for local servers and browser checks. Extra public router
or infrastructure addresses can be listed in `WORKSPACE_BLOCKED_CIDRS`.
Nothing connects **into** a workspace either: a development server bound to every interface is still
reachable only from inside that workspace, never from the host, from another workspace, or from an
unrelated container on the same machine. Only the controller reaches in, and it does so through Docker
rather than over the network.
Because the rules are outside the containers, nothing running in one can weaken them. The controller
installs them at startup through a short-lived helper and then **proves them from a throwaway
workspace**: if any of those destinations answers, it refuses to serve rather than run a command behind
a policy that is not in effect.

They live on a machine this service does not own, though, so they are re-read every
`WORKSPACE_POLICY_CHECK_SECONDS`. A firewall frontend that rebuilds the tables, an administrator
flushing them, a switch to another tool — any of it removes the rules silently. Finding them gone
reinstalls and re-proves them, which is normally invisible; only a policy that cannot be restored pauses
commands and stops the workspaces that were running without it. The health check reports that state too.

Two things about host firewalls are worth knowing before they cost you an afternoon. Published Docker
ports are redirected before `ufw` sees them, so **ufw cannot hide the API port** — bind it to an address
that is not public instead, which is what `WORKSPACE_BIND` is for. And ufw's own
`DEFAULT_FORWARD_POLICY=DROP`, or a cloud provider's outbound rules, can cut a workspace off from the
internet without breaking anything else; the startup probe says so in the log rather than leaving you to
guess.
These are rules for traffic originating in a workspace. The bot's public file, image-search and channel
preview downloads separately reject private/local IPs at connection time, disable proxies, validate each
redirect, and bound the response while reading it. Configured internal services use a different HTTP client.

`WORKSPACE_NETWORK=none` disables external networking while preserving that private loopback. There is
no mode that silently skips firewall enforcement. Bandwidth is capped at 50 Mbit/s in both directions by
default, and that one is the pool's total, since the rules live on the bridge the workspaces share.
Packets and new connections are counted **per workspace** instead: 2,000 packets/second (burst 4,000)
and 100 new connections/second (burst 200), so one busy workspace cannot spend everyone else's budget.
These caps limit sustained traffic and connection churn; they do not make abuse of public services
impossible. A cap that cannot be installed stops startup.

Docker containers still share the host kernel; this is not VM-level isolation. Open internet access also
allows data exfiltration and abuse from the server's address. Do not put credentials in a workspace.
For a public or less-trusted deployment, a dedicated workspace machine limits the impact of an escape;
it uses the [same implementation](workspace.md), not another execution mode.
At the host/router firewall, also prevent a workspace reaching private services through a public IP
that forwards back into the LAN. The container's destination check happens before that external NAT.

## Limits and tuning

Everything here lives in `env/workspace.env` on the workspace machine, the same file that carries the
bind address and the secret, and Compose is handed it with `--env-file` on every command.
`WORKSPACE_MAX_TIMEOUT_SECONDS` is the one value the bot reads as well, from its own file: keep the two
equal.

| Variable | Default | Meaning |
|---|---|---|
| `WORKSPACE_TIMEOUT_SECONDS` | `120` | Default command time limit, clamped to the maximum. |
| `WORKSPACE_MAX_TIMEOUT_SECONDS` | `600` | Maximum requested command time; shared by bot and controller. |
| `WORKSPACE_MAX_CONCURRENT` | `2` | Active commands across all workspaces; one per workspace. |
| `WORKSPACE_MAX_ACTIVE` | `2` | Maximum live containers, including idle ones; further reduced to fit the host resource budget. |
| `WORKSPACE_IDLE_MINUTES` | `60` | Remove an untouched container after this many minutes, unless a command is running. |
| `WORKSPACE_RETAIN_DAYS` | `14` | Delete a workspace entirely — files, home disk and records — after this many days without use. |
| `WORKSPACE_POLICY_CHECK_SECONDS` | `300` | How often the network policy is re-read from the host and repaired if it has drifted. |
| `WORKSPACE_IDLE_CPU_SECONDS` | `600` | Processor time a workspace may spend while no command of its own runs, before its container is removed. |
| `WORKSPACE_MEMORY_MB` | `1024` | Hard memory limit per workspace, with no additional swap allowance. |
| `WORKSPACE_CPUS` | `1` | CPU limit per workspace, in whole cores. |
| `WORKSPACE_PIDS_LIMIT` | `256` | Process/thread limit per workspace. |
| `WORKSPACE_DISK_WARN_MB` | `2048` | Warn after a command when its home exceeds this size. Uses allocated blocks; keep it below the home capacity. |
| `WORKSPACE_MAX_HOME_MB` | `4096` | Fixed backing disk size in MiB. Filesystem metadata uses part of it; changing existing disks requires offline resizing. |
| `WORKSPACE_MAX_FILE_MB` | `4096` | Largest single file a command may write. The writing process is killed at that size. |
| `WORKSPACE_MIN_FREE_MB` | `1024` | Host reserve in MiB, also retained when allocating each new home disk. |
| `WORKSPACE_MIN_FREE_INODES` | `10000` | The same reserve in free inodes. |
| `WORKSPACE_NETWORK` | `open` | `open` or `none`, as above. |
| `WORKSPACE_NETWORK_MBIT` | `50` | Bandwidth cap for the whole pool, in whole megabits, in both directions. |
| `WORKSPACE_BLOCKED_CIDRS` | unset | Additional IPv4 addresses/CIDRs to block, separated by spaces or commas. Private ranges and the machine itself are already refused. |
| `WORKSPACE_WRITE_BPS` | `50mb` | Write bandwidth on the workspace loop device; `none` removes the cap. No host device configuration is needed. |
| `WORKSPACE_READ_BPS` | `100mb` | The same for reads; `none` removes the cap. |
| `WORKSPACE_TOKEN` | — | API bearer secret, 32–256 printable non-whitespace ASCII characters. Required, and the same on both sides: `openssl rand -hex 32`. |
| `WORKSPACE_TOKEN_FILE` | — | A file holding that secret instead, for deployments that mount secrets. An explicit token takes precedence. |
| `WORKSPACE_NAMESPACE` | `vusan` | Stable Docker resource prefix; unique per controller on a host. |
| `WORKSPACE_IMAGE` | `ghcr.io/helltar/vusan-workspace:latest` | Image for both controller and workspace containers. |

The controller has a 512 MiB memory ceiling; file transfers stream through it. By default, each workspace
has 1 GiB RAM, one CPU and at most 256 processes. The pool contains at most two containers, and is reduced
further so their combined memory limits fit within half the Docker host's RAM. On hosts with multiple
CPUs, its CPU limits also fit within half the host's cores; a single-core host shares that core using a
low workspace CPU weight. These limits leave capacity for the bot and OS, but do not reserve resources
against unrelated services an administrator runs on the same host.

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

The home capacity is enforced by its **fixed filesystem**, including its finite inode table. Multi-file
writes, `fallocate`, unreadable directories and open-but-deleted files cannot grow it. Disk space is
reserved before formatting, leaving `WORKSPACE_MIN_FREE_MB` on the backing filesystem. A new workspace
is refused if its disk cannot be reserved. The filesystem and loop attachment are created automatically;
there is no fallback to an unbounded home. `WORKSPACE_MAX_FILE_MB` is an additional per-file kernel limit.

A one-second host-reserve check runs independently of home directory walks. Low free bytes or inodes, or
an unreadable state filesystem, blocks commands and uploads with `507` and repeatedly attempts to stop
live workspaces. Queued startup and shell execution also check that admission is still open. Home usage
uses allocated blocks; a failed measurement stops that workspace instead of bypassing the check. The
guard and health check recover once the reserve returns, without a service restart.

A full home can be cleaned with Bash or `deleteWorkspaceFile`. The latter removes an exact file or
recursive directory in a fresh offline container, without shell profiles, and can repair inaccessible
owned directories. Cancel a running command first; background processes are stopped by cleanup.

`resetWorkspace` is the blunt instrument next to it: the home disk is dropped and reformatted, so a
workspace that cannot start — including one left behind by an older, unbounded layout — becomes usable
again in one call. Everything that person kept there is gone; nothing else on the host is affected.

Deleting files frees space **inside** a home; the preallocated backing disk stays the same size. Host
storage pressure therefore needs an administrator to free host space or retire a backing volume — with
one exception, and it is deliberate: a workspace nobody has used for `WORKSPACE_RETAIN_DAYS` is deleted
outright, disk included, so abandoned homes do not accumulate. Back up a `-disk` volume you want to keep
before retiring it by hand.

Budget disk space for `WORKSPACE_MAX_HOME_MB` times everyone who uses the workspace **within the
retention window**, plus controller logs and the host reserve: the number grows with active people
rather than with everyone who ever tried the bot. Homes survive idle eviction, image replacement and
service shutdown. Host snapshots, thin-provisioned storage and other services need their own capacity
monitoring, and the state volume belongs on the same Docker storage filesystem as the backing volumes so
the reserve check observes the storage that actually fills. Changing the configured home size does not
resize existing images: stop the controller, back up the volume, and resize the image and filesystem
offline with ext4 tools before applying the setting. A size mismatch fails closed, preserving the disk.

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
