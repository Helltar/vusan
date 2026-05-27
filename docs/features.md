# Features

Tools the agent can call (source: [`src/main/kotlin/com/helltar/vusan/tools/`](../src/main/kotlin/com/helltar/vusan/tools/)). Items marked opt-in require an API key — see [configuration.md](configuration.md).

| Tool | Description | Opt-in |
|---|---|:-:|
| Web search | Searches the web and extracts page content (Tavily) | ✓ |
| Voice input | Transcribes incoming voice and audio messages (OpenAI) | ✓ |
| Voice output | Replies with a voice message (ElevenLabs) | ✓ |
| GIFs | Searches and sends GIFs (Giphy) | ✓ |
| Vision | Describes a photo the user replies to | |
| Polls and quizzes | Creates a native Telegram poll or quiz | |
| Reactions | Sends an emoji reaction instead of a text reply | |
| YouTube audio | Extracts audio from a YouTube link (`yt-dlp` + `ffmpeg`) | |
| Telegram channels | Reads recent posts from a `t.me/...` link | |
| Currency | Looks up live exchange rates | |
| File delivery | Sends a generated file as a Telegram document | |
| Scheduled tasks | Schedules one-shot, daily, weekly, or monthly tasks with offline catch-up | |
| Memory | Per-chat conversation memory (persistent SQLite) | |
