# Workspace deployment and administration

The default deployment is `docker compose up -d` on a rootful Linux Docker host with cgroup v2 and loop-device support. It starts the bot and a trusted
controller; each person's workspace container is created on demand. There is only one implementation:
Docker containers with bounded home filesystems backed by persistent named volumes. There is no gVisor or alternate-engine setup.
See [configuration](configuration.md#workspace) for the tools, limits and security boundaries.

## Storage and updates

With the default namespace, workspace `u123` uses container `vusan-workspace-u123`. Its files live in
`home.ext4` inside the **`vusan-workspace-u123-disk` backing volume**. The controller automatically
attaches this preallocated image and creates `vusan-workspace-u123-home`, a temporary volume wrapper
that mounts the bounded filesystem at `/work`. The same `u123` is used in private chat and every group.

The user container receives only the mounted home, owned by UID 1000. A short-lived trusted storage
helper receives the backing volume and loop-device access; it never runs user commands. Controller job
metadata and bounded logs live separately in Compose's `vusan-workspace-state` volume. The API secret
lives in `vusan-workspace-auth`, shared only with the trusted bot. Preserve it across updates; recreating
it rotates the generated token and requires restarting the bot too.

**Back up the `-disk` volumes and controller state.** Stop the controller first for a consistent copy;
normal shutdown removes the user containers and mount wrappers and detaches their loop devices. Copy
the backing volumes using ordinary Docker-volume backup tooling, then start the controller again.
Backing volumes survive idle expiry, image replacement and `docker compose down`. Do not use volume
pruning as cleanup: an idle person's backing disk looks like an unused Docker volume.

Update the bot and workspace together because their HTTP contract changes together:

```bash
docker compose pull
docker compose up -d
```

The controller removes its own old containers during startup, marks unfinished jobs interrupted and
resolves the configured image to an immutable image ID for new containers. You do not need a separate
container-removal command when upgrading. Files remain in their volumes; processes do not resume.

For a source checkout, build and start both images with:

```bash
docker compose -f compose.yaml -f compose.local.yaml up --build -d
```

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

Build it on the workspace host as `vusan-workspace:custom`, set
`WORKSPACE_IMAGE=vusan-workspace:custom` in the repo-root `.env`, which is where Compose reads the values
it resolves itself, and run `docker compose up -d`. Both the controller
and its workspace containers then use it. Rebuild custom images when their base is updated. For the
source-build override, edit `workspace/Dockerfile` and use the source-build command above instead.

Installing Java on the host with `sudo apt install` does not put Java inside a workspace. Do not make
manual changes inside a running user container the source of its dependencies.

## Separate workspace host

This is the same controller and the same containers, moved to another Docker host. It is optional,
but limits what a container escape can reach: keep the bot's tokens, database and other production
services off that host. The controller's Docker socket has host-level authority; never expose its API
publicly. Containers share the host kernel, so keep Docker and the kernel patched.

Use a private network or an encrypted tunnel between the hosts. On the workspace host, put these
values in `.env` beside the Compose files, because Compose resolves both itself (generate your own
strong shared secret):

```dotenv
WORKSPACE_BIND=10.10.10.2
WORKSPACE_TOKEN=<your shared secret>
```

The bot's own file never goes to this host. Nothing there holds an API key, which is the point of moving
the workspace off the machine that does. Limits for the containers go in `env/workspace.env` beside it,
and the same `WORKSPACE_TOKEN` goes into the bot's `env/vusan.env` on the other host.

Start only the controller using the remote-host override:

```bash
docker compose -f compose.yaml -f compose.workspace.yaml up -d
```

The override requires both values, binds port 8080 to that address and puts the bot behind an inactive
profile. It inherits all image and resource settings from the main Compose file. No host home-directory
mount, runtime installation or manual UID mapping is needed. To use it for a JVM on the same host,
bind to `127.0.0.1` instead and use the same token in the JVM's environment.

On the bot host, set:

```dotenv
WORKSPACE_URL=http://10.10.10.2:8080
WORKSPACE_TOKEN=<the same secret>
```

Start the bot with `docker compose up -d --no-deps vusan` and stop any previously running local workspace
controller. For later remote updates, use the same two Compose files with `pull` and then `up -d`.

A bearer token does not encrypt HTTP: the private transport must provide confidentiality. At the host
or infrastructure firewall, allow API access only from the bot and restrict outbound access to private
networks and metadata endpoints. Preserve established replies to the bot's connections. The workspace's
own firewall is still mandatory; there is no setting to skip it. Public internet access can still be
used to upload workspace contents or abuse remote services, so it is not safe storage for credentials.

## Disk limits and the storage emergency

The defaults enforce limits without host quota configuration:

- **Home capacity** — each person gets a preallocated 4 GiB ext4 disk, including filesystem metadata.
  Its fixed block and inode counts stop multi-file writes, preallocation and inode exhaustion at that
  person's boundary. The backing image is not accessible from a workspace.
- **Per-file limit** — `WORKSPACE_MAX_FILE_MB` additionally caps each file a command writes.
- **Disk I/O** — the workspace loop device is selected automatically. Writes are capped at `10mb`
  (`WORKSPACE_WRITE_BPS`), reads at `50mb`; no host device name is needed.
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

Budget disk space for `WORKSPACE_MAX_HOME_MB` times everyone who has used the workspace, plus logs and
the host reserve. Snapshots, thin provisioning and other services can consume additional storage.
Changing the configured home size does not resize existing images: stop the controller, back up the
backing volume, and resize the image/filesystem offline with ext4 administration tools before applying
that setting. A size mismatch fails closed, preserving the existing disk.

## Moving from unbounded home volumes

Fresh deployments need only `docker compose up -d`. Existing plain `*-home` volumes cannot silently
remain writable without a capacity bound. The controller preserves them and reports that migration is
required. Before upgrading, export each home using your volume backup tooling and retain the backup.
Stop the old controller and remove only the old volume whose export has been verified. Start the new
controller and initialize that person's empty bounded workspace, then import the files as UID/GID 1000
through the mounted home. Oversized imports fail at the filesystem capacity; reduce the data or choose
a larger capacity before creating the new disk. Never import user files into the raw `-disk` volume.

Deploy bot and controller together; the health endpoint reports protocol 4. Old `WORKSPACE_WRITE_DEVICE`
settings are no longer used: the service always throttles its own loop device.

## Moving from per-chat containers

Workspace IDs are now `u<userId>` in every chat. Existing private-chat data must also follow the bounded-volume migration above.
Former `u<userId>_g<chatId>` volumes and job records are retained but no longer opened by the bot. History
and its `(userId, chatId)` key are unchanged.

There is no automatic merge of group files: two chats may have different projects at the same path.
Back up the old volumes, stop the controller, and copy wanted projects into separate subdirectories of
the person's `u<userId>` home, preserving UID/GID `1000:1000`. Review collisions and keep the old copies
until verified. Deploy the bot and controller together; the health endpoint reports protocol 4.

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
