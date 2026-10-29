# Model providers

How a configured provider becomes the client every call goes through, and what the ChatGPT subscription adds.
Part of the [architecture index](../architecture.md).

- **LLM provider resolution** — `config/llm/LlmRuntime.resolveLlmRuntime` turns a `LlmProviderConfig` into an `LlmRuntime`:
  the client, the model as the bot needs to know it (`llm/LlmModel`: wire id, window, output ceiling, whether it sees,
  whether it takes an effort and which ones its vendor lists), and the options a turn and a history recap send. Each
  provider is one file, `config/llm/<Vendor>Provider.kt`, holding its runtime builder and its startup check; the two
  dispatchers (`resolveLlmRuntime`, `ModelPreflight.preflighted`) and the name `AppConfig` reads are all that names
  them, and a new provider is one more such file plus its client under `llm/<vendor>/` — or none, where the vendor
  speaks protocols the clients already do, as OpenRouter speaks OpenAI's Responses API and Anthropic's Messages API.
  There is no model catalog: a deployment names a model, and the provider's check asks the vendor about it at startup —
  OpenAI confirms the id, Anthropic also states the window, the ceiling and what the model takes (adaptive thinking,
  which efforts, images), and those replace the runtime's assumptions; OpenRouter's list of the models a key may use
  states the window, the ceiling, the eyes, whether the model calls tools and its efforts; Codex reads the account's own
  catalog. What the vendor said goes back
  into the config as facts (`seesImages`, `efforts`) that the roles read without knowing the provider: vision stops the
  startup on a model its vendor calls blind, and addressing sends the least effort listed, asking the model once where
  nothing is. `openai` always speaks the Responses API, the
  one where tools work alongside reasoning, with the conversation's own `prompt_cache_key` — reads match the prefixes
  most recently written under a key, and one key for the whole deployment would let busy chats evict each other; recaps
  keep a single shared key, their tool-free prefix being identical everywhere — and, from GPT-5.6 on, the cache options
  stated outright (`llm/openai/OpenAiPromptCaching`): the platform's implicit mode, under which each iteration of the loop
  reads the tool results the one before it added, plus an explicit twelve-hour breakpoint on the stable system block,
  which the next turn still reads however its history was replayed and hours later, and one on the last user message
  before the end of each stable part of the replayed history (`ChatRequest.stablePrefixes`), which is where the next
  turn's request stops repeating this one's; a prompt that never repeats, the recap, asks for no caching at all.
  A tool group loaded mid-turn goes as an `additional_tools` item where it was offered, so the tool list stays the cached
  one.
  A preamble the model wrote before its tool calls goes back with its `phase`, which the platform asks for so as not to
  read it back as the answer.
  Reasoning comes back encrypted (`store: false`) and is replayed verbatim through the tool
  loop. `anthropic` asks for `thinking: adaptive` with `block_binding: drop_block` under its beta header — a thinking
  block is bound to the tools and messages before it, and on a Claude model from before tool offers (Opus and Sonnet
  4.6, Opus 4.7, Sonnet 5) `loadTools` widens the tool list mid-turn, so an account the API holds to that check gets
  the stale block dropped rather than a 400; every later model gets the groups' tools declared `defer_loading` and a
  loaded group offered by a `tool_addition` reference, and the list never changes — sends the configured
  `output_config.effort`, the model's whole output ceiling as `max_tokens`, and up to four cache breakpoints: the
  request-level one the API places on the last block, which every iteration of the loop reads back, and hour-long ones
  on the system block and on the end of each stable part of the replayed history, which the next turn — whose current
  message is replayed from storage in a shorter shape — still reads, and which outlive the quiet stretches between a
  chat's messages. Every call is streamed and folded back into one message
  (`llm/anthropic/AnthropicStream`), as the vendor's SDKs do at a ceiling this size, so a stall trips the socket
  timeout where a connection idling through a long think would be dropped at the API's edge. A Claude model from
  before 4.6, which takes neither adaptive thinking nor an effort, stops startup. `openai-compatible` speaks
  either OpenAI API under `LLM_BASE_URL` (`LLM_COMPATIBLE_API`), sees images only when the server's model list says
  the model takes them (with its window and its efforts, where it states them), leaves parallel tool calls
  to the server's default, sends the output ceiling as the original `max_tokens` on Chat Completions, and gets none of
  the OpenAI-only fields unless the base URL is the official API. A server there that thinks aloud in
  `reasoning_content` gets it back with the turn's calls, and a call the provider before a fallback wrote carries an
  empty one. What a server puts on a call beside
  the function (Gemini's `extra_content`, which carries the thought signature Google wants back within the turn) is kept
  as the call's `EndpointFields` and replayed to that endpoint alone. A vision or an addressing model is
  one more `LlmProviderConfig`, read from its own prefix (`VISION_`, `ADDRESSING_`) and resolved the same way; by default
  it runs on the chat provider with the chat key.
- **OpenRouter (`openrouter`)** — `config/llm/OpenRouterProvider` picks a client by the model's vendor prefix: a Claude
  model (`anthropic/…`) goes through the Anthropic client to OpenRouter's Messages API at `https://openrouter.ai/api`,
  everything Anthropic gets included (adaptive thinking with signed blocks replayed, effort, the two breakpoints);
  any other model streams OpenRouter's Responses API through the OpenAI client, stateless with the encrypted reasoning
  replayed, and on GPT-5.6 and later a breakpoint on the developer block as every upstream takes it: no mode named,
  since OpenRouter refuses `implicit` as a value and serves the unnamed one as it, and no twelve-hour lifetime, which
  Azure, one of the upstreams it routes OpenAI models to, refuses as an unknown parameter. The Responses call streams because
  OpenRouter answers 200 once an upstream takes the request, so a later failure is an error event rather than a
  body holding nothing but the error. Both clients send the conversation's cache key as `session_id` too, which keeps
  a conversation on the upstream holding its cached prefix. A Grok model (`x-ai/…`) continues the conversation's last
  request, as Codex does, since xAI keeps no breakpoints there ([context budget](context.md)).
- **DeepSeek (`deepseek`)** — `config/llm/DeepSeekProvider` runs the OpenAI client's Chat Completions at
  `https://api.deepseek.com`, the base URL its docs give: `reasoning_content` echoed with the turn's calls, the output
  ceiling as the `max_tokens` DeepSeek reads (it ignores `max_completion_tokens`), and `none` turning thinking off. Both
  models keep an entry per request, so a turn continues the conversation's last request ([context budget](context.md)).
  The startup check reads the window, the ceiling, the eyes and the levels from `GET /models/{id}`; a level the list does
  not name stops startup, since DeepSeek would serve `medium` as `high`. A `finish_reason` of
  `insufficient_system_resource` is raised as an overload, so the call is repeated or handed to the fallback.
- **xAI (`xai`)** — `config/llm/XaiProvider` points the OpenAI client at `https://api.x.ai/v1/responses` with a key. What
  xAI reads differs from OpenAI and decides what goes along: the conversation's `prompt_cache_key`, which xAI routes on
  as on `x-grok-conv-id` to the server holding the prefix; `store: false` and the encrypted reasoning, replayed through
  the tool loop, since a request missing the reasoning before it is what xAI names the first cause of cache misses; and
  none of OpenAI's cache options, since xAI caches on its own and charges nothing to write. It keeps an entry per
  request, so a turn continues the conversation's last request ([context budget](context.md)). The startup check reads
  the window and the efforts (`none` among them where thinking can be turned off) from `/v1/models/{id}`, which answers
  for an alias too, and whether the model sees from `/v1/language-models/{id}`.
- **Google (`google`)** — `config/llm/GoogleProvider` runs `llm/google/GoogleClient` on the Gemini API's Interactions
  endpoint, the vendor's default since June 2026, stateless (`store: false`): the conversation goes out whole as steps,
  system messages lifted into `system_instruction`. A `thought` step carries the signature the turn's next request is
  checked against, so it is kept as reasoning tagged with the endpoint and replayed verbatim to it alone. `none` goes out
  as `thinking_level: minimal`. The startup check reads the window, the output ceiling and whether the model thinks from
  `GET /v1beta/models/{id}`; the list says nothing of images, which every Gemini and Gemma 4 model takes. The cache is
  implicit, with no breakpoint to place.
- **ChatGPT subscription (`codex`)** — the same OpenAI client pointed at the Codex backend's Responses API, with no API
  key; like the platform, it takes a tool group loaded mid-turn as an `additional_tools` item, so the tool list stays
  the cached one. It takes no cache breakpoints, so a turn continues the conversation's last request rather than
  replaying the stored history (`cachesOnlyRepeatedPrefix`, [context budget](context.md)). `llm/codex/CodexAuth.CodexAuthStore` owns the credentials `codex login` writes to `~/.codex/auth.json` (or
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

  Model discovery runs at startup through `llm/codex/CodexCatalog`, and `config/llm/CodexProvider` holds the configured model
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

  The client implements `tools/imagegen/ImageClient`: `ImageGenTools` hands it an `ImageFraming` that it turns into a
  size the configured model takes, and reads off it how many sources one edit takes, so an album beyond it is cut down
  with the result saying so. An edit left to `auto` is framed like its first source, so when
  that source is the bot's likeness the tool asks for the framing nearest to the picture being edited instead.

  Videos are xAI's alone, on when `XAI_API_KEY` is set (`config/XaiVideoConfig`), rendered by
  `tools/videogen/XaiVideoClient` behind the `videos` group. `VideoGenTools` speaks the model's language: it checks
  that the prompt names only the pictures it was given — references as `<IMAGE_0>` and up, the bot as `<SELF>` — loads
  them within one upload's size, and delivers the result. The client turns that into xAI's request: it numbers the
  pictures the way xAI counts them, puts the bot's photo after the references, gives the bot its configured voice,
  and drops to `720p` where a request with references cannot render `1080p`. A job is asynchronous, so the client
  polls it inside the turn; a look or a download that fails in passing is made again rather than throwing away a
  render that is already paid for, and a moderation refusal becomes `VideoModerationBlocked`.
