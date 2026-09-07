<p align="center">
  <img src="https://helltar.com/projects/vusan/logo.svg" width="128" alt="Vusan">
</p>

<h1 align="center">Vusan</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-beta-yellow" alt="Status: Beta">
  <a href="https://github.com/Helltar/vusan/actions/workflows/build.yml"><img src="https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://github.com/Helltar/vusan/pkgs/container/vusan"><img src="https://img.shields.io/badge/ghcr-vusan-blue?logo=docker" alt="GHCR"></a>
</p>

Vusan is a personal AI agent that lives in Telegram. Talk to it in a private chat or a group; it
picks its own tools — search, code, voice, images, and more.

Try it live in the [Vusan Playground](https://t.me/+56qi5dDwsNszZWFi) Telegram group.

## Quick start

Clone the repo and copy the env template:

```bash
git clone https://github.com/Helltar/vusan.git
cd vusan
cp env/vusan.env.example env/vusan.env
```

Only these values are required to start; everything else is optional and covered in
[configuration.md](docs/configuration.md):

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

Then start the bot — in Docker, or on a local JVM.

### Docker

Use the published image:

```bash
docker compose up -d
```

Or build from source:

```bash
docker compose -f compose.yaml -f compose.local.yaml up --build -d
```

That starts the bot by itself. The workspace shell — the Linux home directory below — is a separate,
optional service that runs model-authored commands, so it stays off until you deploy it, on this
machine or one of its own: see [the workspace guide](docs/workspace.md).

### Local JVM

Prerequisites: JDK 21, plus `ffmpeg` and `yt-dlp` on `PATH`.

```bash
./gradlew run
```

## Features

### Understands what you send

- **Photos** — answers questions about images you send or reply to.
- **Voice and audio** — listens to voice messages and audio files.
- **Videos** — understands videos, video notes and GIFs, including speech.

### Looks things up

- **Web search** — searches the web and reads the pages it finds.
- **Image search** — finds pictures on the web and sends them.
- **Telegram channels** — recaps public channel posts by day or week, searches by keyword, and reads
  memes and screenshots.
- **Currency** — live exchange rates.
- **YouTube video and audio** — finds videos by name or link and sends the video or audio track.
- **YouTube transcripts** — summarizes videos and answers questions using their subtitles.

### Creates

- **Its own workspace** — runs code, builds projects, converts media and analyzes data, then sends
  the results. Each person gets a private Linux home that persists across chats.
- **Images** — draws from descriptions and edits your pictures or its own.
- **Voice replies** — answers out loud with voice messages.
- **Round video messages** — replies with its own face and voice in a video circle.
- **GIFs** — finds and sends a fitting GIF.

### In the chat

- **Live progress** — shows what it is doing in private chats: searching, running code, drawing.
- **Inline choices** — asks for decisions or confirmation with buttons and continues when you tap.
- **Edits** — answers when you add its mention to an earlier message.
- **Replies** — uses the message you reply to as context, including other people's files and
  pictures.
- **Private replies** — moves the answer into your DMs when you ask.

### Speaks Telegram

- **Reactions** — sometimes an emoji on your message is the whole answer.
- **Stickers** — learns your chat's sticker collection and picks fitting replies from it.
- **Polls and quizzes** — creates Telegram polls and quizzes.
- **Files and links** — sends documents, downloads links, and retrieves files behind chat stickers
  and pictures.
- **Long structured answers** — formats longer replies with headings, tables, and checklists.

### Remembers

- **Conversation history** — keeps context with recent messages and recaps, separately in each chat.
- **What the group said** — recaps the whole conversation and answers questions about who said what.
- **Sense of time** — notices gaps between conversations.
- **Memory** — remembers facts about you and the group across history resets; forgets them on
  request.
- **Scheduled tasks** — runs tasks once or on a recurring schedule; lets you pause, resume, edit or
  cancel.
- **Follow-ups** — checks back after an exam, interview or other event you mentioned.

## Documentation

- [Configuration](docs/configuration.md) — every setting, from the required five onward.
- [The workspace shell](docs/workspace.md) — what the persistent home does, and how to deploy it.
- [Architecture](docs/architecture.md) — the layers, and how a message flows through them.

## Stack

Built on [Koog](https://github.com/JetBrains/koog) — JetBrains' Kotlin agent framework — with
[TelegramBots](https://github.com/rubenlagus/TelegramBots) for Telegram and Exposed/SQLite for
storage. Works with OpenAI, Anthropic, Google, DeepSeek, any OpenAI-compatible server, or a ChatGPT
subscription instead of a paid API key — see [configuration.md](docs/configuration.md#llm-provider).
