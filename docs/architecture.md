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

| Package     | Responsibility                                                                                                                                                                                                                                                                                                                                        |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `telegram/` | Telegram I/O. Receives updates (text/voice/audio/sticker/document), filters by allowlist, normalizes input, and delivers agent results back — including markdown/document/text fallbacks and reply anchoring.                                                                                                                                         |
| `agent/`    | Agent orchestration on top of Koog. `AgentRunner` serializes per-user turns; `AgentFactory` builds the `AIAgent` (system prompt + history + memory + tools). `agent/history/` summarizes and persists chat turns; `agent/memory/` stores durable user/group memory that survives a history clear and is injected as `<user_memory>`/`<group_memory>`. |
| `tools/`    | Agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …). `ToolRegistryFactory` owns the clients and builds a per-request registry. See [features.md](features.md).                                                                                                                                            |
| `outbox/`   | The output model. `BotOutput` is the (immutable) sealed set of things the bot can send (text, photo, voice, poll, reaction, …); `BotOutbox` is the per-request queue tools write into, holding each `BotOutput` as an `OutboxItem` that captures its private-routing decision.                                                                        |
| `request/`  | The request-scoped input model shared across layers: `RequestContext` (chat/user/message ids and sender info that tools see) and `AttachedFile` (an image or file — photo or document, sent or replied-to — that both vision (`describeImage`) and the code sandbox (`runCode`) can lazily download).                                                 |
| `tasks/`    | Scheduled-task subsystem: storage, recurrence math, and the background `TaskScheduler`.                                                                                                                                                                                                                                                               |
| `infra/`    | Cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor `Http` client.                                                                                                                                                                                                                                                           |
| `config/`   | `.env` parsing (`AppConfig`) and LLM provider/model resolution (`LlmRuntime`).                                                                                                                                                                                                                                                                        |
| `stt/`      | OpenAI Whisper speech-to-text client (optional, opt-in).                                                                                                                                                                                                                                                                                              |
| `i18n/`     | User-facing message strings, one `Messages` implementation per `Language`. `Language.fromCode` picks the language from the sender's Telegram language code (default English); add a language by adding an enum entry and a `Messages` impl.                                                                                                           |
| `common/`   | Tiny shared utilities (e.g. cancellation rethrow).                                                                                                                                                                                                                                                                                                    |

## Request lifecycle

A normal user message travels:

1. **Receive** — `TelegramBotRunner` handles the long-polling update (`onText`/`onVoice`/`onAudio`/`onSticker`/
   `onDocument`).
2. **Filter** — `MessageFilter.shouldHandle` drops messages the bot shouldn't answer (in groups: only replies, mentions,
   or targeted commands);
   `TelegramBotRunner` then checks the allowlist (`ALLOWED_IDS`) and rejects unknown chats/users.
3. **Normalize** — text is sanitized (`MessageSanitizer`); voice/audio is transcribed (`VoiceTranscriber` → `stt/`);
   replied-message context and any replied
   photo are gathered. `TelegramBotRunner.dispatchToAgent` assembles the agent input and history input.
4. **Run** — `AgentRunner.handle` takes the per-user lock (or returns "busy"), then `AgentFactory.build` constructs a
   Koog `AIAgent` with the system prompt,
   current time, message context, summarized history (`agent/history/ChatHistory`), durable memory
   (`agent/memory/MemoryRepository` — the sender's user memory always, plus the group's memory in non-private chats),
   and
   the per-request tool registry (`ToolRegistryFactory.buildRegistry`).
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`. Tool calls/results are
   recorded for history. The custom
   `single_run` strategy (`AgentFactory`) validates each tool call against its declared required parameters first: a
   garbled call missing them (flaky models
   emit empty-arg siblings when they try to call tools in parallel) is short-circuited into a `ValidationError` result
   instead of being executed, so the run
   stays clean and the follow-up request stays well-formed.
6. **Collect** — `AgentRunner` returns an `AgentResult` (outputs + optional comment + history turns to persist).
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat (or the user's private chat when a tool
   requested it), anchoring replies to the
   original message and falling back when Telegram rejects markdown, a reply target is gone, or the media type fails.
   `TelegramOutputSender` performs the
   low-level API calls.
8. **Persist** — produced history turns are appended via `ChatHistoryRepository`.

## Background and side flows

- **Task scheduler** — `TaskScheduler.launchIn` polls the task store every `TASK_POLL_INTERVAL_SECONDS`. Due tasks run
  through `AgentRunner.handleScheduled` (
  waits for the user lock instead of bailing) and are delivered with `TelegramDelivery.sendScheduled`. Tasks overdue
  beyond `TASK_MAX_LATENESS_MINUTES` (e.g.
  after downtime) get a "missed" notice and are advanced/disabled rather than fired. Recurrence math lives in
  `tasks/Recurrence.kt`.
- **History summarization** — `agent/history/ChatHistory.summarizeForPrompt` keeps recent turns verbatim and condenses
  older ones so the prompt stays within
  budget while keeping tool-call/result pairs anchored.
- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns `AppConfig.llmProvider` into a Koog
  client/model/params triple, supporting OpenAI (
  with prompt caching) and any OpenAI-compatible server (llama.cpp, Ollama, …).

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client and LLM runtime → build
repositories, `ToolRegistryFactory`,
`AgentFactory`, `AgentRunner` → optionally enable voice transcription → start `TelegramBotRunner` and launch
`TaskScheduler`, then block on the bot job until
shutdown (closing the executor, HTTP client, and DB in `finally`).

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom                                                                          | Start here                                                                                                                                              |
|----------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| Bot ignores a message entirely                                                   | `telegram/MessageFilter.kt` (`shouldHandle` — group reply/mention rules), then `TelegramBotRunner.isAccepted`/`isAllowed` (the `ALLOWED_IDS` allowlist) |
| Reply says "still working on your previous request"                              | `agent/AgentRunner.kt` — the per-user `Mutex` rejects a second concurrent turn                                                                          |
| Reply lands in the wrong chat, loses its reply anchor, or DM redirect misbehaves | `telegram/TelegramDelivery.kt` (routing/anchor/private-redirect *policy*)                                                                               |
| Markdown rejected, or media won't send / falls back to a document or text        | `telegram/TelegramOutputSender.kt` (send + fallback *mechanism*) and `telegram/TelegramErrors.kt` (which provider errors trigger a fallback)            |
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
