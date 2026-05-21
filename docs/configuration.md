# Configuration

All configuration is read from environment variables (or a `.env` file in the working directory). This page is the source of truth for required vs optional behavior; [`.env.example`](../.env.example) is a copyable template with example values.

## Required

| Variable | Purpose |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `ALLOWED_IDS` | Comma-separated Telegram IDs the bot will respond to. Positive = user, negative = group. Empty/unset = bot ignores every message. |
| LLM provider keys | Depend on `LLM_PROVIDER` — see [below](#llm-provider). Default `openai` requires `OPENAI_API_KEY`. |

## LLM provider

`LLM_PROVIDER` selects the LLM backend. Default `openai`. Each provider has its own set of required/optional variables.

### `openai` (default)

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_API_KEY` | required | OpenAI API key for the agent LLM and vision. |
| `OPENAI_MODEL` | `gpt-5.4-nano` | OpenAI model ID. |

### `ollama`

Run the agent against a local [Ollama](https://ollama.com) instance. Pick a model that advertises tool support (e.g. `gemma4`, `qwen3.6`); models without `tools` capability won't be able to call any of the bot's tools.

| Variable | Default | Purpose |
| --- | --- | --- |
| `OLLAMA_MODEL` | required | Ollama model ID, e.g. `gemma4`. Pull it ahead of time (`ollama pull gemma4`). |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Base URL of the Ollama HTTP API. |

### `openai-compatible`

Point the OpenAI client at any local server that exposes an OpenAI-compatible `/v1/chat/completions` endpoint: [llama.cpp](https://github.com/ggml-org/llama.cpp), [LM Studio](https://lmstudio.ai), etc. The model must support tool calling.

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_BASE_URL` | required | e.g. `http://localhost:8080` (`llama-server`) or `http://localhost:1234/v1` (LM Studio). |
| `OPENAI_API_KEY` | required | Any non-empty value — most local servers just check the header is present. |
| `OPENAI_MODEL` | required | Model ID as advertised by the local server's `/v1/models`. |

## Optional — feature toggles

If the API key for one of these is missing, the corresponding tool is unregistered at startup with a `WARN` log entry. The bot still runs; the agent just doesn't have access to that capability.

| Variable | Disables when missing |
| --- | --- |
| `TAVILY_API_KEY` | Web search, image search, page extraction. |
| `ELEVENLABS_API_KEY` | Voice / TTS. |
| `GIPHY_API_KEY` | GIF lookup. |
| `OPENAI_STT_API_KEY` | Speech-to-text for incoming Telegram voice and audio messages. |

## Optional — settings and defaults

### ElevenLabs

| Variable | Default | Purpose |
| --- | --- | --- |
| `ELEVENLABS_VOICE_ID` | `VD1if7jDVYtAKs4P0FIY` | Voice ID. Default voice: Milly Maple — Cool and Bright. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v3` | TTS model ID. |
| `ELEVENLABS_TTS_OUTPUT_FORMAT` | `mp3_44100_128` | Audio output format. |

### OpenAI STT

When `OPENAI_STT_API_KEY` is set, the bot transcribes incoming voice messages (Telegram `VoiceContent`) and audio files (`AudioContent`) and feeds the transcript to the agent as a normal user prompt wrapped in a `<voice_transcript>` tag. Voice/audio messages replied to by the user are also transcribed and included in the `<reply_context>`. In a private chat any voice or audio triggers the bot; in groups only messages that reply to the bot. If the key is missing, voice and audio messages are silently ignored.

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_STT_API_KEY` | — | OpenAI API key for the transcription endpoint. Reuse the value of `OPENAI_API_KEY` if you already have one. |
| `OPENAI_STT_MODEL` | `gpt-4o-transcribe` | OpenAI transcription model ID. |
| `OPENAI_STT_MAX_DURATION_SECONDS` | `300` | Reject voice/audio messages longer than this; the user gets a "too long" reply instead of an agent invocation. |

### Storage and tooling

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_FILE` | `data/db/vusan.db` | SQLite path (created on first run; parent dirs auto-created). |
| `YT_DLP_PATH` | `yt-dlp` | Path to the `yt-dlp` binary. |
| `YT_DLP_COOKIES_FILE` | — | Netscape-format `cookies.txt` for YouTube auth. See the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies). |
