<p align="center">
  <img src="https://helltar.com/projects/vusan/avatar.svg" width="160" alt="Vusan avatar">
</p>

<h1 align="center">Vusan</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="Status: Alpha">
  <a href="https://github.com/Helltar/vusan/actions/workflows/build.yml"><img src="https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/Helltar/vusan/pkgs/container/vusan"><img src="https://img.shields.io/badge/ghcr-vusan-blue?logo=docker" alt="GHCR"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="License: MIT"></a>
</p>

Vusan is a Telegram AI agent for private chats and groups, currently in alpha — breaking changes
may occur between updates.

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

## Highlights

- **Code execution** — runs Python in an isolated sandbox: exact math, file transforms, charts,
  and animations.
- **Vision** — understands photos and image documents.
- **Voice in and out** — transcribes voice messages (OpenAI STT) and replies with generated
  speech (ElevenLabs).
- **Scheduled tasks** — the bot acts on its own later: once, on an interval, or on a cron
  schedule.
- **Durable memory** — per-user and per-group memory that survives chat-history clears.
- **And more** — web search, GIFs, YouTube video/audio, currency rates, native polls and quizzes,
  reactions.

See [features.md](docs/features.md) for the full list.

## Stack

Built on [Koog](https://github.com/JetBrains/koog) — JetBrains' Kotlin agent framework — with
[ktgbotapi](https://github.com/InsanusMokrassar/ktgbotapi) for Telegram and Exposed/SQLite for
storage. Works with OpenAI (default), Anthropic, Google, DeepSeek, or any OpenAI-compatible
server — see [configuration.md](docs/configuration.md#llm-provider).

For a tour of the layers and how a message flows through them, see
[architecture.md](docs/architecture.md).

## Quick start

Clone the repo and enter the project directory:

```bash
git clone https://github.com/Helltar/vusan.git
cd vusan
```

Copy the env template:

```bash
cp .env.example .env
```

Only three values are required to start (see
[minimum setup](docs/configuration.md#minimum-setup)); everything else is optional:

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_API_KEY=sk-proj-qwerty
```

### Docker

Use the published images:

```bash
docker compose up -d
```

This starts two containers: the bot and the code-execution sandbox. To run without the sandbox,
see [code execution](docs/configuration.md#code-execution).

Or build from source:

```bash
docker compose -f compose.yaml -f compose.local.yaml up --build -d
```

### Local JVM

Prerequisites: JDK 21, plus `ffmpeg` and `yt-dlp` on `PATH`.

```bash
./gradlew run
```

`./gradlew shadowJar` produces a fat JAR at `build/libs/vusan-<version>-all.jar`.
