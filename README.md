<p align="center">
  <img src="https://helltar.com/projects/vusan/avatar.svg" width="160" alt="Vusan avatar">
</p>

<h1 align="center">Vusan</h1>

[![build](https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg)](https://github.com/Helltar/vusan/actions/workflows/build.yml)
[![GHCR](https://img.shields.io/badge/ghcr-vusan-blue?logo=docker)](https://github.com/Helltar/vusan/pkgs/container/vusan)
[![Image size](https://ghcr-badge.egpl.dev/helltar/vusan/size)](https://github.com/Helltar/vusan/pkgs/container/vusan)

Vusan is a Telegram AI agent for private chats and groups. It chats like a
regular participant and uses tools when the conversation needs them (web
search, polls, voice messages, vision, and more).

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

## Features

Tools the agent can call (source: `src/main/kotlin/com/helltar/vusan/tools/`). Items marked opt-in require an API key — see [docs/configuration.md](docs/configuration.md).

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

The agent itself is built on [Koog](https://github.com/JetBrains/koog) and talks to OpenAI (default), a local Ollama instance, or any OpenAI-compatible local server (llama.cpp, LM Studio) as the LLM backend. Telegram I/O uses [ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi).

## Quick start — Docker

```bash
cp .env.example .env
# Edit .env using docs/configuration.md as the reference.
docker run -d --name vusan --env-file .env -v vusan-data:/app/data ghcr.io/helltar/vusan:latest
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
