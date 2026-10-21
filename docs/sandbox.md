# The sandbox shell

One persistent Linux home **per person, across all chats**. There the agent runs Bash, works on
projects, converts documents and media, installs user-local dependencies and sends the results back.
Files survive new messages, `/clear` and restarts. Each person has their own home and finds the same
files in private chat and in every group, while conversation history stays separate per chat.

It runs on **[Regolith](https://github.com/reified-io/regolith)**, a self-hosted sandbox server with
its own deployment and documentation. The bot is one of its clients: it asks for the person's sandbox,
then runs commands, moves files and reads output over HTTP. Isolation, the image and what a sandbox
can reach are set on the server and documented by Regolith.

## The server

Regolith runs on an ordinary Linux Docker host. Give it a machine of its own if you can — it runs
commands the model wrote and holds the Docker socket to do it — though it also runs beside the bot.
Installing it, making its token and checking the host are in
[Regolith's documentation](https://github.com/reified-io/regolith).

The server has to run **Regolith 0.7**: the bot talks to it through Regolith's own client, which reads
that release's protocol and does not understand another minor version.

The bot assumes only two things about the **sandbox image**: there is no `sudo`, and nothing in
particular is installed. The model checks what a task needs and reports a missing system package
rather than working around it, so for Pandoc, FFmpeg, ImageMagick or a browser, point the server at an
image that has them — such as Regolith's own
[`regolith-sandbox-full`](https://github.com/reified-io/regolith/blob/main/docs/configuration.md#sandbox-images).

## Pointing the bot at it

By default Regolith listens on `127.0.0.1:8080`, which the bot's container cannot reach even on the
same machine. Publish the API on an address the bot can use — a private network or a tunnel. With the
sandbox machine at `10.0.0.4`, in the server's `.env`:

```dotenv
REGOLITH_PUBLISH=10.0.0.4:8080
```

And in the bot's `.env`, after which the shell tools appear:

```dotenv
REGOLITH_URL=http://10.0.0.4:8080
REGOLITH_TOKEN=<the same token>
```

A bot run from source on the server's own machine can use `http://127.0.0.1:8080` as it is. Without
both values the tools are not registered and the bot never mentions them; see
[configuration](configuration.md#sandbox).

- **One way in** — the bot reaches the sandbox host only through that address and token, never through
  a Docker socket.
- **One sandbox per person** — filed on the server under an alias, `telegram:<telegram id>`, and created
  on first use with the server's defaults.
- **The server's settings** — image, memory, home size, idle stop, retention and network policy are set
  there. The bot reads the limits it has to respect from `GET /v1/info` and keeps no copy.

## Files

- **The turn's own directory** — before every command, write, edit or send, the turn's files land in
  `turns/<yyMMdd-HHmmss>/`: what the message carried, an album included, as `00-1-<name>`; what the
  agent's other tools made — a drawn picture, voiced speech, a fetched video — as `05-1-<name>`; and
  what searches, pages, transcripts and vision found as `03-<tool>.txt`. The tool result names each
  path. The last three turns' directories are kept. The group's transcript, tasks and memory are never
  copied there.
- **Back out to other tools** — any file in the home can be handed to another tool as
  `sandbox:<path>`: a cut piece of video to watch, frames to look at, a recording to transcribe, a
  picture to edit, a text to read aloud.
- **Paths** — relative to the home, `/home/sandbox`. `writeSandboxFile` replaces a file atomically
  and creates parent directories; removing one is `rm` in `runCommand`.
- **Reading and editing** — `readSandboxFile` returns a text file with line numbers, a range at a time.
  `editSandboxFile` replaces one exact passage, so a change to a long file costs the passage rather
  than the whole file. Both refuse binary files and files over 2 MB, which the shell handles.
- **`resetSandbox`** — deletes the sandbox with its home, for one too full or tangled to repair file
  by file. A site published from it goes down too, and its address is never served again.
- **Privacy** — what a command creates stays in the sandbox until the agent sends it. A request in a
  group reaches the same files as private chat: files are shared across chats on purpose, history is
  not.

## Commands

Every command is a fresh shell starting at the home.

- **Nothing carries over** — the working directory and shell variables do not survive between
  commands, and nothing is sourced first, `~/.profile` included, so `cd project && …` and any
  environment setup go into the command itself.
- **No terminal** — no stdin and no TTY, so an interactive prompt ends rather than hangs.
- **Long commands do not block** — after about ten seconds the call returns a job id; the agent reads
  more output from the offset it was given, or cancels the job. Output is kept on the server, so
  nothing is lost between reads.
- **Canceling** — stops that command and every process it started, background servers included.
- **History outlives the chat** — recent commands can be listed even after `/clear`.
- **A killed command says why** — `out of memory`, or the process limit, instead of a bare exit code.

## Sending results

`sendFromSandbox` delivers finished files: images as photos, GIFs as animations, videos as videos,
anything else as a document. The model can override that for the whole call — everything as a
document, or a `.gif` or soundless `.mp4` as an animation, which is how an edited GIF goes back. At
most 10 files and 50 MB per call; a kind the chat refuses is named in the result rather than reported
as sent.

## Limits

The server sets most limits and reports them in `GET /v1/info`. The bot adds a few of its own:

| Bound                                    | Limit                                                          |
|------------------------------------------|----------------------------------------------------------------|
| Command timeout                          | The server's ceiling; the bot trims what the model asks for    |
| Output per read                          | 16 KB, then the model continues from the offset it was given   |
| Command text                             | 16,000 characters                                              |
| File written through a tool              | 400,000 characters of text                                     |
| Text file read or edited through a tool  | 2 MB, and 60,000 characters per read                           |
| Files sent out of the sandbox            | 10 files and 50 MB per call                                    |
| Attachment copied in                     | 20 MB                                                          |
| What a turn made, in `turns/`            | 50 MB per file, 200 MB per turn                                |
| A file handed to a tool as `sandbox:`    | 50 MB, or 1 MB where it stands in for text                     |
| A path, a job id                         | 400 and 64 characters                                          |
| Home size, memory, CPU, processes        | The server's per-sandbox settings                              |

A home is a fixed-size disk: when it fills, commands fail with `No space left on device`, and the fix
is to delete what is no longer needed.

## When a home goes away

- **Idle** — the server stops a sandbox idle for a while. Files stay; processes do not, so a
  background server started earlier is gone by the next message.
- **Retention** — a sandbox unused for the server's retention window is deleted with its home. A
  published site outlives it until its own term ends (see [sites](sites.md)). The next command
  starts an empty home.
- **Reset** — `resetSandbox`, immediately and permanently, together with its site.

The bot keeps no copy of a home; back one up on the Regolith host.

## Removing it

Unset `REGOLITH_URL` and restart the bot. The shell tools disappear, and site publishing with them;
the sandboxes stay on the server until deleted there.
