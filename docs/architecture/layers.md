# Layers

The packages under `src/main/kotlin/com/helltar/vusan/` and what each one owns. Part of the [architecture index](../architecture.md).

```
Telegram ──► telegram/ ──► agent/ ◄──► tools/ ──► external services
                │            │           │
                │            │           └─ writes outputs into ─► outbox/
                │            ├─ asks the model through ─► llm/ ──► model providers
                │            ├─ reads/stores the dialogue via ─► agent/conversation/ ─► infra/
                │            └─ reads/stores memory via ──► agent/memory/ ──► infra/
                ├─ records every group message into ─► agent/grouplog/ ─► infra/
                │                                          ▲
                │            agent/presence/ ── reads ─────┘  (and speaks through delivery/)
                └─ delivers outbox back to Telegram
```

`agent/` and `tools/` point both ways, and are one layer rather than two: the runner has to name the tool sets it
recognizes (`ToolActivity` maps a tool's own method reference to what the chat is shown while it runs, so a rename
cannot silently break the mapping), and a tool reads the store its capability is about — `MemoryTools` the memory
repository, `GroupLogTools` the transcript. Those stores sit under `agent/` because
that is who owns writing them, not because only `agent/` reads them. No other arrow here is bidirectional.

- **`telegram/`** — Telegram I/O, split by direction. `TelegramBotRunner` at the root receives updates (text, voice,
  audio, sticker, photo, video, video note, GIF, document, album, callback query), filters them by allowlist and ban
  list, and works out what each one says; `AgentTurns`, also at the root, takes it from there — the reply context, the
  `AgentRequest`, the progress indicator, the delivery and its fallback — so a turn started by a message and one started
  by a button follow the same path; `telegram/tools/` holds the tools only Telegram can implement — resending by
  `file_id`, and the sticker catalog — which reach the catalog through the shared `PlatformToolSets` port rather than
  being registered centrally; `telegram/inbound/` normalizes an update into agent input; `telegram/delivery/`
  sends agent results back, including HTML-formatting, opt-in rich-message, reply-anchor, media/document, media-group,
  and private-message fallbacks; `telegram/callback/` owns the inline-button flows — `CallbackRouter` validates a
  pressed button and picks its flow, `TaskMenuHandler` runs the deterministic `/tasks` UI, and `InlineChoiceHandler` the
  agent-created choice buttons, whose selection becomes an agent input.
- **`llm/`** — the model layer: one `LlmClient` interface, the `Message`/`Part` model every caller builds, `RequestOptions`,
  and a client per wire protocol under `llm/openai/` (the Responses and Chat Completions APIs, which also cover the Codex
  backend and any OpenAI-compatible server) and `llm/anthropic/` (the Messages API), plus `llm/codex/`: what the Codex backend needs beside the protocol —
  the credentials `codex login` writes, the headers Cloudflare checks, the subscription's usage windows and the
  account's model catalog. The package knows nothing about the
  bot: `RetryingLlmClient` repeats a call the provider fumbled, `FallbackLlmClient` hands every call to a second provider
  while the first is out, and the reasoning blocks an endpoint returns are kept raw and replayed verbatim to that endpoint
  alone — the platform, the Codex backend and a compatible server speak one protocol, and none reads another's. The
  clients own their data shapes — adding a field the API grew is one line, and a block type a reply carries that nobody
  reads yet is skipped with a warning rather than failing the call.
