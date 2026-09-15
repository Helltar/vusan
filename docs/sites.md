# Publishing to the web

Vusan can put a finished page, game or small web app on the public internet at **one address per
person**, and hand them the link. The files come from that person's [sandbox](sandbox.md), and the
Regolith server does the publishing itself, so the bot needs no web hosting, storage or certificates
of its own.

## What it needs

Nothing beyond a sandbox. If the Regolith server the bot talks to has its
[pages role](https://github.com/reified-io/regolith/blob/main/docs/pages.md) set up, it reports that
in `GET /v1/info` and the publishing tools work; if not, the model gets the server's own message saying
that sites are unavailable.

The address is the server's, and it is random: something like `https://k7m2q9xwtp.sites.example.com`, which
tells whoever has the link nothing about whose page it is. It stays the same while the site is up, so the link
keeps working when the page is published again, and the bot never builds it — the URL comes back from the
publish call. Limits, retention and what a site may hold are configured on the server as well, and
[Regolith's documentation](https://github.com/reified-io/regolith) describes them.

## What the model does

- **`publishSite`** — publishes one directory of the sandbox. `index.html` must sit at the top of it,
  because that is the page the link opens; the tool warns when it is missing rather than leaving a dead
  link. Everything is static: HTML, CSS, JavaScript, WebAssembly, images, audio, fonts. There is no
  server, no database and no build step on the other side.
- **`siteStatus`** — what is online right now: the address, how much it holds, when it was last
  published.
- **`unpublishSite`** — takes the site off the internet. The sandbox files are untouched, so it can
  be published again later, at a new address.

## What publishing is

A **snapshot**, taken when the tool is called. The files leave the sandbox at that moment, and the
site stays exactly as it was published however much the sandbox changes afterwards — which also means
changing a page means publishing again. One person has one site: publishing replaces everything that
was there before, so the directory must hold every file the page needs. Several projects live as
folders inside it, reachable at `/name/`.

Nothing is served out of a live sandbox: a session stops when it goes idle and its home is reclaimed,
so a page served from there would disappear with it. A published site does keep the sandbox itself:
the server's retention never deletes a sandbox while its site is up, so the files it was built from
are still there to publish again. Resetting the sandbox is the other way a site ends: it goes down with
the sandbox, and its address is never served again.

## What is public

Everything in the published directory, to anyone with the link. The address is not secret, so the
model is told to mention this before publishing anything personal. Nothing else of the
sandbox is reachable: only the directory that was published, as it was at that moment.
