# Configuration

Vusan reads configuration from environment variables. For Docker, put them in a `.env` file in the repo root;
[`.env.example`](../.env.example) is the copy-paste starting point. Blank values are treated as missing.

## Minimum setup

Fill in these values (the OpenAI setup shown is the `.env.example` starting point):

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `ALLOWED_IDS`        | Telegram user/group IDs the bot answers.             |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_PROVIDER`       | LLM backend; see [LLM provider](#llm-provider).      |
| `LLM_MODEL`          | Model id for the chosen provider.                    |
| `LLM_API_KEY`        | API key for the chosen provider.                     |

`ALLOWED_IDS` accepts commas, whitespace, or semicolons as separators. Positive IDs are users; negative IDs are groups.
Empty/unset means the bot answers nobody.

## Telegram command menu

To expose the direct commands in Telegram's menu, paste this into
BotFather's `/setcommands`:

```text
start - Show the welcome message
tasks - Manage scheduled tasks
clear - Clear conversation history
```

## LLM provider

`LLM_PROVIDER` selects the backend; `LLM_PROVIDER`, `LLM_MODEL`, and `LLM_API_KEY` are always required. The chosen model
must support tool calling.

| `LLM_PROVIDER`      | Example `LLM_MODEL`                 |
|---------------------|-------------------------------------|
| `openai`            | `gpt-5.4-mini`                      |
| `anthropic`         | `claude-sonnet-4-6`                 |
| `google`            | `gemini-2.5-flash`                  |
| `deepseek`          | `deepseek-v4-pro`                   |
| `openai-compatible` | any model id the server understands |

`openai-compatible` additionally requires `LLM_BASE_URL`.

| Variable                      | Default | Description                                                                |
|-------------------------------|---------|----------------------------------------------------------------------------|
| `LLM_BASE_URL`                | —       | Base URL of the OpenAI-compatible server. Unused by the native providers.  |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `120`   | Seconds a single LLM HTTP call may hang before it is failed.               |

The native providers (`openai`, `anthropic`, `google`, `deepseek`) talk to each vendor's own API through its dedicated
Koog client, so `LLM_MODEL` must be a model id the client knows. An unrecognized id fails at startup with the list of
supported values. `openai-compatible` instead targets any OpenAI-compatible chat completions API (remote or local) and
accepts any model string the server understands.

`LLM_REQUEST_TIMEOUT_SECONDS` (default `120`) caps how long a single LLM HTTP call may hang. The Koog client otherwise
waits 15 minutes, during which the bot stays silent; the shorter cap lets a stalled call fail fast so the agent can
deliver an error reply. Raise it for slow local servers or heavy reasoning models.

Grok example:

```dotenv
LLM_PROVIDER=openai-compatible
LLM_API_KEY=xai-qwerty
LLM_BASE_URL=https://api.x.ai
LLM_MODEL=grok-4.3
```

llama.cpp example (local server needs no real key, but the value must be non-empty):

```dotenv
LLM_PROVIDER=openai-compatible
LLM_API_KEY=sk-no-key-required
LLM_BASE_URL=http://localhost:8080
LLM_MODEL=unsloth/Qwen3.6-27B-GGUF:Q4_K_M
```

Ollama example (Ollama serves an OpenAI-compatible API; the key value is ignored):

```dotenv
LLM_PROVIDER=openai-compatible
LLM_API_KEY=ollama
LLM_BASE_URL=http://localhost:11434
LLM_MODEL=gemma4
```

## Personality

The bot ships with a built-in personality ("Vusan"), kept generic so each deployment can define its identity, tone, and
interaction style. Override it with either inline text or a file. The operational rules for output and tools are always
appended separately by the bot and cannot be removed by a custom personality.

Unset both variables to use the built-in personality.

| Variable           | Description                                                                                         |
|--------------------|-----------------------------------------------------------------------------------------------------|
| `PERSONALITY`      | Inline personality text. Takes precedence when set.                                                 |
| `PERSONALITY_FILE` | Path to a personality file. Used only when `PERSONALITY` is unset; unreadable files fail startup.   |

`PERSONALITY_FILE` suits a long, multi-line personality — a file keeps line breaks and formatting readable, whereas
`PERSONALITY` is meant for short inline text. A file whose content is blank falls back to the built-in personality.

## Optional tools

Each optional tool is enabled by one env variable. If it is missing, that tool is skipped at startup with a `WARN` log
and the bot keeps running.

| Tool                                      | Enable with             | Notes                                 |
|-------------------------------------------|-------------------------|---------------------------------------|
| Web search, image search, page extraction | `TAVILY_API_KEY`        | See [Web search](#web-search)         |
| Fallback web and image search             | `SEARXNG_URL`           | See [Web search](#web-search)         |
| GIF lookup                                | `GIPHY_API_KEY`         | Giphy                                 |
| Voice output                              | `ELEVENLABS_API_KEY`    | ElevenLabs TTS                        |
| Voice input, sound of a video             | `OPENAI_STT_API_KEY`    | Reuse your OpenAI key                 |
| Image generation                          | `OPENAI_IMAGE_API_KEY`  | Reuse your OpenAI key                 |
| Vision on a chat model that cannot see    | `OPENAI_VISION_API_KEY` | See [Vision](#vision)                 |
| Code execution                            | `SANDBOX_URL`           | See [Code execution](#code-execution) |

### Web search

Two providers cover search, and either can run without the other:

| Variable         | Tools                                             | Role                                          |
|------------------|---------------------------------------------------|-----------------------------------------------|
| `TAVILY_API_KEY` | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search; page extraction. |
| `SEARXNG_URL`    | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus category scoping.     |

Tavily leads on both: its results are cleaned-up page extracts rather than snippets, and `searchImages` reports what is
visible in each photo, which the bot repeats when a user asks what a picture shows.
[SearXNG](https://docs.searxng.org) is self-hosted, so it costs nothing per call and keeps search working when Tavily
fails or its quota runs out. `metaSearch` also scopes a query with `categories` (`news`, `it`, `science`, `videos`,
`music`, `files`, `social media`, `map`), which Tavily cannot do — those categories query different engines, so they
still answer when the general ones are rate-limited.

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

`OPENAI_IMAGE_API_KEY` enables the `generateImage` tool (OpenAI `/v1/images/generations`). It can reuse your OpenAI key.
The agent picks the aspect ratio per request; the model and quality are operator-controlled so generation cost stays
predictable.

| Variable               | Default         | Description                                            |
|------------------------|-----------------|--------------------------------------------------------|
| `OPENAI_IMAGE_MODEL`   | `gpt-image-1.5` | Image model.                                           |
| `OPENAI_IMAGE_QUALITY` | `medium`        | Rendering quality: `low`, `medium`, `high`, or `auto`. |

### Vision

Looking at pictures — `describeImage`, the frames `describeVideo` samples out of a video, and the images inside Telegram
channel posts — needs a model that accepts images. By default that is the chat model itself, so an `openai`,
`anthropic`, or `google` setup needs nothing extra.

A chat model that cannot see images is the case this section is about. `OPENAI_VISION_API_KEY` then runs vision on its
own OpenAI model, the way `OPENAI_STT_API_KEY` runs speech on one, and the chat model keeps answering everything else:

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

Which chat models count as able to see comes from the Koog model catalog: DeepSeek models cannot, and `openai-compatible`
never claims it either, because the server behind `LLM_BASE_URL` may serve anything. So with `openai-compatible`
vision is off until this key is set, even when the model behind it does accept images.

The key always wins when it is set: vision runs on that model even when the chat model could have looked at the picture
itself. With no vision at all, `describeImage` and `describeVideo` are not registered — a startup `WARN` says so, and
the bot answers without looking at attachments instead of failing a call per picture. Telegram channel posts still come
back, as text only. Vision calls use the same `LLM_REQUEST_TIMEOUT_SECONDS` budget as chat calls.

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

To disable code execution, comment out `SANDBOX_URL` in `.env` and start only the bot:

```bash
docker compose up -d vusan
```

If the sandbox is already running, stop it separately:

```bash
docker compose stop vusan-sandbox
```

For a local JVM run, there is no sandbox container unless you start one yourself. Point `SANDBOX_URL` at that service,
or leave it commented.

### Sandbox tuning

Both variables live in the bot's `.env`; the default `compose.yaml` passes them into the sandbox container.

| Variable                  | Default | Used by       | Description                      |
|---------------------------|---------|---------------|----------------------------------|
| `SANDBOX_POOL_SIZE`       | `2`     | service       | Warm Pyodide workers kept ready. |
| `SANDBOX_TIMEOUT_SECONDS` | `120`   | bot + service | Hard per-run limit.              |

`SANDBOX_TIMEOUT_SECONDS` is shared on purpose: the service enforces it as the run limit, and the bot uses the same
value to budget how long to wait for a response (worker queue + run + network slack). Setting it once in `.env` keeps
both sides in sync.

## Memory

The agent keeps a per-user conversation history plus a durable **memory** that survives the user clearing the chat:
personal memory (keyed by user, follows them across DMs and groups) and shared group memory (keyed by chat). Built in;
no env variable is required to enable it.

| Variable               | Default | Description                                                               |
|------------------------|---------|---------------------------------------------------------------------------|
| `MAX_MEMORY_PER_SCOPE` | `10`    | Max durable memory entries per user and per chat; the oldest are evicted. |

## Scheduled tasks

Scheduled tasks are built in. No env variable is required to enable them.

The agent can schedule tasks in three forms:

- `once <datetime>` — fires once. If it is overdue by more than `TASK_MAX_LATENESS_MINUTES`, the bot sends a missed
  notice and disables it instead of firing stale work.
- `every <interval>` — fixed interval, minimum 5 minutes, timezone-independent. Missed fires skip ahead.
- `cron <UNIX expr>` — clock-time patterns, evaluated in the task's timezone. Missed fires skip ahead.

| Variable                    | Default | Description                                  |
|-----------------------------|---------|----------------------------------------------|
| `MAX_TASKS_PER_USER`        | `5`     | Maximum stored tasks per user.               |
| `TASK_MAX_LATENESS_MINUTES` | `60`    | Recurring tasks older than this are skipped. |

## Storage and binaries

| Variable              | Default            | Description                                        |
|-----------------------|--------------------|----------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs are created on first run. |
| `YT_DLP_COOKIES_FILE` | —                  | Optional YouTube cookies file.                     |

`YT_DLP_COOKIES_FILE` must point to a Netscape-format `cookies.txt`; see
the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).
