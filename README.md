<p align="center">
  <img src="https://helltar.com/projects/vusan/avatar.svg" width="160" alt="Vusan avatar">
</p>

<h1 align="center">Vusan</h1>

![Status: Alpha](https://img.shields.io/badge/status-alpha-orange)
[![build](https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg)](https://github.com/Helltar/vusan/actions/workflows/build.yml)
[![GHCR](https://img.shields.io/badge/ghcr-vusan-blue?logo=docker)](https://github.com/Helltar/vusan/pkgs/container/vusan)
[![Image size](https://keeweb-ghcr.onrender.com/helltar/vusan/size)](https://github.com/Helltar/vusan/pkgs/container/vusan)

Vusan is a Telegram AI agent for private chats and groups. See [features.md](docs/features.md) for the full feature list.

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

_Note: This project is currently in active alpha development. Since the architecture and features are evolving rapidly, breaking changes may occur between updates._

## Stack

Built with [Koog](https://github.com/JetBrains/koog) as the agent framework and [ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi) for Telegram I/O. LLM backend is OpenAI by default; a local Ollama instance or any OpenAI-compatible server (llama.cpp, LM Studio) also works.

For a tour of the layers and how a message flows through them, see [architecture.md](docs/architecture.md).

## Quick start

Copy the env template and fill it in using [configuration.md](docs/configuration.md) as the reference:

```bash
cp .env.example .env
```

### Docker

```bash
docker compose up -d
```

To also enable the code sandbox (Python `runCode` tool), bring up its container and set `SANDBOX_URL` — see [code sandbox](docs/configuration.md#code-sandbox--sandbox_url):

```bash
docker compose --profile sandbox up -d
```

### Local JVM

Prerequisites: JDK 21, plus `ffmpeg` and `yt-dlp` on `PATH`.

```bash
./gradlew run
```

`./gradlew shadowJar` produces a fat JAR at `build/libs/vusan-<version>-all.jar`.
