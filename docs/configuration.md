# Configuration

Vusan reads configuration from environment variables. For Docker, put them in a `.env` file in the repo root;
[`.env.example`](../.env.example) is the copy-paste starting point. Blank values are treated as missing.

## Minimum setup

Fill in these values:

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `ALLOWED_IDS`        | Telegram user/group IDs Vusan answers.                |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_PROVIDER`       | LLM backend; see [LLM provider](#llm-provider).      |
| `LLM_MODEL`          | Model id for the chosen provider. Vusan also states it in the system prompt, so "which model are you?" is answered with what is actually deployed instead of a guess. |
| `LLM_API_KEY`        | API key for the chosen provider.                     |

`ALLOWED_IDS` accepts commas, whitespace, or semicolons as separators. Positive IDs are users; negative IDs are groups.
Empty/unset means Vusan answers nobody.

## Telegram command menu

To expose the direct commands in Telegram's menu, paste this into
BotFather's `/setcommands`:

```text
tasks - Manage scheduled tasks
clear - Clear conversation history
```

## LLM provider

`LLM_PROVIDER` selects the backend, and the model you pick must support tool calling.

| `LLM_PROVIDER`      | Example `LLM_MODEL`                 |
|---------------------|-------------------------------------|
| `openai`            | `gpt-5.4-mini`                      |
| `anthropic`         | `claude-sonnet-4-6`                 |
| `google`            | `gemini-2.5-flash`                  |
| `deepseek`          | `deepseek-v4-pro`                   |
| `openai-compatible` | any model id the server understands |
| `codex`             | any model the ChatGPT plan offers   |

The four native providers talk to each vendor's own API and accept only model ids they know; an unrecognized id fails at
startup and lists the supported ones. `openai-compatible` targets any OpenAI-compatible server, remote or local, and
takes whatever model string it serves. `codex` runs on a ChatGPT subscription instead of an API key — see
[ChatGPT subscription](#chatgpt-subscription).

| Variable                      | Default       | Description                                                                  |
|-------------------------------|---------------|------------------------------------------------------------------------------|
| `LLM_BASE_URL`                | —             | Server address. Required by `openai-compatible`, unused by the others.       |
| `LLM_OPENAI_ENDPOINT`         | `completions` | Which OpenAI API to call: `completions` or `responses`.                      |
| `LLM_REASONING_EFFORT`        | model default | Reasoning depth: `none`, `minimal`, `low`, `medium`, or `high`.              |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `120`         | Seconds one LLM call may hang before it fails and Vusan replies with an error. Raise it for slow local servers and heavy reasoning models. |
| `LLM_CONTEXT_WINDOW_TOKENS`   | model metadata or `16384` | Context size override. Native models use catalog metadata; unknown compatible models fall back to `16384`. |

`LLM_OPENAI_ENDPOINT` and `LLM_REASONING_EFFORT` apply to `openai-compatible` only. Give `LLM_BASE_URL` no `/v1` — the
API path is appended for you.

An OpenAI model released after the `openai` provider's model list was last updated is rejected there as unknown, but
stays reachable through `openai-compatible` pointed at OpenAI itself. Newer reasoning models additionally refuse tools
on the completions API, so they need `responses` — or `LLM_REASONING_EFFORT=none`, which turns reasoning off instead:

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

Set `LLM_CONTEXT_WINDOW_TOKENS` whenever an `openai-compatible` model has a different window. Vusan reserves part of
that window for the response, tool results, and estimation error, then fits only complete conversation interactions
into the remainder.

## ChatGPT subscription

`LLM_PROVIDER=codex` runs Vusan on a ChatGPT Plus, Pro, Business or Enterprise plan instead of a paid API key. There is
no `LLM_API_KEY`: the credentials come from the [Codex CLI](https://developers.openai.com/codex/cli), which has to be
installed on the same host and signed in.

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-5.6-terra
```

Sign in once, as the same user that runs the bot:

```bash
codex login              # opens a browser
codex login --device-auth  # headless hosts: shows a code to enter elsewhere
codex login status       # confirm it worked
```

