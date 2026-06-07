# Configuration

Vusan reads configuration from environment variables. For Docker, put them in a `.env` file in the repo root;
[`.env.example`](../.env.example) is the copy-paste starting point. Blank values are treated as missing.

## Minimum setup

Fill in these values for the default OpenAI setup:

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `ALLOWED_IDS`        | Telegram user/group IDs the bot answers.             |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_API_KEY`        | API key for the LLM provider (OpenAI by default).    |

`ALLOWED_IDS` accepts commas, whitespace, or semicolons as separators. Positive IDs are users; negative IDs are groups.
Empty/unset means the bot answers nobody.

## LLM provider

`LLM_PROVIDER` selects the backend. Default: `openai`. The chosen model must support tool calling.

Provider options:

- `openai` — set `LLM_API_KEY`; optionally set `LLM_MODEL` (default `gpt-5.4-nano`).
- `openai-compatible` — set `LLM_PROVIDER=openai-compatible`, `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL`.

`openai-compatible` targets any OpenAI-compatible chat completions API, including remote APIs and local servers.

`LLM_REQUEST_TIMEOUT_SECONDS` (default `120`) caps how long a single LLM HTTP call may hang. The Koog client otherwise
waits 15 minutes, during which the bot stays silent; the shorter cap lets a stalled call fail fast so the agent can
deliver an error reply. Raise it for slow local servers or heavy reasoning models.

DeepSeek example:

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com
LLM_API_KEY=sk-qwerty
LLM_MODEL=deepseek-v4-pro
```

llama.cpp example (local server needs no real key, but the value must be non-empty):

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=http://localhost:8080
LLM_API_KEY=sk-no-key-required
LLM_MODEL=unsloth/Qwen3.6-27B-GGUF:Q4_K_M
```

Ollama example (Ollama serves an OpenAI-compatible API; the key value is ignored):

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=http://localhost:11434
LLM_API_KEY=ollama
LLM_MODEL=gemma4
```

## Persona

The bot ships with a built-in persona ("Vusan"). Override it with either inline text or a file. The operational rules
for output and tools are always appended by the bot and cannot be removed by a custom persona.

Unset both variables to use the built-in persona.

| Variable             | Description                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------------|
| `SYSTEM_PROMPT`      | Inline persona text. Takes precedence when set.                                                 |
| `SYSTEM_PROMPT_FILE` | Path to a persona file. Used only when `SYSTEM_PROMPT` is unset; unreadable files fail startup. |

`SYSTEM_PROMPT_FILE` suits a long, multi-line persona — a file keeps line breaks and formatting readable, where
`SYSTEM_PROMPT` is meant for short inline text.

## Optional tools

Each optional tool is enabled by one env variable. If it is missing, that tool is skipped at startup with a `WARN` log
and the bot keeps running.

| Tool                                      | Enable with          | Notes                             |
|-------------------------------------------|----------------------|-----------------------------------|
| Web search, image search, page extraction | `TAVILY_API_KEY`     | Tavily                            |
| GIF lookup                                | `GIPHY_API_KEY`      | Giphy                             |
| Voice output                              | `ELEVENLABS_API_KEY` | ElevenLabs TTS                    |
| Voice input                               | `OPENAI_STT_API_KEY` | Reuse your OpenAI key             |
| Code execution                            | `SANDBOX_URL`        | See [Code sandbox](#code-sandbox) |

### TTS tuning

| Variable                       | Default                | Description                        |
|--------------------------------|------------------------|------------------------------------|
| `ELEVENLABS_VOICE_ID`          | `VD1if7jDVYtAKs4P0FIY` | Voice used for generated speech.   |
| `ELEVENLABS_TTS_MODEL`         | `eleven_v3`            | ElevenLabs TTS model.              |
| `ELEVENLABS_TTS_OUTPUT_FORMAT` | `mp3_44100_128`        | Audio format for generated speech. |

### STT tuning

| Variable                          | Default             | Description                                        |
|-----------------------------------|---------------------|----------------------------------------------------|
| `OPENAI_STT_MODEL`                | `gpt-4o-transcribe` | Speech-to-text model.                              |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300`               | Max voice length to transcribe; longer is refused. |

## Code sandbox

`runCode` lets the agent run Python in an isolated sandbox to compute exact answers, transform data, and render charts
(`numpy`, `pandas`, `matplotlib`, `sympy`, `scipy`, `Pillow`). A file the user uploads (or one they reply to) is placed
in the working directory so the script can read it by name. The sandbox executes untrusted code on an internal-only
network with no secrets, no internet, and no host mounts.

Docker starts it by default:

```dotenv
SANDBOX_URL=http://vusan-sandbox:8080
```

To disable `runCode`, comment out `SANDBOX_URL` in `.env` and start only the bot:

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

These are sandbox-service environment variables. `SANDBOX_TIMEOUT_SECONDS` is also read by the bot. The default
`compose.yaml` wires `SANDBOX_POOL_SIZE` and `SANDBOX_TIMEOUT_SECONDS` from `.env` into the sandbox service.

| Variable                  | Default | Description                                                                         |
|---------------------------|---------|-------------------------------------------------------------------------------------|
| `SANDBOX_POOL_SIZE`       | `2`     | Warm Pyodide workers kept ready. Service only.                                      |
| `SANDBOX_TIMEOUT_SECONDS` | `120`   | Hard per-run limit. Read by both the service and the bot, so set it once in `.env`. |
| `PORT`                    | `8080`  | Port the sandbox service listens on. Service only.                                  |

## Memory

The agent keeps a per-user conversation history plus a durable **memory** that survives the user
clearing the chat: personal memory (keyed by user, follows them across DMs and groups) and shared
group memory (keyed by chat). Built in; no env variable is required to enable it.

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

| Variable                     | Default | Description                                  |
|------------------------------|---------|----------------------------------------------|
| `MAX_TASKS_PER_USER`         | `5`     | Maximum stored tasks per user.               |
| `TASK_POLL_INTERVAL_SECONDS` | `30`    | How often the scheduler checks due tasks.    |
| `TASK_MAX_LATENESS_MINUTES`  | `60`    | Recurring tasks older than this are skipped. |

## Storage and binaries

| Variable              | Default            | Description                                        |
|-----------------------|--------------------|----------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs are created on first run. |
| `YT_DLP_PATH`         | `yt-dlp`           | Path to the `yt-dlp` binary.                       |
| `YT_DLP_COOKIES_FILE` | —                  | Optional YouTube cookies file.                     |

`YT_DLP_COOKIES_FILE` must point to a Netscape-format `cookies.txt`; see
the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).
