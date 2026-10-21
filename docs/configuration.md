# Configuration

Vusan reads every setting from `.env`. Copy the [example file](../.env.example) and fill in what you
need.

- **Docker** — follow the [quick start](../README.md#quick-start); the same `.env` also holds the few
  Compose settings at the end of the example.
- **Values** — a blank one counts as missing, and booleans are `true` or `false` in any case. One that
  is set but unreadable, like `AGENT_MAX_MODEL_CALLS=7O`, stops startup and names the variable.
- [Sandbox](#sandbox) — configured on its own: a Regolith server that runs the commands the model
  writes and never sees the bot's secrets.

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

`LLM_MODEL` goes into the system prompt, so "which model are you?" gets the true answer.

## Who Vusan answers

`OWNER_ID` is the one person the deployment belongs to: answered everywhere, in private and in any
group, and exempt from the [limit on one person alone in a group](#one-person-alone-in-a-group).

| Variable      | Default | Description                                                  |
|---------------|---------|--------------------------------------------------------------|
| `ALLOWED_IDS` | empty   | Telegram user and group IDs Vusan answers besides the owner. |
| `BANNED_IDS`  | empty   | IDs Vusan ignores, whatever else allows them.                |

- **Format** — separated by commas, spaces or semicolons. Positive IDs are users, negative ones
  groups, and an allowed group admits everyone in it. `telegram:123456789` names the messenger
  explicitly; a plain number means Telegram. `OWNER_ID` takes either form.
- **Empty `ALLOWED_IDS`** — Vusan answers the owner alone.
- **Bans win** — over the allowlist and over the owner, which is how one person is shut out of an
  open group. A banned ID gets no reply, dead buttons, nothing in the group log and no stickers
  learned; their tasks stay in place but do not fire. Removing the ban restores all of it. An ID on
  both lists stays banned, and startup logs it.
- **Scheduled tasks** — follow the same lists: a task whose owner and chat are both outside them
  does not fire. A recurring one skips those runs and resumes when access returns; a one-time one
  waits and runs then, or is reported missed if that is over an hour late.

## LLM provider

`LLM_PROVIDER` picks the backend. Whichever it is, the model must support tool calling.

| Provider            | Example model              | What it is                                                     |
|---------------------|----------------------------|----------------------------------------------------------------|
| `openai`            | `gpt-6.1-sol`              | OpenAI's API.                                                  |
| `anthropic`         | `claude-opus-5-5`          | Anthropic's API, Claude 4.6 or newer.                          |
| `xai`               | `grok-4.7`                 | xAI's API.                                                     |
| `codex`             | a model your plan offers   | A ChatGPT plan instead of a key; see below.                    |
| `openai-compatible` | whatever the server serves | Any OpenAI-compatible server, remote or local.                 |

On `openai`, `anthropic` and `xai`, startup checks the model against the vendor's model list, so a
typo fails there rather than on the first message.

| Variable                      | Default        | Description                                                        |
|-------------------------------|----------------|--------------------------------------------------------------------|
| `LLM_BASE_URL`                | —              | Server address; required by `openai-compatible`, unused otherwise. |
| `LLM_COMPATIBLE_API`          | `completions`  | `completions` or `responses`; `openai-compatible` only.            |
| `LLM_REASONING_EFFORT`        | model default  | `none`, `low`, `medium`, `high`, `xhigh` or `max`.                 |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `300`          | How long one model call may hang before the turn fails.            |
| `LLM_CONTEXT_WINDOW_TOKENS`   | model metadata | Context size; required where nothing states one.                   |

- **`LLM_BASE_URL`** — give it without `/v1`: `https://api.deepseek.com` is called at
  `…/v1/chat/completions`, or `…/v1/responses` with `LLM_COMPATIBLE_API=responses`. `openai` and
  `codex` always call their own Responses API.
- **`LLM_REASONING_EFFORT`** — which values work depends on the model. `codex`, `anthropic` and
  `xai` check at startup, `openai-compatible` does when its server lists efforts (DeepSeek's does), `openai` on
  the first turn. `none` turns reasoning off where a model allows it; since no list names it, the
  first turn tells. Claude takes `low` to `max`, and without a value uses its own default: `medium`
  on Opus 5.5 and Haiku 5.5, `high` on the rest.
- **`LLM_REQUEST_TIMEOUT_SECONDS`** — raise it for slow local servers and heavy reasoning: a Fable
  turn at a high effort can run for minutes.
- **`LLM_CONTEXT_WINDOW_TOKENS`** — overrides what Vusan knows: Anthropic's model list, the plan's
  catalog on `codex` (272,000 if it cannot be read), 1,050,000 on `openai`, xAI's model list on `xai`
  (256,000 if it cannot be read), and the server's list on `openai-compatible` when it states one. When it does not, startup stops until you set the context
  the server really runs the model with. Part of the window is kept for the reply and tool results,
  and history fills the rest with whole interactions.

An `openai` model is taken to be of the current generation: it reasons, sees images and calls tools.
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
without a browser. `codex login status` checks the session. After `codex logout`, Vusan replies that
its connection needs renewing until you sign in again.

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

- **Where it lives** — `~/.codex/auth.json` of whoever signed in. `CODEX_HOME` moves it for both the
  CLI and Vusan: set it in `.env` and pass the same value to `codex login`. In a container, mount
  that directory read-write.
- **Files only** — Vusan cannot reach the OS keyring, so keep `cli_auth_credentials_store = "file"`
  in that directory's `config.toml`.
- **A password** — the file holds live access and refresh tokens; treat it like one.
- **No restart needed** — Vusan rereads the file whenever it changes, so a new sign-in works from
  the next request. With a [fallback](#a-second-provider-behind-the-first), though, an expired
  sign-in keeps Codex out for half an hour; a restart brings it back at once.
- **Token refresh** — an access token lives ten days, and Vusan renews it about a day before expiry.
  Sharing the file with an interactive CLI is fine: either side's refresh keeps the other working.
  A `codex login --with-access-token` credential cannot be renewed, so Vusan asks for a new one as it
  nears expiry.

```dotenv
CODEX_HOME=/home/vusan/.codex-vusan
```

### What the plan decides

At startup Vusan reads the models your plan offers and checks the settings against that list:

- **`LLM_MODEL`** — a model the plan does not offer stops startup and lists the ones it does. Codex
  and the OpenAI Platform serve different models, so a Platform id would otherwise fail on the first
  message.
- **`LLM_REASONING_EFFORT`** — an effort the model does not take stops startup; `none` is left to the
  first turn.
- **`CODEX_SERVICE_TIER`** — a tier the model is not served at stops startup.
- **Vision** — the chat model looks at images only if the catalog says it can.
- **Window and verbosity** — both come from the catalog. Without it, the backend's own wordier
  default applies.

### When a new model is missing

The plan's list depends on the Codex client version Vusan claims, so a model released with a newer
CLI is missing from it. Vusan claims the newest of three: the version this build knows, the `codex`
installed on the host, and the latest release on GitHub. The startup log says which one won, and only
a host that cannot reach `api.github.com` is left on the built-in one. To pin a version:

```dotenv
CODEX_CLIENT_VERSION=0.162.0
```

It must look like a Codex CLI version. One older than the model needs hides the model again.

### Speed, pictures and search

- **Faster tier** — `CODEX_SERVICE_TIER=priority` is what the Codex CLI calls `/fast`, and spends the
  plan quicker. It applies to the chat model and its fallback only; make sure the model offers it.
- **Pictures** — `IMAGE_PROVIDER=codex` draws [images](#image-generation) on the same account,
  whether or not the chat runs on `codex`.
- **Web search** — `answerFromWeb` searches through the plan with no search key; see
  [Web search](#web-search). Each search spends a good deal of the allowance;
  `CODEX_WEB_SEARCH_ENABLED=false` turns it off.

### Limits

- **Usage windows** — the plan is metered, not billed per token, so a heavy day ends in a "usage
  limit reached" reply with the time left — unless a [fallback](#a-second-provider-behind-the-first)
  takes over.
- **Not a public API** — this route uses the endpoint OpenAI ships for its own Codex clients, so an
  OpenAI-side change can break it. `LLM_PROVIDER=openai` with a key is the supported alternative.
- **How much the plan holds** — OpenAI reports only the share of each window used, so Vusan logs a
  `codex limits:` line whenever a share moves, with the calls, pictures and tokens since the last one.
  Read together, they show what one percent of the five-hour or weekly window is worth.

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

The same model on both sides is the usual case: the plan pays until its window is spent, the key
after that. `LLM_FALLBACK_BASE_URL`, `LLM_FALLBACK_COMPATIBLE_API`, `LLM_FALLBACK_REASONING_EFFORT`,
`LLM_FALLBACK_REQUEST_TIMEOUT_SECONDS` and `LLM_FALLBACK_CONTEXT_WINDOW_TOKENS` mean what their `LLM_`
counterparts do; the timeout follows the primary's when unset.

- **Retries come first** — a `5xx`, an overloaded server or a cut stream is tried up to three times,
  a second or two apart, on any provider, with or without a fallback.
- **What switches** — a usage limit, rejected credentials, a rate limit, an overloaded server (`503`,
  `529`), a server error that kept failing (`500`, `502`, `504`), a connection that timed out or
  dropped. The failed call is repeated on the fallback, and every later call — turns, recaps,
  group-log digests, vision on the chat model — goes there too.
- **For how long** — a usage limit until the reset time it named, an expired sign-in for half an
  hour, a rate limit or an outage for two minutes. Then one call probes the primary, and Vusan goes
  back to it or waits another round.
- **What does not switch** — a content refusal, as an error or as a declined reply. It would repeat
  anywhere, so the user is asked to word the request differently.
- **What the chat sees** — the turn's status message names the fallback model, and after a usage
  limit, how long until the usual one is back. The agent is told which model is answering, so it
  answers "what are you running on" truthfully.

Pick a fallback that sees images if the chat model does, since vision switches with it, and whose
window is not much smaller: history is always planned against the smaller of the two. Image
generation does not switch — on `IMAGE_PROVIDER=codex` it fails with the spent plan, so a deployment
that wants pictures through an outage uses `IMAGE_PROVIDER=openai`.

## Personality

Vusan ships with a built-in personality, kept generic so each deployment can give it its own identity,
tone and style. The rules for output and tools are always appended separately, and a personality
cannot remove them.

| Variable           | Description                                                                     |
|--------------------|---------------------------------------------------------------------------------|
| `PERSONALITY_FILE` | The personality text. Unreadable stops startup; a blank file uses the built-in. |

## Appearance

Text-to-image draws a new face every time, so Vusan draws pictures of itself from a reference photo
instead and keeps one face across all of them. The reference is the bot's Telegram avatar unless a
file overrides it, so a bot with an avatar set in [@BotFather](https://t.me/BotFather) already has
one. The [round video message](#voice-output) uses the same photo.

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

`readPage` is always there: it fetches any public `http` or `https` address and keeps the article
text, so a link someone sends, or a search result, can be read on any setup. Search itself comes from
up to three providers, each usable alone:

| Provider             | Tools                                             | Role                                                |
|----------------------|---------------------------------------------------|-----------------------------------------------------|
| `TAVILY_API_KEY`     | `webSearch`, `searchImages`, `extractPageContent` | Default web and image search, and page rendering.   |
| `SEARXNG_URL`        | `metaSearch`, `metaSearchImages`                  | Fallback for both, plus search by category.         |
| `LLM_PROVIDER=codex` | `answerFromWeb`                                   | A researched answer with its sources, on the plan.  |

- **Tavily** — returns cleaned-up page extracts rather than snippets, and `searchImages` describes
  each photo. It gives about five images per search whatever is asked, so `metaSearchImages` fills a
  larger order with a second album. `extractPageContent` renders a page before reading it, so the
  agent tries it first and falls back to `readPage` when it fails or cuts a long page short.
- **SearXNG** — self-hosted, free per call, and what keeps search working when Tavily fails or runs
  out of quota. `metaSearch` can scope a query to `news`, `it`, `science`, `videos`, `music`, `files`,
  `social media` or `map`, which reach other engines when the general ones are rate-limited.
- **`answerFromWeb`** — on a [ChatGPT subscription](#chatgpt-subscription), as the provider or the
  fallback, OpenAI searches itself and returns a written answer with its sources. It is the only
  search a setup without either key has, and the one for questions that take several pages. It is
  slower — ten seconds or more — and spends the plan, so the agent tries the others first.

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

`ELEVENLABS_API_KEY` turns on spoken replies and round video messages.

| Variable               | Default                | Description                      |
|------------------------|------------------------|----------------------------------|
| `ELEVENLABS_VOICE_ID`  | `VD1if7jDVYtAKs4P0FIY` | Voice used for generated speech. |
| `ELEVENLABS_TTS_MODEL` | `eleven_v4`            | ElevenLabs TTS model.            |

A round video message puts the reference photo from [Appearance](#appearance) in the circle with the
same voice over it, drawn by `ffmpeg`. With no photo at all — no `SELF_IMAGE_FILE` and no avatar —
only voice messages are offered.

### Voice input

`OPENAI_STT_API_KEY` turns on listening.

| Variable           | Default          | Description           |
|--------------------|------------------|-----------------------|
| `OPENAI_STT_MODEL` | `gpt-transcribe` | Speech-to-text model. |

Vusan hears the first five minutes of anything: a longer voice message is refused, and of a longer
video or recording only those minutes are transcribed. Any recording a turn can name works, not only
the one a message arrived as — a file someone sent, a track it fetched, a piece it cut in the sandbox.

### Image generation

`IMAGE_PROVIDER` turns on `generateImage` and `editImage` and picks who draws:

- **`openai`** — the OpenAI image API, billed per image against `IMAGE_API_KEY`; your OpenAI key works.
- **`codex`** — the ChatGPT subscription signed in through `CODEX_HOME`, with no key, whatever the
  chat runs on.
- **`xai`** — xAI's Imagine API, billed per image against `IMAGE_API_KEY`.

| Variable         | Default     | Description                                                                       |
|------------------|-------------|-----------------------------------------------------------------------------------|
| `IMAGE_PROVIDER` | unset       | `openai`, `codex` or `xai`.                                                       |
| `IMAGE_API_KEY`  | —           | The key `openai` or `xai` draws with.                                             |
| `IMAGE_MODEL`    | by provider | `gpt-image-2.5-flare` on `openai` and `codex`, `grok-imagine-image-2.0` on `xai`. |
| `IMAGE_QUALITY`  | by provider | `medium` on `openai` and `codex`, `auto` on `xai`.                                |

The agent picks the aspect ratio; the model and quality are yours, so the cost stays predictable.

- **Self-portraits** — a picture of the bot is an edit of the [Appearance](#appearance) photo, which
  keeps the face.
- **Edits** — take everything one message carried, so an album sent with "merge these" becomes one
  picture, with Vusan in it if asked.

On `openai`:

- **Quality** — `low`, `medium`, `high`, `xhigh`, `max` or `auto`. `xhigh` and `max` work only on
  `gpt-image-2.5` models; older ones stop at `high` and fail above it.
- **Likeness** — on `gpt-image-1`, `-1-mini` and `-1.5` holding a face that closely costs extra input
  tokens; from `gpt-image-2` on it is always included.
- **Sizes** — wallpaper and banner framings, and sizes beyond the three classic ones, need
  `gpt-image-2` or newer; older models use the nearest size they have.
- **Format** — pictures arrive as JPEG: Telegram re-encodes photos anyway, and the smaller file keeps
  a `max` picture within its size limit.
- **Refusals** — OpenAI filters both the prompt and the picture, and that cannot be turned off;
  Vusan asks for the looser of its two settings. A refusal is reported as one, with an offer to
  change the picture.

On `codex` the models and qualities are OpenAI's, with three differences: pictures count against the
same usage limit as the chat, the aspect ratio is only a hint because the model picks its own
dimensions, and the filter is ChatGPT's, with no setting.

On `xai`:

- **Quality** — `low`, `medium` or `auto`. On `auto` xAI picks `low` for a new picture and `medium`
  for an edit; a new picture at `medium` takes over a minute instead of about fifteen seconds.
- **Size** — pictures come at about 1,250 pixels on the long side, the most Telegram shows of a photo.
- **Edits** — up to five images at once, Vusan's own photo included; of a bigger album the first are
  used, and Vusan says so.

### Video generation

`VIDEO_PROVIDER=xai` turns on `generateVideo`, `editVideo` and `extendVideo`: short videos with sound
from a description, a photo brought to life, a clip with Vusan in it, and a video changed or carried
on. They render on xAI's Imagine API with an xAI API key.

| Variable           | Default                  | Description                                                |
|--------------------|--------------------------|------------------------------------------------------------|
| `VIDEO_PROVIDER`   | unset                    | `xai`.                                                     |
| `VIDEO_API_KEY`    | —                        | The key xAI renders with.                                  |
| `VIDEO_MODEL`      | `grok-imagine-video-1.5` | Model for new videos.                                      |
| `VIDEO_RESOLUTION` | `720p`                   | `480p`, `720p` or `1080p`.                                 |
| `VIDEO_VOICE`      | the model's own          | The voice Vusan speaks with in a video of itself.          |

- **Waiting** — a clip takes from half a minute to a few minutes, and the turn waits for it with
  🎥 in its status. People ask for videos by name, so the tools load when a request needs them.
- **Price** — billed per second of video, so the agent keeps clips short unless asked otherwise.
- **Models** — `grok-imagine-video-1.5-lite` is a quarter of the price and makes no clip from
  reference pictures or with Vusan in it. Edits and extensions always run on `grok-imagine-video`.
- **Resolution** — a clip with reference pictures, Vusan, a last frame or a voice renders at
  `720p` at most, whatever is set.
- **Vusan in a video** — the [Appearance](#appearance) photo and notes keep the face, and
  `VIDEO_VOICE` names one of xAI's preset voices, such as `eve` or `ara`.
- **Sources** — pictures as JPEG, PNG or WebP; a video to edit as an MP4 under nine seconds, to
  extend as one of two to fifteen.

### Vision

Vision lets the agent look at photos, video frames and images in channel posts, and learn and reply
with the stickers a chat uses. By default the chat model does it, so `openai`, `anthropic` and
`codex` need nothing extra. A look runs at the model's own default effort, not
`LLM_REASONING_EFFORT`.

When the chat model cannot see, `VISION_MODEL` names one that can. On its own it runs on the chat
provider with the chat key, address and timeout; with `VISION_PROVIDER` it is a provider of its own,
read from the same variables under `VISION_`. Its effort is always its own.

```dotenv
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-v4-pro
LLM_API_KEY=sk-qwerty

VISION_PROVIDER=openai
VISION_MODEL=gpt-6-luna
VISION_API_KEY=sk-proj-qwerty
```

| Variable                         | Default           | Description                                                      |
|----------------------------------|-------------------|------------------------------------------------------------------|
| `VISION_MODEL`                   | —                 | Turns on a vision model of its own.                              |
| `VISION_PROVIDER`                | the chat provider | `openai`, `anthropic`, `xai`, `codex` or `openai-compatible`.    |
| `VISION_API_KEY`                 | —                 | Required with `VISION_PROVIDER`, except on `codex`.              |
| `VISION_BASE_URL`                | —                 | Required with `VISION_PROVIDER=openai-compatible`.               |
| `VISION_COMPATIBLE_API`          | `completions`     | `completions` or `responses`, for `openai-compatible`.           |
| `VISION_REASONING_EFFORT`        | model default     | Effort of every look; the values `LLM_REASONING_EFFORT` takes.   |
| `VISION_REQUEST_TIMEOUT_SECONDS` | the chat's        | How long one look may hang; read with `VISION_PROVIDER` only.    |

- **On `openai-compatible`** — a model sees only if the server's model list says so: DeepSeek's says
  it of `deepseek-flash`, not of `deepseek-v4-pro`. On a server that lists nothing, such as Ollama,
  vision stays off until `VISION_MODEL` is set; naming the chat model itself is enough, as in the
  [Ollama example](#llm-provider).
- **Precedence** — a vision model, once set, always does the looking, even where the chat model could.
  It is trusted to see unless its server's list or the plan's catalog says it cannot, which stops
  startup.
- **No vision at all** — startup logs a `WARN`, attachments go unseen, and channel posts come back as
  text only.

Sticker replies come with vision, and they are the one part that spends on its own: each new set a
chat uses is described once — up to sixty vision calls per set, at most three new sets a day per chat
and six for the whole bot.

| Variable           | Default | Description                                                   |
|--------------------|---------|---------------------------------------------------------------|
| `STICKERS_ENABLED` | `true`  | `false` learns no sticker sets and offers no sticker replies. |

## Sandbox

Vusan can give each person one persistent Linux home, **shared across all their chats** — a real
shell for projects, media, documents and data, whose files survive new messages, `/clear` and
restarts. It runs on a **Regolith server**, a separate project with its own deployment, and is off
until the bot can reach one. What the model can do there and where the limits come from is in
[the sandbox guide](sandbox.md).

| Variable         | Default | Description                                                |
|------------------|---------|------------------------------------------------------------|
| `REGOLITH_URL`   | —       | Address of the Regolith server. Unset means no sandbox.    |
| `REGOLITH_TOKEN` | —       | Its API token, the same value the server was started with. |

Without `REGOLITH_URL` the sandbox tools do not exist and the bot never mentions them; a URL without
a valid token stops startup. The token is 32 to 256 printable characters — `openssl rand -hex 32`
makes one. Regolith listens only on its own machine by default, so
[the sandbox guide](sandbox.md#pointing-the-bot-at-it) shows which address to give the bot.

The image, memory, disk, timeouts, retention and network policy are all set on the server, and the
bot reads its limits from there.

## Publishing to the web

Vusan can put a finished page, game or small web app on the public internet, one address per person.
There is nothing to set here: the Regolith server does the publishing, so a sandbox that can publish
brings the tools with it. What a site may hold, where it is served and how long it is kept are the
server's; see [the site guide](sites.md).

## Conversation

History is kept per person **and per chat**, and holds only the exchanges Vusan took part in.
Someone active in a DM and two groups has three threads, each with its own recap and retention.

| Variable                      | Default | Description                                  |
|-------------------------------|---------|----------------------------------------------|
| `CONVERSATION_RETENTION_DAYS` | `90`    | Days recapped exchanges stay on disk.        |

- **What the model sees** — the last 40 interactions word for word, as many as fit the window; older
  ones are folded into a running recap by the chat model.
- **What stays on disk** — recapped rows stay for the retention period, at most a hundred per thread,
  and never reach the prompt again. Cleanup runs after each turn and every six hours.
- **`/clear`** — removes the transcript and recap of the chat it was sent in, for that person only.
  Memory and scheduled tasks stay.

## Memory

Memory outlives `/clear`. Personal entries follow a person into every chat; group entries belong to
the chat. Each holds up to twenty entries and drops the oldest past that. There is nothing to set.

History does not travel the same way — what someone said in a DM is not replayed in a group, and two
groups never see each other's exchanges — so asking Vusan to remember something is how it follows
you everywhere. A person's sandbox is shared across chats too. Neither memory nor the sandbox is
offered to a sender behind a shared account: an anonymous group admin, or a linked channel.

## Scheduled tasks

Built in, with nothing to set. The agent schedules them in three forms:

- **`once <datetime>`** — fires once, then is disabled.
- **`every <interval>`** — a fixed interval of at least 5 minutes, independent of time zone.
- **`cron <UNIX expr>`** — clock times, in the task's time zone.

One person may keep ten tasks. A task that could not fire within an hour of its time — Vusan was
offline, the machine was asleep — is not run late: the chat gets a missed notice and the schedule
moves on.

The agent may also schedule its own one-time follow-up when a conversation gives it a reason to come
back ("ask how the exam went"). Those have a limit of their own, three per person, so they never eat
into the user's ten, and they appear in `/tasks` where the user can cancel them.

## Group log

In groups Vusan records every message it receives, including those not addressed to it, so it can
answer "what did I miss" and recap a day. The log belongs to the chat and is separate from
conversation history. Private chats are never recorded.

| Variable                   | Default | Description                                               |
|----------------------------|---------|-----------------------------------------------------------|
| `GROUP_LOG_ENABLED`        | `true`  | `false` records nothing and drops the group-log tools.    |
| `GROUP_LOG_RETENTION_DAYS` | `30`    | Days a recorded message is kept.                          |

- **Who is recorded** — in a group outside `ALLOWED_IDS`, only the people Vusan answers there: the
  owner and anyone listed by ID. Never a [banned](#who-vusan-answers) sender.
- **What a row holds** — the text (up to 2,000 characters, 1,000 for a forwarded post), sender and
  time, the kind of message, a short label for anything else (`🐤 UtyaDuck`, `0:14`, `report.pdf`),
  where a forward came from, and the message it replied to. Never the media, or the file ids to fetch
  it. Vusan's own replies are recorded; replies redirected to someone's DMs are not.
- **How much** — at most fifty thousand rows per chat on top of the retention, so a very busy group is
  trimmed by count first. Cleanup runs every few hundred messages.
- **Every turn** — a group turn carries the last fifteen messages of the past hour.
- **Long ranges** — a range too big for the context is read through daily recaps, made once per
  closed day by the chat model and cached. The current day is never cached.
- **Forgetting** — asking the agent to forget the group log wipes the chat's messages and recaps.
  `/clear` does not touch it: the log belongs to the group, not to whoever ran the command.

## Answering without a mention

In a group Vusan answers a mention, a reply to its message or a command. With this on, it also
answers a message that calls it by name — «robin, what do you think» — or follows up on what it just
said. A small separate model decides whether each such message is for Vusan; anything that calls it
outright is answered as before, without asking.

```dotenv
ADDRESSING_ENABLED=true
ADDRESSING_MODEL=gpt-6-luna
ADDRESSING_NAMES=robin,robbie
```

It needs its own model and the [group log](#group-log), and either one missing stops startup. A model
set without `ADDRESSING_ENABLED=true` only earns a startup `WARN`. Like a [vision model](#vision), it
runs on the chat provider unless `ADDRESSING_PROVIDER` names its own, read under the `ADDRESSING_`
prefix.

| Variable                             | Default            | Description                                                  |
|--------------------------------------|--------------------|--------------------------------------------------------------|
| `ADDRESSING_ENABLED`                 | `false`            | Turns the feature on.                                        |
| `ADDRESSING_MODEL`                   | —                  | The model that decides; never the chat model.                |
| `ADDRESSING_NAMES`                   | the bot's handles  | Other spellings the chat uses, comma-separated.              |
| `ADDRESSING_PROVIDER`                | the chat provider  | `openai`, `anthropic`, `xai`, `codex` or `openai-compatible`. |
| `ADDRESSING_API_KEY`                 | —                  | Required with `ADDRESSING_PROVIDER`, except on `codex`.      |
| `ADDRESSING_BASE_URL`                | —                  | Required with `ADDRESSING_PROVIDER=openai-compatible`.       |
| `ADDRESSING_COMPATIBLE_API`          | `completions`      | `completions` or `responses`, for `openai-compatible`.       |
| `ADDRESSING_REASONING_EFFORT`        | the least it takes | Effort of every verdict.                                     |
| `ADDRESSING_REQUEST_TIMEOUT_SECONDS` | the chat's         | How long one verdict may hang; with its own provider only.   |

A yes-or-no needs no reasoning, and every token of it is latency, so by default the model gets the
least it takes: `none` if it accepts it (asked once at startup), otherwise the lowest effort its
vendor lists, or `low` where nothing is listed. Measured on small OpenAI models, `gpt-5.6-luna` made
the fewest wrong calls of eight, and no false yes.

- **Names** — the bot's Telegram name always counts. Without `ADDRESSING_NAMES`, so does each of its
  handles minus the ending, so `@robinbot` answers to «robin». A name matches at the start of a word,
  in any case, so a grammatical ending still counts.
- **What is asked about** — a message that names Vusan, one from someone it is still answering, and
  any message within five minutes of its reply while that reply is among the last six lines. Nothing
  else in the chat is sent anywhere.
- **What is sent** — that message and up to six lines before it, none older than ten minutes, with
  names. At most twenty a minute per chat; past that, on an error or a verdict slower than five
  seconds, Vusan stays out.
- **What is skipped** — voice messages, stickers, forwards, commands, and messages from bots or
  channels. An edit counts only when it adds the name, within the same five minutes.
- **While Vusan is busy** — a message that called it waits its turn like a mention, and is dropped
  silently if the line is full. If the answer before already covered it, or it was for someone else,
  Vusan may stay silent.
- **In the log** — `ambient verdict: chat=[…] msg=[…] gate=[…] verdict=[…]` per verdict, without the
  text; the message id finds it in the group log.

## Diary

After a day ends, Vusan writes itself a short entry about each group: what people talked about, who
promised what, what was funny, how it felt about it. The last three entries ride along on every turn
in that group, so it picks up yesterday's thread instead of meeting the chat fresh.

| Variable        | Default | Description                                       |
|-----------------|---------|---------------------------------------------------|
| `DIARY_ENABLED` | `true`  | `false` writes nothing and sends no day anywhere. |

- **What is sent** — once per group per day, that day's transcript from the [group log](#group-log),
  up to 12,000 characters, goes to the chat model with nobody having asked. That is why it has a
  switch, unlike a recap, which someone asks for.
- **Which days** — only a finished day with at least fifteen messages from people. Yesterday is
  written within a quarter of an hour of startup; older days are not filled in.
- **How long** — entries are kept for a week. Forgetting the group log drops them too.
- **Voice** — written in the bot's own [personality](#personality), in the chat's main language.

It needs the group log; with `GROUP_LOG_ENABLED=false` it stays off, and startup says so.

## Initiative

In a group Vusan normally speaks only when spoken to. With this on, it also looks over a chat people
are writing in and, now and then, puts an emoji on a message, says a line of its own — a joke on what
was just said, an opinion, a question about someone who has gone quiet — or answers a joke with one of
the chat's stickers. Most looks end in nothing, which is the point: it should feel like a member of
the chat, not a notification.

It is on for every group in `ALLOWED_IDS`. `INITIATIVE_ENABLED=false` is its only setting; how often
it looks and how much it says are fixed.

- **When it looks** — at a chat someone wrote in during the last fifteen minutes, after a random
  pause of fifteen to forty-five minutes, with at least three new messages since its last look or
  line. Not while it is answering someone there, and not within five minutes of its own last line. A
  look turned away is retried five to ten minutes later.
- **What is sent** — up to sixty lines from the chat's last six hours, with names, to the chat model,
  with nobody having asked; that is why it has a switch. With the [diary](#diary) on, its entries go
  along, as do the names of regulars silent for three days and the chat's sticker shortlist.
- **How much it writes** — at most four lines a day per chat, a sticker counting as one, at least
  ninety minutes apart. Reactions have their own ceiling of twenty a day.
- **What it may do** — nothing, one reaction, one short message, or one sticker from the chat's
  catalog, optionally as a reply.
- **Quiet hours** — no looks from one to eight in the morning, on the bot's own clock.
- **Telling it to stop** — it reads the chat first and stays out when someone asked it to be quiet.
  For a hard stop, turn the switch off.
- **After a restart** — the day's counts live in memory, so they start over, and the first look
  waits one pause.
- **Forum groups** — a reply lands in that message's topic; a standalone line goes to General.

What to read in the log:

```
initiative look: chat=[telegram:-100123] fresh=[7] said=[1/4] reacted=[0/20] action=[reply] msg=[4812] chars=[41] text=[…] ms=[2140] why=[…]
initiative look: chat=[telegram:-100123] fresh=[4] said=[1/4] reacted=[0/20] action=[silent] ms=[1630] why=[two people sorting out a trip]
initiative skip: chat=[telegram:-100123] reason=[already talking] fresh=[5]
```

`action` is `silent`, `react`, `say`, `reply` or `sticker`, and `why` is the model's own short reason,
so a day of looks reads without opening the chat. A decision that was not carried out says so in
`result` (`over budget`, `no such target`, `no such sticker`, `emoji not allowed`,
`no readable decision`). `skip` names what kept it from looking: `quiet`, `already talking` or
`budget spent`.

It needs the group log; with `GROUP_LOG_ENABLED=false` it stays off, and startup says so.

## One person alone in a group

Somebody chatting with Vusan alone at four in the morning leaves the rest of the group a hundred
messages to scroll past at breakfast. So once Vusan has answered one person ten times in a row with
nobody else writing in that chat or topic, its tenth reply rounds things off and says it will pick
up later, and that person's next messages go unanswered — no reply, no reaction.

The floor opens again as soon as anybody else writes in the chat, to Vusan or not, or after an hour
without an answer from it. Both numbers are fixed; in a month of a live group, only one exchange in a
hundred ran that long.

Nothing else changes: scheduled tasks still fire, [initiative](#initiative) keeps its own budget,
commands and the buttons of a question it asked still work. Private chats are never limited, and
neither is the owner.

## Leaving someone alone

Anyone can tell Vusan not to write to them — «leave me alone», «stop writing to me» — and it keeps to
that in the chat it was said in: until the end of the day and for at least twelve hours, or for as
long as they asked, up to a week. Meanwhile nothing of its own reaches them: no reactions to their
messages, no unprompted replies, no follow-ups on their lines, and planned check-ins are dropped. In
a private chat, the check-ins are what stops.

A message they send it themselves — a mention, a reply, its name — is answered as usual, and «you can
write to me again» lifts the request early. It survives a restart, and there is nothing to set.

Told to be quiet, or insulted, Vusan may also answer with nothing at all. That is a deliberate choice
it records with its reason — the log says `staying silent … why=[…]` — and the next message from that
person is read knowing it was asked to be quiet.

## Rights in a group

Before each turn Vusan reads what the group lets it post and adapts: a chat that forbids photos is
never offered image generation, one that forbids polls gets no poll tools, and slow mode makes the
answer one message instead of several Telegram would drop. Nothing to set, but three group settings
change what it can do:

- **Privacy mode decides what it sees** — a new bot has it on, and then Telegram hands it only
  mentions, replies to it and commands. The [group log](#group-log), recaps,
  [answering without a mention](#answering-without-a-mention), the [diary](#diary) and
  [initiative](#initiative) need the whole chat: turn privacy mode off with `/setprivacy` in
  [@BotFather](https://t.me/BotFather) and add the bot to the group again, or make it an admin.
- **A plain member follows the group's defaults** — turning off photos, stickers, polls, voice
  messages or files for everyone turns them off for Vusan too, and the matching tools disappear in
  that chat. An admin is exempt, slow mode included.
- **Losing the right to write pauses that chat's tasks** — removing Vusan from a group, or taking
  away its send permission, pauses every task scheduled there. They stay in `/tasks`; resume them
  after adding it back.

A permission lookup that fails counts as "unrestricted", so a Telegram hiccup never strips tools.

## Command menu

Nothing to set: the bot publishes its command menu on every start, in each language it speaks. It is
the same list BotFather's `/setcommands` edits, so a change made there lasts only until the next
restart.

In groups, `/tasks` and `/clear` are ephemeral: the command and its answer are seen only by the
person who used it. `/stop` stays visible on purpose, so the chat sees why the bot went quiet.
Private chats get the same commands as plain ones.

## How many requests at once

| Variable               | Default | Description                               |
|------------------------|---------|-------------------------------------------|
| `MAX_CONCURRENT_TURNS` | `8`     | Requests Vusan works on at the same time. |

One person's messages in a chat are answered one at a time, in order, so a follow-up sent mid-answer
starts with that answer already known. Up to three wait like that; the next is told to hold on.

`MAX_CONCURRENT_TURNS` is how many *different* people Vusan serves at once, waiting follow-ups not
counted. Each request is a model call plus whatever tools it runs, so match it to what your provider
accepts at once, not to what the machine could hold.

Past the limit, people wait their turn with the usual typing indicator. Only when the queue is
several times the limit does Vusan say it is overloaded. Scheduled tasks always wait instead: nobody
is watching them arrive, and refusing one would skip the run.

## Agent loop

One turn may call tools again and again — search, read a page, search again — before it answers. A
ceiling keeps a model stuck on a broken tool from burning tokens forever.

| Variable                | Default | Description                    |
|-------------------------|---------|--------------------------------|
| `AGENT_MAX_MODEL_CALLS` | `100`   | Model calls one turn may make. |

Each round of tool calls is one model call, so the default allows nearly a hundred rounds: enough to
build, run and fix a project in the sandbox, or to research a question across many sources. Hitting
the ceiling is not an error — the last call is kept for a wrap-up from what was gathered, saying what
is unfinished. The minimum is three.

What really bounds a long turn's cost is the budget for tool results, which the agent checks with
`checkContextBudget`. Lower this only to keep a cheap model from wandering.

## Storage and binaries

| Variable              | Default            | Description                                       |
|-----------------------|--------------------|---------------------------------------------------|
| `DB_FILE`             | `data/db/vusan.db` | SQLite path; parent directories are created.      |
| `YT_DLP_COOKIES_FILE` | —                  | Cookies for YouTube videos that ask for a login.  |

In Docker the `data/` directory sits beside the compose file, and `VUSAN_HOST_DIR` in `.env` moves it.
It is a plain directory rather than a named volume so everything in it is easy to reach: the database
is a file you copy for a backup, and `SELF_IMAGE_FILE`, `APPEARANCE_FILE` and `YT_DLP_COOKIES_FILE`
are files you put there. Create it before the first start — `mkdir -p data` — because a directory
Docker creates is owned by `root`, and the bot runs as uid 1000.

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

Logback reads both from the process environment at startup, not from `.env`. In the Compose
deployment that makes no difference, since `env_file` turns `.env` into real environment variables.
A local run needs them where it gets its environment — the IDE run configuration or the command line:

```bash
PROMPT_DUMP_LEVEL=DEBUG java -jar build/libs/vusan-*-all.jar
```

`PROMPT_DUMP_LEVEL=DEBUG` is for debugging, not for leaving on: every model call prints the system
prompt, recap, history, the current turn and every tool call with its result — tens of thousands of
characters of raw chat per turn, all in the container log. It is independent of `LOG_LEVEL` either
way.

An unrecognized level — a typo, an empty value — is read as `DEBUG`, the opposite of what a stray
value usually means.

## Health check

The image has a Docker `HEALTHCHECK`, so `docker compose ps` shows whether the bot is actually
polling, not just whether its process is alive:

```console
$ docker compose ps
NAME              STATUS
vusan-container   Up 6 hours (healthy)
```

Nothing to set. Vusan refreshes `/tmp/health` every 30 seconds while its `getUpdates` loop keeps
turning, and the check fails once the file is 90 seconds old; the first `healthy` takes about two
minutes. It follows poll cycles, not messages, so a bot nobody writes to stays healthy, while one whose
polling stalled — alive, `Up`, silent — goes unhealthy and logs a single `ERROR`.

- **A short Telegram outage** — stays healthy. Polling retries with a backoff that tops out around a
  minute and resumes on its own; a restart would not help.
- **A long one** — past about 15 minutes of failures, the backoff settles at 15 minutes between
  tries and the container goes unhealthy. Then a restart helps: it resets the backoff and polling
  resumes at once. An outage that hangs connections trips the check sooner, since each try waits out
  a 100-second read timeout.
- **At startup** — an unreachable Telegram holds the start, retrying, instead of ending it; the
  container reads unhealthy until it gets through.

To act on it automatically, pair it with a container that restarts unhealthy services, or point an
uptime monitor at Docker's health state. To read it directly:

```bash
docker inspect --format '{{.State.Health.Status}}' vusan-container
```
