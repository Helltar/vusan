# Features

Tools the agent can call (source: [`src/main/kotlin/com/helltar/vusan/tools/`](../src/main/kotlin/com/helltar/vusan/tools/)). Items marked opt-in require an API key — see [configuration.md](configuration.md).

| Tool | What it does | Opt-in |
|---|---|:-:|
| Web search | Tavily search + page extraction | ✓ |
| Voice input | STT for incoming voice/audio messages via OpenAI | ✓ |
| Voice | TTS via ElevenLabs | ✓ |
| GIFs | Giphy search | ✓ |
| Vision | Describes a replied photo | |
| Polls/quizzes | Native Telegram polls | |
| Reactions | Emoji reaction on a message instead of a text reply | |
| YouTube audio | `yt-dlp` + `ffmpeg` | |
| TG channels | Recent posts from `t.me/...` | |
| Currency | Live FX rates | |
| File delivery | Sends as a Telegram document | |
| Chat history | Persistent SQLite memory | |
