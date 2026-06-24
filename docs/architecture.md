# Architecture

This document is the orientation map for the codebase: the layers, how a message flows through
them, and the background flows that run alongside. Code lives under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

## Layers

```
Telegram ──► telegram/ ──► agent/ ──► tools/ ──► external services
                │            │           │
                │            │           └─ writes outputs into ─► outbox/
                │            ├─ reads/stores history via ─► agent/history/ ─► infra/
                │            └─ reads/stores memory via ──► agent/memory/ ──► infra/
                └─ delivers outbox back to Telegram
```

- **`telegram/`** — Telegram I/O. Receives updates (text, voice, audio, sticker, photo, document,
  album), filters by allowlist, normalizes input, and delivers agent results back — including
  HTML-formatting, reply-anchor, media/document, media-group, and private-message fallbacks.
- **`agent/`** — agent orchestration on top of Koog. `AgentRunner` serializes per-user turns;
  `AgentFactory` builds the `AIAgent` (system prompt + history + memory + tools). `agent/history/`
  summarizes and persists chat turns; `agent/memory/` stores durable user/group memory that
  survives a history clear and is injected as `<user_memory>`/`<group_memory>`.
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision,
  scheduled tasks, …). `ToolRegistryFactory` owns clients and builds a per-request registry from
  required tools plus optional tools whose env/config is present. See [features.md](features.md).
- **`outbox/`** — the output model. `BotOutput` is the immutable sealed set of things the bot can
  send (text, photo, voice, audio, video, document, poll, reaction, …); `BotOutbox` is the
  per-request queue tools write into, holding each `BotOutput` as an `OutboxItem` that captures
  its private-routing decision.
- **`request/`** — the request-scoped input model shared across layers: `RequestContext`
  (chat/user/message ids and sender info tools see) and `AttachedFile` (photo or document, from
  the current message or a replied-to message, that vision (`describeImage`) and code execution
  (`codeExecution`) can lazily download).
- **`tasks/`** — scheduled-task subsystem: storage, recurrence math, and the background
  `TaskScheduler`.
- **`infra/`** — cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor
  `Http` client.
- **`config/`** — `.env` parsing (`AppConfig`) and LLM provider/model resolution (`LlmRuntime`).
- **`stt/`** — OpenAI speech-to-text client (`OpenAiWhisperClient`, default model
  `gpt-4o-transcribe`); used for voice transcription, opt-in via `OPENAI_STT_API_KEY`.
- **`i18n/`** — user-facing message strings, one `Messages` implementation per `Language`.
  `Language.fromCode` picks the language from the sender's Telegram language code (default
  English); add a language by adding an enum entry and a `Messages` impl.
- **`common/`** — tiny shared utilities: prompt/text helpers (`Strings.kt`) and cancellation
  rethrow (`Cancellation.kt`).

## Request lifecycle

A normal user message travels:

1. **Receive** — `TelegramBotRunner` handles the long-polling update (`onText`, `onVoice`,
   `onAudio`, `onSticker`, `onDocument`, `onPhoto`, `onVisualGalleryMessages`). Albums (media
   groups) arrive as one message: the caption may sit on any album part, only the first photo
   becomes the `AttachedFile`, and the agent is told how many items it cannot see.
2. **Filter** — `MessageFilter.shouldHandle` drops messages the bot shouldn't answer (in groups:
   only replies, mentions, or targeted commands); `TelegramBotRunner` then checks the allowlist
   (`ALLOWED_IDS`) and rejects unknown chats/users.
3. **Normalize** — text is sanitized (`MessageSanitizer`); voice/audio is transcribed
   (`VoiceTranscriber` → `stt/`); stickers become a metadata prompt; replied-message context is
   wrapped in `<reply_context>`/`<user_message>`; current or replied photo/image-document input
   becomes `AttachedFile`. `TelegramBotRunner.dispatchToAgent` assembles the agent input and the
   shorter history input.
