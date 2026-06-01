# Configuration

Config comes from environment variables (or a `.env` file in the working directory). [`.env.example`](../.env.example) is a copy-paste template; this page says
what each variable does.

## Required

| Variable             | Purpose                                                                                                                |
|----------------------|------------------------------------------------------------------------------------------------------------------------|
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather).                                                                   |
| `ALLOWED_IDS`        | Comma-separated Telegram IDs the bot responds to. Positive = user, negative = group. Empty/unset = ignores everything. |
| `OPENAI_API_KEY`     | LLM API key. Required unless you switch `LLM_PROVIDER` (see below).                                                    |

## LLM provider

`LLM_PROVIDER` selects the backend (default `openai`). The chosen model must support tool calling.

| Provider            | Required                                                            | Optional (default)                           |
|---------------------|---------------------------------------------------------------------|----------------------------------------------|
| `openai`            | `OPENAI_API_KEY`                                                    | `OPENAI_MODEL` (`gpt-5.4-nano`)              |
| `ollama`            | `OLLAMA_MODEL` (e.g. `gemma4`)                                      | `OLLAMA_BASE_URL` (`http://localhost:11434`) |
| `openai-compatible` | `OPENAI_BASE_URL`, `OPENAI_API_KEY` (any non-empty), `OPENAI_MODEL` | —                                            |

`openai-compatible` targets any server exposing OpenAI's `/v1/chat/completions` — e.g. [llama.cpp](https://github.com/ggml-org/llama.cpp) (
`http://localhost:8080`) or [LM Studio](https://lmstudio.ai) (`http://localhost:1234/v1`).

## Persona

The bot ships with a built-in persona ("Vusan"). Override it to run your own character — describe identity, tone, and language; the operational rules (
output/tool contract) are always appended by the bot and can't be broken by a custom persona. Unset both to use the default.

| Variable             | Purpose                                                                                                                               |
|----------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `SYSTEM_PROMPT`      | Inline persona text. Takes precedence when set.                                                                                       |
| `SYSTEM_PROMPT_FILE` | Path to a persona file (handy for multi-line text). Read only when `SYSTEM_PROMPT` is unset; a set-but-unreadable path fails startup. |

## Optional features

Each feature is gated by one env variable (an API key or service URL). If it's missing, that tool is skipped at startup with a `WARN` log and the bot runs
without it.

| Feature                                            | Gate                 | Notes                                  |
|----------------------------------------------------|----------------------|----------------------------------------|
| Web search, image search, page extraction (Tavily) | `TAVILY_API_KEY`     | —                                      |
| GIF lookup (Giphy)                                 | `GIPHY_API_KEY`      | —                                      |
| Voice output — TTS (ElevenLabs)                    | `ELEVENLABS_API_KEY` | tuning below                           |
| Voice input — STT                                  | `OPENAI_STT_API_KEY` | reuse `OPENAI_API_KEY` if you have one |
| Code sandbox — `runCode` tool                      | `SANDBOX_URL`        | see [Code sandbox](#code-sandbox)      |

### TTS tuning

| Variable                       | Default                                                |
|--------------------------------|--------------------------------------------------------|
| `ELEVENLABS_VOICE_ID`          | `VD1if7jDVYtAKs4P0FIY` (Milly Maple — Cool and Bright) |
| `ELEVENLABS_TTS_MODEL`         | `eleven_v3`                                            |
| `ELEVENLABS_TTS_OUTPUT_FORMAT` | `mp3_44100_128`                                        |

### STT tuning

| Variable                          | Default                                        |
|-----------------------------------|------------------------------------------------|
| `OPENAI_STT_MODEL`                | `gpt-4o-transcribe`                            |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300` — longer messages get a "too long" reply |

### Code sandbox

`runCode` lets the agent run Python in an isolated sandbox to compute exact answers, transform data, and render charts (`numpy`, `pandas`, `matplotlib`,
`sympy`). Unlike the other features, `SANDBOX_URL` is not an API key but the URL of the bundled **sandbox service** — it executes untrusted code on an
internal-only network with no secrets, no internet, and no host mounts.

`docker compose up -d` starts the sandbox alongside the bot. `.env.example` ships with `SANDBOX_URL=http://sandbox:8080` (the compose service), so the `runCode`
tool is active out of the box. Comment that line out to disable the tool — e.g. when running `docker compose up -d vusan` without the sandbox container.

For a local JVM run there is no sandbox container, so point `SANDBOX_URL` at one yourself or leave it commented.

Tuning (all optional):

| Variable                  | Default | Purpose                                                                                                                                                                                                                                                                               |
|---------------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SANDBOX_POOL_SIZE`       | `2`     | Warm Pyodide workers kept ready. Service only.                                                                                                                                                                                                                                        |
| `SANDBOX_TIMEOUT_SECONDS` | `30`    | Hard per-run limit; a stuck script is killed. Higher = more render time for animations but a worker stays busy longer. Read by **both** the service and the bot (which sizes its HTTP timeout from it), so set it once in `.env` — the compose file passes it through to the service. |
| `PORT`                    | `8080`  | Port the service listens on. Service only.                                                                                                                                                                                                                                            |

## Scheduled tasks

Built in. The agent schedules tasks in one of three forms:

- `once <datetime>` — fires once; if missed, fires late with a notice.
- `every <interval>` — fixed interval (min 5 minutes), timezone-independent; missed fires skip ahead.
- `cron <UNIX expr>` — clock-time / specific-day patterns (e.g. weekdays at 18:00), evaluated in the task's timezone (JVM default unless the request names a
  city or IANA zone); missed fires skip ahead.

| Variable                     | Default                                            |
|------------------------------|----------------------------------------------------|
| `MAX_TASKS_PER_USER`         | `10`                                               |
| `TASK_POLL_INTERVAL_SECONDS` | `30`                                               |
| `TASK_MAX_LATENESS_MINUTES`  | `60` — recurring only; skip a fire older than this |

## Storage and tooling

| Variable              | Default            | Purpose                                                                                                                                            |
|-----------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path. Parent dirs auto-created on first run.                                                                                                |
| `YT_DLP_PATH`         | `yt-dlp`           | Path to the `yt-dlp` binary.                                                                                                                       |
| `YT_DLP_COOKIES_FILE` | —                  | Netscape-format `cookies.txt` for YouTube auth. See the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies). |
