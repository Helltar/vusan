# Configuration

Everything the bot reads from `.env`. Copy
[`.env.example`](../.env.example) and fill it in; blank values count as missing.

For Docker, follow the [quick start](../README.md#quick-start). The same `.env` also carries the few
Compose settings of the bot's own deployment, listed at the bottom of `.env.example`.

One thing sits outside this file: the [sandbox](#sandbox), a Regolith server with its own deployment
and its own configuration, which also publishes the pages people build. That split is a boundary rather
than tidiness: it runs commands the model writes and holds a Docker socket to do it, and what it
publishes faces the internet, so it is never handed the bot's secrets.

**How values are read.** Booleans take `true` or `false` in any case and nothing else. A value that
is set but unreadable — `AGENT_MAX_MODEL_CALLS=7O`, `GROUP_LOG_ENABLED=off`, a zero timeout, an ID
that is not a number — stops startup with a message naming the variable and what it was given,
rather than falling back to the default: writing the variable down at all says the default was not
wanted.

## Minimum setup

Five values, and Vusan runs:

```dotenv
OWNER_ID=123456789
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-6.1-sol
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `OWNER_ID`           | Your Telegram user ID: the person Vusan belongs to.  |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_PROVIDER`       | LLM backend; see [LLM provider](#llm-provider).      |
| `LLM_MODEL`          | Model id for the chosen provider.                    |
| `LLM_API_KEY`        | API key for the chosen provider.                     |

Vusan states `LLM_MODEL` in its own system prompt, so "which model are you?" is answered with what
is actually deployed instead of a guess.

## Who Vusan answers

`OWNER_ID` is the one person the deployment belongs to: answered everywhere, in private and in any
group, without being listed anywhere else, and exempt from what limits everybody else in a group — see
[One person alone in a group](#one-person-alone-in-a-group). It is a single user ID, never a list.

| Variable      | Default | Description                                                    |
|---------------|---------|----------------------------------------------------------------|
| `ALLOWED_IDS` | empty   | Telegram user and group IDs Vusan answers besides the owner.   |
| `BANNED_IDS`  | empty   | IDs Vusan ignores, whatever else allows them. Same format.     |

`ALLOWED_IDS` accepts commas, whitespace or semicolons as separators. Positive IDs are users,
negative IDs are groups, and an allowlisted group admits everyone in it. Empty or unset means Vusan
answers the owner alone. Scheduled tasks are held to the same list: one whose owner and chat are both
outside it never fires while that lasts. A recurring one moves on past the fires nobody may have and
resumes once either is back; a one-time one waits in place and runs then, or is reported missed when
that comes more than an hour late.

A plain number is a Telegram ID. An entry may also name the messenger it belongs to —
`telegram:123456789` — which is how the same number stays two different people once Vusan runs on
more than one. All three take either form.

The ban list wins over the allowlist, the owner included, which is the point: it shuts one person out of a group that
stays open for everyone else. For a banned ID nothing happens — no reply, dead buttons, nothing
recorded in the group log, no stickers learned, and their scheduled tasks stay in place but never
fire. Removing the ID restores all of it; nothing is deleted meanwhile. An ID on both lists stays
banned, and startup says so in the log.

## LLM provider

`LLM_PROVIDER` selects the backend, and whichever you pick, the model must support tool calling.

| `LLM_PROVIDER`      | Example `LLM_MODEL`                 |
|---------------------|-------------------------------------|
| `openai`            | `gpt-6.1-sol`                       |
| `anthropic`         | `claude-opus-5-5`                   |
| `openai-compatible` | any model id the server understands |
| `codex`             | any model the ChatGPT plan offers   |

- **`openai`** and **`anthropic`** — each talks to its vendor's own API and takes any model id the vendor
  serves: startup asks the vendor's model list about it, so a typo fails there rather than on the
  first message. Anthropic's list also states the model's window, output ceiling and what it takes,
  and Vusan reads those from there; OpenAI's states nothing, so an OpenAI model is assumed to be what
  the current generation is — a reasoning model that sees images and calls tools.
- **`openai-compatible`** — any OpenAI-compatible server, remote or local, taking whatever model
  string it serves.
- **`codex`** — a ChatGPT subscription instead of an API key; see
  [ChatGPT subscription](#chatgpt-subscription).

| Variable                      | Default                   | Description                                                                     |
|-------------------------------|---------------------------|---------------------------------------------------------------------------------|
| `LLM_BASE_URL`                | —                         | Server address. Required by `openai-compatible`, unused by the others.          |
| `LLM_COMPATIBLE_API`          | `completions`             | `completions` or `responses`. Read by `openai-compatible` alone.                |
| `LLM_REASONING_EFFORT`        | model default             | Reasoning depth: `none`, `low`, `medium`, `high`, `xhigh`, or `max`.            |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `300`                     | Seconds one LLM call may hang before Vusan gives up and replies with an error.  |
| `LLM_CONTEXT_WINDOW_TOKENS`   | model metadata or `16384` | Context size override.                                                          |

`LLM_BASE_URL` and `LLM_COMPATIBLE_API` are read by `openai-compatible` alone. Give the address
without `/v1`: Vusan appends `/v1/chat/completions`, or `/v1/responses` with
`LLM_COMPATIBLE_API=responses`, so `LLM_BASE_URL=https://api.deepseek.com` is called at
`https://api.deepseek.com/v1/chat/completions`. The default is the API every compatible server speaks;
`responses` is OpenAI's newer one, for a server that offers it. `openai` and `codex` read neither:
they always call OpenAI's own address on the Responses API, the one where tools work alongside
reasoning.

`LLM_REASONING_EFFORT` applies to every provider. Which efforts work depends on the model: `codex`
and `anthropic` check yours at startup, and so does `openai-compatible` when its server lists the
model's efforts, as DeepSeek's does; `openai` checks on the first turn. On `anthropic` the values are
`low` to `max`, and `none` stops startup. Vusan needs a Claude model from 4.6 on, which thinks
adaptively on every turn here; an older one, such as `claude-haiku-4-5-20251001`, stops startup.
Without an effort a Claude model runs at its own default, which is `medium` on Opus 5.5 and Haiku 5.5
and `high` on the others. Raise the timeout for slow local servers and heavy reasoning models: a Fable
turn at a high effort can run for minutes.

An `openai-compatible` model's window is read at startup from the server's model list when the list
states one, as DeepSeek's does; otherwise the bot assumes 16,384 tokens, so set
`LLM_CONTEXT_WINDOW_TOKENS` whenever such a model has a different window. An `openai` model is assumed
to have the window of OpenAI's current generation, 1,050,000 tokens; an `anthropic` model's is read
from Anthropic's model list at startup. The variable overrides any of them. Vusan reserves part of that
window for the response, tool results and estimation error, then fits only complete conversation
interactions into the remainder.

Third-party servers all take the same shape, with `LLM_PROVIDER=openai-compatible`:

```dotenv
# DeepSeek
LLM_API_KEY=sk-qwerty
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-flash
```

## ChatGPT subscription

`LLM_PROVIDER=codex` runs Vusan on a ChatGPT Plus, Pro, Business or Enterprise plan instead of a
paid API key. There is no `LLM_API_KEY`: the credentials come from the
[Codex CLI](https://developers.openai.com/codex/cli), which has to sign in on the bot host.

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-6-astra
```

Sign in as the user that runs the bot:

```bash
codex login
```

Add `--device-auth` when the host has no browser. `codex login status` checks the session, and
`codex logout` ends it, after which Vusan replies that its connection needs renewing until you sign
in again.

**Where the credentials live.** `codex login` writes `~/.codex/auth.json` in the home of whoever ran
it, so a bot under its own user already has its own session. `CODEX_HOME` points both the CLI and
Vusan somewhere else; set it in `.env` and pass the same value to `codex login`:

```dotenv
CODEX_HOME=/home/vusan/.codex-vusan
```

Vusan reads that `auth.json` and cannot reach the OS keyring, so if your setup switched the CLI to
keyring storage, put `cli_auth_credentials_store = "file"` back in that directory's `config.toml`.
Treat the file like a password, because it holds live access and refresh tokens, and in a container
mount its directory read-write.

It is reread before every request, so a later CLI login, logout, sandbox switch or token rotation
takes effect without restarting the bot. Sharing one file with an interactive CLI is fine — a
refresh from either side keeps the other working, and the newer version wins. A ChatGPT access token
lives ten days; Vusan rotates it about a day before expiry and writes it back atomically with
owner-only permissions. `codex login --with-access-token` is also accepted, but that credential has
no refresh token: Vusan uses it while it is fresh and asks for a replacement as expiry approaches,
instead of failing a request after it expires.

**What the plan's catalog decides.** The models your plan actually offers are read at startup, and
six settings are checked against that list:

- **`LLM_MODEL`** — startup fails with the available ids if it does not match. Codex and the OpenAI
  Platform API expose different model sets, so a Platform-only id would otherwise fail on the first
  message with an opaque error.
- **Vision** — the chat model becomes the default vision model only when the catalog says it reads
  images.
- **`LLM_REASONING_EFFORT`** — an effort the model does not offer stops startup instead of a turn.
- **The context window** — comes from there too, so `LLM_CONTEXT_WINDOW_TOKENS` is only an override.
- **`CODEX_SERVICE_TIER`** — a tier the model is not served at stops startup.
- **How wordy replies are** — the model's default verbosity is taken from there and sent with every
  request, the way the Codex CLI does it. Without a catalog the backend's own, wordier default applies.

Older catalog responses without capability metadata retain the compatible image-capable default.

**When a brand-new model is missing.** That list is filtered by the Codex client version Vusan
claims, so a model released alongside a newer CLI is absent from it and startup rejects it as one the
plan does not offer. At startup Vusan claims the newest version it can find: the one this build knows
of, the `codex` installed on the host, and the latest release on GitHub, read where the CLI's own
update check reads it. The startup log says which one won. So a container with no CLI at all follows
Codex releases on its own, and only a host that cannot reach `api.github.com` is left with the version
this build knows of. To pin a version yourself, on such a host or when the automatic choice is wrong:

```dotenv
CODEX_CLIENT_VERSION=0.162.0
```

It has to look like a Codex CLI version, and anything else stops startup. One older than the model
needs hides it again, so use the release the model shipped with.

**A faster serving tier.** `CODEX_SERVICE_TIER=priority` buys roughly the speed-up the Codex CLI
offers as `/fast`, at the price of spending the plan's allowance quicker. It is off unless you set
it, applies to the chat model and its fallback alone — a vision or addressing model on the plan runs
at the standard tier — and startup fails if the chosen model does not offer the tier, so check the
model first:

```dotenv
CODEX_SERVICE_TIER=priority
```

**Pictures on the plan.** [Image generation](#image-generation) can run on the subscription too, from
the same allowance, with `IMAGE_PROVIDER=codex` — whether or not the chat itself runs on `codex`; the
account is the one `CODEX_HOME` points at.

**Web search on the plan.** The subscription also answers a [web search](#web-search) of its own,
`answerFromWeb`, with no search key at all. Each search draws on the same allowance as the turns, and
a good deal more of it than a search through a key does; switch it off to keep the plan for answering:

```dotenv
CODEX_WEB_SEARCH_ENABLED=false
```

Two limits are worth knowing. Usage is metered against the plan rather than billed per token, so a
heavy day ends in a "usage limit reached" reply that says how long the window still has to run —
unless a [fallback provider](#a-second-provider-behind-the-first) takes over. And this route depends
on an endpoint OpenAI ships for its own Codex clients rather than documents for third-party apps, so
an OpenAI-side change can break it; `LLM_PROVIDER=openai` with an API key stays the supported
fallback.

**How much the plan holds.** OpenAI states only the share of each window used, never its size, so
Vusan logs a `codex limits:` line every time a share moves, with the calls, pictures and tokens spent
since the previous move. Read side by side, those lines say how many tokens one percent of the
five-hour or the weekly window holds on your plan and model.

## A second provider behind the first

A second provider can stand behind the first for when it is out: a subscription whose window is
spent, a key whose credit ran dry, a sign-in that expired. It is the same set of variables again
under `LLM_FALLBACK_`, and any provider may take the role, the subscription included — in either
direction, but not on both sides, since the `CODEX_*` settings and the signed-in account are one set:

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-6-astra

LLM_FALLBACK_PROVIDER=openai
LLM_FALLBACK_MODEL=gpt-6.1-sol
LLM_FALLBACK_API_KEY=sk-proj-qwerty
```

The same model on both sides is the ordinary case: the plan pays for it until its window is spent,
the key after that.

`LLM_FALLBACK_BASE_URL`, `LLM_FALLBACK_COMPATIBLE_API`, `LLM_FALLBACK_REASONING_EFFORT`,
`LLM_FALLBACK_REQUEST_TIMEOUT_SECONDS` and `LLM_FALLBACK_CONTEXT_WINDOW_TOKENS` mean what their
`LLM_` counterparts mean; the timeout follows the primary's when unset.

A call the provider's server fumbled — a `5xx`, an overloaded server, a stream cut short — is made up
to three times in all, a second or two apart, on any provider and with or without a fallback. What
still fails is the fallback's when it is one of the failures below.

These failures switch: the plan's usage limit, credentials it no longer accepts, a rate limit, an
overloaded server (`503`, `529`), a server error that kept failing (`500`, `502`, `504`), a
connection that timed out or dropped. The call that
ran into it is repeated on the fallback with the fallback's own model, so the turn finishes instead
of ending in an error reply, and every later call — turns, history recaps, group-log digests, vision
on the chat model — goes the same way for as long as the primary is held out. How long that is
depends on the failure: a usage limit until the deadline the refusal named, a dead sign-in for half
an hour, a rate limit or an outage for two minutes, long enough that a blip does not flip every call
back and forth and short enough that the fallback is not paid for once it has passed. Then one call
probes the primary again, and the bot returns to it or waits another round. A content refusal is not
an outage — it repeats on any provider — whether the provider answers it with an error or the model
declines in its reply, and the user is asked to word the request differently.

While the fallback is answering, the status message a turn puts up names it, so the chat shows which
model is behind the reply rather than only the log. When the primary ran out of its usage limit and
named the time it resets, a second line under it says how long until the usual model is due back; any other
failure has no such time to give, only the moment of the next probe, so the line stays silent about
it. The agent is told which model is answering, so someone who asks what it is running on gets the
truth instead of the one the system prompt names.

Pick a fallback that can do what the primary does: one that sees images if the chat model does,
because vision rides on the same switch, and one whose context window is not much smaller, because
the history is planned against the primary's. Image generation does not follow: it has a provider of
its own, and on `IMAGE_PROVIDER=codex` it spends the same subscription and fails with it until the
window resets, so a deployment that wants pictures through the outage draws on `IMAGE_PROVIDER=openai`.

## How many requests at once

| Variable                            | Default | Description                                                  |
|-------------------------------------|---------|--------------------------------------------------------------|
| `MAX_CONCURRENT_TURNS`              | `8`     | Requests Vusan works on at the same time.                    |

Vusan answers one person's messages in a chat one at a time, in order, so a follow-up sent while it is
still working starts with the previous answer already known. Up to three wait that way; the next is
told to hold on.

`MAX_CONCURRENT_TURNS` is the other half: how many *different* people Vusan serves at once. Waiting
messages do not count toward it. Every request is a model
call with whatever tools it decides to run, so the number to match is what your provider accepts at
once, not what the machine could hold.

Beyond that number people wait their turn, with the usual typing indicator, and the answer simply
arrives a little later. Only when the queue is already several times the limit does Vusan say it is
overloaded instead of queueing further. Scheduled tasks always wait rather than being turned away —
nobody is watching one arrive, and refusing it would mean skipping the run.

## One person alone in a group

A group is a shared room, and somebody chatting with Vusan alone at four in the morning leaves the
rest of it a hundred messages to scroll past at breakfast. So in a group Vusan keeps an eye on who
holds the floor: once it has answered one person eight times in a row while nobody else wrote
anything in that chat (or in that forum topic), its eighth reply rounds the exchange off and says it
will pick things up later, and that person's next messages go unanswered — no reply, no reaction,
nothing to scroll. The floor opens again the moment anybody else writes in the chat, to Vusan or
not, or after an hour without an answer from it. Both numbers are fixed; they came out of a month of
a live group, where only one exchange in a hundred ran that long.

Nothing else changes: a message Vusan would have answered anyway is just not answered. Scheduled
tasks still fire, Vusan still speaks up on its own under its own daily budget, slash commands and the
buttons of a question it asked still work, and a private chat — with nobody else to read it — is
never limited. The owner is not either.

## Personality

The agent ships with a built-in personality named "Vusan", kept generic so each deployment can
define its identity, tone and interaction style. Override it with a file, or leave the variable unset
to keep the built-in one. The operational rules for output and tools are always appended separately
and cannot be removed by a custom personality.

| Variable           | Description                                                                           |
|--------------------|---------------------------------------------------------------------------------------|
| `PERSONALITY_FILE` | Path to the personality text. Unreadable fails startup; blank falls back to built-in. |

## Appearance

Text-to-image invents a face on every call, so "send me a selfie" drawn from the prompt alone shows
a different person each time. Vusan builds a picture of itself from a reference photo instead, which
keeps one face across every picture it sends. The reference is the bot's own Telegram avatar unless
a file overrides it, so a deployment that set an avatar in [@BotFather](https://t.me/BotFather)
already has this. The same photo is what a [round video message](#voice-output) puts in its circle,
so it is read whenever either of those is enabled.

| Variable          | Description                                                                                           |
|-------------------|-------------------------------------------------------------------------------------------------------|
| `SELF_IMAGE_FILE` | PNG, JPEG or WebP reference photo. Unreadable fails startup; unset falls back to the Telegram avatar. |
| `APPEARANCE_FILE` | Path to notes on what a portrait cannot show — height, build, tattoos, usual clothes.                 |

Point `SELF_IMAGE_FILE` at the original whenever you have it: Telegram serves an avatar at 640x640,
and a bigger, sharper face gives the image model more to hold on to. Keep the written notes to a few
sentences. They go to the image model and nowhere else, so if you also want the agent to describe
its looks in words, say it in the personality too.

## Optional tools

Each optional tool is enabled by one env variable. If it is missing, that tool is skipped at startup
with a `WARN` log and Vusan keeps running.

| Variable                | Enables                                   | Notes                                      |
|-------------------------|-------------------------------------------|--------------------------------------------|
| `TAVILY_API_KEY`        | Web search, image search, page rendering  | See [Web search](#web-search)              |
| `SEARXNG_URL`           | Fallback web and image search             | See [Web search](#web-search)              |
| `GIPHY_API_KEY`         | GIF lookup                                | Giphy                                      |
| `KLIPY_API_KEY`         | GIF, meme and clip lookup                 | KLIPY; used instead of Giphy when both are set |
| `ELEVENLABS_API_KEY`    | Voice messages and round video messages   | See [Voice output](#voice-output)          |
| `OPENAI_STT_API_KEY`    | Voice input, sound of a video, transcribing any recording | Reuse your OpenAI key      |
| `IMAGE_PROVIDER`        | Image generation                          | `openai` with `IMAGE_API_KEY`, or `codex`; see [Image generation](#image-generation) |
| `VISION_MODEL`          | Vision on a chat model that cannot see    | See [Vision](#vision)                      |
| `REGOLITH_URL`          | Shell sandbox                           | See [Sandbox](#sandbox)                |

### GIFs, memes and clips

Giphy finds GIFs. KLIPY finds GIFs, meme pictures and short clips, and answers alone when both keys
are set. KLIPY's [integration requirements](https://docs.klipy.com/integration-requirements) expect
requests from the end user's own device, and a bot sends them from its server, so running Vusan
with a KLIPY key needs their prior written approval from developers@klipy.com. The key the sign-up
form issues is not that approval.

### Web search

Reading a page needs nothing: `readPage` is built in, fetches any public `http` or `https` address
and reduces it to its article text, so a link the user sends is answered from, and a search result
is read in full, on every setup. With a Tavily key it becomes the fallback for `extractPageContent`.
Three providers cover the search itself, and each can run without the others:

| Variable             | Tools                                             | Role                                                    |
|----------------------|---------------------------------------------------|---------------------------------------------------------|
| `TAVILY_API_KEY`     | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search; the default page read.    |
| `SEARXNG_URL`        | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus category scoping.               |
| `LLM_PROVIDER=codex` | `answerFromWeb`                                   | A researched answer with its sources, on the plan.      |

Tavily leads on both: its results are cleaned-up page extracts rather than snippets, and
`searchImages` describes what is in each photo. Tavily returns the images of a search as a side list
of about five, whatever `maxResults` asks, so a request for ten arrives short; `metaSearchImages`
is what fills a larger order, as a second album. Tavily's `extractPageContent` renders a page before
reading it, which is what `readPage` cannot do for a page that draws its content with scripts, so
the agent reads with it first and falls back to `readPage` when it fails, returns nothing or cuts a
long page short — `readPage` continues one in parts. [SearXNG](https://docs.searxng.org) is self-hosted,
so it costs nothing per call and keeps search working when Tavily fails or runs out of quota.
`metaSearch` also scopes a query with `categories` (`news`, `it`, `science`, `videos`, `music`,
`files`, `social media`, `map`), which Tavily cannot do — those categories query different engines,
so they still answer when the general ones are rate-limited.

`answerFromWeb` needs no key: on a [ChatGPT subscription](#chatgpt-subscription), as the provider or
as the fallback behind another one, OpenAI runs the search itself and hands back a written answer
with the pages it rests on instead of a list of results. That makes it the only search a setup with
neither key has, and the one the agent turns to for a question that takes several pages to answer.
It is slower than the other two — ten seconds or more — and spends the plan's allowance, so the agent
still searches with Tavily or SearXNG first when they are there. `CODEX_WEB_SEARCH_ENABLED=false`
leaves it out.

Point `SEARXNG_URL` at the instance root, without the `/search` path:

```dotenv
SEARXNG_URL=http://searxng:8080
```

The instance must serve JSON. SearXNG ships with `formats: [html]` only and answers anything else
with `403`, so add `json` in its `settings.yml`:

```yaml
search:
  formats:
    - html
    - json
```

### Voice output

`ELEVENLABS_API_KEY` enables spoken replies; these tune the voice they come out in.

| Variable               | Default                | Description                      |
|------------------------|------------------------|----------------------------------|
| `ELEVENLABS_VOICE_ID`  | `VD1if7jDVYtAKs4P0FIY` | Voice used for generated speech. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v4`            | ElevenLabs TTS model.            |

The same key also enables the round video message — the reference photo from
[Appearance](#appearance) in the circle, the same voice over it, drawn by `ffmpeg`. A deployment
with no reference photo at all, meaning no `SELF_IMAGE_FILE` and no avatar in
[@BotFather](https://t.me/BotFather), has nothing to put in that circle and is offered only the
voice message.

### Voice input

`OPENAI_STT_API_KEY` enables listening; this picks what does the listening.

| Variable                          | Default             | Description                               |
|-----------------------------------|---------------------|-------------------------------------------|
| `OPENAI_STT_MODEL`                | `gpt-transcribe`    | Speech-to-text model.                     |

Vusan hears the first five minutes of anything. A voice message longer than that is refused, and of a
video she watches, or any other recording she is handed, only those minutes are transcribed. The same
key lets Vusan transcribe any recording a turn can name, not only the one a message arrives as: an
audio or video file someone sent, a track or video she fetched, or a piece she cut out in the sandbox.

### Image generation

`IMAGE_PROVIDER` enables the `generateImage` and `editImage` tools and says who renders: `openai` is
the OpenAI image API (`/v1/images/generations`), billed per image against `IMAGE_API_KEY`, which can
reuse your OpenAI key; `codex` is the ChatGPT subscription signed in through `CODEX_HOME`, with no key
at all and whatever the chat itself runs on. Without a provider the tools stay out. The agent picks the
aspect ratio per request; the model and quality are operator-controlled so generation cost stays
predictable. A picture of the bot itself goes to the edit endpoint instead, with the reference photo
from [Appearance](#appearance) as its subject.

| Variable         | Default                                    | Description                                                            |
|------------------|--------------------------------------------|------------------------------------------------------------------------|
| `IMAGE_PROVIDER` | unset                                      | `openai` or `codex`.                                                   |
| `IMAGE_API_KEY`  |                                            | The key `openai` renders with.                                         |
| `IMAGE_MODEL`    | `gpt-image-2.5-flare`                      | Image model, on either provider.                                       |
| `IMAGE_QUALITY`  | `medium`                                   | Rendering quality: `low`, `medium`, `high`, `xhigh`, `max`, or `auto`. |

`xhigh` and `max` render only on the `gpt-image-2.5` models; every earlier model stops at `high`
and fails the request if you ask for more. Quality drives the price per image, so raise it
deliberately.

OpenAI filters both the description it is given and the picture it produced, and that filter cannot
be turned off — Vusan asks for the less restrictive of its two settings. A refusal still comes back
as a refusal: Vusan says the picture cannot be
drawn and offers to change it, instead of reporting that something broke.

Editing keeps the original as faithfully as the model allows, which is what holds a face still in a
picture of the bot itself. On `gpt-image-1`, `gpt-image-1-mini` and `gpt-image-1.5` that costs extra
input tokens; `gpt-image-2` and later always edit that way and cost the same either way.

An edit works on everything one message carried, so an album sent with "merge these" becomes a single
picture, and Vusan can put itself into it with the same face its self-portraits use. Wallpaper and
banner framings, and any size beyond the three classic ones, need a `gpt-image-2` or newer model —
older ones fall back to the nearest size they have rather than failing. Finished pictures arrive as
JPEG, because Telegram re-encodes every photo it delivers anyway and the smaller upload is what keeps
a `max`-quality picture inside Telegram's own size limit.

Three differences are worth knowing before relying on `codex`: images count against your ChatGPT
usage limit, so a heavy image day can exhaust the same quota that answers messages when the chat runs
there too; the model chooses its own output dimensions, so the requested aspect ratio is a hint rather
than a guarantee; and it filters the way ChatGPT does, with no strictness to choose.

### Vision

Vision lets the agent inspect photos, sampled video frames and images in Telegram channel posts. It
also lets Vusan learn the sticker sets a chat uses, search them by meaning, and choose replies from
them. These features need a model that accepts images. By default that is the chat model itself, so
an `openai`, `anthropic` or `codex` setup needs nothing extra. A look runs there at the model's own
default effort rather than `LLM_REASONING_EFFORT`, which is tuned for turns, and caches nothing,
since a picture is looked at once.

When the chat model cannot accept images, `VISION_MODEL` runs vision on a model of its own and the
chat model keeps answering everything else. On its own the model runs on the chat provider with the
chat key, address and timeout; with `VISION_PROVIDER` it is a provider of its own, read like the chat
one from the same variables under the `VISION_` prefix. Either way its effort and window are its
own, never the chat's:

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-pro
LLM_API_KEY=sk-qwerty

VISION_PROVIDER=openai
VISION_MODEL=gpt-6-luna
VISION_API_KEY=sk-proj-qwerty
```

| Variable                         | Default           | Description                                                                          |
|----------------------------------|-------------------|--------------------------------------------------------------------------------------|
| `VISION_MODEL`                   | —                 | Enables a vision model of its own.                                                   |
| `VISION_PROVIDER`                | the chat provider | `openai`, `anthropic`, `openai-compatible` or `codex`.                               |
| `VISION_API_KEY`                 | —                 | Its own key. Required with `VISION_PROVIDER`, except on `codex`.                     |
| `VISION_BASE_URL`                | —                 | Server address. Required with `VISION_PROVIDER=openai-compatible`, unused otherwise. |
| `VISION_COMPATIBLE_API`          | `completions`     | `completions` or `responses`, for `VISION_PROVIDER=openai-compatible`.               |
| `VISION_REASONING_EFFORT`        | model default     | Reasoning depth of every look, the values `LLM_REASONING_EFFORT` takes.              |
| `VISION_REQUEST_TIMEOUT_SECONDS` | the chat's        | Seconds one look may hang. Read with `VISION_PROVIDER` only.                         |
| `VISION_CONTEXT_WINDOW_TOKENS`   | model metadata    | Context size override.                                                               |

An `openai-compatible` model sees only when the server's own model list says it takes images:
DeepSeek's says so of `deepseek-flash`, which then needs no vision model of its own, and not of
`deepseek-v4-pro`. A server that lists nothing of the kind may serve anything, so with it vision stays
off until `VISION_MODEL` is set, even when the model itself does accept images. A vision model always
wins when it is set, even where the chat model could have looked at the picture itself, and is taken
at its word about seeing — unless its own server's list or the plan's catalog says it takes no images,
which stops startup instead of failing every look.

Sticker replies come with vision and stay off without it. They are the one thing here that spends
on its own: a set a chat keeps using is pulled in and each of its stickers is described once, up to
sixty vision calls per set, at most three new sets a day for any chat and six for the whole bot. A
deployment that would rather not pay for that turns it off:

| Variable           | Default | Description                                                           |
|--------------------|---------|-----------------------------------------------------------------------|
| `STICKERS_ENABLED` | `true`  | Set to `false` to learn no sticker sets and offer no sticker replies. |

With no vision at all, a startup `WARN` says so and Vusan answers without looking at attachments;
Telegram channel posts still come back, as text only.

## Sandbox

Vusan can keep one persistent Linux home **per person, across all chats** — a real shell for projects,
media, documents and data, with files that survive new messages, `/clear` and restarts. The sandbox
is a **Regolith server**: a separate project with its own deployment, which runs the commands and
confines them. It is **off by default**, and the bot grows the shell tools only once it can reach one.
What the model can do with it, what the bot expects of the server and where its limits come from are
in [the sandbox guide](sandbox.md).

These are the bot's side of it, and belong in `.env`:

| Variable         | Default | Description                                                         |
|------------------|---------|---------------------------------------------------------------------|
| `REGOLITH_URL`   | —       | Address of the Regolith server. Unset means the tools do not exist. |
| `REGOLITH_TOKEN` | —       | Its API token, the same value the server was started with.          |

Without `REGOLITH_URL` the sandbox tools are not registered and the bot never mentions them; a URL
without a valid token stops startup. The token takes 32 to 256 printable characters, and `openssl rand -hex 32` makes one
both sides accept. Regolith's default address reaches only its own machine, so
[the sandbox guide](sandbox.md#pointing-the-bot-at-it) shows which address to give the bot.

Everything else — the sandbox image, memory, home size, timeouts, retention and network policy — is
configured on the server, and the bot reads its limits from it.

## Publishing to the web

Vusan can put a finished page, game or small web app on the public internet at one address per person.
There is nothing to configure here: it is the Regolith server's doing, so a sandbox that can publish
brings the tools with it and one that cannot says so to the model. What a site may hold, where it is
served and how long it is kept belong to that server; see [the site guide](sites.md).

## Conversation

Stored per Telegram user **and chat**, so a person keeps one thread in each place they talk to the
bot, and it holds only the turns the bot took part in. Recent interactions are replayed exactly;
older ones are merged by the active chat model into a persisted semantic recap. Raw rows remain
available for a bounded time but never enter the prompt again after their recap checkpoint.

Every limit below applies to one such thread. Someone active in a DM and two groups keeps three of
them, each with its own recap and its own retention.

| Variable                      | Default | Description                                        |
|-------------------------------|---------|----------------------------------------------------|
| `CONVERSATION_RETENTION_DAYS` | `90`    | Days summarized raw interactions remain in SQLite. |

The last 40 interactions are offered to the model verbatim, as many of them as the context window
fits; past that count the older ones are folded into the recap. Once recapped, the raw rows stay for
the retention period, at most a hundred per thread, and never enter the prompt again. Cleanup runs
when that thread completes a turn, and again in the maintenance pass every six hours, which is what
reaches a conversation nobody has come back to. `/clear` removes the raw transcript and its recap for the chat it
was sent from, leaving the caller's other chats and everyone else's history alone; durable memory
and scheduled tasks remain.

## Memory

Alongside the [conversation](#conversation) history, the agent keeps a durable **memory** that
survives the user clearing the chat: personal memory keyed by user, and shared group memory keyed by
chat. Built in, with nothing to configure: each user and each chat holds up to twenty entries, and
the oldest is evicted past that.

Personal memory follows a person between chats, and their sandbox files are shared too. The
history is not: what someone told the bot in a DM is not replayed inside a group, and two groups
never see each other's exchanges — so ask the bot to remember something if it should follow you
everywhere. Both need to know who you are, so neither is offered to a sender Telegram delivers under
an account shared with other people: an anonymous group admin, or a linked channel's posts.

## Group log

Separate from conversation history and keyed by chat alone, with no sender in the key. In groups the
bot records every message it receives, including the ones not addressed to it, so it can answer
"what did I miss" and recap a day. Private chats are never recorded — they already have conversation
history above. Nothing is recorded for a chat outside `ALLOWED_IDS`, and nothing for a sender in
[`BANNED_IDS`](#who-vusan-answers).

| Variable                   | Default | Description                                                             |
|----------------------------|---------|-------------------------------------------------------------------------|
| `GROUP_LOG_ENABLED`        | `true`  | Set to `false` to record nothing and drop the group-log tools entirely. |
| `GROUP_LOG_RETENTION_DAYS` | `30`    | Days a recorded message stays in SQLite.                                |

A chat is also held to fifty thousand rows, so a group busier than a thousand messages a day is
trimmed by count before it is trimmed by age. Each group turn carries the last fifteen messages of
the past hour as a glance at what the chat was just saying.

What a row holds: the text (collapsed and capped at 2000 characters, 1000 for a forwarded post), who
sent it and when, the kind of message, a short label for non-text content (`🐤 UtyaDuck`, `0:14`,
`report.pdf`), the channel or person a forward came from, and the message it replied to. What it
never holds: the media itself, or the Telegram file ids that would let it be fetched later. The
bot's own replies into the group are recorded too; replies redirected to a user's DMs are not.

Reading it back costs no extra model call while the requested window fits the context budget. When
it does not, each closed day is compressed into a one-off recap by the active chat model and cached,
so a repeated weekly or monthly question is answered from cache. The current day is never cached,
because it is still being written to.

Cleanup is amortized over inserts rather than scheduled, so it runs every few hundred recorded
messages in a chat. Asking the agent to forget the group log wipes that chat's messages and every
cached daily recap; `/clear` does not touch it, since the log belongs to the group rather than to
the person running the command.

## Answering without a mention

In a group Vusan answers a mention, a reply to its message, or a command. With this on, it also
answers a message that calls it by name — «robin, what do you think» — or follows up on what it just
said, with no mention at all. A small separate model decides whether each such message is meant for
Vusan; everything that calls it outright is answered exactly as before, without asking that model.

```dotenv
ADDRESSING_ENABLED=true
ADDRESSING_PROVIDER=openai
ADDRESSING_MODEL=gpt-6-luna
ADDRESSING_API_KEY=sk-proj-qwerty
ADDRESSING_NAMES=robin,robbie
```

It is off until `ADDRESSING_ENABLED=true` turns it on, and then needs a model of its own and the
[group log](#group-log): either missing stops startup. A model set without the switch only
earns a startup `WARN`.

Like a vision model, it runs on the chat provider with the chat key, address and timeout unless
`ADDRESSING_PROVIDER` names a provider of its own, read like the chat one under the `ADDRESSING_`
prefix; its effort and window are its own either way.

| Variable                             | Default            | Description                                                                              |
|--------------------------------------|--------------------|------------------------------------------------------------------------------------------|
| `ADDRESSING_ENABLED`                 | `false`            | Turns the feature on; needs `ADDRESSING_MODEL` and the group log.                        |
| `ADDRESSING_MODEL`                   | —                  | The model that decides, never the chat model.                                            |
| `ADDRESSING_NAMES`                   | from the profile   | Spellings the chat uses, comma-separated.                                                |
| `ADDRESSING_PROVIDER`                | the chat provider  | `openai`, `anthropic`, `openai-compatible` or `codex`.                                   |
| `ADDRESSING_API_KEY`                 | —                  | Its own key. Required with `ADDRESSING_PROVIDER`, except on `codex`.                     |
| `ADDRESSING_BASE_URL`                | —                  | Server address. Required with `ADDRESSING_PROVIDER=openai-compatible`, unused otherwise. |
| `ADDRESSING_COMPATIBLE_API`          | `completions`      | `completions` or `responses`, for `ADDRESSING_PROVIDER=openai-compatible`.               |
| `ADDRESSING_REASONING_EFFORT`        | the least it takes | Reasoning depth of every verdict; see below for what the least is.                       |
| `ADDRESSING_REQUEST_TIMEOUT_SECONDS` | the chat's         | Seconds one verdict may hang. Read with `ADDRESSING_PROVIDER` only.                      |
| `ADDRESSING_CONTEXT_WINDOW_TOKENS`   | model metadata     | Context size override.                                                                   |

The model gets the least reasoning it is known to take unless `ADDRESSING_REASONING_EFFORT` says
otherwise: on `openai` `none` where the model takes it and `low` where it does not — OpenAI's models
disagree on that and nothing lists it, so the model is asked once at startup with a request of a few
tokens; on `codex` the lowest the plan's catalog lists; `low` on `anthropic`; and its own default on
`openai-compatible`, whose models are anyone's guess. A yes-or-no over a few
lines of chat needs no reasoning, and every token of it is latency. The
verdicts were measured on small OpenAI models — `gpt-5.6-luna` made the fewest wrong calls of eight
and no false yes at all.

- **Names** — the bot's Telegram name is always one of them. Without `ADDRESSING_NAMES` the other is
  its handle minus the `bot` ending, so `@robinbot` answers to «robin». A name matches at the start of
  a word in any case, so an ending the language adds to it still counts.
- **What is asked about** — a message that names Vusan, one from someone whose request it is still
  answering, and any message within five minutes of its reply while that reply is still among the
  last six lines. Nothing else in the chat is ever sent anywhere.
- **What is sent** — that one message and at most six lines before it, none older than ten minutes,
  with names. At most twenty messages per chat a minute; past that, and on any error or a verdict
  slower than five seconds, Vusan simply stays out.
- **What is not asked about** — voice messages, stickers, edits, forwards, commands, and messages
  from bots or from a channel.
- **While Vusan is busy** — a message it was called into by name waits its turn like a mention
  does, and is dropped without a word if the line is full. If the answer before it already covered
  it, or it turns out to be for somebody else, Vusan may stay silent.
- **In the log** — each verdict is a line,
  `ambient verdict: chat=[…] msg=[…] gate=[…] verdict=[…]`, without the text, and the message id finds
  the text in the group log when a verdict needs a second look.

It needs the [group log](#group-log), which is where the recent lines come from; with
`GROUP_LOG_ENABLED=false` it stays off, and startup says so.

## Diary

After a day ends, Vusan writes itself a short entry about what that day was like in each group: what
people talked about, who promised what, what was funny, how it felt about it. The last three entries
ride along on every turn in that group, so it can pick up yesterday's thread instead of meeting the
chat fresh every time.

```dotenv
DIARY_ENABLED=false
```

| Variable        | Default | Description                                            |
|-----------------|---------|--------------------------------------------------------|
| `DIARY_ENABLED` | `true`  | `false` writes nothing and sends no day anywhere.      |

- **What is sent** — once per group per day, that day's transcript from the [group log](#group-log)
  (up to 12,000 characters of it) goes to the chat model, with nobody having asked. That is the
  difference from a recap, which is made only when someone asks for one, and why this has a switch
  of its own.
- **Which days** — only a day that has ended, and only one with at least fifteen messages from
  people. Yesterday is written within a quarter of an hour of the bot being up; older days are not
  backfilled.
- **How long** — entries are kept for a week. Asking the agent to forget the group log drops the
  chat's entries with it.
- **Voice** — an entry is written in the bot's own [personality](#personality), in the language the
  chat mostly uses.

It needs the group log; with `GROUP_LOG_ENABLED=false` it stays off, and startup says so.

## Initiative

In a group Vusan normally speaks only when spoken to. With this on, it also looks over a chat people
are writing in and, now and then, puts an emoji on a message, says a line of its own — a joke on
what was just said, an opinion, a question about someone who has gone quiet — or answers a joke with a
sticker the chat uses. Most looks end with
nothing, which is the point: it is meant to feel like a member of the chat, not a notification.

```dotenv
INITIATIVE_ENABLED=false
```

It is on for every group in `ALLOWED_IDS`, and `INITIATIVE_ENABLED=false` is the only setting it
has: how often it looks and how much it says are fixed.

- **When it looks** — only at a chat somebody wrote in during the last fifteen minutes, after a pause
  drawn at random between fifteen and forty-five minutes, with at least three new messages since its
  last look or its own last line there, not while it is answering somebody there, and not within
  five minutes of its own last line — if it is already in the conversation, it does not talk over
  itself. A look one of these turned away is tried
  again five to ten minutes later.
- **What is sent** — up to sixty lines of the chat's last six hours, with names, to the chat
  model, with nobody having asked. Everything else Vusan does sends a group's messages somewhere
  only when it is addressed, which is why this has a switch. With the [diary](#diary) on, its entries
  go along, as do the names of people who used to write in the chat and have not for three days, and
  the chat's sticker shortlist when it has one.
- **How often it writes** — at most four lines of its own in one chat a day, a sticker counting as one,
  at least ninety minutes apart. A reaction is not held back by that.
- **What it may do** — nothing, one reaction, one short message or one sticker from the chat's own
  catalog, optionally as a reply. Reactions have a ceiling of twenty per chat a day beside the message
  limit.
- **Quiet hours** — it does not look at all from one to eight in the morning, on the bot's own clock.
- **Telling it to stop** — it reads the chat it is about to speak into, and is told to stay out when
  someone asked it to be quiet. For a hard stop, turn the switch off.
- **After a restart** — what it has looked at and how much it said today are kept in memory only, so
  a restart starts the day's count again; it also waits out one pause before its first look.
- **Forum groups** — a line that replies to a message lands in that message's topic; one that
  stands alone goes to General.

What to read in the log:

```
initiative look: chat=[telegram:-100123] fresh=[7] said=[1/4] reacted=[0/20] action=[reply] msg=[4812] chars=[41] text=[…] ms=[2140] why=[…]
initiative look: chat=[telegram:-100123] fresh=[4] said=[1/4] reacted=[0/20] action=[silent] ms=[1630] why=[two people sorting out a trip]
initiative skip: chat=[telegram:-100123] reason=[already talking] fresh=[5]
```

`action` is `silent`, `react`, `say`, `reply` or `sticker`, and `why` is the model's own short reason, there
so a day of looks can be read without opening the chat. A decision that was not carried out says so in
`result` (`over budget`, `no such target`, `no such sticker`, `emoji not allowed`, `no readable decision`). `skip` names
the gate that kept it from looking: `quiet`, `already talking` or `budget spent`.

It needs the group log; with `GROUP_LOG_ENABLED=false` it stays off, and startup says so.

## Scheduled tasks

Scheduled tasks are built in. The agent can schedule them in three forms:

- **`once <datetime>`** — fires once, then is disabled.
- **`every <interval>`** — fixed interval, minimum 5 minutes, timezone-independent.
- **`cron <UNIX expr>`** — clock-time patterns, evaluated in the task's timezone.

Nothing here is configured. One person may keep ten tasks. A task that could not fire within an hour
of its time — because Vusan was offline or the machine was asleep — is not run late: it gets a missed
notice in the chat and the schedule moves on to the next fire.

Separately from tasks a user asks for, the agent may schedule its own one-time follow-up when the
conversation gives it a reason to come back later ("ask how the exam went"). Those are counted
against a limit of their own, three per person, so they can never use up the quota for what the user
schedules, and they show up in `/tasks` like any other task, where the user can cancel them.

## Rights in a group

Vusan reads what the group lets it post before each turn and adapts: a chat that forbids photos is
never offered image generation, one that forbids polls gets no poll tools, and slow mode is stated
in the turn so the answer comes as one message instead of several that Telegram would drop. Nothing
has to be configured for this — but three group settings do change what it can do:

- **Privacy mode decides what it sees** — a bot is created with it on, and then Telegram hands it
  only mentions, replies to its messages and commands. The [group log](#group-log), recaps,
  [answering without a mention](#answering-without-a-mention), the [diary](#diary) and
  [initiative](#initiative) all need the whole chat: turn privacy mode off in
  [@BotFather](https://t.me/BotFather) with `/setprivacy` and add the bot to the group again, or
  make it an administrator.
- **A plain member obeys the group's default permissions** — turning off photos, stickers, polls,
  voice messages or files for everyone turns them off for Vusan too, and the matching tools
  disappear from that chat. Promoting it to administrator lifts all of it, slow mode included.
- **Losing the right to write pauses that chat's tasks** — removing Vusan from a group, or taking
  its send permission away, pauses every task scheduled there rather than letting each one run a
  full turn and fail at delivery. They stay listed in `/tasks`; resume them after adding it back.

A permission lookup that fails is treated as "unrestricted", so a Telegram hiccup never silently
strips capabilities.

## Command menu

Nothing to set up: the bot publishes its own command menu on every start, in each language it
speaks. That is the same list BotFather's `/setcommands` edits — there is no separate one — so an
edit made there survives only until the bot restarts. Groups get `/tasks` and `/clear` as ephemeral
commands: typed or picked from the menu, the command and its answer are seen by that person and the bot alone.
`/stop` stays in the open on purpose, so the chat sees why the bot fell silent. Private chats get the
same commands plain, since a client hides an ephemeral command where nothing is ephemeral.

## Agent loop

One turn may call tools repeatedly — search, read a page, search again — before it answers. The loop
has a ceiling, so a model looping on a broken tool stops costing tokens instead of running forever.

| Variable                | Default | Description                        |
|-------------------------|---------|------------------------------------|
| `AGENT_MAX_MODEL_CALLS` | `100`   | Model calls one turn may make.     |

Every round of tool calls costs one model call, so the default allows close to a hundred tool
rounds: enough to build a project in the sandbox, run it, fix it and send the result, or to research
a question across many sources. Reaching the ceiling is not an error: the last call is reserved for
a wrap-up in which the agent answers from what it gathered and says which parts it could not finish.
Three is the least that works — a request, a wrap-up and the nudge in between. What bounds the
cost of a long turn is not this number but the budget for tool results, which the agent can check
with `checkContextBudget`; lower this only to keep a cheap model from wandering.

## Storage and binaries

Where the database lives, and the one external binary that needs a credential of its own.

| Variable              | Default            | Description                                        |
|-----------------------|--------------------|----------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs are created on first run. |
| `YT_DLP_COOKIES_FILE` | —                  | Cookies for YouTube videos that ask for a login.   |

In Docker that directory is `data/` beside the compose file, and `VUSAN_HOST_DIR` in `.env` moves
it. It is a plain directory rather than a named volume because everything in it is yours to handle.
The database is a file you can copy for a backup, and
`SELF_IMAGE_FILE`, `APPEARANCE_FILE` and `YT_DLP_COOKIES_FILE` are files you put there. Create it
before the first start — `mkdir -p data` — because a bind mount Docker creates comes out owned by
`root` while the bot runs as uid 1000.

`VUSAN_IMAGE` in the same file picks the image the bot runs, `ghcr.io/helltar/vusan:latest` when
unset — set it to pin a release tag or to run a build of your own.

**Upgrading a deployment that used the `vusan-data` volume.** Earlier versions kept this in a named
volume. If `docker volume ls` shows `vusan_vusan-data`, copy it out once, with the bot stopped, or
it will start on an empty database and quietly build a new one:

```bash
docker compose down
mkdir -p data
docker run --rm -v vusan_vusan-data:/from -v "$PWD/data":/to alpine sh -c 'cp -a /from/. /to/'
sudo chown -R 1000:1000 data      # only if your login user is not uid 1000
docker compose up -d
```

Remove the old volume once the bot is up and its history is there.

`YT_DLP_COOKIES_FILE` must point to a Netscape-format `cookies.txt`; see the
[yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).

## Logging

Two levels, and one deployment quirk in how they are read.

| Variable            | Default | Description                                              |
|---------------------|---------|----------------------------------------------------------|
| `LOG_LEVEL`         | `INFO`  | Level for everything Vusan and its libraries log.        |
| `PROMPT_DUMP_LEVEL` | `OFF`   | `DEBUG` prints every request sent to the model, in full. |

Both levels are read by logback at startup rather than by `AppConfig`, and that is the one place
where writing them into `.env` is not enough: logback looks at the process environment,
while that file reaches the application through dotenv and nowhere else. In the compose deployment
they work from it anyway, because `env_file` turns it into real environment variables. A local run
does not, so set them where that run gets its environment — the IDE run configuration, or the
command line:

```bash
PROMPT_DUMP_LEVEL=DEBUG java -jar build/libs/vusan-*-all.jar
```

`PROMPT_DUMP_LEVEL=DEBUG` is a debugging aid, not something to leave on: it prints the whole request
on every LLM call — system prompt, conversation recap, replayed history, the current turn with its
context blocks, and each tool call with its result — so one turn can run to tens of thousands of
characters, and all of it is raw chat content sitting in the container log. It is independent of
`LOG_LEVEL`: the dump stays off when everything else is at `DEBUG`, and it still prints when the
rest is quieter.

A level neither of them recognizes — a typo, an empty value — is read as `DEBUG`, which is the
opposite of what a stray value usually intends.

## Health check

The image ships a Docker `HEALTHCHECK`, so `docker ps` and `docker compose ps` report whether the
bot is actually working rather than just whether its process exists:

```console
$ docker compose ps
NAME              STATUS
vusan-container   Up 6 hours (healthy)
```

There is nothing to configure. Vusan refreshes `/tmp/health` every 30 seconds for as long as its
`getUpdates` loop keeps cycling, and the check fails once that file is older than 90 seconds. A
container needs about two minutes to report the first `healthy`, which is the startup grace period.

What this catches is a bot that is running but no longer polling — the process is alive, the
container says `Up`, and nothing new appears in the logs. Long polling runs on a scheduled executor
that the JVM outlives, so without the heartbeat that state is invisible from outside. An idle chat
is not a failure, so the heartbeat follows poll cycles rather than incoming messages: a bot nobody
writes to all day stays healthy. When polling does stall, the log says so once, at `ERROR`, instead
of simply going quiet.

**When Telegram is unreachable.** A short outage does not make the bot unhealthy. Its polling loop
retries on an exponential backoff that tops out around a minute — well inside the staleness window —
and it resumes on its own once Telegram answers again. A check that failed here would fail on every
bot at once over something a restart cannot fix.

Past roughly 15 minutes of continuous failures that changes, and the container goes unhealthy —
worth acting on. The backoff stops growing and sleeps 15 minutes between attempts: a bot retrying
four times an hour is polling in name only, and once Telegram recovers it can stay silent for
another 15 minutes until its next attempt. Restarting resets the backoff and it picks up straight
away, which makes this one of the few failures a restart genuinely fixes. An outage that hangs
connections instead of refusing them trips the check sooner, because every attempt then burns its
100-second read timeout first.

To act on the status automatically, pair it with a container that restarts unhealthy services, or
point an uptime monitor at Docker's health state. To read it directly:

```bash
docker inspect --format '{{.State.Health.Status}}' vusan-container
```
