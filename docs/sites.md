# Publishing to the web

Vusan can put a finished page, game or small web app on the public internet at **one address per
person**, and hand them the link. The files come from that person's [workspace](workspace.md), and the
publishing is the workspace server's own doing: this repository holds no site host, no storage and no
certificates.

## What it needs

Nothing beyond a workspace. If the Regolith server the bot talks to has a public role configured, it
says so in its own `GET /v1/info` and the publishing tools work; if it does not, the model is told that
this server publishes nothing, in the server's own words.

The address is the server's to decide — normally `<person>.<the server's site domain>` — and the bot
never builds it: the URL comes back from the publish call. Limits, retention and what a site may hold
belong to that server too, and are documented with it.

## What the model does

- **`publishSite`** — publishes one directory of the workspace. `index.html` must sit at the top of it,
  because that is the page the link opens; the tool warns when it is missing rather than leaving a dead
  link. Everything is static: HTML, CSS, JavaScript, WebAssembly, images, audio, fonts. There is no
  server, no database and no build step on the other side.
- **`siteStatus`** — what is online right now: the address, how much it holds, when it was last
  published.
- **`unpublishSite`** — takes the site off the internet. The workspace files are untouched, so it can
  be published again later.

## What publishing is

A **snapshot**, taken when the tool is called. The files leave the workspace at that moment, and the
site stays exactly as it was published however much the workspace changes afterwards — which also means
changing a page means publishing again. One person has one site: publishing replaces everything that
was there before, so the directory must hold every file the page needs. Several projects live as
folders inside it, reachable at `/name/`.

Nothing is served out of a live workspace: a session stops when it goes idle and its home is reclaimed,
so a page served from there would disappear with it.

## What is public

Everything in the published directory, to anyone with the link. The model is told to say so before
publishing anything personal, and the address is not secret — a link is a link. Nothing else of the
workspace is reachable: only the directory that was published, as it was at that moment.
