# Configuration

All configuration is read from environment variables (or a `.env` file in the working directory). [`.env.example`](../.env.example) is a copy-paste template; this page documents what every variable does.

## Required

| Variable | Purpose |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `ALLOWED_IDS` | Comma-separated Telegram IDs the bot responds to. Positive = user, negative = group. Empty/unset = bot ignores every message. |
| `OPENAI_API_KEY` | LLM API key — required unless you switch `LLM_PROVIDER`. See below. |

## LLM provider

`LLM_PROVIDER` selects the backend. Default `openai`.

| Provider | Required | Optional (default) |
| --- | --- | --- |
| `openai` | `OPENAI_API_KEY` | `OPENAI_MODEL` (`gpt-5.4-nano`) |
| `ollama` | `OLLAMA_MODEL` — pick one with tool support, e.g. `gemma4` | `OLLAMA_BASE_URL` (`http://localhost:11434`) |
| `openai-compatible` | `OPENAI_BASE_URL`, `OPENAI_API_KEY` (any non-empty), `OPENAI_MODEL` | — |

`openai-compatible` points at any local server exposing an OpenAI-compatible `/v1/chat/completions` — [llama.cpp](https://github.com/ggml-org/llama.cpp) (`http://localhost:8080`), [LM Studio](https://lmstudio.ai) (`http://localhost:1234/v1`), etc. The chosen model must support tool calling.

## Persona

The bot ships with a built-in persona (the "Vusan" identity). To run your own character without touching code, override just the persona — describe identity, tone, and language. The operational contract (output/tool rules) is always appended by the bot and is not configurable, so a custom persona can't accidentally break message delivery.

| Variable | Purpose |
| --- | --- |
| `SYSTEM_PROMPT` | Inline persona text. Takes precedence when set. |
| `SYSTEM_PROMPT_FILE` | Path to a file holding the persona — convenient for multi-line text. Read only when `SYSTEM_PROMPT` is unset; a set-but-unreadable path fails startup. |

Unset both to use the default persona.

## Optional features

Each feature is gated by an API key. Missing key → the corresponding tool is unregistered at startup with a `WARN` log; the bot keeps running without it.

### Web search · `TAVILY_API_KEY`

Enables web search, image search, and page extraction (Tavily).

### GIFs · `GIPHY_API_KEY`

Enables GIF lookup (Giphy).

### Voice output — TTS · `ELEVENLABS_API_KEY`

| Variable | Default |
| --- | --- |
| `ELEVENLABS_VOICE_ID` | `VD1if7jDVYtAKs4P0FIY` (Milly Maple — Cool and Bright) |
| `ELEVENLABS_TTS_MODEL` | `eleven_v3` |
| `ELEVENLABS_TTS_OUTPUT_FORMAT` | `mp3_44100_128` |

### Voice input — STT · `OPENAI_STT_API_KEY`

Transcribes incoming voice/audio messages and replies to them. Reuse `OPENAI_API_KEY` if you already have one.

| Variable | Default |
| --- | --- |
| `OPENAI_STT_MODEL` | `gpt-4o-transcribe` |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300` — longer messages get a "too long" reply. |

## Scheduled tasks

Built in. The agent can schedule one-shot, daily, weekly, or monthly tasks. Clock follows the JVM default timezone — override per task by naming a city or IANA zone in the request. Missed recurring tasks skip ahead; missed one-shots fire late with a notice.

| Variable | Default |
| --- | --- |
| `MAX_TASKS_PER_USER` | `10` |
| `TASK_POLL_INTERVAL_SECONDS` | `30` |
| `TASK_MAX_LATENESS_MINUTES` | `60` — for recurring; skip a fire if it's older than this. |

## Storage and tooling

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_FILE` | `data/db/vusan.db` | SQLite path. Parent dirs auto-created on first run. |
| `YT_DLP_PATH` | `yt-dlp` | Path to the `yt-dlp` binary. |
| `YT_DLP_COOKIES_FILE` | — | Netscape-format `cookies.txt` for YouTube auth. See the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies). |
