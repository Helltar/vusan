# Workspace deployment and administration

The default deployment is `docker compose up -d` on a Docker host. It starts the bot and a trusted
controller; each person's workspace container is created on demand. There is only one implementation:
Docker containers with persistent named home volumes. There is no gVisor or alternate-engine setup.
See [configuration](configuration.md#workspace) for the tools, limits and security boundaries.

## Storage and updates

With the default namespace, workspace `u123` uses container `vusan-workspace-u123` and volume
`vusan-workspace-u123-home`. The same `u123` is used in private chat and every group. Every container mounts
only its own home at `/work`; its UID 1000 owns the volume. The controller mounts none of these homes.
Its command metadata and bounded logs live separately in Compose's `vusan-workspace-state` volume.
The API secret lives in `vusan-workspace-auth`, shared only with the trusted bot. Preserve that volume
across updates; recreating it rotates the generated token and requires restarting the bot too.

Back up the home volumes and controller state using your Docker-volume backup tooling. Stop the
controller first for a consistent copy; a normal stop also removes live workspace containers and
interrupts commands. Start it again after the snapshot. Home volumes are retained on stop, idle
expiry, image replacement and `docker compose down`. Do not use volume-pruning commands as cleanup:
an idle workspace's valuable files look like an unused volume to Docker.

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
`WORKSPACE_IMAGE=vusan-workspace:custom` in `.env`, and run `docker compose up -d`. Both the controller
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
values in `.env` beside the Compose files (generate your own strong shared secret):

```dotenv
WORKSPACE_BIND=10.10.10.2
WORKSPACE_TOKEN=<your shared secret>
```

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

Three layers keep a workspace from filling the host, and only the first is enforced by the kernel:

- `WORKSPACE_MAX_FILE_MB` (4 GiB) caps any single file a command writes. The writing process is killed
  with `File size limit exceeded`, which is what stops a stray `dd`.
- `WORKSPACE_MAX_HOME_MB` (4 GiB) caps a whole home. The controller measures live homes whenever the
  state filesystem has lost 256 MiB, and once a minute regardless; a workspace over the limit loses its
  container and its running command ends as failed. Its files are kept, so the next command can delete them.
- `WORKSPACE_MIN_FREE_MB` / `WORKSPACE_MIN_FREE_INODES` is the host reserve, checked every second. Below
  it, or when the check itself fails, the controller stops every live workspace container and refuses
  file uploads with `507`. Commands stay available, because deleting files is the way back and the
  reserve exists to leave room for that. No home volume is ever deleted automatically.

The guard clears itself as soon as space returns; the service does not need a restart. While it is
latched the health check reports unhealthy, so `docker compose ps` shows the problem. Free space with
administrator tooling if the pressure comes from outside the workspaces.

None of this is a filesystem quota: measurement is periodic, so a fast writer overshoots its home limit
by whatever it writes between two checks. Setting `WORKSPACE_WRITE_DEVICE` and `WORKSPACE_WRITE_BPS`
together caps a workspace's write bandwidth to a host block device and removes that overshoot:

```dotenv
WORKSPACE_WRITE_DEVICE=/dev/nvme0n1
WORKSPACE_WRITE_BPS=50mb
```

Size the disk for `WORKSPACE_MAX_HOME_MB` times the number of people plus the reserve, since homes
persist for everyone who has ever used the workspace. Keep workspace storage on a filesystem separate
from the bot's database where you can, and note that the guard watches the controller's state
filesystem: a custom volume backend on different storage needs monitoring of its own.

## Moving from per-chat containers

Workspace IDs are now `u<userId>` in every chat. Existing private-chat home volumes are reused unchanged.
Former `u<userId>_g<chatId>` volumes and job records are retained but no longer opened by the bot. History
and its `(userId, chatId)` key are unchanged.

There is no automatic merge of group files: two chats may have different projects at the same path.
Back up the old volumes, stop the controller, and copy wanted projects into separate subdirectories of
the person's `u<userId>` home, preserving UID/GID `1000:1000`. Review collisions and keep the old copies
until verified. Deploy the bot and controller together; the new health endpoint reports protocol 3.

## Moving from the old shared workspace

This rewrite changes the API, job storage and home layout. Deploy the bot and service together; do not
mix the old client with the new service. Remove old isolation, engine, runtime, UID-pool, host-directory
and quota settings from deployment overrides. `WORKSPACE_DISK_WARN_MB` is still only a warning; the
limit that stops a workspace is `WORKSPACE_MAX_HOME_MB`, above.

There is **no automatic import** of the old shared `vusan-workspaces` volume or bind-mounted homes.
They are not deleted by this change. Back them up before deploying. To keep a project, export its
`u<userId>[_g<chatId>]` home from the old store. First run a harmless command through the new bot so its
controller creates and labels the target home volume. Stop the controller, then import into that volume,
setting the imported files' ownership to `1000:1000`. Use an empty
target volume or review collisions first; keep the old copy until the result is verified. The old
UID registry and old command logs are not part of the new controller state.
