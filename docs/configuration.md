# Configuration

Vusan reads every setting from `.env`. Copy the [example file](../.env.example) and fill in what you
need.

- **Docker** — follow the [quick start](../README.md#quick-start); the same `.env` also holds the few
  Compose settings at the end of the example.
- **Values** — a blank one counts as missing, and booleans are `true` or `false` in any case. One that
  is set but unreadable, like `AGENT_MAX_MODEL_CALLS=7O`, stops startup and names the variable.
- [Sandbox](#sandbox) — configured on its own: a Regolith server that runs the commands the model
  writes and never sees the bot's secrets.

How Vusan behaves with people, which needs no setting, is in [Vusan in a chat](chats.md).

## Minimum setup

Five values, and Vusan runs:

```dotenv
OWNER_ID=123456789
TELEGRAM_BOT_TOKEN=1234567890:qwerty
LLM_PROVIDER=openai
LLM_MODEL=gpt-6.1-sol
LLM_API_KEY=sk-proj-qwerty
```

| Variable             | Description                                          |
|----------------------|------------------------------------------------------|
| `OWNER_ID`           | Your Telegram user ID: the person Vusan belongs to.  |
| `TELEGRAM_BOT_TOKEN` | Bot token from [@BotFather](https://t.me/BotFather). |
| `LLM_PROVIDER`       | LLM backend; see [LLM provider](#llm-provider).      |
| `LLM_MODEL`          | Model id for the chosen provider.                    |
| `LLM_API_KEY`        | API key for the chosen provider.                     |

## Who Vusan answers

`OWNER_ID` is answered everywhere, in private and in any group.

| Variable      | Default | Description                                                  |
|---------------|---------|--------------------------------------------------------------|
| `ALLOWED_IDS` | empty   | Telegram user and group IDs Vusan answers besides the owner. |
| `BANNED_IDS`  | empty   | IDs Vusan ignores, whatever else allows them.                |

- **Format** — separated by commas, spaces or semicolons. Positive IDs are users, negative ones
  groups, and an allowed group admits everyone in it. `telegram:123456789` names the messenger
  explicitly; a plain number means Telegram. `OWNER_ID` takes either form.
- **Empty `ALLOWED_IDS`** — Vusan answers the owner alone.
- **Bans win** — over the allowlist and over the owner, which is how one person is shut out of an
  open group. A banned ID gets nothing, and their tasks wait until the ban is lifted.
- **Scheduled tasks** — a task whose owner and chat are both outside the lists does not fire until
  access returns.

## LLM provider

`LLM_PROVIDER` picks the backend. Whichever it is, the model must support tool calling.

| Provider            | Example model               | What it is                                                  |
|---------------------|-----------------------------|-------------------------------------------------------------|
| `openai`            | `gpt-6.1-sol`               | OpenAI's API, a current model that reasons and sees images. |
| `anthropic`         | `claude-opus-5-5`           | Anthropic's API, Claude 4.6 or newer.                       |
| `xai`               | `grok-4.7`                  | xAI's API.                                                  |
| `openrouter`        | `anthropic/claude-opus-5.5` | Many vendors' models behind one OpenRouter key; see below.  |
| `codex`             | a model your plan offers    | A ChatGPT plan instead of a key; see below.                 |
| `openai-compatible` | whatever the server serves  | Any OpenAI-compatible server, remote or local.              |

| Variable                      | Default        | Description                                                        |
|-------------------------------|----------------|--------------------------------------------------------------------|
| `LLM_BASE_URL`                | —              | Server address; required by `openai-compatible`, unused otherwise. |
| `LLM_COMPATIBLE_API`          | `completions`  | `completions` or `responses`; `openai-compatible` only.            |
| `LLM_REASONING_EFFORT`        | model default  | `none`, `low`, `medium`, `high`, `xhigh` or `max`.                 |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `300`          | How long one model call may hang before the turn fails.            |
| `LLM_CONTEXT_WINDOW_TOKENS`   | model metadata | Context size; required where nothing states one.                   |

- **`LLM_BASE_URL`** — give it without `/v1`: `https://api.deepseek.com` is called at
  `…/v1/chat/completions`, or `…/v1/responses` with `LLM_COMPATIBLE_API=responses`.
- **`LLM_REASONING_EFFORT`** — which values work depends on the model; `none` turns reasoning off
  where the model allows it.
- **`LLM_REQUEST_TIMEOUT_SECONDS`** — raise it for slow local servers and heavy reasoning.
- **`LLM_CONTEXT_WINDOW_TOKENS`** — needed only when the server's model list leaves the window out;
  startup stops and asks for it. Set it to the context the server really runs the model with.

Third-party servers all use `LLM_PROVIDER=openai-compatible`:

```dotenv
# DeepSeek
LLM_API_KEY=sk-qwerty
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-flash

# Ollama — the key is not checked, but must not be empty
LLM_API_KEY=ollama
LLM_BASE_URL=http://localhost:11434
LLM_COMPATIBLE_API=responses
LLM_MODEL=gemma4:latest
LLM_CONTEXT_WINDOW_TOKENS=131072
VISION_MODEL=gemma4:latest
```

Ollama states neither the window nor whether a model sees. Set the window to Ollama's
`OLLAMA_CONTEXT_LENGTH` (`ollama ps` shows it), and name the model again as `VISION_MODEL` if it
takes images.

## OpenRouter

`LLM_PROVIDER=openrouter` reaches many vendors' models with one [OpenRouter](https://openrouter.ai)
key. Name the model as OpenRouter lists it:

```dotenv
LLM_PROVIDER=openrouter
LLM_MODEL=anthropic/claude-opus-5.5
LLM_API_KEY=sk-or-v1-qwerty
```

- **Startup check** — the model has to be one OpenRouter lists for the key, and one that calls
  tools. The list also gives its window, whether it sees and which efforts it takes, so nothing
  needs setting by hand. It does not apply every privacy setting: a free model the account's
  privacy settings exclude still passes, and its first turn fails.
- **Claude and the rest** — a Claude model works as it does on Anthropic's own API, thinking and
  effort included, and the others as on OpenAI's.
- **Credit** — before each call OpenRouter sets aside enough for the longest reply the model may
  write. On a low balance, or near a key's daily limit, a large model is refused while a smaller one
  would still answer. Vusan takes that as credit run out, the way it takes a spent plan.
- **A key's spending limit** — once it is spent, Vusan leaves OpenRouter alone until the limit
  resets, answering on the [fallback](#a-second-provider-behind-the-first) if there is one.

## ChatGPT subscription

`LLM_PROVIDER=codex` runs Vusan on a ChatGPT Plus, Pro, Business or Enterprise plan instead of an API
key. There is no `LLM_API_KEY`: the credentials come from a [Codex CLI](https://developers.openai.com/codex/cli)
sign-in.

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-6-astra
```

### Signing in

On the bot host, as the user that runs the bot, run `codex login` — with `--device-auth` on a host
without a browser. `codex login status` checks the session.

The bot host does not need the CLI, though, and the image does not ship it. You can sign in on any
machine with a browser and copy the file over — the same after a logout or a change of the account's
email:

```bash
mkdir -p /tmp/codex-vusan
echo 'cli_auth_credentials_store = "file"' > /tmp/codex-vusan/config.toml
CODEX_HOME=/tmp/codex-vusan codex login
scp /tmp/codex-vusan/auth.json bot-host:/path/to/codex-home/auth.json
ssh bot-host chmod 600 /path/to/codex-home/auth.json
rm -r /tmp/codex-vusan
```

```dotenv
CODEX_HOME=/home/vusan/.codex-vusan
```

- **Where it lives** — `~/.codex/auth.json` of whoever signed in. `CODEX_HOME` moves it for both the
  CLI and Vusan: set it in `.env` and pass the same value to `codex login`. In a container, mount
  that directory read-write.
- **Files only** — Vusan cannot reach the OS keyring, so keep `cli_auth_credentials_store = "file"`
  in that directory's `config.toml`.
- **A password** — the file holds live access and refresh tokens; treat it like one.
- **No restart needed** — a new sign-in works from the next request. With a
  [fallback](#a-second-provider-behind-the-first), an expired sign-in keeps Codex out for half an
  hour; a restart brings it back at once.
- **Sharing** — an interactive CLI may use the same file. A `codex login --with-access-token`
  credential cannot be renewed, so Vusan asks for a new one as it nears expiry.

### What the plan decides

At startup Vusan checks `LLM_MODEL`, `LLM_REASONING_EFFORT` and `CODEX_SERVICE_TIER` against the
models your plan offers; a mismatch stops startup and lists what the plan has. Codex and the OpenAI
Platform serve different models, so not every Platform id is offered here.

The plan's list depends on the Codex client version Vusan claims, so a model released with a newer CLI
can be missing. Vusan claims the newest of the version it knows, the `codex` on the host and the
latest release on GitHub; a host that cannot reach `api.github.com` can pin one:

```dotenv
CODEX_CLIENT_VERSION=0.162.0
```

### Speed, pictures and search

- **Faster tier** — `CODEX_SERVICE_TIER=priority` is what the Codex CLI calls `/fast`, and spends the
  plan quicker. It applies to the chat model and its fallback only.
- **Pictures** — `IMAGE_PROVIDER=codex` draws [images](#image-generation) on the same account,
  whether or not the chat runs on `codex`.
- **Web search** — `answerFromWeb` searches through the plan with no search key, and each search
  spends a good deal of it; `CODEX_WEB_SEARCH_ENABLED=false` turns it off.

### Limits

- **Usage windows** — the plan is metered, so a heavy day ends in a "usage limit reached" reply
  with the time left, unless a [fallback](#a-second-provider-behind-the-first) takes over.
- **Not a public API** — this route uses the endpoint OpenAI ships for its own Codex clients, so an
  OpenAI-side change can break it. `LLM_PROVIDER=openai` with a key is the supported alternative.
- **How much the plan holds** — a `codex limits:` log line follows every move of a window's share,
  with the calls, pictures and tokens since the last one.

## A second provider behind the first

A second provider can answer while the first is out: a spent subscription window, a key out of
credit, an expired sign-in. It takes the same variables under `LLM_FALLBACK_`. Any provider can play
either role, but `codex` cannot be on both sides — there is one signed-in account.

```dotenv
LLM_PROVIDER=codex
LLM_MODEL=gpt-6-astra

LLM_FALLBACK_PROVIDER=openai
LLM_FALLBACK_MODEL=gpt-6.1-sol
LLM_FALLBACK_API_KEY=sk-proj-qwerty
```

`LLM_FALLBACK_BASE_URL`, `LLM_FALLBACK_COMPATIBLE_API`, `LLM_FALLBACK_REASONING_EFFORT`,
`LLM_FALLBACK_REQUEST_TIMEOUT_SECONDS` and `LLM_FALLBACK_CONTEXT_WINDOW_TOKENS` mean what their `LLM_`
counterparts do; the timeout follows the primary's when unset.

- **What switches** — a usage limit, rejected credentials, a rate limit, an overloaded or failing
  server, a dropped connection. A content refusal does not: it would repeat anywhere.
- **For how long** — a usage limit until the reset time it named, an expired sign-in for half an
  hour, a rate limit or an outage for two minutes; then Vusan tries the primary again.
- **Vision** — switches too, so pick a fallback that sees images if the chat model does.
- **Window** — history is planned against the smaller of the two, so keep them close.
- **Pictures** — do not switch: on `IMAGE_PROVIDER=codex` they fail with the spent plan, so a
  deployment that wants them through an outage uses `IMAGE_PROVIDER=openai`.

## Personality

Vusan ships with a built-in personality, kept generic so each deployment can give it its own identity,
tone and style. The rules for output and tools are appended separately, and a personality cannot
remove them.

| Variable           | Description                                                                     |
|--------------------|---------------------------------------------------------------------------------|
| `PERSONALITY_FILE` | The personality text. Unreadable stops startup; a blank file uses the built-in. |

## Appearance

Pictures, videos and round video messages of Vusan itself are drawn from one reference photo, so they
keep one face. It is the bot's Telegram avatar unless a file overrides it.

| Variable          | Description                                                                          |
|-------------------|--------------------------------------------------------------------------------------|
| `SELF_IMAGE_FILE` | PNG, JPEG or WebP reference photo. Unreadable stops startup; unset uses the avatar.  |
| `APPEARANCE_FILE` | Notes on what a portrait cannot show — height, build, tattoos, usual clothes.        |

Use the original photo when you have it: Telegram serves avatars at 640×640, and a sharper face gives
the image model more to hold on to. Keep the notes to a few sentences. Only the image model reads
them, so if the agent should describe its looks in words, put that in the personality too.

## Optional tools

Each optional tool is switched on by one variable. Without it, the tool is left out and the startup
summary says so in one line.

| Variable             | Enables                                    | Notes                                                    |
|----------------------|--------------------------------------------|----------------------------------------------------------|
| `TAVILY_API_KEY`     | Web search, image search, page rendering   | See [Web search](#web-search)                            |
| `SEARXNG_URL`        | Fallback web and image search              | See [Web search](#web-search)                            |
| `GIPHY_API_KEY`      | GIF lookup                                 | See [GIFs](#gifs-memes-and-clips)                        |
| `KLIPY_API_KEY`      | GIF, meme and clip lookup                  | Wins over Giphy; see [GIFs](#gifs-memes-and-clips)       |
| `ELEVENLABS_API_KEY` | Voice and round video messages             | See [Voice output](#voice-output)                        |
| `OPENAI_STT_API_KEY` | Voice input and transcribing any recording | Your OpenAI key works; see [Voice input](#voice-input)   |
| `IMAGE_PROVIDER`     | Image generation                           | See [Image generation](#image-generation)                |
| `VIDEO_PROVIDER`     | Video generation                           | See [Video generation](#video-generation)                |
| `VISION_MODEL`       | Vision on a chat model that cannot see     | See [Vision](#vision)                                    |
| `REGOLITH_URL`       | Shell sandbox                              | See [Sandbox](#sandbox)                                  |

### GIFs, memes and clips

Giphy finds GIFs. KLIPY finds GIFs, meme pictures and short clips, and is the one used when both keys
are set. KLIPY's [integration requirements](https://docs.klipy.com/integration-requirements) expect
requests from the end user's device, while a bot sends them from its server, so a KLIPY key needs
their prior written approval from developers@klipy.com. The key from the sign-up form is not that
approval.

### Web search

Reading a link works on any setup. Search comes from up to three providers, each usable alone:

| Provider             | Tools                                             | Role                                                |
|----------------------|---------------------------------------------------|-----------------------------------------------------|
| `TAVILY_API_KEY`     | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search, and page rendering.   |
| `SEARXNG_URL`        | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus search by category.         |
| `LLM_PROVIDER=codex` | `answerFromWeb`                                   | A researched answer with its sources, on the plan.  |

- **SearXNG** — self-hosted and free per call, so search keeps working when Tavily fails or runs out
  of quota.
- **`answerFromWeb`** — on a [ChatGPT subscription](#chatgpt-subscription), as the provider or the
  fallback. It is slower and spends the plan, so the agent tries the others first.

Point `SEARXNG_URL` at the instance root, without `/search`, and let the instance serve JSON — it
ships with `formats: [html]` only and answers anything else with `403`:

```dotenv
SEARXNG_URL=http://searxng:8080
```

```yaml
# searxng settings.yml
search:
  formats:
    - html
    - json
```

### Voice output

`ELEVENLABS_API_KEY` turns on spoken replies and round video messages. A round video message needs
the [Appearance](#appearance) photo; without one, only voice messages are offered.

| Variable               | Default                | Description                      |
|------------------------|------------------------|----------------------------------|
| `ELEVENLABS_VOICE_ID`  | `VD1if7jDVYtAKs4P0FIY` | Voice used for generated speech. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v4`            | ElevenLabs TTS model.            |

### Voice input

`OPENAI_STT_API_KEY` turns on listening, up to the first five minutes of any recording.

| Variable           | Default          | Description           |
|--------------------|------------------|-----------------------|
| `OPENAI_STT_MODEL` | `gpt-transcribe` | Speech-to-text model. |

### Image generation

`IMAGE_PROVIDER` turns on `generateImage` and `editImage` and picks who draws.

| Variable         | Default     | Description                                                                       |
|------------------|-------------|-----------------------------------------------------------------------------------|
| `IMAGE_PROVIDER` | unset       | `openai`, `codex` or `xai`.                                                       |
| `IMAGE_API_KEY`  | —           | The key `openai` or `xai` draws with.                                             |
| `IMAGE_MODEL`    | by provider | `gpt-image-2.5-flare` on `openai` and `codex`, `grok-imagine-image-2.0` on `xai`. |
| `IMAGE_QUALITY`  | by provider | `medium` on `openai` and `codex`, `auto` on `xai`.                                |

- **`openai`** — OpenAI's image API, billed per image. Quality goes from `low` to `max`, or `auto`;
  `xhigh` and `max` need a `gpt-image-2.5` model, and models before `gpt-image-2` lack wide framings.
- **`codex`** — the ChatGPT subscription signed in through `CODEX_HOME`, with no key: the same models
  and qualities, counted against the chat's usage limit.
- **`xai`** — xAI's Imagine API, billed per image. Quality is `low`, `medium` or `auto`, which picks
  `low` for a new picture and `medium` for an edit; a new one at `medium` takes over a minute.

### Video generation

`VIDEO_PROVIDER=xai` turns on `generateVideo`, `editVideo` and `extendVideo`, rendered on xAI's
Imagine API.

| Variable           | Default                  | Description                                                |
|--------------------|--------------------------|------------------------------------------------------------|
| `VIDEO_PROVIDER`   | unset                    | `xai`.                                                     |
| `VIDEO_API_KEY`    | —                        | The key xAI renders with.                                  |
| `VIDEO_MODEL`      | `grok-imagine-video-1.5` | Model for new videos.                                      |
| `VIDEO_RESOLUTION` | `720p`                   | `480p`, `720p` or `1080p`.                                 |
| `VIDEO_VOICE`      | the model's own          | The voice Vusan speaks with in a video of itself.          |

- **Price** — billed per second of video, so the agent keeps clips short unless asked otherwise.
- **Models** — `grok-imagine-video-1.5-lite` is a quarter of the price and makes no clip from
  reference pictures or with Vusan in it. Edits and extensions always run on `grok-imagine-video`.
- **Resolution** — a clip with reference pictures, Vusan, a last frame or a voice renders at
  `720p` at most, whatever is set.
- **Voice** — one of xAI's preset voices, such as `eve` or `ara`.

### Vision

Vision lets the agent look at photos, video frames and images in channel posts, and reply with the
stickers a chat uses. By default the chat model does it, so `openai`, `anthropic` and `codex` need
nothing extra.

When the chat model cannot see, `VISION_MODEL` names one that can. On its own it runs on the chat
provider with the chat key, address and timeout; with `VISION_PROVIDER` it is a provider of its own,
read from the same variables under `VISION_`.

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-pro
LLM_API_KEY=sk-qwerty

VISION_PROVIDER=openai
VISION_MODEL=gpt-6-luna
VISION_API_KEY=sk-proj-qwerty
```

| Variable                         | Default           | Description                                                                 |
|----------------------------------|-------------------|-----------------------------------------------------------------------------|
| `VISION_MODEL`                   | —                 | Turns on a vision model of its own.                                         |
| `VISION_PROVIDER`                | the chat provider | `openai`, `anthropic`, `xai`, `openrouter`, `codex` or `openai-compatible`. |
| `VISION_API_KEY`                 | —                 | Required with `VISION_PROVIDER`, except on `codex`.                         |
| `VISION_BASE_URL`                | —                 | Required with `VISION_PROVIDER=openai-compatible`.                          |
| `VISION_COMPATIBLE_API`          | `completions`     | `completions` or `responses`, for `openai-compatible`.                      |
| `VISION_REASONING_EFFORT`        | model default     | Effort of every look; the values `LLM_REASONING_EFFORT` takes.              |
| `VISION_REQUEST_TIMEOUT_SECONDS` | the chat's        | How long one look may hang; read with `VISION_PROVIDER` only.               |

- **On `openai-compatible`** — a model sees only if the server's model list says so: DeepSeek's says
  it of `deepseek-flash`, not of `deepseek-v4-pro`. Where the server lists nothing, as on Ollama, set
  `VISION_MODEL`, even to the chat model itself.
- **Precedence** — a vision model, once set, always does the looking, even where the chat model could.
- **No vision at all** — startup logs a `WARN`, and attachments go unseen.

Sticker replies spend vision on their own: each new set a chat uses is described once, up to sixty
calls per set, at most three new sets a day per chat and six for the whole bot.

| Variable           | Default | Description                                                   |
|--------------------|---------|---------------------------------------------------------------|
| `STICKERS_ENABLED` | `true`  | `false` learns no sticker sets and offers no sticker replies. |

## Sandbox

Vusan can give each person one persistent Linux home, shared across all their chats. It runs on a
**Regolith server**, a separate project with its own deployment, and is off until the bot can reach
one; see [the sandbox guide](sandbox.md).

| Variable         | Default | Description                                                |
|------------------|---------|------------------------------------------------------------|
| `REGOLITH_URL`   | —       | Address of the Regolith server. Unset means no sandbox.    |
| `REGOLITH_TOKEN` | —       | Its API token, the same value the server was started with. |

A URL without a valid token stops startup. The token is 32 to 256 printable characters —
`openssl rand -hex 32` makes one. Regolith listens only on its own machine by default, so
[the sandbox guide](sandbox.md#pointing-the-bot-at-it) shows which address to give the bot.

The image, memory, disk, timeouts, retention and network policy are all set on the server.

## Publishing to the web

There is nothing to set: the Regolith server does the publishing, so a sandbox that can publish brings
the tools with it. What a site may hold, where it is served and how long it is kept are the server's;
see [the site guide](sites.md).

## Conversation

History is kept per person and per chat; older exchanges are folded into a recap by the chat model.

| Variable                      | Default | Description                                  |
|-------------------------------|---------|----------------------------------------------|
| `CONVERSATION_RETENTION_DAYS` | `90`    | Days recapped exchanges stay on disk.        |

## Group log

In groups Vusan records every message it receives, including those not addressed to it, so it can
answer "what did I miss" and recap a day. Private chats are never recorded.

| Variable                   | Default | Description                                               |
|----------------------------|---------|-----------------------------------------------------------|
| `GROUP_LOG_ENABLED`        | `true`  | `false` records nothing and drops the group-log tools.    |
| `GROUP_LOG_RETENTION_DAYS` | `30`    | Days a recorded message is kept.                          |

- **Who is recorded** — in a group outside `ALLOWED_IDS`, only the people Vusan answers there. Never
  a [banned](#who-vusan-answers) sender.
- **What a row holds** — the text, sender and time, and a short label for anything else; never the
  media. At most fifty thousand rows per chat.
- **Forgetting** — asking the agent to forget the group log wipes the chat's messages, recaps and
  [diary](#diary);
  `/clear` does not touch it.

## Answering without a mention

In a group Vusan answers a mention, a reply to its message or a command. With this on, it also
answers a message that calls it by name — «robin, what do you think» — or follows up on what it just
said. A small separate model decides whether each such message is for Vusan.

```dotenv
ADDRESSING_ENABLED=true
ADDRESSING_MODEL=gpt-6-luna
ADDRESSING_NAMES=robin,robbie
```

It needs its own model and the [group log](#group-log), and either one missing stops startup. Like a
[vision model](#vision), it runs on the chat provider unless `ADDRESSING_PROVIDER` names its own,
read under the `ADDRESSING_` prefix.

| Variable                             | Default            | Description                                                                 |
|--------------------------------------|--------------------|-----------------------------------------------------------------------------|
| `ADDRESSING_ENABLED`                 | `false`            | Turns the feature on.                                                       |
| `ADDRESSING_MODEL`                   | —                  | The model that decides; never the chat model.                               |
| `ADDRESSING_NAMES`                   | the bot's handles  | Other spellings the chat uses, comma-separated.                             |
| `ADDRESSING_PROVIDER`                | the chat provider  | `openai`, `anthropic`, `xai`, `openrouter`, `codex` or `openai-compatible`. |
| `ADDRESSING_API_KEY`                 | —                  | Required with `ADDRESSING_PROVIDER`, except on `codex`.                     |
| `ADDRESSING_BASE_URL`                | —                  | Required with `ADDRESSING_PROVIDER=openai-compatible`.                      |
| `ADDRESSING_COMPATIBLE_API`          | `completions`      | `completions` or `responses`, for `openai-compatible`.                      |
| `ADDRESSING_REASONING_EFFORT`        | the least it takes | Effort of every verdict.                                                    |
| `ADDRESSING_REQUEST_TIMEOUT_SECONDS` | the chat's         | How long one verdict may hang; with its own provider only.                  |

- **Model** — a small, fast one, since every verdict is latency; of eight small OpenAI models
  measured, `gpt-5.6-luna` made the fewest wrong calls.
- **Names** — the bot's Telegram name always counts. Without `ADDRESSING_NAMES`, so does each of its
  handles minus the ending, so `@robinbot` answers to «robin».
- **What is sent** — a message that names Vusan or follows its reply, with up to six lines before
  it. Nothing else in the chat leaves the bot.

## Diary

After a day ends, Vusan writes itself a short entry about each group, and after a week or a month it
looks back over that span's entries in one. The last three days, the last week and the last month ride
along on every turn there, so it picks up yesterday's thread and remembers what came before.

| Variable        | Default | Description                                       |
|-----------------|---------|---------------------------------------------------|
| `DIARY_ENABLED` | `true`  | `false` writes nothing and sends no day anywhere. |

- **What is sent** — once per group per day with at least fifteen messages, that day's transcript,
  up to 100,000 characters, goes to the chat model with nobody having asked. A look-back sends the
  span's own entries, not the messages.
- **How long** — entries are kept after the day's messages expire, until the group log is forgotten.

It needs the [group log](#group-log); with `GROUP_LOG_ENABLED=false` it stays off.

## Initiative

In a group Vusan normally speaks only when spoken to. With this on, it also looks over a chat people
are writing in and, now and then, reacts, says a line of its own or answers with one of the chat's
stickers. It is on for every group in `ALLOWED_IDS`.

| Variable             | Default | Description                                    |
|----------------------|---------|------------------------------------------------|
| `INITIATIVE_ENABLED` | `true`  | `false` keeps Vusan quiet until spoken to.     |

- **What is sent** — up to sixty lines from the chat's last six hours go to the chat model, with
  nobody having asked, every fifteen to forty-five minutes while people write.
- **How much it writes** — at most four lines a day per chat, and twenty reactions; nothing from one
  to eight in the morning, on the bot's clock.
- **In the log** — each look leaves an `initiative look:` line with what it did and why.

It needs the [group log](#group-log); with `GROUP_LOG_ENABLED=false` it stays off.

## Rights in a group

Vusan reads what each group lets it post and adapts, with nothing to set, but three group settings
change what it can do:

- **Privacy mode decides what it sees** — a new bot has it on, and then Telegram hands it only
  mentions, replies to it and commands. The [group log](#group-log), recaps,
  [answering without a mention](#answering-without-a-mention), the [diary](#diary) and
  [initiative](#initiative) need the whole chat: turn privacy mode off with `/setprivacy` in
  [@BotFather](https://t.me/BotFather) and add the bot to the group again, or make it an admin.
- **A plain member follows the group's defaults** — turning off photos, stickers, polls, voice
  messages or files for everyone turns them off for Vusan too. An admin is exempt, slow mode
  included.
- **Losing the right to write pauses that chat's tasks** — they stay in `/tasks`; resume them after
  adding Vusan back.

## Command menu

The bot publishes its command menu on every start, in each language it speaks. It is the same list
BotFather's `/setcommands` edits, so a change made there lasts only until the next restart.

## How many requests at once

| Variable               | Default | Description                               |
|------------------------|---------|-------------------------------------------|
| `MAX_CONCURRENT_TURNS` | `8`     | Requests Vusan works on at the same time. |

It counts different people; one person's messages in a chat are answered in order. Each request is a
model call plus its tools, so match it to what your provider accepts at once, not to what the machine
could hold. Past the limit, people wait their turn.

## Agent loop

| Variable                | Default | Description                    |
|-------------------------|---------|--------------------------------|
| `AGENT_MAX_MODEL_CALLS` | `100`   | Model calls one turn may make. |

The default is enough to build and fix a project in the sandbox or research a question across many
sources. Reaching it ends the turn with a wrap-up of what was done. Lower it only to keep a cheap
model from wandering; the minimum is three.

## Storage and binaries

| Variable              | Default            | Description                                       |
|-----------------------|--------------------|---------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path; parent directories are created.      |
| `YT_DLP_COOKIES_FILE` | —                  | Cookies for YouTube videos that ask for a login.  |

In Docker the `data/` directory sits beside the compose file, and `VUSAN_HOST_DIR` in `.env` moves it.
The database is a file you copy for a backup, and `SELF_IMAGE_FILE`, `APPEARANCE_FILE` and
`YT_DLP_COOKIES_FILE` are files you put there. Create it before the first start — `mkdir -p data` —
because a directory Docker creates is owned by `root`, and the bot runs as uid 1000.

`VUSAN_IMAGE` picks the image, `ghcr.io/helltar/vusan:latest` by default; set it to pin a release or
run your own build.

A release that changes the database moves it forward on its first start, with nothing to run by hand.
It first leaves a copy beside it named after the old version (`vusan.db.schema-4`), which you can
delete once the new one runs. A database from before 0.13.0, or from a newer release than the one
starting, stops the start and says so.

`YT_DLP_COOKIES_FILE` is a Netscape-format `cookies.txt`; see the
[yt-dlp wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies).

## Logging

| Variable            | Default | Description                                              |
|---------------------|---------|----------------------------------------------------------|
| `LOG_LEVEL`         | `INFO`  | Level for everything Vusan and its libraries log.        |
| `PROMPT_DUMP_LEVEL` | `OFF`   | `DEBUG` prints every request sent to the model, in full. |

Both are read from the process environment, not from `.env`. In the Compose deployment that makes no
difference, since `env_file` turns `.env` into real environment variables; a local run needs them on
the command line or in the IDE:

```bash
PROMPT_DUMP_LEVEL=DEBUG java -jar build/libs/vusan-*-all.jar
```

`PROMPT_DUMP_LEVEL=DEBUG` is for debugging, not for leaving on: it writes tens of thousands of
characters of raw chat per turn into the log. An unrecognized level, even an empty one, is read as
`DEBUG`.

## Health check

The image has a Docker `HEALTHCHECK`, so `docker compose ps` shows whether the bot is actually
polling, not just whether its process is alive:

```console
$ docker compose ps
NAME              STATUS
vusan-container   Up 6 hours (healthy)
```

The first `healthy` takes about two minutes. A short Telegram outage stays healthy and recovers on its
own; past about fifteen minutes of failures the container goes unhealthy, and a restart resumes
polling at once. To act on it automatically, pair it with a container that restarts unhealthy
services, or point an uptime monitor at Docker's health state:

```bash
docker inspect --format '{{.State.Health.Status}}' vusan-container
```
