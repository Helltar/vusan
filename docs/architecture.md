# Architecture

This document is the orientation map for the codebase: the layers, how a message flows through them, and the background
flows that run alongside. The bot is Kotlin under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/); the code-execution sandbox is a separate
Deno service under [`sandbox/`](../sandbox/), see [Code execution service](#code-execution-service).

## Layers

```
Telegram ──► telegram/ ──► agent/ ──► tools/ ──► external services
                │            │           │
                │            │           └─ writes outputs into ─► outbox/
                │            ├─ reads/stores history via ─► agent/history/ ─► infra/
                │            └─ reads/stores memory via ──► agent/memory/ ──► infra/
                └─ delivers outbox back to Telegram
```

- **`telegram/`** — Telegram I/O. Receives updates (text, voice, audio, sticker, photo, video, video note, GIF,
  document, album), filters by
  allowlist, normalizes input, and delivers agent results back — including HTML-formatting, opt-in rich-message,
  reply-anchor, media/document, media-group, and private-message fallbacks.
- **`agent/`** — agent orchestration on top of Koog. `AgentRunner` serializes per-user turns;
  `AgentFactory` builds the `AIAgent` (system prompt + history + memory + tools). `agent/history/`
  summarizes and persists chat turns; `agent/memory/` stores durable user/group memory that survives a history clear and
  is injected as `<user_memory>`/`<group_memory>`.
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …).
  `ToolRegistryFactory` owns clients and builds a per-request registry from required tools plus optional tools whose
  env/config is present. See the Features section of the [README](../README.md). `tools/images/` is not a tool surface
  but the pipeline every image search shares: download a provider's candidates, drop what Telegram would refuse, and
  queue the survivors.
- **`outbox/`** — the output model. `BotOutput` is the immutable sealed set of things the bot can send (text, rich
  message, photo, voice, audio, video, document, poll, reaction, …); `BotOutbox` is the per-request queue tools write
  into, holding each `BotOutput` as an `OutboxItem` that captures its private-routing decision.
- **`request/`** — the request-scoped input model shared across layers: `RequestContext`
  (chat/user/message ids and sender info tools see) and `AttachedFile` (photo, video, or document, from the current
  message or a replied-to message, that vision (`describeImage`, `describeVideo`) and code execution (`codeExecution`)
  can lazily download). Its `kind` (`IMAGE`/`VIDEO`/`OTHER`) decides which of those tools accepts it; a video also
  carries its duration and a loader for Telegram's own thumbnail.
- **`tasks/`** — scheduled-task subsystem: storage, recurrence math, and the background
  `TaskScheduler`.
- **`infra/`** — cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor
  `Http` client.
- **`config/`** — `.env` parsing (`AppConfig`) and LLM provider/model resolution (`LlmRuntime`).
  `VisionRuntime` resolves separately which model looks at images: the `OPENAI_VISION_*` model when configured, the chat
  model when it accepts images, and nothing at all otherwise — which leaves the vision tools unregistered.
- **`stt/`** — OpenAI speech-to-text client (`OpenAiWhisperClient`, default model
  `gpt-4o-transcribe`); used for voice transcription and for the sound of a video the vision tool watches, opt-in via
  `OPENAI_STT_API_KEY`.
- **`i18n/`** — user-facing message strings, one `Messages` implementation per `Language`.
  `Language.fromCode` picks the language from the sender's Telegram language code (default English); add a language by
  adding an enum entry and a `Messages` impl.
- **`common/`** — tiny shared utilities: prompt/text helpers (`Strings.kt`) and cancellation rethrow
  (`Cancellation.kt`).

## Request lifecycle

A normal user message travels:

1. **Receive** — `TelegramBotRunner` long-polls via `TelegramBotsLongPollingApplication`, funnels updates into a
   channel, and dispatches each message by content (text/command, rich message, sticker, voice, audio, photo, video,
   video note, GIF, document). Album (media
   group) parts arrive as separate updates sharing a
   `media_group_id`; the runner buffers them until the update stream goes quiet (`ALBUM_QUIET_PERIOD`, or the ten-item
   album cap) and handles the batch as one gallery message: the caption may sit on any album part, only the first
   inspectable item becomes the
   `AttachedFile`, and the agent is told how many items it cannot see.
2. **Filter** — `MessageFilter.shouldHandle` drops messages the bot shouldn't answer (in groups:
   only replies, mentions, or targeted commands); `TelegramBotRunner` then checks the allowlist (`ALLOWED_IDS`) and
   rejects unknown chats/users.
