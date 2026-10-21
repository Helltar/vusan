# Sandbox and publishing

The bot's side of the Regolith server: the sandbox client and site publishing. Part of the [architecture index](../architecture.md).

## Sandbox

The sandbox is a [Regolith](../sandbox.md) server: a separate project with its own deployment, which
runs the commands and confines them. This repository holds only the client —
[`tools/sandbox/SandboxClient.kt`](../../src/main/kotlin/com/helltar/vusan/tools/sandbox/SandboxClient.kt), the one
file that uses Regolith's Kotlin SDK, which is what speaks the `/v1` API. Docker, homes and network policy never
enter the bot's request flow.

Each `userId` is an alias on that server — `telegram:<userId>` — and the sandbox it stands for is the same in
every chat. The server makes the id everything is addressed by, and the bot keeps none of it. Conversation
history still uses `(userId, chatId)`; only the files and the commands running in them are shared.

- **`SandboxClient`** — `sandboxOf` gives a turn one handle for the person's sandbox, which asks the server for
  it on first use, created if it has none; the sandbox and site tools of that turn share it, so a reset by one is
  seen by the other. A command is an exec: it is started, then its output is read from a byte offset until the
  command ends, a page comes back empty or the call's ten seconds are up — each page carrying the exec as it
  stands, so no second request asks for its status — and the model continues from `nextOffset`. A file is read within the call's remaining transfer budget, which the
  server refuses to exceed before sending any of it. Refusals carry the server's error `code`, which decides
  what the model is told — capacity reads as "try again" with the wait the server asked for, an unavailable
  server as its own reason plus that wait, everything else as the server's own sentence; no answer at all reads
  as temporarily unavailable.
- **`SandboxTools`** — the model-facing surface: run a command and read its output, cancel it, write, read and edit
  a file, reset the sandbox, send files out. It copies each of
  the turn's files into a directory of the turn's own before the first command that might want them, and renders a
  command as text the model can act on — the exit code, and the session limit that explains it
  when the memory or process cap is what killed it. Before every call that writes or runs, `TurnShelf.copyToSandbox`
  brings `turns/<yyMMdd-HHmmss>/` up to date: the request's attachments as `00-1-<name>` on the first copy, every file a
  call kept as `05-1-<name>`, and the text of the answers their tools mark `@Tool(copiedToSandbox = true)` — searches,
  pages, transcripts, what vision saw — as `03-<tool>.txt`. The first copy of a turn also removes all but the last three
  turns' directories: a turn often picks up the one before it, and the rest would only fill a home of a fixed size. The
  mark is opt-in on purpose: the group log, tasks and memory never reach a home that outlives the turn and can be
  published, unless the model writes them there itself.
- **What the bot does not decide** — the sandbox image, memory, home size, idle stop, retention and network
  policy all belong to the server. The bot reads `GET /v1/info` for the limits it must respect, and trims a
  requested timeout to that ceiling instead of keeping a copy of the number.
- **What a person keeps** — their home, until the server's retention window passes or `resetSandbox` deletes
  the sandbox; while their site is up, retention leaves both alone. Processes do not outlive an idle stop;
  files do.

See [the sandbox guide](../sandbox.md) for behavior, setup and limits.

## Publishing to the web

A person's site is published by the same Regolith server that holds their sandbox — this repository holds no site host.
[`tools/sites/SiteTools.kt`](../../src/main/kotlin/com/helltar/vusan/tools/sites/SiteTools.kt) is the whole of it: `publishSite`
sends one directory's path, the server snapshots it out of the sandbox and answers with the address, and `siteStatus` and
`unpublishSite` read and remove it.

- **One site per person**, because a site belongs to the sandbox it was published from, and that sandbox is the
  person's in every chat.
- **The bot never builds the URL, and never learns how it was chosen.** The server picks an address that says nothing
  about the sandbox or the person, keeps it while the site is up, and hands it back from the publish call.
- **The one mistake worth a warning**: a directory with no `index.html` at its top publishes fine and its link then
  opens nothing. The server reports it as `hasIndex` on the publish answer, and the tool says so rather than handing
  over a dead link; nothing here lists the directory to find out.
- **Whether publishing exists at all** is the server's answer, not a setting here: `GET /v1/info` reports it, and a server
  without a pages role refuses the call in its own words.
