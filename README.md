<p align="center">
  <img src="https://helltar.com/projects/vusan/avatar.svg" width="160" alt="Vusan avatar">
</p>

<h1 align="center">Vusan</h1>

[![build](https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg)](https://github.com/Helltar/vusan/actions/workflows/build.yml)
[![GHCR](https://img.shields.io/badge/ghcr-vusan-blue?logo=docker)](https://github.com/Helltar/vusan/pkgs/container/vusan)
[![Image size](https://ghcr-badge.egpl.dev/helltar/vusan/size)](https://github.com/Helltar/vusan/pkgs/container/vusan)

Vusan is a Telegram AI agent for private chats and groups. It chats like a regular participant and uses tools when the conversation needs them — web search, voice in/out, vision, polls, GIFs, and more. See [docs/features.md](docs/features.md) for the full tool list.

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

## Stack

Built on [Koog](https://github.com/JetBrains/koog) for the agent loop and [ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi) for Telegram I/O. LLM backend is OpenAI by default; a local Ollama instance or any OpenAI-compatible server (llama.cpp, LM Studio) also works — see [docs/configuration.md](docs/configuration.md).

## Quick start

Copy the env template and fill it in using [docs/configuration.md](docs/configuration.md) as the reference:

```bash
cp .env.example .env
```

### Docker

```bash
docker run -d --name vusan --env-file .env -v vusan-data:/app/data ghcr.io/helltar/vusan:latest
```

The `-v vusan-data:/app/data` mount keeps the SQLite database (chat history) across container restarts.

### Local JVM

Prerequisites: JDK 21, plus `ffmpeg` and `yt-dlp` on `PATH`.

```bash
./gradlew run
```

`./gradlew shadowJar` produces a fat JAR at `build/libs/vusan-<version>-all.jar`.