- **`agent/`** — agent orchestration. `AgentRunner` serializes the turns of one conversation, assembles
  the current user turn (chat metadata + durable memory + request), and owns every history write for it, so no other
  layer appends or clears turns behind a running turn's back. What it orchestrates sits beside it, one file per concern:
  `TurnPrompt` renders the blocks the model is shown for this turn, `TurnShelf` keeps what the turn's tool calls made
  under the labels later calls take it by, `TurnInput` writes the ones the request itself
  arrives in — what it replies to, the quoted fragment, the attachment, an album, a transcript, a pressed choice — so
  every adapter fills in the tags the contract describes instead of spelling its own, `TurnSurroundings` reads what the chat around
  the turn adds to it — the group's diary, the slice of what the group was just saying, the sticker shortlist — each only
  where it applies, `TurnHistory` decides what the finished turn leaves
  behind, and `ProviderErrors` reads a provider's refusal out of the status and body the client kept and picks the
  reply it earns — and, for `llm/FallbackLlmClient`, how long the primary is out for, which is also what `TurnPrompt`'s
  `<current_model>` block and the status message's fallback line read to say who is answering. `AgentFactory` builds
  the `AgentTurn` (system prompt + history + tools over the model client) and budgets its model context, and
  `AgentTurn` is the agent loop itself; `SystemPrompt` keeps the deployment's customizable personality and the
  fixed delivery/tool contract in separate XML-delimited blocks, the contract naming the model and whichever of
  pictures and videos this deployment cannot make. `agent/conversation/` groups turns into complete
  interactions, persists raw history and maintains its semantic recap; `ConversationPlanner` picks what of it a prompt
  carries, recapping first when it does not fit. All of it is keyed by a `ConversationScope` — one
  person in one chat, so a private exchange can never be replayed as that person's own words inside a group, and what
  travels between chats is durable memory rather than raw turns; `agent/memory/` stores that memory under a
  `MemoryOwner` (one person, or one group), which survives a history clear and is injected as
  `<user_memory>`/`<group_memory>`; `agent/grouplog/` is the group transcript, keyed by chat alone, holding every message the bot saw in a group rather than only the turns it took
  part in. `GroupLogReader` answers a window from it under a character budget, falling back to cached per-day recaps
  produced by `GroupLogDigester` when the window is too wide to quote. `agent/presence/` is what the bot does in a
  group with nobody asking: `Diary` writes an entry about each closed day, and `Initiative` looks over a chat people are
  writing in and now and then reacts or says something. Neither is a turn — no history, no tools, one model call each —
  and both are off unless switched on; see [Background and side flows](groups.md). `LeaveAloneRepository`
  beside them holds who asked not to be written to, which is what both, the ambient gate and the turn's context block
  keep to.
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …).
  `ToolCatalogFactory` owns clients and builds a per-request `ToolCatalog` from required tools, optional tools whose
  env/config is present, and whatever the turn's messenger adds through the `PlatformToolSets` port — a tool only one
  messenger can implement lives in that adapter, so the factory never names one. The catalog splits what is registered
  from what the request carries: a set registered under a `ToolGroup` is in the catalog from the first step, but its
  schemas are withheld until the model calls `loadTools` (`tools/catalog/`), which the `<tool_groups>` menu in the
  turn prompt tells it about. Deferring is what keeps the schemas of a dozen rarely-used capabilities out of the
  conversation budget; a call is resolved against everything registered, so a tool named before its group is loaded
  still runs. The request declares the groups' tools deferred from its first step, and a group once loaded is offered in
  the conversation (`Message.ToolsOffered`): on a model that takes tools partway through — Anthropic's `tool_addition`,
  OpenAI's `additional_tools`, `LlmModel.offersToolsMidConversation` — the tool array, which providers cache ahead of
  everything else, then never changes; elsewhere the client lists what was offered up front, and a load costs the cache
  for the rest of the turn. `LoadedToolGroups` keeps the last few groups a conversation loaded and offers them again
  with its next request, which spares the model the load and, on the second kind of endpoint, keeps the array from
  changing every turn. See the Features section of the [README](../../README.md). `tools/images/` is not a tool surface
  but the pipeline every image search shares: download a provider's candidates, drop what Telegram would refuse, and
  queue the survivors.
- **`outbox/`** — the output model. `BotOutput` is the immutable sealed set of Telegram outputs (text, inline choice,
  rich message, photo, voice, audio, video, document, poll, reaction, …); `BotOutbox` is the per-request queue tools
  write into, holding each `BotOutput` as an `OutboxItem` that captures its private-routing decision.
