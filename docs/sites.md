# Publishing to the web

Vusan can put a finished page, game or small web app on the public internet at **one address per
person**, and hand them the link. The files come from that person's [sandbox](sandbox.md), and the
Regolith server publishes them, so the bot needs no hosting, storage or certificates of its own.

## What it needs

Nothing beyond a sandbox. If the Regolith server has its
[pages role](https://github.com/reified-io/regolith/blob/main/docs/pages.md) set up, it says so in
`GET /v1/info` and the tools work; if not, the model gets the server's own message that sites are
unavailable.

The address is the server's and random — something like `https://k7m2q9xwtp.sites.example.com` —
so it says nothing about whose page it is. It stays the same while the site is up, so the link keeps
working after the page is published again. Limits, retention and what a site may hold are set on the
server and described in [Regolith's documentation](https://github.com/reified-io/regolith).

## What the model does

- **`publishSite`** — publishes one directory of the sandbox. `index.html` must sit at its top, since
  that is the page the link opens, and the tool warns when it is missing. Everything is static — HTML,
  CSS, JavaScript, WebAssembly, images, audio, fonts — with no server, database or build step behind it.
- **`siteStatus`** — what is online: the address, how much it holds, when it was last published.
- **`unpublishSite`** — takes the site down. The sandbox files are untouched, so it can be published
  again later, at a new address.

## What publishing is

- **A snapshot** — the files leave the sandbox when the tool is called, and the site stays as it was
  however the sandbox changes. Changing a page means publishing again.
- **One site per person** — publishing replaces everything that was there, so the directory must hold
  every file the page needs. Several projects can live in it as folders, reachable at `/name/`.
- **Its own term** — a site is never served out of a live sandbox, which is reclaimed when it goes
  idle. The server sets how long a site stays up, states it in every publish answer and takes the site
  down after.
- **The sandbox's retention** — takes only the sandbox files. The site stays at its address, and the
  agent is told its source is gone before it tries to change it.
- **Reset** — `resetSandbox` ends the site at once, and its address is never served again.

## What is public

Everything in the published directory, to anyone with the link, and nothing else of the sandbox. The
address is not secret, so the model is told to mention that before publishing anything personal.