That writes `~/.codex/auth.json`. Vusan reads the access token from there, refreshes it a few minutes before it expires,
and writes the rotated token back, so a long-running bot stays signed in without further attention. Set `CODEX_HOME` to
move the file — useful in a container, where the simplest setup is to mount the host's `~/.codex` read-write. Treat that
file like a password: it holds live access tokens.

`codex logout` ends the session; Vusan then replies that its connection needs renewing until you sign in again.

`LLM_MODEL` is checked against the models your plan actually offers, and startup fails with the available ids if it does
not match — Codex and the OpenAI Platform API expose different model sets, so a Platform-only id would otherwise fail on
the first message with an opaque error. The context window comes from the same catalog, so `LLM_CONTEXT_WINDOW_TOKENS`
is only needed to override it. `LLM_REASONING_EFFORT` applies here too.

Two limits are worth knowing. Usage is metered against the plan rather than billed per token, so a heavy day ends in a
"usage limit reached" reply until the window resets — `LLM_DAILY_TOKEN_BUDGET` still works but is not what stops you
first. And this route depends on an endpoint OpenAI documents for its own Codex clients rather than for third-party
apps, so an OpenAI-side change can break it; `LLM_PROVIDER=openai` with an API key stays the supported fallback.

### Daily token budget

A ceiling on the tokens Vusan may spend in a day, off by default. It exists for a provider allowance that refills on a
clock — OpenAI hands out free daily tokens at 00:00 UTC in exchange for sharing API inputs and outputs — and works just
as well as a plain daily spending cap.

| Variable                                 | Default   | Description                                                                              |
|------------------------------------------|-----------|------------------------------------------------------------------------------------------|
| `LLM_DAILY_TOKEN_BUDGET`                 | unlimited | Input plus output tokens allowed per day. Unset means no ceiling and no bookkeeping at all. |
| `LLM_TOKEN_BUDGET_TIMEZONE`              | `UTC`     | The zone whose midnight starts the next budget, e.g. `Europe/Kyiv`. Match it to your provider's reset — OpenAI's is `UTC`. |
| `LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT` | `70`      | How much of the day is first come, first served. Past that point one person can no longer take the rest of it. `100` turns sharing off. |

Everything Vusan asks the chat model counts: replies, its own history recaps, group-chat digests, and reading images
when vision runs on the chat model. A separate `OPENAI_VISION_API_KEY` model has its own key and quota and is not
counted here.

Once the day's budget is gone, Vusan answers every request with "come back in about N hours" instead of thinking, and
scheduled tasks that come due are skipped and moved to their next run rather than retried. The count survives a
restart, and the ceiling is checked before each request rather than mid-reply, so the last request of the day can go
slightly over it instead of being cut off in the middle.

**Sharing it out.** Nobody gets a personal quota up front: splitting the day equally between everyone on the allowlist
would freeze tokens for the members who never use the bot, while the few who do would run out by noon. Instead the day
is free for all until `LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT` of it is spent. Past that point a person who has already
used more than `budget ÷ people active in the last week` waits for the reset, and everyone below their share carries on.
The divisor counts who actually used the bot recently, not who is allowed to, so idle members reserve nothing.

With a 2.5M budget and six people who used the bot this week, the first 1.75M go to whoever asks for them. After that
the share is about 416k: someone who has already burned through 1.2M is told to come back later, while someone at 80k
keeps working until the day's 2.5M is gone. If everyone still around is over their share, the remainder is held until
the reset rather than handed to the heaviest user — that is the point of the rule, and the percentage is the knob for
how much of the day it applies to.

Vusan's own background work — describing stickers, digesting a group's day — belongs to no share and answers to the
day's ceiling alone.

## Personality

The agent ships with a built-in personality named "Vusan", kept generic so each deployment can define its identity,
tone, and interaction style. Override it with either inline text or a file, or unset both to keep the built-in one.
The operational rules for output and tools are always appended separately and cannot be removed by a custom personality.

| Variable           | Description                                                                                        |
|--------------------|----------------------------------------------------------------------------------------------------|
| `PERSONALITY`      | Inline personality text, for something short. Takes precedence when set.                           |
| `PERSONALITY_FILE` | Path to a file, for longer multi-line text. Unreadable fails startup; blank falls back to built-in. |

