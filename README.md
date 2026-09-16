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

The bot runs from its published image, so it needs only the compose file and the env template:

```bash
mkdir -p vusan/data && cd vusan && curl -fsSLO \
  "https://github.com/Helltar/vusan/raw/master/{compose.yaml,.env.example}" && \
  mv .env.example .env
```

Only these values in `.env` are required to start; everything else is optional and covered in
[configuration.md](docs/configuration.md):

```dotenv
ALLOWED_IDS=123456789,-1001234567890
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-5.4-mini
LLM_API_KEY=sk-proj-qwerty
```

Then start it:

```bash
docker compose up -d
```

That starts the bot, which is the whole deployment. Two capabilities stay off until it can reach a
[Regolith](docs/sandbox.md) server: the sandbox shell, which runs model-authored commands, and
[publishing to the web](docs/sites.md), which puts what was built there at a public address. Both come
from that one server, which is a separate project with a machine of its own as the recommendation.

### From source

To build the image yourself or run on a local JVM, clone the repository and set up `.env` the same
way:

```bash
git clone https://github.com/Helltar/vusan.git && cd vusan && \
  cp .env.example .env && mkdir data
```

Then build and start the image:

```bash
docker compose up --build -d
```

Or run it on JDK 21, with `ffmpeg` and `yt-dlp` on `PATH`:

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
- **Links** — reads a web page you send and answers from it.
- **Image search** — finds pictures on the web and sends them.
- **Telegram channels** — recaps public channel posts by day or week, searches by keyword, and reads
  memes and screenshots.
- **Currency** — live exchange rates.
- **YouTube video and audio** — finds videos by name or link and sends the video or audio track.
- **YouTube transcripts** — summarizes videos and answers questions using their subtitles.

### Creates

- **Its own sandbox** — runs code, builds projects, converts media and analyzes data, then sends
  the results. Each person gets a private Linux home that persists across chats.
- **Web pages** — puts a page, game or small app it built on the internet at your own address, and
  hands you the link.
- **Images** — draws from descriptions, edits your pictures, and merges several into one.
- **Voice replies** — answers out loud with voice messages.
- **Round video messages** — replies with its own face and voice in a video circle.
- **GIFs** — finds and sends a fitting GIF.

### In the chat

- **Live progress** — shows what it is doing, and says what it is about to make before a long job.
- **Stop button** — ends a running answer from the progress message, in groups too.
- **Inline choices** — asks for decisions or confirmation with buttons and continues when you tap.
- **Edits** — answers when you add its mention to an earlier message.
- **Replies** — uses the message you reply to as context, including other people's files and
  pictures.
- **Private replies** — moves the answer into your DMs when you ask.

### Speaks Telegram

- **Reactions** — sometimes an emoji on your message is the whole answer.
- **Stickers** — learns your chat's sticker collection and picks fitting replies from it.
- **Polls and quizzes** — creates Telegram polls and quizzes, and follows who answered what in a quiz.
- **Forum topics** — answers, notices and scheduled reminders stay in the topic they belong to.
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
- [The sandbox shell](docs/sandbox.md) — what the persistent home does, and how to deploy it.
- [Publishing to the web](docs/sites.md) — how a page built in the sandbox gets an address.
- [Architecture](docs/architecture.md) — the layers, and how a message flows through them.

## Stack

Built on [Koog](https://github.com/JetBrains/koog) — JetBrains' Kotlin agent framework — with
[TelegramBots](https://github.com/rubenlagus/TelegramBots) for Telegram and Exposed/SQLite for
storage. Works with OpenAI, Anthropic, Google, DeepSeek, any OpenAI-compatible server, or a ChatGPT
subscription instead of a paid API key — see [configuration.md](docs/configuration.md#llm-provider).
