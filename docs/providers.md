# Model providers

What each `LLM_PROVIDER` needs beyond a key and a model: where the key comes from, what the startup
check reads, and a ChatGPT plan's sign-in. The variables every provider shares, the
[table of providers](configuration.md#llm-provider) and the
[second provider behind the first](configuration.md#a-second-provider-behind-the-first) are in
[Configuration](configuration.md).

## OpenRouter

`LLM_PROVIDER=openrouter` reaches many vendors' models with one [OpenRouter](https://openrouter.ai)
key. Name the model as OpenRouter lists it:

```dotenv
LLM_PROVIDER=openrouter
LLM_MODEL=google/gemini-3.8-flash
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
  resets, answering on the [fallback](configuration.md#a-second-provider-behind-the-first) if there is one.

## Google

`LLM_PROVIDER=google` runs Vusan on Google's Gemini API with a key from
[AI Studio](https://aistudio.google.com/api-keys). Name the model as Google lists it:

```dotenv
LLM_PROVIDER=google
LLM_MODEL=gemini-3.8-flash
LLM_API_KEY=AIzaSy-qwerty
```

- **Startup check** — the model has to be one Google serves the key, and one that writes text. Its
  window comes from Google's list, so `LLM_CONTEXT_WINDOW_TOKENS` is only an override. The list says
  nothing about images; every Gemini and Gemma 4 model takes them, so the chat model is taken to see.
- **`LLM_REASONING_EFFORT`** — `none`, `low`, `medium` or `high`. A Gemini 3 model cannot stop thinking,
  so `none` asks for the least it does. Which levels a model takes is the model's, and Google lists
  none of them: Gemini 3.5 Flash-Lite takes all four, Gemini 3.8 Flash no `none`, Gemma 4 only `none`
  and `high`. A level the model refuses fails the first turn.
- **Limits** — Vusan takes a spent rate or daily limit as the provider being out, answering on the
  [fallback](configuration.md#a-second-provider-behind-the-first) if there is one.

## DeepSeek

`LLM_PROVIDER=deepseek` runs Vusan on [DeepSeek's API](https://api-docs.deepseek.com) with a key from
its [platform](https://platform.deepseek.com/api_keys):

```dotenv
LLM_PROVIDER=deepseek
LLM_MODEL=deepseek-flash
LLM_API_KEY=sk-qwerty
```

- **Startup check** — the model has to be one DeepSeek serves. Its list gives the window, whether the
  model sees (`deepseek-flash` does, `deepseek-v4-pro` does not) and its levels, so nothing needs
  setting by hand.
- **`LLM_REASONING_EFFORT`** — `none` turns thinking off; `low`, `high` and `max` are the levels
  DeepSeek lists. Anything else stops startup: DeepSeek would quietly serve `medium` as `high`.

## xAI

`LLM_PROVIDER=xai` runs Vusan on [xAI's API](https://docs.x.ai) with a key from its
[console](https://console.x.ai):

```dotenv
LLM_PROVIDER=xai
LLM_MODEL=grok-4.7
LLM_API_KEY=xai-qwerty
```

- **Startup check** — the model has to be one xAI serves, under its own id or an alias. Its lists give
  the window, whether the model sees and which efforts it takes; `none` only where the model lists it,
  as `grok-4.3` does and `grok-4.7` does not.
- **Credits** — xAI answers the startup check even on an account out of credits; the first turn then
  fails, which Vusan takes as the provider being out, answering on the
  [fallback](configuration.md#a-second-provider-behind-the-first) if there is one.
- **Video** — [video generation](configuration.md#video-generation) has a key setting of its own, `XAI_API_KEY`; the
  same key may serve both.

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
  [fallback](configuration.md#a-second-provider-behind-the-first), an expired sign-in keeps Codex out for half an
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
- **Pictures** — `IMAGE_PROVIDER=codex` draws [images](configuration.md#image-generation) on the same account,
  whether or not the chat runs on `codex`.
- **Web search** — `answerFromWeb` searches through the plan with no search key, and each search
  spends a good deal of it; `CODEX_WEB_SEARCH_ENABLED=false` turns it off.

### Limits

- **Usage windows** — the plan is metered, so a heavy day ends in a "usage limit reached" reply
  with the time left, unless a [fallback](configuration.md#a-second-provider-behind-the-first) takes over.
- **Not a public API** — this route uses the endpoint OpenAI ships for its own Codex clients, so an
  OpenAI-side change can break it. `LLM_PROVIDER=openai` with a key is the supported alternative.
- **How much the plan holds** — a `codex limits:` log line follows every move of a window's share,
  with the calls, pictures and tokens since the last one.

## OpenAI-compatible servers

Any other server uses `LLM_PROVIDER=openai-compatible`, with `LLM_BASE_URL` and `LLM_COMPATIBLE_API`
([LLM provider](configuration.md#llm-provider)). A local one such as Ollama:

```dotenv
# Ollama — the key is not checked, but must not be empty
LLM_PROVIDER=openai-compatible
LLM_API_KEY=ollama
LLM_BASE_URL=http://localhost:11434
LLM_COMPATIBLE_API=responses
LLM_MODEL=gemma4:latest
LLM_CONTEXT_WINDOW_TOKENS=131072
```

Vusan asks Ollama's own API whether the model sees, so a model that takes images needs no
`VISION_MODEL`. The window it runs with is stated only by a Modelfile's `num_ctx`, or by `ollama ps`
while the model is loaded, so set `LLM_CONTEXT_WINDOW_TOKENS` to Ollama's `OLLAMA_CONTEXT_LENGTH`
unless the Modelfile sets one.
