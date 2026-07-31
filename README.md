<p align="center">
  <img src="https://helltar.com/projects/vusan/logo.svg" width="128" alt="Vusan">
</p>

<h1 align="center">Vusan</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-beta-yellow" alt="Status: Beta">
  <a href="https://github.com/Helltar/vusan/actions/workflows/build.yml"><img src="https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/Helltar/vusan/pkgs/container/vusan"><img src="https://img.shields.io/badge/ghcr-vusan-blue?logo=docker" alt="GHCR"></a>
</p>

Vusan is a Telegram AI agent for private chats and groups. You ask in plain language; it picks the
tools itself — search, code, voice, images, and more.

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

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

Only a few values are required to start (see
[minimum setup](docs/configuration.md#minimum-setup)); everything else is optional:

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

With that in place, start the bot — in Docker, or on a local JVM.

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

## Features

Items marked *(opt-in)* need an API key or an extra service, see
[configuration.md](docs/configuration.md).

### Understands what you send

- **Photos** — looks at images you send or reply to and answers questions about them.
- **Voice and audio** *(opt-in)* — listens to voice messages and audio files.
- **Videos** — watches videos, video notes and GIFs; when voice input is enabled, it also hears
  speech.

### Looks things up

- **Web search** *(opt-in)* — searches the web and reads the pages it finds.
- **Image search** *(opt-in)* — finds pictures on the web and sends them.
- **Telegram channels** — reads recent posts from any public channel, and can look at the images
  in them.
- **Currency** — live exchange rates.

### YouTube

- **Video and audio** — finds a video by name or link and sends it, or just its audio track.
- **Transcripts** — reads a video's subtitles, so it can summarize it or answer questions about it.

### Creates

- **Code execution** *(opt-in)* — runs Python in an isolated sandbox: exact math, data crunching,
  charts, Word and PDF documents, animations.
- **Images** *(opt-in)* — draws a picture from a description, or edits one you sent.
- **Voice replies** *(opt-in)* — answers out loud with a generated voice message.
- **GIFs** *(opt-in)* — finds and sends a fitting GIF.

### In the chat

- **Inline choices** — when it needs a specific decision or confirmation, it can ask with buttons
  and continue as soon as you tap one.
- **Polls and quizzes** — creates real Telegram polls and quizzes, not a text imitation.
- **Reactions** — sometimes an emoji on your message is the whole answer.
- **Files and links** — sends what it wrote as a document, or downloads a link and passes it on
  as a file.
- **Long structured answers** — headings, tables, and checklists when a reply is genuinely big.
- **Private replies** — moves the answer into your DMs when you ask.

### Remembers

- **Conversation history** — recent messages, so replies keep context.
- **Memory** — separate from the history: facts it keeps about you, and about the group. Clearing
  the history leaves them; ask it to forget one or all of them.
- **Scheduled tasks** — acts on its own later: once, on an interval, or on a cron schedule. You can
  pause, resume, cancel, or edit them.

## Stack

Built on [Koog](https://github.com/JetBrains/koog) — JetBrains' Kotlin agent framework — with
[TelegramBots](https://github.com/rubenlagus/TelegramBots) for Telegram and Exposed/SQLite for
storage. Works with OpenAI, Anthropic, Google, DeepSeek, or any OpenAI-compatible server — see
[configuration.md](docs/configuration.md#llm-provider).

For a tour of the layers and how a message flows through them, see
[architecture.md](docs/architecture.md).
