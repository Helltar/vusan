<p align="center">
  <img src="https://helltar.com/projects/vusan/logo.svg" width="128" alt="Vusan">
</p>

<h1 align="center">Vusan</h1>

<p align="center">An AI agent that lives in Telegram and is at its best in a group.</p>

<p align="center">
  <a href="https://github.com/Helltar/vusan/actions/workflows/build.yml"><img src="https://github.com/Helltar/vusan/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2FHelltar%2Fvusan%2Fmaster%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&logo=kotlin&label=kotlin&color=blue" alt="Kotlin"></a>
  <a href="https://github.com/Helltar/vusan/pkgs/container/vusan"><img src="https://img.shields.io/badge/ghcr-vusan-blue?logo=docker" alt="GHCR"></a>
</p>

<p align="center">
  Try it live in the <a href="https://t.me/+56qi5dDwsNszZWFi">Vusan Playground</a> Telegram group.
</p>

Vusan follows the conversation, knows who said what, remembers yesterday, and joins in like one more
member. In a private chat it works the same, and everywhere it picks its own tools: search, code,
voice, images, and more.

## Quick start

Vusan runs from its published image, so it needs only the compose file and the env template:

```bash
mkdir -p vusan/data && cd vusan && curl -fsSLO \
  "https://github.com/Helltar/vusan/raw/master/{compose.yaml,.env.example}" && \
  mv .env.example .env
```

Fill in these five values in `.env`:

```dotenv
OWNER_ID=123456789
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-6.1-sol
LLM_API_KEY=sk-proj-qwerty
```

And start it:

```bash
docker compose up -d
```

That is the whole deployment. The model can come from OpenAI, Anthropic, Google, DeepSeek, xAI,
OpenRouter, any OpenAI-compatible server such as Ollama, or a ChatGPT subscription instead of an API
key: see [LLM provider](docs/configuration.md#llm-provider). Everything else is optional and covered
in [configuration.md](docs/configuration.md).

The [sandbox shell](docs/sandbox.md) and [publishing to the web](docs/sites.md) stay off until Vusan
can reach a Regolith server, a separate project that runs the commands the model writes and is best
kept on a machine of its own.

### Add it to a group

Add the group's ID to `ALLOWED_IDS`, then let the bot see the whole chat: turn off its privacy mode
in [@BotFather](https://t.me/BotFather) with `/setprivacy`, or make it an administrator. Without
that Telegram shows it only mentions, replies and commands, so it can neither recap the chat nor
answer to its name. More in [Rights in a group](docs/configuration.md#rights-in-a-group).

## Features

### At home in a group

- **Called by name** — answers when you say its name or follow up, no mention needed.
- **Speaks up on its own** — now and then reacts, drops a line or a sticker, or asks after someone.
- **What the group said** — recaps the chat, answers who said what, and finds when something came up.
- **Diary** — writes down what each day was like and picks up a thread from yesterday or months ago.
- **Each person apart** — everyone in a chat keeps their own conversation, memory and sandbox.
- **Replies** — reads the message you reply to, other people's files and pictures included.
- **Private replies** — moves the answer into your DMs when you ask.
- **Leaves you be** — stops writing to you on its own when you ask, until you say otherwise.
- **Stickers** — learns your chat's sticker collection and picks fitting replies from it.
- **Forum topics** — keeps answers and reminders in their topic, and as an admin manages the topics.
- **Group settings** — shows the group's photo, and as an admin changes its photo, title or description.

### Understands what you send

- **Photos** — answers questions about images you send or reply to.
- **Voice and audio** — listens to voice messages and audio files.
- **Videos** — understands videos, video notes and GIFs, including speech.

### Looks things up

- **Web search** — searches the web and reads the pages it finds.
- **Links** — reads a web page you send and answers from it.
- **Image search** — finds pictures on the web and sends them.
- **Telegram channels** — recaps and searches public channel posts, pictures included.
- **YouTube** — finds a video by name or link, sends its video or audio, and answers from its subtitles.

### Creates

- **Its own sandbox** — a private Linux home per person for code, media and data, with snapshots.
- **One job, many tools** — chains its tools, like putting itself into your video and voicing it.
- **Web pages** — publishes a page, game or app it built at your own address, kept up longer on request.
- **Images** — draws from descriptions, edits your pictures, merges several into one, and draws GIFs.
- **Video clips** — makes short videos with sound, animates your photos, and edits or extends a clip.
- **Voice replies** — answers out loud with voice messages.
- **Round video messages** — replies with its own face and voice in a video circle.
- **GIFs, memes and clips** — finds a fitting GIF, meme or clip, or turns your video into a GIF.

### In the chat

- **Live progress** — shows each step as it works, sends early results, and keeps how long a job took.
- **Stop button** — ends a running answer from the progress message, in groups too.
- **Inline choices** — asks for decisions or confirmation with buttons and continues when you tap.
- **Reactions** — sometimes an emoji on your message is the whole answer.
- **Polls and quizzes** — creates polls and quizzes, and follows who answered what.
- **Files** — sends documents, downloads links, and fetches the file behind a sticker or picture.
- **Long structured answers** — formats longer replies with headings, tables, and checklists.
- **Edits** — answers when you add its mention, or its name, to an earlier message.
- **Sandbox at a glance** — shows your sandbox, its snapshots and your site, and until when each is kept.

### Remembers

- **Conversation history** — keeps context with recent messages and recaps, separately in each chat.
- **Sense of time** — notices gaps between conversations.
- **Memory** — keeps facts about you and the group through history resets, and forgets on request.
- **Scheduled tasks** — runs tasks once or on a schedule, which you can pause, edit or cancel.
- **Follow-ups** — checks back after an exam, interview or other event you mentioned.

## Documentation

- [Configuration](docs/configuration.md) — every setting, from the required five onward.
- [Model providers](docs/providers.md) — what each model provider needs beyond a key, a ChatGPT plan's sign-in included.
- [Vusan in a chat](docs/chats.md) — how it behaves with people: memory, tasks, groups, being left alone.
- [The sandbox shell](docs/sandbox.md) — what the persistent home does, and how to connect it.
- [Publishing to the web](docs/sites.md) — how a page built in the sandbox gets an address.
- [Architecture](docs/architecture.md) — the layers, and how a message flows through them.

## Building from source

[Quick start](#quick-start) runs the published image. To build the image yourself or run on a
local JVM instead, clone the repository and fill in `.env` the same way:

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

## Stack

Kotlin, with [TelegramBots](https://github.com/rubenlagus/TelegramBots) for Telegram, Exposed/SQLite
for storage, and its own thin clients for the model providers' APIs.