3. **Normalize** — text is sanitized (`MessageSanitizer`); voice/audio is transcribed (`VoiceTranscriber` → `stt/`);
   stickers become a metadata prompt; a rich message — which never carries `text` — is flattened back into rich
   markdown (`telegram/RichMessageText.kt`), both as its own input and when one is quoted in a reply, capped at
   `MAX_RICH_MESSAGE_CHARS` because Telegram allows it 32768 characters against plain text's 4096;
   replied-message context is wrapped in `<reply_context>`/`<user_message>`; current
   or replied photo, video, and document input becomes `AttachedFile`. `TelegramBotRunner.dispatchToAgent` assembles the agent
   input and the shorter history input.
4. **Run** — `AgentRunner.handle` takes the per-user lock (or returns "busy"), then
   `AgentFactory.build` constructs a Koog `AIAgent` with the system prompt, current time, message context, summarized
   history (`agent/history/ChatHistory`), durable memory (`agent/memory/MemoryRepository` — the sender's user memory
   always, plus the group's memory in non-private chats), and the per-request tool registry
   (`ToolRegistryFactory.buildRegistry`).
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`; tool calls/results are
   recorded for history. The custom `single_run` strategy (`AgentFactory`)
   guards against flaky models in two ways:
    - a tool call missing its declared required parameters (flaky models emit empty-arg siblings when they try to call
      tools in parallel) is short-circuited into a `ValidationError` result instead of being executed, so the run stays
      clean and the follow-up request stays well-formed;
    - a turn that ends having delivered nothing — no `sendMessage`, media, or reaction, and empty assistant text (flaky
      providers return an empty completion after a batch of tool results) — gets one nudge to actually deliver before
      finishing, so a full turn of research does not collapse into silence.
6. **Collect** — `AgentRunner` returns an `AgentResult` (outputs + optional comment + history turns to persist).
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat, or to the user's private chat when a tool
   requested it, anchoring replies to the original message.
    - **Chat action** — a live indicator runs through the whole turn (`TelegramBotRunner.withLiveChatAction`): it starts
      as `typing`, then follows the executing tool, e.g. `upload_photo` while an image generates. Koog's
      `onToolCallStarting` resolves the running tool to a neutral `ToolActivity` (`agent/ToolActivity.kt`, keyed by
      `@Tool` method references); the Telegram layer translates that to a chat action (`chatActionFor`). During delivery
      each item is preceded by the action matching its own content (`botActionFor`).
    - **HTML and its fallbacks** — text and captions go out with Telegram's `HTML` parse mode; `agent/SystemPrompt.kt`
      instructs the agent to use only the supported tags and escape `<`/`>`/`&`. Models still slip in `<br>`, so
      `TelegramOutputSender` turns `<br>`-style tags into real newlines instead of letting Telegram reject the whole
      message. Rejected reply text is re-sent as a `message.html` document (`telegram/HtmlReplyDocument.kt` — a
      standalone, responsive, light/dark page with a no-script CSP) so the formatting still arrives; a rejected caption
      resends the media captionless and delivers the caption the same way; bot notices fall back to plain text.
    - **Rich messages** — opt-in Bot API 10.1 (`BotOutput.RichMessage`, github-flavored markdown) via the
      `sendRichMessage` tool, resent as a `message.md` document if rejected. Opt-in because some third-party clients
      (e.g. Telegram X) render rich messages as unsupported.
    - **Gone targets and blocked DMs** — a reply whose target no longer exists is retried without the anchor
      (`DeliveryTarget.withoutReply`); a private chat the bot cannot write to produces a notice in the group instead.
    - **Rate limits** — consecutive sends are paced (`INTER_MESSAGE_DELAY`) to stay under Telegram's per-chat limit.
      Upstream, `BotOutbox` coalesces consecutive `sendMessage` text into the trailing bubble while it fits
      (`MAX_TEXT_MESSAGE_CHARS`), so a model that splits one answer into many messages produces few real sends, and caps
      the resulting bubbles (`MAX_TEXT_MESSAGES`) so a looping model cannot flood the chat.
    - **Sender split** — `TelegramOutputSender.kt` maps each `BotOutput` kind to a Bot API call and picks the fallback
      wrapping it, `TelegramSendFallbacks.kt` holds the output-kind-agnostic rejection handling (plain-text retry,
      media-to-document, text-as-document), `TelegramRequests.kt` the raw request builders. Sandbox image previews opt
      out of photo-to-document fallback because their uncompressed document copy is already queued.
8. **Persist** — produced history turns are appended via `ChatHistoryRepository`.

## Background and side flows

- **Task scheduler** — `TaskScheduler.launchIn` polls the task store every 30 seconds. Due tasks run through
  `AgentRunner.handleScheduled` (waits for the user lock instead of bailing), are delivered with
  `TelegramDelivery.sendScheduled`, and then append produced history turns. Tasks overdue beyond
  `TASK_MAX_LATENESS_MINUTES` (e.g. after downtime) get a "missed" notice and are advanced/disabled rather than fired. A
  task whose run fails is still advanced/disabled (logged, no retry) so a persistent error cannot re-fire it on every
  poll tick. Recurrence math lives in `tasks/Recurrence.kt`.
- **History summarization** — `agent/history/ChatHistory.summarizeForPrompt` keeps recent turns verbatim and condenses
  older ones so the prompt stays within budget while keeping tool-call/result pairs anchored.
- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns
  `AppConfig.llmProvider` into a Koog client/model/params triple. Native clients cover OpenAI (with prompt caching),
  Anthropic, Google, and DeepSeek — models are matched against each client's predefined catalog. `openai-compatible`
  keeps a hand-declared model for any other server (llama.cpp, Ollama, …).

## Code execution service

`codeExecution` is the only tool backed by a service living in this repo instead of a third-party API. It is Deno +
TypeScript under [`sandbox/`](../sandbox/), has its own `Dockerfile`, contains no Kotlin, and is reached over HTTP from
`tools/sandbox/SandboxClient.kt`.

- **`main.ts`** — HTTP server on port 8080: `POST /run` (code plus input files) and `GET /health`. Keeps a pool of warm
  Pyodide workers (`SANDBOX_POOL_SIZE`, default 2), hands one out per request, and **terminates it after the run and
  spawns a replacement** — one run per worker, so no state leaks between executions. Oversized input is rejected before
  a worker is taken (`MAX_CODE_CHARS`, `MAX_INPUT_FILES`); an empty pool answers `503` after
  `ACQUIRE_TIMEOUT_SECONDS`; a crashed worker leaves the pool and is respawned after a backoff.
- **`worker.ts`** — one Pyodide instance: loads the baked packages, unpacks the extra wheels onto `sys.path`, registers
  the bundled fonts so matplotlib and Pillow draw real glyphs instead of tofu, warms the font cache during startup,
  runs the code in `/work`, and returns stdout/stderr plus the files written there, capped by `MAX_FILES`,
  `MAX_FILE_BYTES`, and `MAX_OUTPUT_CHARS`.
- **`packages.ts`** — Pyodide packages baked into the image. **`extra-wheels.txt`** — version-pinned pure-Python wheels
  that Pyodide does not ship, downloaded in the Dockerfile `wheels` stage so document handling works offline.

Isolation is enforced by the Deno entrypoint flags, not by convention: `--allow-net` is limited to the listening socket,
`--allow-read` to `/app`, `/deno-dir`, and `/fonts`, and there is no `--allow-write` at all. The code being run is
model-authored and untrusted — keep it that way.

`SANDBOX_TIMEOUT_SECONDS` is read by both sides: the service enforces it per run, the bot budgets its wait around it.

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client and LLM runtime → build
repositories, `ToolRegistryFactory`, `AgentFactory`, `AgentRunner` → optionally enable voice transcription → start
`TelegramBotRunner` and launch `TaskScheduler`, then block on the bot job until shutdown (closing the executor, HTTP
client, and DB in `finally`).

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom                                                                          | Start here                                                                                                                                                                                                                                                                                            |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Bot ignores a message entirely                                                   | `telegram/MessageFilter.kt` (`shouldHandle` — group reply/mention rules), then `TelegramBotRunner.isAccepted`/`isAllowed` (the `ALLOWED_IDS` allowlist)                                                                                                                                               |
| Reply says "still working on your previous request"                              | `agent/AgentRunner.kt` — the per-user `Mutex` rejects a second concurrent turn                                                                                                                                                                                                                        |
| Reply lands in the wrong chat, loses its reply anchor, or DM redirect misbehaves | `telegram/TelegramDelivery.kt` (routing/anchor/private-redirect *policy*)                                                                                                                                                                                                                             |
| Formatting renders wrong, message rejected, or media falls back to document/text | `agent/SystemPrompt.kt` (allowed HTML tags the agent emits), `telegram/TelegramOutputSender.kt` (which call and which fallback each output kind gets), `telegram/TelegramSendFallbacks.kt` (the fallback *mechanism* itself), `telegram/TelegramErrors.kt` (which provider errors trigger a fallback) |
| Bot floods a chat or stalls on Telegram 429 over a long multi-message reply      | `outbox/BotOutbox.kt` (text coalescing + `MAX_TEXT_MESSAGES` cap) + `telegram/TelegramDelivery.kt` (`INTER_MESSAGE_DELAY` pacing)                                                                                                                                                                     |
| A specific tool misbehaves                                                       | `tools/<feature>/<Feature>Tools.kt` for the tool surface, plus its `<Feature>Client.kt` for the external call                                                                                                                                                                                         |
| Code execution times out, says "busy", or loses produced files                   | `tools/sandbox/SandboxClient.kt` (HTTP call + wait budget), then the service in [`sandbox/`](../sandbox/): `main.ts` (worker pool, `503` when none free) and `worker.ts` (Pyodide setup, output caps)                                                                                                 |
| Wrong language in a canned reply (busy/error/voice/start)                        | `i18n/Language.kt` (language selection) + `i18n/Messages.kt` (the strings)                                                                                                                                                                                                                            |
| Bot forgets context or the history recap looks wrong                             | `agent/history/ChatHistory.kt` (summarize/slice) + `agent/history/ChatHistoryRepository.kt` (storage)                                                                                                                                                                                                 |
| Voice/audio not transcribed                                                      | `telegram/VoiceTranscriber.kt` + `stt/OpenAiWhisperClient.kt` (needs `OPENAI_STT_API_KEY`); for a video's sound `tools/vision/VideoAudioTranscriber.kt`                                                                                                                                                                                                            |
| Bot cannot see what is in a video                                                | `tools/vision/VisionTools.kt` (`describeVideo` guards and the preview-frame fallback), `tools/vision/VideoVisionClient.kt` (frames + transcript prompt), `tools/vision/VideoSampler.kt` (ffmpeg), `telegram/ReplyContext.kt` (which media becomes an `AttachedFile`)                                  |
| Web search picks the wrong provider, or results are thin                        | the `@LLMDescription` text that ranks them: `tools/tavily/TavilyToolDescriptions.kt` (`webSearch`, the default) and `tools/searxng/SearxngToolDescriptions.kt` (`metaSearch`, the fallback)                                                                                                           |
| Image search sends nothing, or sends irrelevant pictures                        | `tools/images/ImageSearchDelivery.kt` (candidate retries, size caps, media group) + `tools/images/ImageDownloadClient.kt` (user agent, format/dimension checks); for relevance, `SearxngTools.IMAGE_ENGINES` and `TavilyTools.imageExcludedDomains`                                                   |
| A rich message reads as empty, `unknown`, or loses its structure                 | `telegram/RichMessageText.kt` (block tree → rich markdown), then `MessageMetadata.contentTypeName`/`textSnippetOrNull` and `ReplyContext.repliedTextOrNull`                                                                                                                                           |
| Scheduled task fires late, not at all, or reports "missed"                       | `tasks/TaskScheduler.kt` (polling/lateness) + `tasks/Recurrence.kt` (next-run math)                                                                                                                                                                                                                   |
| An env var has no effect                                                         | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example)                                                                                                                                                            |
| Model / provider / request-timeout selection                                     | `config/LlmRuntime.kt` (provider → client/model/params)                                                                                                                                                                                                                                               |
| `describeImage`/`describeVideo` missing from the tool list                       | `config/VisionRuntime.kt` (chat model vs `OPENAI_VISION_API_KEY`), then `tools/ToolRegistryFactory.kt` (registration is skipped when there is no vision runtime)                                                                                                                                       |
| Garbled or empty tool-call crashes from a flaky model                            | `agent/AgentFactory.kt` — `vusanSingleRunStrategy` and `missingRequiredArgs` short-circuit them                                                                                                                                                                                                       |

## Adding a tool

A new agent tool typically touches these, in order:

1. **`tools/<feature>/<Feature>Tools.kt`** — `class <Feature>Tools(...) : ToolSet` whose constructor takes the
   `BotOutbox` and/or a client; each method is
   `@Tool @LLMDescription(...) suspend fun … = suspendToolGuard { … }`.
2. **`tools/<feature>/<Feature>ToolDescriptions.kt`** — an `internal object` of `const val`
   descriptions referenced by the `@LLMDescription` annotations (see the convention in `AGENTS.md`).
3. *(optional)* **`<Feature>Client.kt`** / **`<Feature>Models.kt`** — the external I/O and its DTOs.
4. **`tools/ToolRegistryFactory.kt`** — register it in `buildRegistry`; wrap construction in the
   `optional(...)` helper when it depends on an API key that may be unset.
5. **Docs** — add the tool to the Features section of the [README](../README.md); add any new env vars to
   [`configuration.md`](configuration.md) and [`.env.example`](../.env.example).

## Conventions

Coding conventions (logger placement, error handling, tool structure, DB/config access) are documented in
[`AGENTS.md`](../AGENTS.md) at the repo root.
