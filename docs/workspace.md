# The workspace shell

One persistent Linux home **per person, across all chats**. It runs Bash, works on projects, converts
documents and media, installs user-local dependencies and sends the results back; files survive new
messages, `/clear`, a stopped container and a restarted service. Different people have separate homes,
and the same person uses the same files in private chat and in every group, while conversation history
stays separate per chat.

The workspace itself is **[Regolith](#the-server)**, a self-hosted sandbox server that is its own
project and its own deployment. This bot is one of its clients: it asks for a sandbox named after the
person and then runs commands, moves files and reads output over HTTP. Nothing about Docker, homes or
network policy lives here — see Regolith's own documentation for how it confines what it runs.

- **Setting it up** — [The server](#the-server) · [Pointing the bot at it](#pointing-the-bot-at-it)
- **What the model can do** — [Files](#files) · [Commands](#commands) · [Sending results](#sending-results)
- **What to expect** — [Limits](#limits) · [When a home goes away](#when-a-home-goes-away) ·
  [Removing it](#removing-it)

## The server

Regolith runs on an ordinary Linux Docker host, beside the bot or on a machine of its own. A machine
of its own is the recommendation for anything public or less trusted: it runs commands the model
writes, and holds the Docker socket to do it. Install it, generate its token, and check the host with
its own `doctor` command before the first start.

The **sandbox image** is chosen there, not here. Its default image is deliberately small — Python,
Node, Git, curl, jq and the usual shell tools — so a deployment that wants Pandoc, FFmpeg, ImageMagick
or a browser builds an image with them and points the server at it. The model is told to check what a
task needs rather than assume, and to report a missing system package instead of working around it:
there is no `sudo` in a sandbox.

## Pointing the bot at it

Two values in the bot's `.env`, and the shell tools appear:

```dotenv
REGOLITH_URL=http://10.10.10.2:8080
REGOLITH_TOKEN=the-same-token-the-server-was-started-with
```

`REGOLITH_TOKEN_FILE` holds the token in a file instead. Both a URL and a token must be present, or
the tools are not registered and the bot never mentions them — see
[configuration](configuration.md#workspace). The bot never reaches the sandbox host any other way: one
token, one HTTPS or private address, no Docker socket on this side.

Each person gets a sandbox named by their person key (`u<telegram id>`), created on first use with the
server's own defaults. Everything else — image, memory, home size, idle stop, retention, network
policy — is the server's to decide, and the bot asks `GET /v1/info` for the limits it must respect
rather than keeping a copy.

## Files

- **Attachments** — copied to `inbox/<unique-id>/<filename>` before the first command that might want
  them, and the tool result names the exact path. Repeated filenames never overwrite.
- **Paths** — relative to the home, `/home/sandbox`. `writeWorkspaceFile` replaces a file atomically
  and creates parent directories; `deleteWorkspaceFile` removes one exact path, recursively for a
  directory, and leaves running commands alone.
- **`resetWorkspace`** — deletes the sandbox with its home; the next command starts in an empty one.
  For a home too full, too broken or too tangled to repair file by file.
- **Privacy** — what a command creates stays in the sandbox until the agent sends it. One home per
  person: a request in a group reaches the same files as private chat. Sharing files across chats is
  intentional; sharing raw history is not.

## Commands

Every command is a fresh shell starting at the home.

- **Nothing carries over** — use `cd project && ...` explicitly; shell variables and the working
  directory do not survive between commands. `~/.profile` is the place for setup that should.
- **No terminal** — commands get no stdin and no TTY, so an interactive prompt ends rather than hangs.
- **Long commands do not block** — the call returns in about ten seconds with a job id, and the agent
  reads more output from the byte offset it was given or cancels the job. Output is recorded on the
  server, so nothing is lost between reads.
- **Cancelling** stops that command and every process it started, background servers included; other
  commands keep running.
- **History outlives the chat** — asking for recent commands with an empty job id works after
  `/clear`.
- **A killed command says why** — a command the session's memory limit killed reports `out of memory`
  and one that hit the process limit says so, instead of a bare exit code.

## Sending results

`sendFromWorkspace` delivers finished files to the chat: images as photos, videos as videos, anything
else as a document. At most 10 files and 50 MB per call, and a file kind the chat refuses is named in
the result rather than reported as sent.

## Limits

The server owns them and states them in `GET /v1/info`; the bot adds only what it must enforce on its
own side:

| Bound | Where it comes from |
|---|---|
| Command timeout | The server's default and ceiling; the bot trims what the model asks for to that ceiling |
| Output per read | 16 KB, then the model continues from the offset it was given |
| Command text | 16 000 characters |
| File write through a tool | 400 000 characters of text |
| Files out of the workspace | 10 files and 50 MB per call |
| Attachment into the workspace | 20 MB |
| Home size, memory, CPU, processes | The server's per-sandbox settings |

A home is a fixed-size disk: when it fills, commands fail with `No space left on device` and the fix
is to delete what is no longer needed.

## When a home goes away

- **Idle** — the server stops a sandbox that has been idle for a while. Files stay; processes do not,
  so a background server started by an earlier command is gone by the next message.
- **Retention** — a sandbox nobody has used for the server's retention window is deleted with its
  home. The next command creates an empty one.
- **Reset** — `resetWorkspace`, immediately and permanently.

Backups are the server's business, not the bot's.

## Removing it

Unset `REGOLITH_URL` and restart the bot: the shell tools disappear, and with them site publishing,
which has nothing to publish without a workspace. The sandboxes stay on the server until deleted
there.
