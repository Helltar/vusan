# Vusan

Vusan is a Telegram AI agent for private chats and groups. It chats like a
regular participant and uses tools when the conversation needs them (web
search, polls, voice messages, vision, and more).

[![build](https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg)](https://github.com/Helltar/vusan/actions/workflows/build.yml)

## Features

The bot exposes the following tools to the agent (see `src/main/kotlin/com/helltar/vusan/tools/`). Tools tagged *(opt-in)* are only registered when the matching API key is set — see [docs/configuration.md](docs/configuration.md).

- **Vision** — describes a photo the user replies to, using an OpenAI vision-capable model.
- **Voice** — text-to-speech via ElevenLabs, delivered as a Telegram voice message *(opt-in)*.
- **Web search** — Tavily-powered web search, image search, and full page-content extraction *(opt-in)*.
- **GIFs** — Giphy search *(opt-in)*.
- **Polls and quizzes** — native Telegram polls/quizzes with validated questions and options.
- **YouTube audio** — full-track download via `yt-dlp` + `ffmpeg`, delivered as a Telegram audio file.
- **Telegram channels** — fetches recent posts from public `t.me/...` channels (with vision over their images) so the agent can answer questions about them.
- **Currency rates** — live ISO-4217 currency conversion via [open.er-api.com](https://open.er-api.com).
- **File delivery** — sends generated content back as a Telegram document.
- **Persistent chat history** — every turn is stored in a local SQLite database so the agent has long-term context across sessions; the agent can also wipe its own history on request.

The agent itself is built on [koog-agents](https://github.com/JetBrains/koog) and talks to OpenAI as the LLM backend. Telegram I/O uses [tgbotapi](https://github.com/InsanusMokrassar/ktgbotapi).

## Quick start — Docker

```bash
cp .env.example .env
# Edit .env using docs/configuration.md as the reference.
docker build -t vusan .
docker run -d --name vusan --env-file .env -v vusan-data:/app/data vusan
```

The `-v vusan-data:/app/data` mount keeps the SQLite database (chat history)
across container restarts.

## Quick start — Local JVM

Prerequisites: JDK 21, plus `ffmpeg` and `yt-dlp` on `PATH`.

```bash
cp .env.example .env
./gradlew run
```

`./gradlew shadowJar` produces a fat JAR at `build/libs/vusan-<version>-all.jar`.

## Configuration

See [docs/configuration.md](docs/configuration.md) for the full reference.

## Conventions

See [AGENTS.md](AGENTS.md) for coding conventions and architectural rules.