- **`request/`** — the request-scoped input model shared across layers. `RequestContext` is the single record an
  adapter builds at ingress and the runner and every tool then read: a `ChatContext` (where the turn is — id, flavor,
  sub-conversation, title, description and what the chat allows), a `SenderContext` (who sent it, and whether that
  sender is one person at all rather than a shared account the platform posts under), the message being answered — or
  `null` when nothing sent one — its reply anchor, the attachments and the language. Every reference in it is opaque
  text, a message and a topic as much as a person or a chat: `telegram/TelegramIds.kt` is the only place that reads one
  back as a number. Beside it: `ChatProfile` (what an
  adapter has to look the chat up for), `ChatCapabilities` (what the chat lets the bot post, and its slow mode —
  defaulting to unrestricted so a failed lookup never removes an ability), and `AttachedFile` (photo, video, or document, from the
  current message or a replied-to message, that vision (`describeImage`, `describeVideo`) and the sandbox
  (which copies it into the turn's own directory under `turns/`) can lazily download; the same type
  carries a file a tool names by reference — one another call made, or one in the sandbox, read only when its bytes
  are wanted). Its `kind`
  (`IMAGE`/`VIDEO`/`OTHER`) decides which of those tools accepts it; a video also carries its duration and a loader for
  Telegram's own thumbnail.
- **`delivery/`** — the shared output address and port: a `Destination` (chat plus optional thread — an anchor is not
  part of an address), the `Attribution` saying who a scheduled answer belongs to, where it hangs, and why the chat is
  hearing from the bot at all
  (`AttributionReason`) — the adapter writes the mention itself, since naming a person is platform syntax — and
  `OutputDelivery`, which an adapter implements so nothing outside one needs a messenger client to deliver a turn. Its
  `deliverUnprompted` is the send with no person behind it at all — what the bot says of its own accord, optionally
  hung under one message.
- **`tasks/`** — scheduled-task subsystem: storage, persisted pause state, recurrence math, and the background
  `TaskScheduler`. It knows no messenger: it delivers through `OutputDelivery` and reads chat facts through
  `ChatProfileLookup`.
- **`infra/`** — cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor `Http` client. Every
  table that holds state belonging to somebody carries a `platform` column beside the external id, so two messengers
  issuing the same number never read each other's rows. `Db.connect` creates a fresh database whole and stamps the version
  declared in `infra/Schema.kt` into SQLite's own `PRAGMA user_version`. Nothing is inferred by comparing declarations
  to what is there: an older database is moved forward through `Schema.steps`, plain SQL per version run in one
  transaction with the stamps after a `VACUUM INTO` copy is kept beside it (`vusan.db.schema-<N>`), and a test holds
  the result to the shape a fresh database gets. A database older than the first step, or from a newer build, stops
  startup instead of being reshaped.
- **`config/`** — `.env` parsing (`AppConfig`) into the settings of each feature. Its `llm/` holds the model wiring:
  provider/model resolution (`LlmRuntime`) and the startup check of every configured model against its vendor
  (`ModelPreflight`), both of which dispatch to one file per provider (`OpenAiProvider`, `AnthropicProvider`,
  `OpenAiCompatibleProvider`, `CodexProvider`, `OpenRouterProvider`), and the two roles on a model of
  their own. `VisionRuntime` resolves which model looks at images: the `VISION_*` model when configured, the chat
  model when it accepts images, and nothing at all otherwise — which leaves the vision tools and sticker catalog
  unavailable. `AddressingRuntime` resolves the model that decides whether an unmentioned message is for the bot.
- **`stt/`** — OpenAI speech-to-text client (`OpenAiWhisperClient`, default model `gpt-transcribe`); used for voice
  transcription and for the sound of a video the vision tool watches, opt-in via `OPENAI_STT_API_KEY`.
- **`i18n/`** — user-facing message strings: the `Messages` interface, and one implementation per `Language` in a file
  of its own (English, Ukrainian, Russian, Spanish). `Language.ofText` reads the language off the message itself —
  Ukrainian and Russian by the letters and everyday words only one of them has — and leaves what it cannot tell, Latin
  script or a bare `ок`, to `Language.fromCode` on the sender's Telegram language code, falling back to English; a
  Cyrillic message from a client set to neither is answered in Ukrainian. The strings are plain and gender-neutral,
  because a deployment's personality is its own and the bot's grammatical gender is not known here. Adding a
  language is an enum entry plus a `Messages` file — the exhaustive
  `when` in `Messages.of` and the interface itself make the compiler name everything still missing.
- **`common/`** — tiny shared utilities: prompt/text helpers (`Strings.kt`) and cancellation rethrow
  (`Cancellation.kt`).
