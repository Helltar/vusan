# Configuration

Everything the bot reads from `env/vusan.env`. Copy
[`env/vusan.env.example`](../env/vusan.env.example) and fill it in; blank values count as missing.

The optional [workspace service](#workspace) has a file and a guide of its own. That split is a
boundary rather than tidiness: the workspace process holds the Docker socket, so it is never handed
the bot's secrets. Do not merge the two, even when one machine runs both.

- **Getting started** — [Minimum setup](#minimum-setup) · [Who Vusan answers](#who-vusan-answers)
- **The model** — [LLM provider](#llm-provider) · [ChatGPT subscription](#chatgpt-subscription) ·
  [Daily token budget](#daily-token-budget)
- **Who it is** — [Personality](#personality) · [Appearance](#appearance)
- **Tools** — [Optional tools](#optional-tools) · [Web search](#web-search) ·
  [Voice output](#voice-output) · [Voice input](#voice-input) ·
  [Image generation](#image-generation) · [Vision](#vision) · [Workspace](#workspace)
- **What it remembers** — [Conversation](#conversation) · [Memory](#memory) ·
  [Group log](#group-log) · [Scheduled tasks](#scheduled-tasks)
- **In Telegram** — [Rights in a group](#rights-in-a-group) · [Command menu](#command-menu)
- **Running it** — [Agent loop](#agent-loop) · [Storage and binaries](#storage-and-binaries) ·
  [Logging](#logging) · [Health check](#health-check)

**How values are read.** Booleans take `true` or `false` in any case and nothing else. A value that
is set but unreadable — `AGENT_MAX_ITERATIONS=7O`, `GROUP_LOG_ENABLED=off`, a zero timeout, an ID
that is not a number — stops startup with a message naming the variable and what it was given,
rather than falling back to the default: writing the variable down at all says the default was not
wanted.

## Minimum setup

Five values, and Vusan runs:

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `ALLOWED_IDS`        | Telegram user and group IDs Vusan answers.           |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_PROVIDER`       | LLM backend; see [LLM provider](#llm-provider).      |
| `LLM_MODEL`          | Model id for the chosen provider.                    |
| `LLM_API_KEY`        | API key for the chosen provider.                     |

Vusan states `LLM_MODEL` in its own system prompt, so "which model are you?" is answered with what
is actually deployed instead of a guess.

## Who Vusan answers

`ALLOWED_IDS` accepts commas, whitespace or semicolons as separators. Positive IDs are users,
negative IDs are groups, and an allowlisted group admits everyone in it. Empty or unset means Vusan
answers nobody.

| Variable     | Default | Description                                                |
|--------------|---------|------------------------------------------------------------|
| `BANNED_IDS` | empty   | IDs Vusan ignores, whatever else allows them. Same format. |

The ban list wins over the allowlist, which is the point: it shuts one person out of a group that
stays open for everyone else. For a banned ID nothing happens — no reply, dead buttons, nothing
recorded in the group log, no stickers learned, and their scheduled tasks stay in place but never
fire. Removing the ID restores all of it; nothing is deleted meanwhile. An ID on both lists stays
banned, and startup says so in the log.

## LLM provider

`LLM_PROVIDER` selects the backend, and whichever you pick, the model must support tool calling.

| `LLM_PROVIDER`      | Example `LLM_MODEL`                 |
|---------------------|-------------------------------------|
| `openai`            | `gpt-5.4-mini`                      |
| `anthropic`         | `claude-sonnet-4-6`                 |
| `google`            | `gemini-2.5-flash`                  |
| `deepseek`          | `deepseek-v4-pro`                   |
| `openai-compatible` | any model id the server understands |
| `codex`             | any model the ChatGPT plan offers   |

- **The four native providers** — each talks to its vendor's own API and accepts only model ids it
  knows; an unrecognized id fails at startup and lists the supported ones.
- **`openai-compatible`** — any OpenAI-compatible server, remote or local, taking whatever model
  string it serves.
- **`codex`** — a ChatGPT subscription instead of an API key; see
  [ChatGPT subscription](#chatgpt-subscription).

| Variable                      | Default                   | Description                                                                    |
|-------------------------------|---------------------------|--------------------------------------------------------------------------------|
| `LLM_BASE_URL`                | —                         | Server address. Required by `openai-compatible`, unused by the others.         |
| `LLM_OPENAI_ENDPOINT`         | `completions`             | Which OpenAI API to call: `completions` or `responses`.                        |
| `LLM_REASONING_EFFORT`        | model default             | Reasoning depth: `none`, `minimal`, `low`, `medium`, or `high`.                |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `120`                     | Seconds one LLM call may hang before Vusan gives up and replies with an error. |
| `LLM_CONTEXT_WINDOW_TOKENS`   | model metadata or `16384` | Context size override.                                                         |

`LLM_OPENAI_ENDPOINT` applies to `openai-compatible` only, `LLM_REASONING_EFFORT` to
`openai-compatible` and `codex`. Give `LLM_BASE_URL` no `/v1` — the API path is appended for you.
Raise the timeout for slow local servers and heavy reasoning models.

Set `LLM_CONTEXT_WINDOW_TOKENS` whenever an `openai-compatible` model has a different window. Vusan
reserves part of that window for the response, tool results and estimation error, then fits only
complete conversation interactions into the remainder.

**Reaching OpenAI itself this way.** An OpenAI model released after the `openai` provider's model
list was last updated is rejected there as unknown, but stays reachable through `openai-compatible`.
Newer reasoning models additionally refuse tools on the completions API, so they need `responses` —
or `LLM_REASONING_EFFORT=none`, which turns reasoning off instead:

```dotenv
LLM_PROVIDER=openai-compatible
LLM_API_KEY=sk-proj-qwerty
LLM_BASE_URL=https://api.openai.com
LLM_MODEL=gpt-5.6-luna
LLM_OPENAI_ENDPOINT=responses
```

Other servers follow the same shape, with `LLM_PROVIDER=openai-compatible`:

```dotenv
# Grok
LLM_API_KEY=xai-qwerty
LLM_BASE_URL=https://api.x.ai
LLM_MODEL=grok-4.3

# llama.cpp — a local server needs no real key, but the value must be non-empty
LLM_API_KEY=sk-no-key-required
LLM_BASE_URL=http://localhost:8080
LLM_MODEL=unsloth/Qwen3.6-27B-GGUF:Q4_K_M

# Ollama — serves an OpenAI-compatible API; the key value is ignored
LLM_API_KEY=ollama
LLM_BASE_URL=http://localhost:11434
LLM_MODEL=gemma4
```

## ChatGPT subscription

`LLM_PROVIDER=codex` runs Vusan on a ChatGPT Plus, Pro, Business or Enterprise plan instead of a
paid API key. There is no `LLM_API_KEY`: the credentials come from the
[Codex CLI](https://developers.openai.com/codex/cli), which has to sign in on the bot host.

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-5.6-terra
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
Vusan somewhere else; set it in `env/vusan.env` and pass the same value to `codex login`:

```dotenv
CODEX_HOME=/home/vusan/.codex-vusan
```

Vusan reads that `auth.json` and cannot reach the OS keyring, so if your setup switched the CLI to
keyring storage, put `cli_auth_credentials_store = "file"` back in that directory's `config.toml`.
Treat the file like a password, because it holds live access and refresh tokens, and in a container
mount its directory read-write.

It is reread before every request, so a later CLI login, logout, workspace switch or token rotation
takes effect without restarting the bot. Sharing one file with an interactive CLI is fine — a
refresh from either side keeps the other working, and the newer version wins. A ChatGPT access token
lives ten days; Vusan rotates it about a day before expiry and writes it back atomically with
owner-only permissions. `codex login --with-access-token` is also accepted, but that credential has
no refresh token: Vusan uses it while it is fresh and asks for a replacement as expiry approaches,
instead of failing a request after it expires.

**What the plan's catalog decides.** The models your plan actually offers are read at startup, and
four settings are checked against that list:

- **`LLM_MODEL`** — startup fails with the available ids if it does not match. Codex and the OpenAI
  Platform API expose different model sets, so a Platform-only id would otherwise fail on the first
  message with an opaque error.
- **Vision** — the chat model becomes the default vision model only when the catalog says it reads
  images.
- **`LLM_REASONING_EFFORT`** — an effort the model does not offer stops startup instead of a turn.
- **The context window** — comes from there too, so `LLM_CONTEXT_WINDOW_TOKENS` is only an override.

Older catalog responses without capability metadata retain the compatible image-capable default.

**When a brand-new model is missing.** That list is filtered by the Codex client version Vusan
claims, so a model released alongside a newer CLI is absent from it and startup rejects it as one the
plan does not offer. Vusan claims the newest CLI it knows of, and the installed one when that is
newer, so upgrading `codex` on the host usually settles it. Where there is no CLI to upgrade — a
container, most often — claim a version yourself:

```dotenv
CODEX_CLIENT_VERSION=0.153.4
```

It has to look like a Codex CLI version, and anything else stops startup. One older than the model
needs hides it again, so use the release the model shipped with.

**A faster serving tier.** `CODEX_SERVICE_TIER=priority` buys roughly the speed-up the Codex CLI
offers as `/fast`, at the price of spending the plan's allowance quicker. It is off unless you set
it, and startup fails if the chosen model does not offer the tier, so check the model first:

```dotenv
CODEX_SERVICE_TIER=priority
```

Two limits are worth knowing. Usage is metered against the plan rather than billed per token, so a
heavy day ends in a "usage limit reached" reply that says how long the window still has to run — the
[daily token budget](#daily-token-budget) still works but is not what stops you first. And this
route depends on an endpoint OpenAI ships for its own Codex clients rather than documents for
third-party apps, so an OpenAI-side change can break it; `LLM_PROVIDER=openai` with an API key stays
the supported fallback.

## Daily token budget

A ceiling on the tokens Vusan may spend in a day, off by default. It exists for a provider allowance
that refills on a clock — OpenAI hands out free daily tokens at 00:00 UTC in exchange for sharing
API inputs and outputs — and works just as well as a plain daily spending cap.

| Variable                                 | Default   | Description                                                               |
|------------------------------------------|-----------|---------------------------------------------------------------------------|
| `LLM_DAILY_TOKEN_BUDGET`                 | unlimited | Input plus output tokens allowed per day.                                 |
| `LLM_TOKEN_BUDGET_TIMEZONE`              | `UTC`     | The zone whose midnight starts the next budget, e.g. `Europe/Kyiv`.       |
| `LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT` | `70`      | How much of the day is first come, first served; `100` turns sharing off. |

Leaving `LLM_DAILY_TOKEN_BUDGET` unset means no ceiling and no bookkeeping at all. Match the
timezone to your provider's reset — OpenAI's is `UTC`.

Everything Vusan asks the chat model counts: replies, its own history recaps, group-chat digests,
and reading images when vision runs on the chat model. A separate `OPENAI_VISION_API_KEY` model has
its own key and quota and is not counted here.

Once the day's budget is gone, Vusan answers every request with "come back in about N hours" instead
of thinking, and scheduled tasks that come due are skipped and moved to their next run rather than
retried. The count survives a restart, and the ceiling is checked before each request rather than
mid-reply, so the day's last request can go slightly over it instead of being cut off in the middle.

**Sharing it out.** Nobody gets a personal quota up front: splitting the day equally between
everyone on the allowlist would freeze tokens for the members who never use the bot, while the few
who do would run out by noon. Instead the day is free for all until
`LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT` of it is spent. Past that point a person who has already
used more than `budget ÷ people active in the last week` waits for the reset, and everyone below
their share carries on. The divisor counts who actually used the bot recently, not who is allowed
to, so idle members reserve nothing.

With a 2.5M budget and six people active this week, the first 1.75M go to whoever asks for them.
After that the share is about 416k: someone already through 1.2M is told to come back later, while
someone at 80k keeps working until the day's 2.5M is gone. If everyone left is over their share, the
remainder is held until the reset rather than handed to the heaviest user.

Vusan's own background work — describing stickers, digesting a group's day — belongs to no share and
answers to the day's ceiling alone.

## Personality

The agent ships with a built-in personality named "Vusan", kept generic so each deployment can
define its identity, tone and interaction style. Override it with either inline text or a file, or
unset both to keep the built-in one. The operational rules for output and tools are always appended
separately and cannot be removed by a custom personality.

| Variable           | Description                                                                                         |
|--------------------|-----------------------------------------------------------------------------------------------------|
| `PERSONALITY`      | Inline personality text, for something short. Takes precedence when set.                            |
| `PERSONALITY_FILE` | Path to a file, for longer multi-line text. Unreadable fails startup; blank falls back to built-in. |

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
| `APPEARANCE`      | Inline notes on what a portrait cannot show — height, build, tattoos, usual clothes.                  |
| `APPEARANCE_FILE` | Path to a file, for longer text. Takes effect only when `APPEARANCE` is unset.                        |

Point `SELF_IMAGE_FILE` at the original whenever you have it: Telegram serves an avatar at 640x640,
and a bigger, sharper face gives the image model more to hold on to. Keep the written notes to a few
sentences. They go to the image model and nowhere else, so if you also want the agent to describe
its looks in words, say it in the personality too.

## Optional tools

Each optional tool is enabled by one env variable. If it is missing, that tool is skipped at startup
with a `WARN` log and Vusan keeps running.

| Variable                | Enables                                   | Notes                                      |
|-------------------------|-------------------------------------------|--------------------------------------------|
| `TAVILY_API_KEY`        | Web search, image search, page extraction | See [Web search](#web-search)              |
| `SEARXNG_URL`           | Fallback web and image search             | See [Web search](#web-search)              |
| `GIPHY_API_KEY`         | GIF lookup                                | Giphy                                      |
| `ELEVENLABS_API_KEY`    | Voice messages and round video messages   | See [Voice output](#voice-output)          |
| `OPENAI_STT_API_KEY`    | Voice input, sound of a video             | Reuse your OpenAI key                      |
| `OPENAI_IMAGE_API_KEY`  | Image generation                          | Reuse your OpenAI key; optional on `codex` |
| `OPENAI_VISION_API_KEY` | Vision on a chat model that cannot see    | See [Vision](#vision)                      |
| `WORKSPACE_URL`         | Shell workspace                           | See [Workspace](#workspace)                |
| `SITES_URL`             | Publishing pages to the web               | See [Site host](#site-host)                |

### Web search

Two providers cover search, and either can run without the other:

| Variable         | Tools                                             | Role                                           |
|------------------|---------------------------------------------------|------------------------------------------------|
| `TAVILY_API_KEY` | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search; page extraction. |
| `SEARXNG_URL`    | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus category scoping.      |

Tavily leads on both: its results are cleaned-up page extracts rather than snippets, and
`searchImages` describes what is in each photo. [SearXNG](https://docs.searxng.org) is self-hosted,
so it costs nothing per call and keeps search working when Tavily fails or runs out of quota.
`metaSearch` also scopes a query with `categories` (`news`, `it`, `science`, `videos`, `music`,
`files`, `social media`, `map`), which Tavily cannot do — those categories query different engines,
so they still answer when the general ones are rate-limited.

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
| `ELEVENLABS_TTS_MODEL` | `eleven_v3`            | ElevenLabs TTS model.            |

The same key also enables the round video message — the reference photo from
[Appearance](#appearance) in the circle, the same voice over it, drawn by `ffmpeg`. A deployment
with no reference photo at all, meaning no `SELF_IMAGE_FILE` and no avatar in
[@BotFather](https://t.me/BotFather), has nothing to put in that circle and is offered only the
voice message.

### Voice input

`OPENAI_STT_API_KEY` enables listening; these tune what does the listening.

| Variable                          | Default             | Description                               |
|-----------------------------------|---------------------|-------------------------------------------|
| `OPENAI_STT_MODEL`                | `gpt-4o-transcribe` | Speech-to-text model.                     |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300`               | Longest voice or video Vusan transcribes. |

Past that length a voice message is refused, and a video is watched without its sound.

### Image generation

`OPENAI_IMAGE_API_KEY` enables the `generateImage` and `editImage` tools (OpenAI
`/v1/images/generations`). It can reuse your OpenAI key. The agent picks the aspect ratio per
request; the model and quality are operator-controlled so generation cost stays predictable. A
picture of the bot itself goes to `/v1/images/edits` instead, with the reference photo from
[Appearance](#appearance) as its subject.

| Variable               | Default                                    | Description                                                            |
|------------------------|--------------------------------------------|------------------------------------------------------------------------|
|------------------------|--------------------------------------------|------------------------------------------------------------------------|
| `OPENAI_IMAGE_QUALITY` | `medium`                                   | Rendering quality: `low`, `medium`, `high`, `xhigh`, `max`, or `auto`. |

`xhigh` and `max` render only on the `gpt-image-2.5` models; every earlier model stops at `high`
and fails the request if you ask for more. Quality drives the price per image, so raise it
deliberately.

On `LLM_PROVIDER=codex` the key is optional: with none set, both tools run on the ChatGPT
subscription instead. Setting `OPENAI_IMAGE_API_KEY` always wins, because it bills separately rather
than spending the same subscription allowance the conversation itself runs on. Two differences are
worth knowing before relying on the subscription route: images count against your ChatGPT usage
limit, so a heavy image day can exhaust the same quota that answers messages; and the model chooses
its own output dimensions, so the requested aspect ratio is a hint rather than a guarantee.

### Vision

Vision lets the agent inspect photos, sampled video frames and images in Telegram channel posts. It
also lets Vusan learn the sticker sets a chat uses, search them by meaning, and choose replies from
them. These features need a model that accepts images. By default that is the chat model itself, so
an `openai`, `anthropic`, `google` or `codex` setup needs nothing extra.

When the chat model cannot accept images, `OPENAI_VISION_API_KEY` runs vision on its own OpenAI
model, the way `OPENAI_STT_API_KEY` runs speech on one, and the chat model keeps answering
everything else:

```dotenv
LLM_PROVIDER=deepseek
LLM_MODEL=deepseek-v4-pro
LLM_API_KEY=sk-qwerty

OPENAI_VISION_API_KEY=sk-proj-qwerty
```

| Variable                | Default        | Description                                                        |
|-------------------------|----------------|--------------------------------------------------------------------|
| `OPENAI_VISION_API_KEY` | —              | Enables a separate vision model. Can reuse your OpenAI key.        |
| `OPENAI_VISION_MODEL`   | `gpt-5.4-mini` | OpenAI model that reads the images; must be one that accepts them. |

DeepSeek models cannot see, and `openai-compatible` never claims it either, because the server
behind `LLM_BASE_URL` may serve anything — so with either one vision stays off until this key is
set, even when the model itself does accept images. The key always wins when it is set, even where
the chat model could have looked at the picture itself.

Sticker replies have no setting of their own: the catalog is enabled automatically whenever vision
is available and stays off without it. With no vision at all, a startup `WARN` says so and Vusan
answers without looking at attachments; Telegram channel posts still come back, as text only. Vision
calls share the `LLM_REQUEST_TIMEOUT_SECONDS` budget.

## Workspace

Vusan can keep one persistent Linux home directory **per person, across all chats** — a real shell
for projects, media, documents and data, with files that survive new messages, `/clear` and
restarts. It is **off by default and not part of `docker compose up -d`**: the shell runs commands
the model writes, so it deploys on its own. Beside the bot is a supported arrangement; a machine of
its own is the recommendation for anything public. What it can do, its limits, its network policy,
every setting and how to deploy it are in [the workspace guide](workspace.md).

These are the bot's side of it, and belong in `env/vusan.env`:

| Variable                        | Default | Description                                                                      |
|---------------------------------|---------|----------------------------------------------------------------------------------|
| `WORKSPACE_URL`                 | —       | Address of the workspace controller. Unset means the tools do not exist.         |
| `WORKSPACE_TOKEN`               | —       | The shared API secret, the same value the controller is given.                   |
| `WORKSPACE_TOKEN_FILE`          | —       | A file holding that secret instead. An explicit token takes precedence.          |
| `WORKSPACE_MAX_TIMEOUT_SECONDS` | `600`   | Longest command timeout the bot will ask for; keep it equal to the controller's. |

Both `WORKSPACE_URL` and a secret must be present, or the workspace tools are not registered and the
bot never mentions them.

## Site host

Vusan can put a finished page, game or small web app on the public internet, one address **per person**
at `<their Telegram id>.<your domain>`. Like the workspace it is **off by default and deploys on its
own**, on a machine with a public address; the bot only connects out to it, so a bot behind CGNAT can
publish to a VPS. What it serves, its limits, DNS and certificates, and how to deploy it are in
[the site guide](sites.md).

These are the bot's side of it, and belong in `env/vusan.env`:

| Variable           | Default | Description                                                          |
|--------------------|---------|----------------------------------------------------------------------|
| `SITES_URL`        | —       | Address of the publishing API. Unset means the tools do not exist.   |
| `SITES_TOKEN`      | —       | The shared API secret, the same value the host is given.             |
| `SITES_TOKEN_FILE` | —       | A file holding that secret instead. An explicit token takes precedence. |

Publishing is a snapshot of files the person built somewhere, so it needs the workspace: with
`SITES_URL` set but `WORKSPACE_URL` missing the tools are not registered and the log says why. The
service is authoritative about how large a site may be and how many files it may hold, and tells the bot
those numbers when an upload starts — there is nothing to keep in step by hand.

## Conversation

Stored per Telegram user **and chat**, so a person keeps one thread in each place they talk to the
bot, and it holds only the turns the bot took part in. Recent interactions are replayed exactly;
older ones are merged by the active chat model into a persisted semantic recap. Raw rows remain
available for a bounded time but never enter the prompt again after their recap checkpoint.

Every limit below applies to one such thread. Someone active in a DM and two groups keeps three of
them, each with its own recap and its own retention.

| Variable                               | Default | Description                                                |
|----------------------------------------|---------|------------------------------------------------------------|
| `CONVERSATION_MAX_RECENT_INTERACTIONS` | `24`    | Unsummarized interactions offered to the model.            |
| `CONVERSATION_MAX_STORED_INTERACTIONS` | `100`   | Raw interactions retained after they have been summarized. |
| `CONVERSATION_RETENTION_DAYS`          | `90`    | Days summarized raw interactions remain in SQLite.         |

How many of those recent interactions actually fit is decided by the context window. Cleanup runs
when that thread completes a turn. `/clear` removes the raw transcript and its recap for the chat it
was sent from, leaving the caller's other chats and everyone else's history alone; durable memory
and scheduled tasks remain.

## Memory

Alongside the [conversation](#conversation) history, the agent keeps a durable **memory** that
survives the user clearing the chat: personal memory keyed by user, and shared group memory keyed by
chat. Built in; no env variable is required to enable it.

| Variable               | Default | Description                                                               |
|------------------------|---------|---------------------------------------------------------------------------|
| `MAX_MEMORY_PER_SCOPE` | `10`    | Max durable memory entries per user and per chat; the oldest are evicted. |

Personal memory follows a person between chats, and their workspace files are shared too. The
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

| Variable                          | Default | Description                                                             |
|-----------------------------------|---------|-------------------------------------------------------------------------|
| `GROUP_LOG_ENABLED`               | `true`  | Set to `false` to record nothing and drop the group-log tools entirely. |
| `GROUP_LOG_RETENTION_DAYS`        | `30`    | Days a recorded message stays in SQLite.                                |
| `GROUP_LOG_MAX_MESSAGES_PER_CHAT` | `20000` | Ceiling on rows per chat; the oldest are dropped past it.               |
| `GROUP_LOG_RECENT_MESSAGES`       | `15`    | Recent messages shown to the model on each group turn; `0` disables.    |
| `GROUP_LOG_RECENT_MINUTES`        | `60`    | How far back those recent messages may reach.                           |

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

## Scheduled tasks

Scheduled tasks are built in. The agent can schedule them in three forms:

- **`once <datetime>`** — fires once, then is disabled.
- **`every <interval>`** — fixed interval, minimum 5 minutes, timezone-independent.
- **`cron <UNIX expr>`** — clock-time patterns, evaluated in the task's timezone.

| Variable                    | Default | Description                                                   |
|-----------------------------|---------|---------------------------------------------------------------|
| `MAX_TASKS_PER_USER`        | `5`     | Maximum stored user-requested tasks per user.                 |
| `MAX_FOLLOW_UPS_PER_USER`   | `3`     | Maximum pending follow-ups the agent may owe one user.        |
| `TASK_MAX_LATENESS_MINUTES` | `60`    | How late a due task may still run before it counts as missed. |

A task that could not fire in time — because Vusan was offline or the machine was asleep — is not
run late. It gets a missed notice in the chat and the schedule moves on to the next fire.

Separately from tasks a user asks for, the agent may schedule its own one-time follow-up when the
conversation gives it a reason to come back later ("ask how the exam went"). Those are counted
against their own limit, so they can never use up the quota for what the user schedules, and they
show up in `/tasks` like any other task, where the user can cancel them.

## Rights in a group

Vusan reads what the group lets it post before each turn and adapts: a chat that forbids photos is
never offered image generation, one that forbids polls gets no poll tools, and slow mode is stated
in the turn so the answer comes as one message instead of several that Telegram would drop. Nothing
has to be configured for this — but two group settings do change what it can do:

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
edit made there survives only until the bot restarts.

## Agent loop

One turn may call tools repeatedly — search, read a page, search again — before it answers. The loop
has a ceiling, so a model looping on a broken tool stops costing tokens instead of running forever.

| Variable               | Default | Description              |
|------------------------|---------|--------------------------|
| `AGENT_MAX_ITERATIONS` | `70`    | Steps one turn may take. |

A tool round is two steps, so the default allows roughly 34 tool calls. Reaching the ceiling is not
an error: the last steps are reserved for a wrap-up in which the agent answers from what it gathered
and says which parts it could not finish. Deep research over many sources is what runs into it —
raise the value if such answers arrive routinely cut short, at the cost of a longer, more expensive
worst-case turn.

## Storage and binaries

Where the database lives, and the one external binary that needs a credential of its own.

| Variable              | Default            | Description                                        |
|-----------------------|--------------------|----------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs are created on first run. |
| `YT_DLP_COOKIES_FILE` | —                  | Cookies for YouTube videos that ask for a login.   |

`YT_DLP_COOKIES_FILE` must point to a Netscape-format `cookies.txt`; see the
[yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).

## Logging

Two levels, and one deployment quirk in how they are read.

| Variable            | Default | Description                                              |
|---------------------|---------|----------------------------------------------------------|
| `LOG_LEVEL`         | `INFO`  | Level for everything Vusan and its libraries log.        |
| `PROMPT_DUMP_LEVEL` | `OFF`   | `DEBUG` prints every request sent to the model, in full. |

Both levels are read by logback at startup rather than by `AppConfig`, and that is the one place
where writing them into `env/vusan.env` is not enough: logback looks at the process environment,
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
