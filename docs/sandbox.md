# The sandbox shell

One persistent Linux home **per person, across all chats**. It runs Bash, works on projects, converts
documents and media, installs user-local dependencies and sends the results back; files survive new
messages, `/clear`, a stopped container and a restarted service. Different people have separate homes,
and the same person uses the same files in private chat and in every group, while conversation history
stays separate per chat.

It runs on **[Regolith](https://github.com/reified-io/regolith)**, a self-hosted sandbox server with
its own deployment and documentation. This bot is one of its clients: it asks for a sandbox named
after the person, then runs commands, moves files and reads output over HTTP. How a sandbox is
isolated, which image it runs and what it can reach are set on the server, and Regolith's
documentation covers them.

## The server

Regolith runs on an ordinary Linux Docker host. A machine of its own is the recommended setup, since
it runs commands the model wrote and holds the Docker socket to do it, though it also runs beside the
bot. Installing it, generating its token and checking the host before the first start are covered in
[Regolith's documentation](https://github.com/reified-io/regolith).

The **sandbox image** is set on the server, and the bot assumes only two things about it: there
is no `sudo`, and nothing in particular is installed. The model is told to check what a task needs and
to report a missing system package rather than work around it, so a deployment that wants Pandoc,
FFmpeg, ImageMagick or a browser points the server at an image carrying them — Regolith's own
[`regolith-sandbox-full`](https://github.com/reified-io/regolith/blob/main/docs/configuration.md#sandbox-images)
is one.

## Pointing the bot at it

Out of the box Regolith publishes its API on `127.0.0.1:8080`, which only its own machine reaches. The
bot in its container cannot, even on that machine, so publish the API on an address the bot can use —
a private network or a tunnel, where the sandbox machine is `10.0.0.4` here. In the server's `.env`:

```dotenv
REGOLITH_PUBLISH=10.0.0.4:8080
```

Then two values in the bot's `.env`, and the shell tools appear:

```dotenv
REGOLITH_URL=http://10.0.0.4:8080
REGOLITH_TOKEN=<the same token>
```

A bot run from source on the server's own machine needs none of that: `http://127.0.0.1:8080` works
as it is.

`REGOLITH_TOKEN_FILE` holds the token in a file instead. Both a URL and a token must be present, or
the tools are not registered and the bot never mentions them — see
[configuration](configuration.md#sandbox). The bot never reaches the sandbox host any other way: one
token, one HTTPS or private address, no Docker socket on this side.

The server has to run **Regolith 0.3**. The bot talks to it through Regolith's own client, which reads
the server's answers by that release's protocol, so a server from another minor version is not
understood.

Each person gets a sandbox named by their person key (`tg-<telegram id>`), created on first use with the
server's own defaults. Everything else — image, memory, home size, idle stop, retention, network
policy — is configured on the server, and the bot reads the limits it has to respect from
`GET /v1/info` instead of keeping its own copy.

## Files

- **Attachments** — copied to `inbox/<unique-id>/<filename>` before the first command that might want
  them, and the tool result names the exact path. Repeated filenames never overwrite.
- **Paths** — relative to the home, `/home/sandbox`. `writeSandboxFile` replaces a file atomically
  and creates parent directories; `deleteSandboxFile` removes one exact path, recursively for a
  directory, and leaves running commands alone.
- **`resetSandbox`** — deletes the sandbox with its home; the next command starts in an empty one.
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

`sendFromSandbox` delivers finished files to the chat: images as photos, videos as videos, anything
else as a document. At most 10 files and 50 MB per call, and a file kind the chat refuses is named in
the result rather than reported as sent.

## Limits

The server sets them and reports them in `GET /v1/info`; on top of that, the bot enforces a few of its
own:

| Bound | Where it comes from |
|---|---|
| Command timeout | The server's default and ceiling; the bot trims what the model asks for to that ceiling |
| Output per read | 16 KB, then the model continues from the offset it was given |
| Command text | 16 000 characters |
| File write through a tool | 400 000 characters of text |
| Files out of the sandbox | 10 files and 50 MB per call |
| Attachment into the sandbox | 20 MB |
| Home size, memory, CPU, processes | The server's per-sandbox settings |

A home is a fixed-size disk: when it fills, commands fail with `No space left on device` and the fix
is to delete what is no longer needed.

## When a home goes away

- **Idle** — the server stops a sandbox that has been idle for a while. Files stay; processes do not,
  so a background server started by an earlier command is gone by the next message.
- **Retention** — a sandbox nobody has used for the server's retention window is deleted with its
  home. The next command creates an empty one.
- **Reset** — `resetSandbox`, immediately and permanently.

The bot keeps no copy of a home; backing one up is done on the Regolith host.

## Removing it

Unset `REGOLITH_URL` and restart the bot: the shell tools disappear, and with them site publishing,
which has nothing to publish without a sandbox. The sandboxes stay on the server until deleted
there.
