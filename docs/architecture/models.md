# Model providers

How a configured provider becomes the client every call goes through, and what the ChatGPT subscription adds.
Part of the [architecture index](../architecture.md).

- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns a `LlmProviderConfig` into an `LlmRuntime`:
  the client, the model as the bot needs to know it (`llm/LlmModel`: wire id, window, output ceiling, whether it sees,
  whether it takes an effort and which ones its vendor lists), and the options a turn and a history recap send. Each
  provider is one file, `config/<Vendor>Provider.kt`, holding its runtime builder and its startup check; the two
  dispatchers (`resolveLlmRuntime`, `ModelPreflight.preflighted`) and the name `AppConfig` reads are all that names
  them, and a new provider is one more such file plus its client under `llm/<vendor>/`. There is no model catalog: a
  deployment names a model, and the provider's check asks the vendor about it at startup — OpenAI confirms the id,
  Anthropic also states the window, the ceiling and what the model takes (adaptive thinking, which efforts, images),
  and those replace the runtime's assumptions; Codex reads the account's own catalog. What the vendor said goes back
  into the config as facts (`seesImages`, `efforts`) that the roles read without knowing the provider: vision stops the
  startup on a model its vendor calls blind, and addressing sends the least effort listed, asking the model once where
  nothing is. `openai` always speaks the Responses API, the
  one where tools work alongside reasoning, with the conversation's own `prompt_cache_key` — reads match the prefixes
  most recently written under a key, and one key for the whole deployment would let busy chats evict each other; recaps
  keep a single shared key, their tool-free prefix being identical everywhere — and, from GPT-5.6 on, the cache options
  stated outright (`llm/openai/OpenAiPromptCaching`): the platform's implicit mode, under which each iteration of the loop
  reads the tool results the one before it added, plus an explicit breakpoint on the stable system block, which the next
  turn still reads however its history was replayed; a prompt that never repeats, the recap, asks for no caching at all.
  Reasoning comes back encrypted (`store: false`) and is replayed verbatim through the tool
  loop. `anthropic` asks for `thinking: adaptive` with `block_binding: drop_block` under its beta header — a thinking
  block is bound to the tools and messages before it, and `loadTools` widens the tool list mid-turn, so an
  account the API holds to that check gets the stale block dropped rather than a 400 — sends the configured
  `output_config.effort`, the model's whole output ceiling as `max_tokens`, and two cache breakpoints: the request-level
  one the API places on the last block, which every iteration of the loop reads back, and an hour-long one on the
  system block, which the next turn — whose history is replayed from storage in another shape — still reads, and which
  outlives the quiet stretches between a chat's messages. Every call is streamed and folded back into one message
  (`llm/anthropic/AnthropicStream`), as the vendor's SDKs do at a ceiling this size, so a stall trips the socket
  timeout where a connection idling through a long think would be dropped at the API's edge. A Claude model from
  before 4.6, which takes neither adaptive thinking nor an effort, stops startup. `openai-compatible` speaks
  either OpenAI API under `LLM_BASE_URL` (`LLM_COMPATIBLE_API`), sees images only when the server's model list says
  the model takes them (with its window and its efforts, DeepSeek's states all three), disables parallel tool calls
  because third-party models garble the siblings, and gets none of the OpenAI-only fields unless the base URL is the
  official API. A server there that thinks aloud in `reasoning_content` (DeepSeek) refuses a tool call of the turn
  without it, so a call the provider before a fallback wrote carries an empty one. A vision or an addressing model is
  one more `LlmProviderConfig`, read from its own prefix (`VISION_`, `ADDRESSING_`) and resolved the same way; by default
  it runs on the chat provider with the chat key.
- **ChatGPT subscription (`codex`)** — the same OpenAI client pointed at the Codex backend's Responses API, with no API
  key. `llm/codex/CodexAuth.CodexAuthStore` owns the credentials `codex login` writes to `~/.codex/auth.json` (or
  `$CODEX_HOME`). `AppConfig` resolves that path into the Codex provider config, and the store rereads the file per
  request so an external login, logout, sandbox switch, or CLI refresh takes effect without a restart. It refreshes
  OAuth sessions a few minutes before expiry and replaces the file atomically through an owner-only temporary file,
  preserving CLI-owned fields and refusing to overwrite a version that changed during refresh. A mutex keeps concurrent
  bot turns from spending the same single-use refresh token. A Ktor plugin (`llm/codex/CodexHttpClient.codexRequestPlugin`)
  stamps the current bearer token, the account header and the headers Cloudflare checks on every request, and reads the
  subscription's usage windows off every response. Signing in, out, and device-code stay the CLI's job; this bridge
  requires file-backed credentials and cannot read the OS keyring. `CODEX_SERVICE_TIER` rides along as the Responses
  `service_tier` field and in the `x-codex-routing-hint` header the CLI sends beside it, both fixed for the process at
  startup, on the chat model alone: a vision or addressing model on the plan runs at the standard tier.

  The backend accepts streaming requests only (`stream=false` and `store=true` are both rejected), and its final
  `response.completed` event carries an empty `output`. So the client streams the call and folds the
  `response.output_item.done` items back into the response object the non-streaming API would have returned
  (`llm/openai/ResponsesStream`), preserving a non-empty completed output if the backend supplies one, folding an
  incomplete one the same way so a reply cut at the output ceiling reads as one, and rejecting failed or canceled
  terminal events. Codex requests use `store=false` and explicitly request encrypted
  reasoning content, so reasoning items can be echoed through a stateless multi-step tool loop. Every completed call is
  logged with its cache share and where its prefix drifted, and counted toward the subscription's next step.

  Model discovery runs at startup through `llm/codex/CodexCatalog`, and `config/CodexProvider` holds the configured model
  against it: the account's own catalog decides which ids and context window are valid, since Codex and the Platform API expose different model sets. Input modalities decide whether the
  model may be reused for vision, advertised reasoning efforts validate `LLM_REASONING_EFFORT`, and advertised service
  tiers validate `CODEX_SERVICE_TIER`; older catalog entries without those fields keep the compatibility defaults. A
  catalog that cannot be read is a warning, not a failure — the endpoint is undocumented, so a shape change there must
  not take a working bot down — but a model the account plainly cannot run stops startup with the list of ones it can.
  A `VISION_MODEL` still selects a separate vision model, on the subscription or, with `VISION_PROVIDER`, elsewhere.

  The same session also covers image generation when `IMAGE_PROVIDER=codex`. Pictures have a provider of their own
  (`config/ImageProviderConfig`: `openai` with a key, or `codex` on the subscription), and the subscription is signed in
  once for every use it has — `AppConfig.codexSignIn` names the file whether the plan is the chat, a role or the
  pictures alone, and a plan that only draws proves its sign-in at startup in place of a model preflight.
  `OpenAiImageClient` takes an `ImageAuth` telling it which: the generation call differs only
  by URL and credentials, but the edit call genuinely forks — the Platform endpoint takes a multipart upload while the
  Codex one takes JSON with the source inlined as a data URL and infers the output size from it. The fork extends to
  what each request may carry: only the Platform one sends the `low` moderation setting, jpeg output, and high
  `input_fidelity` on the models that accept it, because the Codex backend's request has none of those fields. Both
  routes answer a refusal the same way: an error body naming OpenAI's content filter becomes an
  `ImageModerationBlocked`, so the tool tells the model to rewrite the description or give up instead of handing it a
  failed HTTP call to interpret. An edit takes every image the turn carries, which is what makes an album one picture;
  the first source is the one both routes hold closest to the original, so a picture of the bot itself puts its
  reference photo there.
