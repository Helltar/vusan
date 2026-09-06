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

Each home is its own 4 GiB ext4 filesystem, mounted at `/work` and used as `HOME`. The preallocated
image behind it sits in a Docker volume that no workspace container ever receives.

- **Attachments** — copied to `inbox/<unique-id>/<filename>` before the first command that might want
  them, and the tool result names the exact path. Repeated filenames never overwrite.
- **Privacy** — what a command creates stays in the workspace until the agent sends it.
- **One home per person** — a request in a group reaches that person's files from private chat.
  Sharing files across chats is intentional; sharing raw history is not.

### What the image ships

Python with pip and venv, Node.js and npm, Git, curl, wget, jq, SQLite, ripgrep, zip, unzip, Pandoc,
FFmpeg, ImageMagick, and Chromium with basic fonts.

Not included: Java, Kotlin, a C/C++ compiler, a typesetting suite. Chromium is there to open a page or a
game and confirm it runs; its launcher turns off the browser's own sandbox, because the container around
it already is one.

### Installing anything else

The model gets no package inventory. It checks what a task needs, installs into the home where that
works, and says what is missing where it does not. Virtual environments and project-local npm packages
persist across restarts. There is no `sudo` and the system image is read-only, so system packages are an
[image rebuild](#adding-system-packages) rather than a command.

## Commands and processes

Every command is a fresh `bash -lc` starting at `/work`.

- **Nothing carries over** — use `cd project && ...` explicitly; shell variables and the working
  directory do not survive between commands. `~/.profile` is the place for setup that should.
- **No terminal** — there is no interactive stdin and no TTY.
- **Long commands do not block** — a call returns in about ten seconds with a job ID, and the agent
  reads or cancels it from there. Run long builds normally; detaching them buys nothing.
- **History outlives the chat** — asking for recent commands with an empty job ID works after `/clear`.

### What stops a background process

A normal exit does not. Whatever a command leaves running keeps running, redirected output or not, and
only the **whole container** going away removes it:

- **Idle expiry** — nobody has touched the workspace for `WORKSPACE_IDLE_MINUTES`.
- **Cancellation or timeout** — the command was stopped, so its descendants are too.
- **Unattended CPU** — the workspace spent `WORKSPACE_IDLE_CPU_SECONDS` of processor time while none of
  its own commands was running. This is what bounds a detached loop, which otherwise costs one command
  and then runs free. Waiting on work does not count, and an idle server spends nothing.
- **Pool pressure** — at capacity, the least recently used container without an active command or
  transfer is reclaimed.

Files survive every one of them. A running command is exempt from idle expiry, a controller restart
interrupts commands without resuming them, and an abrupt stop is reconciled at the next startup. One
person occupies at most one container and one command slot, however many chats they use.

### The numbers you will meet

| | |
|---|---|
| Command records kept | last 20 per workspace |
| Output stored per command | 8 MiB, then truncated and marked as such |
| Output returned per read | 16 KiB, after control-code cleanup |
| Largest single file | `WORKSPACE_MAX_FILE_MB`, 4 GiB by default |
| One file transfer | 50 MiB |
| Sending to chat | 10 files and 50 MiB per call |
| Workspace kept without use | `WORKSPACE_RETAIN_DAYS`, then deleted whole |

Redirect to a file when the complete output matters. Transfer paths are relative and must not contain
symlinks; Bash can copy a linked file first.

### Cleaning up

- **`deleteWorkspaceFile`** — removes one exact file or directory, including owned directories made
  unreadable. It stops background processes and runs in a fresh offline container with no shell
  profiles, so cancel any running command first. A final symlink can go; parent symlinks and the
  workspace root are refused. It keeps working when storage pressure has paused everything else.
- **`resetWorkspace`** — throws the home away and formats an empty one. For a workspace too full, too
  broken or too tangled to repair file by file, including one that can no longer start. Everything in it
  goes permanently, other people's are untouched, and because it drops the disk instead of walking it, a
  home holding a million files empties as fast as an empty one.

## Network and security

Every workspace gets its own filesystem mounts, process table, network namespace and resource limits.

- **No privilege, ever** — commands run as UID 1000 with an **empty capability set**, not merely unused
  ones, under Docker's default seccomp profile, `no-new-privileges` and a read-only root filesystem.
  Nothing in the container is privileged at any point, including its first instant.
- **No secrets, no socket** — a workspace receives neither. Only the controller holds the Docker
  socket, which is why **control of the controller means control of the Docker host**.
- **One privileged helper, briefly** — preparing or detaching a home disk needs `SYS_ADMIN`, `MKNOD` and
  loop devices, so a short-lived container gets them, with no network and a fixed image-owned script. It
  sees the backing image, never user paths, and workspaces never see any of it.

### What a workspace can reach

Under the default `open` policy it has the public internet, for downloads and package installs, and
nothing else. The pool has a Docker network of its own, and the rules for it live **on the machine**
rather than in the containers.

- **Blocked outbound** — private and local ranges, cloud metadata, CGNAT, the machine hosting it, and
  SMTP on 25, 465 and 587. IPv6 is off, DNS goes to public resolvers only, and
  `WORKSPACE_BLOCKED_CIDRS` adds anything else your infrastructure needs refused.
- **Blocked inbound** — a server bound to every interface is still reachable only from inside its own
  workspace: not from the host, not from another workspace, not from an unrelated container. The
  controller reaches in through Docker, never over the network.
- **Its own loopback** — available, and useful for preview servers and browser checks.
- **`WORKSPACE_NETWORK=none`** — drops external networking entirely, loopback included. No mode skips
  enforcement silently.

Because the rules are outside the containers, nothing running in one can weaken them. The controller
installs them at startup and then **proves them from a throwaway workspace**: if any blocked destination
answers, it refuses to serve rather than run a command behind a policy that is not in effect. They are
re-read every `WORKSPACE_POLICY_CHECK_SECONDS` afterwards, because they live on a machine this service
does not own — a firewall frontend rebuilding the tables, an administrator flushing them, a switch to
another tool. Drift is reinstalled and re-proved invisibly; only a policy that cannot be restored pauses
commands, stops the workspaces that were running without it, and turns the health check red.

### Traffic caps

| | |
|---|---|
| Bandwidth | 50 Mbit/s both ways, shared by the whole pool |
| Packets | 2,000/second per workspace, burst 4,000 |
| New connections | 100/second per workspace, burst 200 |

Per-workspace metering is what keeps one busy workspace from spending everyone else's budget. These caps
bound sustained traffic and connection churn; they do not make abuse of public services impossible, and
a cap that cannot be installed stops startup.

### Two host-firewall traps

- **`ufw` cannot hide the API port** — published Docker ports are redirected before ufw sees them. Bind
  the port to a non-public address instead, which is what `WORKSPACE_BIND` is for.
- **`DEFAULT_FORWARD_POLICY=DROP`** — ufw's own default, and a cloud provider's outbound rules, can cut
  a workspace off from the internet while everything else keeps working. The startup probe says so in
  the log rather than leaving you to guess.

Also stop a workspace from reaching private services through a public address that forwards back into
the LAN: the container's destination check happens before that external NAT, so that one belongs on the
host or router.

### What this does not protect against

Containers share the host kernel; this is not VM-level isolation. Open internet access allows
exfiltration and abuse from the server's address, so **do not put credentials in a workspace**. For a
public or less-trusted deployment, a machine of its own limits what an escape reaches — the same
implementation, not another execution mode.

These rules cover traffic that starts in a workspace. The bot's own downloads — files, image search,
channel previews — are a separate boundary: they reject private and local IPs at connection time,
disable proxies, validate every redirect, and bound the response while reading it.

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

The controller runs on a 512 MiB ceiling and streams transfers through it. Each workspace gets 1 GiB of
RAM, one CPU and 256 processes, and the pool holds at most two containers — reduced further so their
combined limits fit inside half the host's RAM and half its cores. A single-core host shares that core at
a low workspace weight. That leaves room for the bot and the OS, but reserves nothing against other
services on the same machine.

### What bounds a workspace

- **Home capacity** — a preallocated 4 GiB ext4 disk per person, metadata included. Its fixed block and
  inode counts stop multi-file writes, `fallocate`, unreadable directories and open-but-deleted files in
  the kernel rather than by measurement. There is no unbounded fallback.
- **Per-file limit** — `WORKSPACE_MAX_FILE_MB` caps each file a command writes, as a second bound.
- **Disk I/O** — writes at `50mb` and reads at `100mb` on the home's loop device, both accepting `none`.
  The cap protects a machine shared with other services; on a dedicated one it mostly gets in the way.
- **Host reserve** — a new home is created only if `WORKSPACE_MIN_FREE_MB` would still remain, so the
  machine cannot be filled one workspace at a time.

### When the host runs low

A one-second check watches free bytes and inodes on the state filesystem, separately from the directory
walks that measure homes, so a slow or unreadable home cannot delay it. Pressure blocks commands and
uploads with `507`, keeps trying to stop live workspaces, and lifts by itself once the reserve returns —
no restart needed. Home usage counts allocated blocks, and a failed measurement stops that workspace
instead of bypassing the check.

Deleting files frees space **inside** a home; the preallocated disk keeps its size. Freeing host space is
therefore an administrator's job — with one deliberate exception: a workspace unused for
`WORKSPACE_RETAIN_DAYS` is deleted outright, disk included, so abandoned homes do not pile up. Back up a
`-disk` volume you mean to keep before retiring it by hand, and see [Cleaning up](#cleaning-up) for what
the agent can remove on its own.

### Sizing the disk

Budget `WORKSPACE_MAX_HOME_MB` times the people who use the workspace **within the retention window**,
plus controller logs and the host reserve. The number grows with active people, not with everyone who
ever tried the bot. Homes survive idle eviction, image replacement and service shutdown. Keep the state
volume on the same Docker filesystem as the backing volumes so the reserve check watches the storage that
actually fills, and give snapshots or thin provisioning their own monitoring.

Changing the home size does not resize existing images: stop the controller, back up the volume, resize
the image and filesystem offline with ext4 tools, then apply the setting. A mismatch fails closed and
preserves the disk.

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

Deploy the bot and the service together: they share an HTTP contract that changes together, and the
health endpoint reports the protocol version it speaks. A home the current layout cannot open — one left
by an older build, say — is refused rather than opened unbounded, and `resetWorkspace` replaces it with
an empty bounded one in a single call.

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
`WORKSPACE_IMAGE=vusan-workspace:custom` in `env/workspace.env`, and bring the service up again. Both the
controller and its workspace containers then use it. Rebuild custom images when their base is updated.
For the source-build override, edit `workspace/Dockerfile` and use the source-build command above
instead.

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