4. **Run** — `AgentRunner.handle` takes the per-user lock (or returns "busy"), then
   `AgentFactory.build` constructs a Koog `AIAgent` with the system prompt, current time, message
   context, summarized history (`agent/history/ChatHistory`), durable memory
   (`agent/memory/MemoryRepository` — the sender's user memory always, plus the group's memory in
   non-private chats), and the per-request tool registry (`ToolRegistryFactory.buildRegistry`).
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`;
   tool calls/results are recorded for history. The custom `single_run` strategy (`AgentFactory`)
   guards against flaky models in two ways:
   - a tool call missing its declared required parameters (flaky models emit empty-arg siblings
     when they try to call tools in parallel) is short-circuited into a `ValidationError` result
     instead of being executed, so the run stays clean and the follow-up request stays
     well-formed;
   - a turn that ends having delivered nothing — no `sendMessage`, media, or reaction, and empty
     assistant text (flaky providers return an empty completion after a batch of tool results) —
     gets one nudge to actually deliver before finishing, so a full turn of research does not
     collapse into silence.
6. **Collect** — `AgentRunner` returns an `AgentResult` (outputs + optional comment + history
   turns to persist).
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat (or the user's
   private chat when a tool requested it), anchoring replies to the original message and falling
   back when Telegram rejects formatting, a reply target is gone, a private DM is blocked, or a
   media send fails. A live chat action runs through the whole turn (`TelegramBotRunner.withLiveChatAction`):
   it starts as `typing` and switches to the currently executing tool's action, e.g. `upload_photo` while an
   image generates. Koog's `onToolCallStarting` resolves the running tool to a neutral `ToolActivity`
   (`agent/ToolActivity.kt`, keyed by `@Tool` method references); the Telegram layer translates that to a chat
   action (`chatActionFor`). During delivery each item is then preceded by the action matching its content
   (`botActionFor`) so the indicator tracks what is happening. Outgoing text and captions are sent with Telegram's `HTML` parse mode; the
   agent is instructed (in `agent/SystemPrompt.kt`) to format with the supported HTML tags and to
   escape `<`/`>`/`&`. Rejected formatting on a reply text is re-sent as a `message.html` document
   (`telegram/HtmlReplyDocument.kt` — a standalone, responsive, light/dark page with a no-script
   CSP) carrying the reply so the formatting still arrives; a rejected media caption resends the
   media captionless and delivers the caption the same way, while bot notices fall back to plain
   text. Sandbox image previews opt out of photo-to-document fallback because their
   uncompressed document copy is already queued. Consecutive sends in a multi-output reply are
   paced (`INTER_MESSAGE_DELAY`) so a batch stays under Telegram's per-chat rate limit. Upstream,
   `BotOutbox` coalesces consecutive `sendMessage` text into the trailing bubble while it fits
   (`MAX_TEXT_MESSAGE_CHARS`), so a model that splits one answer into many small messages produces
   few real sends; the number of resulting bubbles is still capped (`MAX_TEXT_MESSAGES`) so a
   looping model cannot flood the chat. `TelegramOutputSender` performs the low-level API calls.
8. **Persist** — produced history turns are appended via `ChatHistoryRepository`.

## Background and side flows

- **Task scheduler** — `TaskScheduler.launchIn` polls the task store every 30 seconds. Due tasks
  run through `AgentRunner.handleScheduled` (waits for the
  user lock instead of bailing), are delivered with `TelegramDelivery.sendScheduled`, and then
  append produced history turns. Tasks overdue beyond `TASK_MAX_LATENESS_MINUTES` (e.g. after
  downtime) get a "missed" notice and are advanced/disabled rather than fired. A task whose run
  fails is still advanced/disabled (logged, no retry) so a persistent error cannot re-fire it on
  every poll tick. Recurrence math lives in `tasks/Recurrence.kt`.
- **History summarization** — `agent/history/ChatHistory.summarizeForPrompt` keeps recent turns
  verbatim and condenses older ones so the prompt stays within budget while keeping
  tool-call/result pairs anchored.
- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns
  `AppConfig.llmProvider` into a Koog client/model/params triple. Native clients cover OpenAI
  (with prompt caching), Anthropic, Google, and DeepSeek — models are matched against each
  client's predefined catalog. `openai-compatible` keeps a hand-declared model for any other
  server (llama.cpp, Ollama, …).

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client
and LLM runtime → build repositories, `ToolRegistryFactory`, `AgentFactory`, `AgentRunner` →
optionally enable voice transcription → start `TelegramBotRunner` and launch `TaskScheduler`,
then block on the bot job until shutdown (closing the executor, HTTP client, and DB in `finally`).

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom                                                                          | Start here                                                                                                                                              |
|----------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Bot ignores a message entirely                                                   | `telegram/MessageFilter.kt` (`shouldHandle` — group reply/mention rules), then `TelegramBotRunner.isAccepted`/`isAllowed` (the `ALLOWED_IDS` allowlist) |
| Reply says "still working on your previous request"                              | `agent/AgentRunner.kt` — the per-user `Mutex` rejects a second concurrent turn                                                                          |
| Reply lands in the wrong chat, loses its reply anchor, or DM redirect misbehaves | `telegram/TelegramDelivery.kt` (routing/anchor/private-redirect *policy*)                                                                               |
| Formatting renders wrong, message rejected, or media falls back to document/text  | `agent/SystemPrompt.kt` (allowed HTML tags the agent emits), `telegram/TelegramOutputSender.kt` (HTML parse mode + fallback *mechanism*), `telegram/TelegramErrors.kt` (which provider errors trigger a fallback) |
| Bot floods a chat or stalls on Telegram 429 over a long multi-message reply       | `outbox/BotOutbox.kt` (text coalescing + `MAX_TEXT_MESSAGES` cap) + `telegram/TelegramDelivery.kt` (`INTER_MESSAGE_DELAY` pacing)                        |
| A specific tool misbehaves                                                       | `tools/<feature>/<Feature>Tools.kt` for the tool surface, plus its `<Feature>Client.kt` for the external call                                           |
| Wrong language in a canned reply (busy/error/voice/start)                        | `i18n/Language.kt` (language selection) + `i18n/Messages.kt` (the strings)                                                                              |
| Bot forgets context or the history recap looks wrong                             | `agent/history/ChatHistory.kt` (summarize/slice) + `agent/history/ChatHistoryRepository.kt` (storage)                                                   |
| Voice/audio not transcribed                                                      | `telegram/VoiceTranscriber.kt` + `stt/OpenAiWhisperClient.kt` (needs `OPENAI_STT_API_KEY`)                                                              |
| Scheduled task fires late, not at all, or reports "missed"                       | `tasks/TaskScheduler.kt` (polling/lateness) + `tasks/Recurrence.kt` (next-run math)                                                                     |
| An env var has no effect                                                         | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example)              |
| Model / provider / request-timeout selection                                     | `config/LlmRuntime.kt` (provider → client/model/params)                                                                                                 |
| Garbled or empty tool-call crashes from a flaky model                            | `agent/AgentFactory.kt` — `vusanSingleRunStrategy` and `missingRequiredArgs` short-circuit them                                                         |

## Adding a tool

A new agent tool typically touches these, in order:

1. **`tools/<feature>/<Feature>Tools.kt`** — `class <Feature>Tools(...) : ToolSet` whose
   constructor takes the `BotOutbox` and/or a client; each method is
   `@Tool @LLMDescription(...) suspend fun … = suspendToolGuard { … }`.
2. **`tools/<feature>/<Feature>ToolDescriptions.kt`** — an `internal object` of `const val`
   descriptions referenced by the `@LLMDescription` annotations (see the convention in `AGENTS.md`).
3. *(optional)* **`<Feature>Client.kt`** / **`<Feature>Models.kt`** — the external I/O and its DTOs.
4. **`tools/ToolRegistryFactory.kt`** — register it in `buildRegistry`; wrap construction in the
   `optional(...)` helper when it depends on an API key that may be unset.
5. **Docs** — add the tool to [`features.md`](features.md); add any new env vars to
   [`configuration.md`](configuration.md) and [`.env.example`](../.env.example).

## Conventions

Coding conventions (logger placement, error handling, tool structure, DB/config access) are
documented in [`AGENTS.md`](../AGENTS.md) at the repo root.
