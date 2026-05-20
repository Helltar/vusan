# Configuration

All configuration is read from environment variables (or a `.env` file in the working directory). This page is the source of truth for required vs optional behavior; [`.env.example`](../.env.example) is a copyable template with example values.

## Required

| Variable | Purpose |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `OPENAI_API_KEY` | OpenAI API key for the agent LLM and vision. |
| `ALLOWED_IDS` | Comma-separated Telegram IDs the bot will respond to. Positive = user (private chat), negative = group/channel. Empty/unset = bot ignores every message. |

## Optional — feature toggles

If the API key for one of these is missing, the corresponding tool is unregistered at startup with a `WARN` log entry. The bot still runs; the agent just doesn't have access to that capability.

| Variable | Disables when missing |
| --- | --- |
| `TAVILY_API_KEY` | Web search, image search, and page extraction tools. |
| `ELEVENLABS_API_KEY` | Voice / text-to-speech tool. |
| `GIPHY_API_KEY` | GIF lookup tool. |

## Optional — settings and defaults

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENAI_MODEL` | `gpt-5.4-nano` | OpenAI model ID. |
| `ELEVENLABS_VOICE_ID` | `VD1if7jDVYtAKs4P0FIY` | ElevenLabs voice ID. Default voice: Milly Maple - Cool and Bright. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v3` | ElevenLabs TTS model ID. |
| `ELEVENLABS_TTS_OUTPUT_FORMAT` | `mp3_44100_128` | ElevenLabs audio output format. |
| `DB_FILE` | `data/db/vusan.db` | Path to the SQLite database file (created on first run, parent directories auto-created). |
| `YT_DLP_PATH` | `yt-dlp` | Path to the `yt-dlp` binary. |
| `YT_DLP_COOKIES_FILE` | — | Netscape-format `cookies.txt` for YouTube auth. See the [yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies). |
