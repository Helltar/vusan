# The site host

One public web address **per person**, at `<their Telegram id>.<your domain>`. The agent builds a page,
a game or a small web app in that person's [workspace](workspace.md), zips it, and publishes it; the
link works for anyone the person sends it to. Publishing again replaces the whole site.

It is a **separate deployment**, and off until you make it. `compose.yaml` starts the bot alone; this
service comes up on its own with `compose.sites.yaml`, on a machine with a public address. The bot only
ever connects out to it, so the machine running Vusan needs no inbound address of its own — a home
server behind CGNAT publishes to a VPS perfectly well.

Everything served is static: HTML, CSS, JavaScript, WebAssembly, images, audio, fonts. There is no
server-side code, no database and no build step on the host — whatever needs one is built in the
workspace first, and only the result is published.

- **Deploying it** — [Setting it up](#setting-it-up) · [DNS and certificates](#dns-and-certificates)
- **What it does** — [What publishing does](#what-publishing-does) · [Limits](#limits)
- **Running it** — [Taking a page down](#taking-a-page-down) · [Storage and updates](#storage-and-updates)

## Setting it up

The deployment is a directory holding four things and no checkout, because both images come from the
registry:

```
~/vusan/compose.sites.yaml
       /env/sites.env
       /certs/{site.pem,site.key,origin-pull-ca.pem}
       /data/
```

```bash
mkdir -p ~/vusan/env ~/vusan/certs ~/vusan/data && cd ~/vusan
curl -fsSLO https://raw.githubusercontent.com/Helltar/vusan/master/compose.sites.yaml
curl -fsSL  https://raw.githubusercontent.com/Helltar/vusan/master/env/sites.env.example -o env/sites.env

# fill in SITES_DOMAIN, and write a fresh shared secret
sed -i "s/^SITES_TOKEN=.*/SITES_TOKEN=$(openssl rand -hex 32)/" env/sites.env

docker compose --env-file env/sites.env -f compose.sites.yaml up -d
```

`data/` must exist before the first start and belong to the user running compose: a bind mount Docker
creates for you comes out owned by `root`, while the service runs as uid 1000. On a machine whose login
user is uid 1000 — the usual case on a fresh VPS — there is nothing else to set.

The bot gets the same secret in its own `env/vusan.env`, along with the address of the API:

```
SITES_URL=https://api.example.com
SITES_TOKEN=<the same value>
```

Publishing needs a workspace to build in, so `WORKSPACE_URL` must be configured too. Without it the
tools are not registered and the log says so at startup.

Only nginx publishes a port, and only `443/tcp`. The service itself is reachable solely over the compose
network — never publish its port: a published Docker port bypasses `ufw` without saying so.

## DNS and certificates

Two records, both **proxied** through Cloudflare, both pointing at this machine:

| Name           | Purpose                                        |
|----------------|------------------------------------------------|
| `*.example.com` | Every person's site, at `<id>.example.com`     |
| `api.example.com` | The publishing API the bot talks to          |

The wildcard must be proxied: the certificate below is a **Cloudflare Origin CA** certificate, trusted
by Cloudflare and by nobody else, so a grey-clouded record would show visitors an untrusted one. A
wildcard never matches the apex and never matches a name that already has its own record, so an existing
site on the same domain keeps working, on its own machine if that is where it lives.

Issue the certificate under SSL/TLS → Origin Server for `example.com` and `*.example.com`, and put it in
`certs/` as `site.pem` and `site.key`. Add Cloudflare's
[Authenticated Origin Pulls CA](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/)
as `origin-pull-ca.pem`; nginx requires that client certificate on every request, so anything reaching
the machine directly is refused at the handshake. Turn Authenticated Origin Pulls on for the zone.

Two zone settings are worth checking:

- **Browser Cache TTL** overrides what this host sends. Its default of four hours means a republished
  page keeps serving its old JavaScript to anyone who already loaded it. A Cache Rule on
  `*.example.com` set to respect origin TTLs fixes it without touching the rest of the zone.
- **Bot Fight Mode** challenges automated requests and would break publishing on `api.` with no useful
  error.

## What publishing does

The agent zips the finished files in the workspace and calls `publishSite`. The bot reads that one
archive, checks every path in it, and uploads the files one at a time; the host stages them and swaps
the new tree in by rename when the last one arrives. A visitor sees either the previous site or the new
one, never a half-written mix. One upload has one writer, so the size and file caps hold however many
transfers arrive at once.

Paths that escape the site, absolute paths and dotfiles are refused — `.git` and `.env` reach a build
directory far more often than anyone means to publish them. A zip entry that was a symlink becomes an
ordinary file holding its target, because nothing here writes to a filesystem on the way in.

Since a person has one site, several projects live as folders inside it and are reachable at `/name/`.
`index.html` at the top of the archive is the page the bare link opens; publish without one and the tool
says so rather than leaving a link that shows nothing.

Every site is served with `nosniff`, `noindex, nofollow`, `no-referrer`, and camera, microphone and
geolocation switched off. HTML is sent with `no-cache` so a republished page is picked up; other files
carry a short cache lifetime.

## Limits

The host is the authority on all of these and states them to the bot when an upload starts, so raising
one here needs no matching change in the bot.

| Variable                    | Default | Description                                                         |
|-----------------------------|---------|---------------------------------------------------------------------|
| `SITES_DOMAIN`              | —       | Required. The domain sites are served under.                        |
| `SITES_TOKEN`               | —       | Required. The shared API secret, the same value the bot is given.   |
| `SITES_TOKEN_FILE`          | —       | A file holding that secret instead. An explicit token wins.         |
| `SITES_HOST_DIR`            | `./data` | Where published sites live on this machine.                        |
| `SITES_CERT_DIR`            | `./certs` | The certificate, its key and the origin-pull CA.                   |
| `SITES_IMAGE`               | `ghcr.io/helltar/vusan-sites:latest` | The service image.                 |
| `SITES_NGINX_IMAGE`         | `ghcr.io/helltar/vusan-sites-nginx:latest` | The nginx image; keep both on one tag. |
| `SITES_MAX_MB`              | `100`   | The largest a single site may be.                                   |
| `SITES_MAX_FILE_MB`         | `25`    | The largest one file may be. Above 32 needs nginx's body cap raised. |
| `SITES_MAX_FILES`           | `1000`  | How many files one site may hold.                                   |
| `SITES_MAX_DEPTH`           | `10`    | How deep a path inside a site may go.                               |
| `SITES_RETAIN_DAYS`         | never   | Days after which a site nobody republishes is deleted. Unset or `never` keeps sites indefinitely. |
| `SITES_MIN_FREE_MB`         | `2048`  | Publishing pauses below this much free disk and resumes on its own.  |
| `SITES_PUBLISHES_PER_HOUR`  | `20`    | How often one person may publish.                                   |
| `SITES_UPLOAD_IDLE_MINUTES` | `15`    | An unfinished upload is discarded after this long.                  |

## Taking a page down

Whoever published it can ask the agent to, which calls `unpublishSite` and leaves their workspace files
alone. As the operator you never depend on the bot being up:

```bash
rm -rf ~/vusan/data/sites/<id>          # gone at the next request
echo "<id>" >> ~/vusan/data/blocked     # and it cannot be published again
```

The block list is read on every publish and on every sweep, so a blocked site is removed even if it was
put back a moment ago. One numeric id per line; anything else on a line is ignored.

## Storage and updates

Published sites are ordinary files under `data/sites/<id>`, with one small record per site under
`data/meta`. Reading, backing up or deleting them needs nothing but a shell.

**Nothing expires by default.** A published site is a finished thing someone handed a link to, so it
stays until it is taken down. Setting `SITES_RETAIN_DAYS` turns on expiry, and it is worth knowing what
the clock measures: a site's date is its **last publish**, and nothing else. Visits are served by nginx
and never reach the service, so a page people read every day still ages. Nobody is warned before a
deletion, and a directory with no record is left alone rather than guessed at. Uploads that were never
committed expire on their own either way.

The source stays in the workspace regardless, under its own retention, and that one counts any use of
the shell — so the usual outcome of an expired site is that republishing it is one sentence.

Updating is a pull and a restart, both images together:

```bash
cd ~/vusan && docker compose --env-file env/sites.env -f compose.sites.yaml pull \
  && docker compose --env-file env/sites.env -f compose.sites.yaml up -d
```

Published sites survive it — they are files on the host, not container state.