## Optional tools

Each optional tool is enabled by one env variable. If it is missing, that tool is skipped at startup with a `WARN` log
and Vusan keeps running.

| Variable                | Enables                                   | Notes                                 |
|-------------------------|-------------------------------------------|---------------------------------------|
| `TAVILY_API_KEY`        | Web search, image search, page extraction | See [Web search](#web-search)         |
| `SEARXNG_URL`           | Fallback web and image search             | See [Web search](#web-search)         |
| `GIPHY_API_KEY`         | GIF lookup                                | Giphy                                 |
| `ELEVENLABS_API_KEY`    | Voice output                              | ElevenLabs TTS                        |
| `OPENAI_STT_API_KEY`    | Voice input, sound of a video             | Reuse your OpenAI key                 |
| `OPENAI_IMAGE_API_KEY`  | Image generation                          | Reuse your OpenAI key; optional on `codex` |
| `OPENAI_VISION_API_KEY` | Vision on a chat model that cannot see    | See [Vision](#vision)                 |
| `SANDBOX_URL`           | Code execution                            | See [Code execution](#code-execution) |

### Web search

Two providers cover search, and either can run without the other:

| Variable         | Tools                                             | Role                                          |
|------------------|---------------------------------------------------|-----------------------------------------------|
| `TAVILY_API_KEY` | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search; page extraction. |
| `SEARXNG_URL`    | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus category scoping.     |

Tavily leads on both: its results are cleaned-up page extracts rather than snippets, and `searchImages` describes what
is in each photo. [SearXNG](https://docs.searxng.org) is self-hosted, so it costs nothing per call and keeps search
working when Tavily fails or runs out of quota. `metaSearch` also scopes a query with `categories` (`news`, `it`,
`science`, `videos`, `music`, `files`, `social media`, `map`), which Tavily cannot do — those categories query different
engines, so they still answer when the general ones are rate-limited.

Point `SEARXNG_URL` at the instance root, without the `/search` path:

```dotenv
SEARXNG_URL=http://searxng:8080
```

The instance must serve JSON. SearXNG ships with `formats: [html]` only and answers anything else with `403`, so add
`json` in its `settings.yml`:

```yaml
search:
  formats:
    - html
    - json
```

### TTS tuning

| Variable               | Default                | Description                      |
|------------------------|------------------------|----------------------------------|
| `ELEVENLABS_VOICE_ID`  | `VD1if7jDVYtAKs4P0FIY` | Voice used for generated speech. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v3`            | ElevenLabs TTS model.            |

### STT tuning

| Variable                          | Default             | Description                                                                                        |
|-----------------------------------|---------------------|----------------------------------------------------------------------------------------------------|
| `OPENAI_STT_MODEL`                | `gpt-4o-transcribe` | Speech-to-text model.                                                                              |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300`               | Max voice/video length to transcribe; a longer voice message is refused, a longer video is watched without its sound. |

### Image generation tuning

`OPENAI_IMAGE_API_KEY` enables the `generateImage` and `editImage` tools (OpenAI `/v1/images/generations`). It can reuse
your OpenAI key. The agent picks the aspect ratio per request; the model and quality are operator-controlled so
generation cost stays predictable.

On `LLM_PROVIDER=codex` the key is optional: with none set, both tools run on the ChatGPT subscription instead, and the
model defaults to `gpt-image-2`. Setting `OPENAI_IMAGE_API_KEY` always wins, because it bills separately rather than
spending the same subscription allowance the conversation itself runs on. Two differences are worth knowing before
relying on the subscription route: images count against your ChatGPT usage limit, so a heavy image day can exhaust the
same quota that answers messages; and the model chooses its own output dimensions, so the requested aspect ratio is a
hint rather than a guarantee.

| Variable               | Default                            | Description                                            |
|------------------------|------------------------------------|--------------------------------------------------------|
| `OPENAI_IMAGE_MODEL`   | `gpt-image-1.5` / `gpt-image-2` on `codex` | Image model.                                   |
| `OPENAI_IMAGE_QUALITY` | `medium`                           | Rendering quality: `low`, `medium`, `high`, or `auto`. |

### Vision

Vision lets the agent inspect photos, sampled video frames, and images in Telegram channel posts. It also lets Vusan
learn the sticker sets a chat uses and choose replies from them. These features need a model that accepts images. By
default, that is the chat model itself, so an `openai`, `anthropic`, or `google` setup needs nothing extra.

When the chat model cannot accept images, `OPENAI_VISION_API_KEY` runs vision on its own OpenAI model, the way
`OPENAI_STT_API_KEY` runs speech on one, and the chat model keeps answering everything else:

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

Sticker replies have no setting of their own: the catalog is enabled automatically whenever vision is available and
stays off without it.

DeepSeek models cannot see, and `openai-compatible` never claims it either, because the server behind `LLM_BASE_URL` may
serve anything — so with either one vision stays off until this key is set, even when the model itself does accept
images.

The key always wins when it is set, even where the chat model could have looked at the picture itself. With no vision at
all, a startup `WARN` says so and Vusan answers without looking at attachments; Telegram channel posts still come
back, as text only. Vision calls share the `LLM_REQUEST_TIMEOUT_SECONDS` budget.

## Code execution

The `codeExecution` tool lets the agent run Python in an isolated sandbox to compute exact answers, transform data, and
render charts (`numpy`, `pandas`, `matplotlib`, `sympy`, `scipy`, `Pillow`). A file the user uploads (or one they reply
to) is placed in the working directory so the script can read it by name. The sandbox executes untrusted code on an
internal-only network with no secrets, no internet, and no host mounts. Its own source and internals are described in
[architecture.md](architecture.md#code-execution-service).

Docker starts it by default:

```dotenv
SANDBOX_URL=http://vusan-sandbox:8080
```

To disable code execution, comment `SANDBOX_URL` out and start only the bot with `docker compose up -d vusan`
(`docker compose stop vusan-sandbox` if it is already running). A local JVM run has no sandbox container unless you
start one yourself; point `SANDBOX_URL` at that service, or leave it commented.

### Sandbox tuning

Both variables go in the repo-root `.env`; the default `compose.yaml` passes them into the sandbox container.

| Variable                  | Default | Used by           | Description                      |
|---------------------------|---------|-------------------|----------------------------------|
| `SANDBOX_POOL_SIZE`       | `2`     | sandbox           | Warm Pyodide workers kept ready. |
| `SANDBOX_TIMEOUT_SECONDS` | `120`   | Vusan + sandbox   | Hard per-run limit.              |

`SANDBOX_TIMEOUT_SECONDS` is shared on purpose: the sandbox enforces the run limit, while Vusan uses the same value for
its wait budget. Setting it once keeps the two in sync.

## Memory

The agent keeps a conversation history per person **per chat** plus a durable **memory** that survives the user
clearing the chat: personal memory (keyed by user, follows them across DMs and groups) and shared group memory (keyed by
chat). Built in; no env variable is required to enable it.

Memory is the only thing that travels between chats. The history does not: what someone told the bot in a DM is not
replayed inside a group, and two groups never see each other's exchanges. Ask the bot to remember something if it
should follow you everywhere.

| Variable               | Default | Description                                                               |
|------------------------|---------|---------------------------------------------------------------------------|
| `MAX_MEMORY_PER_SCOPE` | `10`    | Max durable memory entries per user and per chat; the oldest are evicted. |

## Conversation

Stored per Telegram user **and chat**, so a person keeps one thread in each place they talk to the bot, and it holds
only the turns the bot took part in. Recent interactions are replayed exactly; older ones are merged by the active chat
model into a persisted semantic recap. Raw rows remain available for a bounded time but never enter the prompt again
after their recap checkpoint.

Every limit below applies to one such thread. Someone active in a DM and two groups keeps three of them, each with its
own recap and its own retention.

| Variable                               | Default | Description                                                        |
|----------------------------------------|---------|--------------------------------------------------------------------|
| `CONVERSATION_MAX_RECENT_INTERACTIONS` | `24`    | Complete unsummarized interactions offered to the model context; the context window decides how many of them fit. |
| `CONVERSATION_MAX_STORED_INTERACTIONS` | `100`   | Complete raw interactions retained after they have been summarized. |
| `CONVERSATION_RETENTION_DAYS`          | `90`    | Days summarized raw interactions remain in SQLite.                 |

Cleanup runs when that thread completes a turn. `/clear` removes the raw transcript and its recap for the chat it was
sent from, leaving the caller's other chats and everyone else's history alone; durable memory and scheduled tasks
remain.

## Group log

Separate from conversation history and keyed by chat alone, with no sender in the key. In groups the bot records
every message it receives, including the ones not addressed to it, so it can answer "what did I miss" and recap a day.
Private chats are never recorded — they already have conversation history above. Nothing is recorded for a chat outside
`ALLOWED_IDS`.

What a row holds: the text (collapsed and capped at 2000 characters, 1000 for a forwarded post), who sent it and when,
the kind of message, a short label for non-text content (`🐤 UtyaDuck`, `0:14`, `report.pdf`), the channel or person a
forward came from, and the message it replied to. What it never holds: the media itself, or the Telegram file ids that
would let it be fetched later. The bot's own replies into the group are recorded too; replies redirected to a user's
DMs are not.

Reading it back costs no extra model call while the requested window fits the context budget. When it does not, each
closed day is compressed into a one-off recap by the active chat model and cached, so a repeated weekly or monthly
question is answered from cache. The current day is never cached, because it is still being written to.

| Variable                          | Default | Description                                                            |
|-----------------------------------|---------|------------------------------------------------------------------------|
| `GROUP_LOG_ENABLED`               | `true`  | Set to `false` to record nothing and drop the group-log tools entirely. |
| `GROUP_LOG_RETENTION_DAYS`        | `30`    | Days a recorded message stays in SQLite.                               |
| `GROUP_LOG_MAX_MESSAGES_PER_CHAT` | `20000` | Ceiling on rows per chat; the oldest are dropped past it.              |
| `GROUP_LOG_RECENT_MESSAGES`       | `15`    | Recent messages shown to the model on each group turn; `0` disables.   |
| `GROUP_LOG_RECENT_MINUTES`        | `60`    | How far back those recent messages may reach.                          |

Cleanup is amortized over inserts rather than scheduled, so it runs every few hundred recorded messages in a chat.
Asking the agent to forget the group log wipes that chat's messages and every cached daily recap; `/clear` does not
touch it, since the log belongs to the group rather than to the person running the command.

## Scheduled tasks

Scheduled tasks are built in. The agent can schedule them in three forms:

- `once <datetime>` — fires once, then is disabled.
- `every <interval>` — fixed interval, minimum 5 minutes, timezone-independent.
- `cron <UNIX expr>` — clock-time patterns, evaluated in the task's timezone.

A task that could not fire in time — because Vusan was offline or the machine was asleep — is not run late. It gets a
missed notice in the chat and the schedule moves on to the next fire.

Separately from tasks a user asks for, the agent may schedule its own one-time follow-up when the conversation gives it
a reason to come back later ("ask how the exam went"). Those are counted against their own limit, so they can never use
up the quota for what the user schedules, and they show up in `/tasks` like any other task, where the user can cancel
them.

| Variable                    | Default | Description                                              |
|-----------------------------|---------|----------------------------------------------------------|
| `MAX_TASKS_PER_USER`        | `5`     | Maximum stored user-requested tasks per user.            |
| `MAX_FOLLOW_UPS_PER_USER`   | `3`     | Maximum pending follow-ups the agent may owe one user.   |
| `TASK_MAX_LATENESS_MINUTES` | `60`    | How late a due task may still run before it counts as missed. |

## Storage and binaries

| Variable              | Default            | Description                                        |
|-----------------------|--------------------|----------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs are created on first run. |
| `YT_DLP_COOKIES_FILE` | —                  | Cookies for YouTube videos that ask for a login.   |

`YT_DLP_COOKIES_FILE` must point to a Netscape-format `cookies.txt`; see
the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).
