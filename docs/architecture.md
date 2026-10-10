# Architecture

This document is the orientation map for the codebase: the layers, how a message flows through them, and the background
flows that run alongside. The main application is Kotlin under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/), and it is the whole deployment. One thing
runs outside it: the sandbox, a Regolith server this bot is a client of, which also publishes the pages people build —
see [Sandbox](#sandbox) and [Publishing to the web](#publishing-to-the-web).

Chasing a symptom rather than reading for orientation? Start at [Where to look when…](#where-to-look-when) — it maps a
symptom to the file that owns it, and beats searching the tree.

## Layers

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
  fixed delivery/tool contract in separate XML-delimited blocks. `agent/conversation/` groups turns into complete
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
  and both are off unless switched on; see [Background and side flows](#background-and-side-flows).
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …).
  `ToolCatalogFactory` owns clients and builds a per-request `ToolCatalog` from required tools, optional tools whose
  env/config is present, and whatever the turn's messenger adds through the `PlatformToolSets` port — a tool only one
  messenger can implement lives in that adapter, so the factory never names one. The catalog splits what is registered
  from what the request carries: a set registered under a `ToolGroup` is in the catalog from the first step, but its
  schemas are withheld until the model calls `loadTools` (`tools/catalog/`), which the `<tool_groups>` menu in the
  turn prompt tells it about. Deferring is what keeps the schemas of a dozen rarely-used capabilities out of the
  conversation budget; a call is resolved against everything registered, so a tool named before its group is loaded
  still runs. `LoadedToolGroups` then keeps the last few groups a conversation loaded and offers them again from its next
  first request: the tool array is part of the prompt prefix providers cache, so a set that changes every turn would
  cost more than the schemas it saved. See the Features section of the [README](../README.md). `tools/images/` is not a tool surface
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
  to what is there, and nothing is migrated in code: a database of any other version — from before versions existed, or
  from a newer build — stops startup instead of being reshaped, and is moved by hand.
- **`config/`** — `.env` parsing (`AppConfig`), LLM provider/model resolution (`LlmRuntime`) and the startup check of
  every configured model against its vendor (`ModelPreflight`), both of which dispatch to one file per provider
  (`OpenAiProvider`, `AnthropicProvider`, `OpenAiCompatibleProvider`, `CodexProvider`). `VisionRuntime` resolves separately which model looks at
  images: the `VISION_*` model when configured, the chat model when it accepts images, and nothing at all
  otherwise — which leaves the vision tools and sticker catalog unavailable.
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

## Request lifecycle

A normal user message travels:

1. **Receive** — `TelegramBotRunner` long-polls via `TelegramBotsLongPollingApplication`, funnels updates into a
   channel, and dispatches each message by content (text/command, rich message, sticker, voice, audio, photo, video,
   video note, GIF, document). Album (media group) parts arrive as separate updates sharing a `media_group_id`; the
   runner buffers them until the update stream goes quiet (`ALBUM_QUIET_PERIOD`, or the ten-item album cap) and handles
   the batch as one gallery message: the caption may sit on any album part, every inspectable item becomes an
   `AttachedFile` on the turn, the sandbox and image editing take them all, and the agent is told that vision sees only
   the first. `/tasks`, `/clear`, `/stop`, and task-menu
   callback queries take direct paths that never enter the agent loop. Every pressed button reaches `CallbackRouter`,
   which rechecks the allowlist and picks the flow: an agent-created inline-choice callback is validated and consumed by
   `InlineChoiceHandler`, and its selected option then enters the agent loop as the user's next turn. Callback data no
   handler recognizes (a button from an older build) is still answered, so the caller's client stops spinning. A
   `my_chat_member` update — the bot's own membership changing, which Telegram delivers by default — carries no message
   and goes straight to `telegram/BotMembership.kt` instead of the dispatch below; it is in the default
   `allowed_updates` set, which is why it needs no `allowed_updates` parameter of its own. An `edited_message` update always rewrites its
   group-transcript row (`GroupLogRepository.recordEdit`, which also drops the cached digest of that message's day), and
   only then may enter the dispatch below. `startsTurnOnEdit` decides: an edit answers only when it is what made the
   message addressed to the bot — someone adding the mention they forgot. An edit of a message older than
   `EDIT_TURN_WINDOW`, an edit into a slash command, and an edit of an album part are recorded but never answered; the
   reply anchors to the edited message, so once the chat has moved past it there is nothing left to answer.

   Every polled batch is written to `pending_updates` (`telegram/UpdateSpool.kt`) before the poll callback returns, and
   the row is deleted the moment the dispatch loop picks the update up. The write blocks the session's poller thread on
   purpose: the session confirms updates to Telegram by asking for a higher offset on its *next* request, so returning
   from the callback is what makes them unrecoverable. At startup `drain` replays what is left, oldest first, and drops
   anything past `SPOOL_RETENTION` rather than answering a conversation that has moved on. The boundary is what was
   never begun: a turn already running may have sent messages, spent tokens and written history, so it is dropped
   instead of repeated. A replay racing Telegram's own redelivery of the same update is harmless — the row is written
   with `ignore`, and `AnsweredMessages` claims the message once either way.

2. **Filter** — the allowlist (`ALLOWED_IDS`) comes first, in `TelegramBotRunner.passesAllowlist`, on the polling loop
   itself: an update from a chat and user it does not name is dropped there, before the transcript, the sticker catalog,
   album buffering or a dispatch coroutine can cost anything, and only a message actually aimed at the bot is logged as
   denied. `BANNED_IDS` is checked first and wins over the allowlist, so a banned user is denied inside a chat that is
   otherwise open. `TaskScheduler` puts the same question to the same `AccessPolicy` before every fire — a task runs on
   nobody's behalf but its owner's, so losing access stops the work that goes on without them too — and skips the fire
   without running or announcing it, moving the recurrence on. The schedule is kept either way: being allowed back
   resumes the task instead of resurrecting the fires it missed, and a one-time task, which has no recurrence to move
   on, waits in place for the tick that may run it. Two sinks then run on every allowlisted message, *before* the addressing check, because what they
   collect is precisely what nobody addressed to the bot: `recordGroupLog` writes the group transcript row, and
   `learnSticker` teaches the catalog which sets the chat uses. Both sit ahead of album buffering too, so each part of a
   gallery is seen individually. `MessageFilter.shouldHandle` then drops messages the bot shouldn't answer (in groups:
   only replies, mentions, or targeted commands), and past it `isAccepted` claims the message in `AnsweredMessages`; a
   message already claimed is dropped with a warning, because Telegram hands the same one over more than once — as an
   edit of it, and as a plain redelivery under a fresh update id, which the polling session's own duplicate filter does
   not catch. An ephemeral command — `/tasks` or `/clear` in a group, sent so by a client that knows them as such — has no message id, so `shouldHandle` takes
   it as addressed, `isAccepted` does not dedupe it and the group log skips it; a reply to the bot's ephemeral answer
   is ephemeral itself, and `dispatch` turns any ephemeral message that is not a command into a private note to write
   in the open (`TelegramDelivery.sendForSenderOnly`), since the agent's own delivery is not ephemeral and a public
   answer to a private message would leak it. On the text, caption and album paths a group message `shouldHandle` turned away gets one more look when
   `ADDRESSING_ENABLED` is on: `TelegramBotRunner.acceptance` builds an `AmbientCandidate`
   (`telegram/inbound/AmbientCandidates.kt` — typed text or a caption only, never an edit, forward, command, bot or
   channel post) and asks `agent/addressing/AmbientAddressing`. That puts it to a classifier model of its own only when
   the message names the bot, its author has a turn under way (`AgentRunner.hasTurnUnderWay`), or the bot spoke within
   five minutes and that line is among the six the classifier is shown, read from the group log; nothing else leaves
   the machine. A failure, a timeout, an unreadable answer or a chat over twenty checks a minute all mean no, so it can
   add answers and never take one away, and it costs nothing to a message the bot answers today. A yes is claimed like
   any other message and runs as an ordinary turn with `RequestContext.ambient` set.
3. **Normalize** — text is sanitized (`MessageSanitizer`); voice/audio is transcribed (`VoiceTranscriber` → `stt/`);
   stickers become a metadata prompt; a rich message — which never carries `text` — is flattened back into rich markdown
   (`telegram/inbound/RichMessageText.kt`), both as its own input and when one is quoted in a reply, capped on the way
   in by `TelegramBotRunner.MAX_RICH_MESSAGE_CHARS` — well under the 32768 characters Telegram allows a rich message
   against plain text's 4096, and a different constant from the outbound cap `MessageTools` enforces; replied-message
   context is wrapped in `<reply_context>`/`<user_message>` — the adapter reads the update, and `agent/TurnInput.kt`
   writes those blocks, as it writes every block the request arrives in; current or replied photo, video, and document input becomes
   `AttachedFile`. A reply carries that context whoever wrote the message it answers, the bot's own included, and an
   `author` line says whose it is (`you` for the bot's own). The history that would otherwise carry it belongs to one
   user in one chat, so in a group a reply to something the bot wrote for somebody else has nothing behind it — and no
   history carries bytes, which is why the replied file travels along and "edit this" works against a picture the bot
   drew. A reply that quotes part of a message adds `<quoted_fragment>` right before the request, so the fragment says
   which part was asked about. Text quoted from outside has this prompt's own block tags defused first — the message
   itself, a transcript, a replied-to post, the group's transcript, durable memory, the recap, the sticker catalog — so
   no one's text can end a block early or open one of its own. `xmlBlock` escapes only a closing tag of its own name,
   which is what keeps nesting working, so this is the step that stops a group member forging a block in somebody else's
   turn. `AgentTurns.dispatchToAgent` assembles the agent input and the shorter history input.
4. **Run** — `AgentRunner.handle` joins the conversation's line in `agent/ConversationLocks` and then takes a place
   from `agent/TurnAdmission`. One turn per conversation runs at a time, because a turn reads the history when it
   starts and appends to it when it ends: the next message waits with its typing indicator and starts with the answer
   before it already in its history. Three messages may wait that way, in the order they
   arrived, and the one after that is answered "busy" — or, for an ambient message, turned away without a word, since
   "hold on" dropped into someone else's conversation is worse than silence; the same goes for "overloaded". Admission is the ceiling every conversation shares —
   `MAX_CONCURRENT_TURNS` turns at once, the rest waiting the same way, and only a queue several times that long
   answered "overloaded" instead of queued; a queued turn (a scheduled fire, a pressed button) waits in both rather
   than being refused. The lock is taken **before** the place in every path: a turn that holds a place is running and
   waits for nothing, so nothing can wait in a circle, and a person's line costs everybody else no places.
   It then loads durable memory
   (`agent/memory/MemoryRepository` — the sender's user memory always, plus the group's memory in non-private chats),
   and places it with `<message_context>` immediately before the current request in one user-role turn. That
   metadata also carries
   `last_exchange` — how long ago this user last spoke with Vusan in this chat — but only once the gap is long enough to
   be worth noticing, so ordinary back-and-forth stays free of it. When the message was written before that exchange
   was stored — it waited in the line behind the turn that produced it — the metadata also carries
   `before_your_last_reply` (from `RequestContext.writtenAt`): replayed history alone puts the message after a reply its
   author had not seen yet, and the agent answers it again instead of letting that reply stand. In a group the turn
   also carries `<recent_chat>`: a hard-capped slice of what the chat was saying just before, so a question with no subject ("and what do you think?")
   still has one, and — where the deployment keeps a [diary](#background-and-side-flows) — `<diary>`, the bot's own
   entries about the chat's last three written-up days. `<recent_chat>` leaves out the triggering message and this user's own exchanges with the bot, both of which the
   prompt already carries — the first as the request itself, the second as replayed `user`/`assistant` turns. Other
   people's messages and the bot's replies to *them* stay, since one person's conversation never contains those. An
   ambient turn keeps this user's exchanges too and cuts the slice right before the message (`recentChatSlice`): with
   no mention and no reply, the order of the lines is the only thing that says whether a bare "which one?" follows the
   bot's last reply or somebody else's line in between, and the replayed history carries no such order.
   `<current_time>` and the chat's `<sticker_catalog>` ride in that same user turn rather than in a system message of
   their own: a system message reads as a higher-priority instruction, which is wrong for context assembled out of what
   people sent, and the Messages API has no system role inside the conversation, so `llm/anthropic/AnthropicClient`
   moves every system message into the top-level system field regardless of where it sat. In a group the turn also carries what that chat lets the bot post, read through
   `telegram/ChatProfiles.kt`: one cached `getChat` + `getChatMember` pair yields the chat description, the permissions
   binding a bot that is a plain member (an administrator is bound by none of them, slow mode included), and the
   slow-mode delay. `ChatCapabilities` travels in `RequestContext.chat` and reaches three places — a tool that makes
   something is registered whatever the chat accepts, since with `send` it is a producer first, and checks the chat
   itself before producing for a send the chat would drop (`refusedByChat` in `tools/CallShelf.kt`), so the model
   cannot spend an image generation or a download on something undeliverable and can still make it for a later call,
   while `ToolCatalogFactory` leaves out only what does nothing but post (reactions, polls, GIFs by address); `BotOutbox`
   refuses a queued output the chat does not accept, because that alone proves nothing about the *mixed-output* paths
   (a text-first search queues photos, the sandbox sends whatever files it was asked for), and those tools report the
   refusal to the model rather than claiming a send nobody will see — image search checks first and never queries its
   provider at all; and `<message_context>` names the rest so the agent
   knows why and answers in one message under slow mode. Anything the lookup could not answer counts as unrestricted, since guessing "forbidden" would strip
   real abilities. The runner builds the per-request tool catalog before the turn text, because what the catalog
   defers goes into that text as `<tool_groups>`; `AgentFactory.prepare` then estimates the fixed
   system/tool/current-turn cost — from the visible tools alone, so a group loaded later is spent from the agent
   reserve rather than from the history budget. The history planner reserves room for output, future tool calls, and estimation error,
   then admits only complete interactions. If an older prefix no longer fits or exceeds the configured recent count,
   `LlmConversationCompactor` merges it into the persisted `<conversation_recap>` before `AgentFactory.build` creates
   the `AgentTurn`. Every turn is capped on the way into that recap prompt, and a user turn budgets its
   `<user_message>` first: reply metadata and a quoted fragment are written ahead of the request and can outrun the cap
   between them, so capping from the front alone would recap what the user was replying to and not what they asked.
   An Anthropic model's window comes from the vendor's model list at startup, a compatible server's from that server's
   list where it states one, and an OpenAI model's is assumed to be its generation's; `LLM_CONTEXT_WINDOW_TOKENS`
   supplies what nobody stated or overrides any of them.
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`; tool calls/results are
   recorded for history. Live textual tool results share a cumulative bound derived from the reserved agent-growth
   budget before later LLM calls; the runner opens that bound as a `TurnToolBudget` the loop spends and the
   `checkContextBudget` tool reports, so a turn can narrow a long read instead of discovering the ceiling by getting an
   empty result back. Once a quarter of the reserve is left the run states it without being asked, once, in a note that
   follows the batch of tool results — nothing may come between an assistant's tool call and that call's result. The calls
   of one batch run in order, except that read-only ones standing next to each other (`@Tool(readOnly = true)`: a
   search, a page, a file read) run side by side; results are recorded in batch order either way. Every call also
   takes a place on the turn's shelf (`agent/TurnShelf.kt`) before its run starts, so what one call makes another can
   take: its result reaches the model under a label, `[#3]`, with the files it kept listed under it as `#3/1`, and the
   request's attachments are `#0/1` on. An `AttachedFile` parameter takes a label or `sandbox:<path>`, and so does a
   text parameter that declares `@Arg(takesReference = true)` — opt-in, so a value that merely reads like a reference
   anywhere else arrives as written; the decoder in `tools/ToolSet.kt` resolves both through the call's `CallShelf`, and
   the text is the result whole, not the copy the budget cut for the model, which a cut result says. A call may only take
   what came before it, and one that takes an earlier call of its own read-only run waits for that call. A tool that
   makes a file keeps it with `keepOnShelf` whether or not it sends it, and `send: false` keeps it without delivering
   it. History stores results without their labels, which mean nothing to the next turn. A turn whose own
   pile of results would no longer fit the window has its oldest result batches folded into a stub — under the result's
   own label, since the shelf still holds the whole of it there — before the next
   request, together with the long arguments they answered (`agent/TurnCompaction.kt`), the latest batch and the stored
   history never — so a long build goes on instead of dying on the context limit. The loop
   (`agent/AgentTurn.kt`) guards against flaky models in four ways:
    - a tool call that arrives with no arguments at all for a tool that takes them (flaky models emit empty-arg siblings
      when they try to call tools in parallel) is answered with a validation error instead of being executed, so the run
      stays clean and the follow-up request stays well-formed. A call that provides some arguments and omits a required
      one is answered by the decoder's own complaint, and one that names a tool nothing answers to with the names that
      exist; every one of them is a result the model reads, never a crashed turn;
    - a reply the output ceiling cut short runs none of its tool calls, since the last of them may have been cut with
      it and reads like a whole one: each is answered with the reason, and the model reissues it smaller. A reply the
      model or a provider's classifier declined ends the turn (`ModelRefusal`) — what it holds is not an answer, and
      the same prompt is declined again — and the runner answers with the content-policy reply;
    - a turn that ends having delivered nothing — no `sendMessage`, media, or reaction, and empty assistant text (flaky
      providers return an empty completion after a batch of tool results) — gets one nudge to actually deliver before
      finishing, so a full turn of research does not collapse into silence; when the next call is the last, the
      wrap-up below takes its place, so the nudge never pushes a turn past its limit. An ambient turn is exempt on its first
      reply only (`owesDelivery`): the system prompt lets it end at once, empty and with no tool calls, when the
      message turns out to be for someone else or already answered, while an empty reply after tools is still the
      flaky provider and still nudged;
    - a turn that announced its plan (`announcePlan`) and then ended without a single tool call after it is sent back
      to the work once (`PROMISE_NUDGE`): the announcement reached the chat, so the user is waiting on exactly what it
      promised, and "I'll build it now" followed by silence is the one reading worse than an error.

   It also lands a turn that runs long instead of letting it crash. `AGENT_MAX_MODEL_CALLS` bounds the model calls one
   turn may make, and the last of them is reserved: once a batch of tool results would be answered by that call, the
   results go to a wrap-up request in which no tool may be called — the tools stay defined, since the calls the turn
   made are replayed with them — so the model cannot spend the call on one more search, and
   its answer, written from what it gathered and saying what it could not finish, is queued into the outbox like any
   other message, so it survives even a turn that already reacted or sent something (trailing agent text is otherwise
   dropped as duplicate chatter). Without that, a research turn would die with every search it paid for still
   unanswered.
6. **Collect** — `AgentRunner` persists the produced history as one interaction through `ConversationRepository` while
   it still holds the turn lock. Delivery tools whose payload already became assistant history are omitted; other tool
   events are stored as bounded complete call/result pairs. Only the newest two interactions replay those raw pairs,
   while older recent interactions replay user/assistant text. Summarized raw interactions are pruned by whole
   interaction after the configured count or age. Persisting inside the lock is what makes a concurrent `/clear` safe:
   no caller can append a turn into a history that was just wiped. A run that dies with outputs already queued is
   collected all the same: the queued answer is delivered and stored instead of being replaced by the canned failure
   reply, and the turn does not count as failed — so a scheduled task keeps what it produced rather than paying for the
   whole run again on a retry. A failure with an empty outbox still answers with the canned reply
   (`AgentResult.failed`).
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat, or to the user's private chat when a tool
   requested it, anchoring replies to the original message.
    - **Live progress** — an indicator runs through the whole turn (`telegram/TelegramProgress.kt`, `withLiveProgress`).
      The loop resolves each tool it is about to run to a neutral `ToolActivity` (`agent/ToolActivity.kt`, keyed
      by `@Tool` method references), and the Telegram layer renders it two ways: as a chat action (`chatActionFor`, e.g.
      `upload_photo` while an image generates) and, once there is something to name, as the turn's status message
      (`telegram/TurnStatus.kt`, the words of `Messages.progressLabel` behind an emoji per activity). Only one is on
      screen — both announce the same turn — so the action carries the turn until the status message lands and then
      stands down, and it keeps the turn to itself when there is no status. A tool too fast to read a caption for is
      left unmapped and reads as `null`: plain `typing`, and nothing named. During delivery each item is still preceded
      by the action matching its own content (`botActionFor`).
    - **The status message** — one silent message per turn, the same in every kind of chat, carrying the running line
      and a stop button. Telegram's own surface for a generating agent, `sendMessageDraft`, is accepted for **private
      chats only**, so it could never be half of this; an ordinary message is what a group can have too. It opens lazily
      — on a named activity that has outlasted its grace, or the moment `announcePlan` says something — and, unlike a
      draft or a chat action, it does not expire, so it is written only when something actually changes. The grace is
      per activity (`statusGraceFor`), and it is what decides which turns become a progress UI at all: a job the user
      waits through — a search, a build, a download, a drawing — earns a message after `JOB_GRACE`, while an activity
      that is part of the exchange, vision on a photo or `sendMessage` itself, waits `CONVERSATION_GRACE` and in a
      normal turn never opens one, leaving the chat action to say the same thing the way a person typing does. The
      clock runs from the start of the turn, so a run of quick searches adds up. Once a message is open every activity
      fills it, light or not, since naming the next step is the edit it would make anyway (`showActivity`'s `mayOpen`).
      Its writes are `NonCancellable`, because a send canceled in flight can still have created the message, leaving a
      bubble whose id nobody holds. `finish` deletes it, or, when the model put its own words in it, edits those words
      to stand alone without the running line and without the button; either way that happens in `withLiveProgress`'s
      `finally`, so a turn canceled by `/stop` still takes its bubble off the screen. In a slow-mode group the bot's messages are rationed, so an activity alone never opens one — only words
      the model chose to send do.
    - **Announcing a plan before the work** — every output a tool produces is queued and delivered when the turn is
      over, which for a long turn means the plan arrives after the thing it planned. `announcePlan` (`MessageTools`) is
      the way out: it writes into the live status through `TurnNarrator` — a neutral interface in `agent/`, so no
      Telegram type reaches `tools/` — and then records the text in the outbox as an already `delivered` `OutboxItem`.
      Delivery skips such an item instead of sending it twice, and the history carries it like any other assistant
      text. Its transcript row is written the moment the words are in the chat — `TurnStatus` reports them and
      `AgentTurns` hands them to `TelegramDelivery.recordPostedMidTurn` — not by delivery: stamped at the end, the row
      would sit after messages written while the work ran, and a turn queued behind this one reads the transcript
      before that delivery is over. One announcement per turn (`BotOutbox.hasDelivered`): a second would
      rewrite what the user has already read. A turn with no live status — a scheduled run — queues the words with the
      rest of the reply instead.
    - **A result worth reading early** — `sendMessageNow` (`MessageTools`) posts a message of its own through
      `TurnNarrator.send`, which stays in the chat, and takes the status bubble down and up again so it sits under the
      newest message. It is recorded as a delivered item that is an answer rather than an announcement
      (`BotOutbox.hasAnswered`), so the turn owes no further delivery, and reaches the transcript the same way the plan
      does; a few per turn, and queued like any text when
      nobody is watching.
    - **HTML and its fallbacks** — text and captions go out with Telegram's `HTML` parse mode; `agent/SystemPrompt.kt`
      instructs the agent to use only the supported tags and escape `<`/`>`/`&`. Models still slip in `<br>`, and
      cheaper ones answer in Markdown code anyway, so `TelegramSendFallbacks` repairs what maps onto HTML one to one
      before the send: `<br>`-style tags become real newlines, a fenced block becomes `<pre>` and a backticked span
      `<code>`, with bare `<`/`>`/`&` escaped inside — a message that would have gone out with literal backticks, or
      been rejected whole, arrives formatted. Rejected reply text is re-sent as a `message.html` document (`telegram/delivery/HtmlReplyDocument.kt` — a
      standalone, responsive, light/dark page with a no-script CSP) so the formatting still arrives; a rejected caption
      resends the media captionless and delivers the caption the same way; localized notices fall back to plain text.
    - **Rich messages** — opt-in Bot API 10.1 (`BotOutput.RichMessage`, github-flavored markdown) via the
      `sendRichMessage` tool, resent as a `message.md` document if rejected. Opt-in because some third-party clients
      (e.g. Telegram X) render rich messages as unsupported.
    - **A send that fails after every fallback** — the kind's own fallbacks and the text-as-document one have all
      been tried by then, so `TelegramDelivery` treats the item as not sent: no transcript row is written for it, and a
      comment that was to ride on it as a caption goes out as a message of its own, since the words are the answer and
      the media only what they rode on. A poll has no shape to fall back to and fails the same way.
    - **Gone targets and blocked DMs** — a reply whose target no longer exists is retried without the anchor
      (`DeliveryTarget.withoutReply`); a private chat the bot cannot write to produces a notice in the group instead.
    - **Unreachable chats** — a chat that refuses the bot rather than the payload (kicked, left, blocked, deleted, write
      rights taken away — `TelegramErrors.isChatUnreachable`) is answered differently from every other rejection: no
      fallback can help, so the send cascade stops instead of buying one more rejection per degradation step, the rest
      of the queued outputs are abandoned, and the delivery port answers `DeliveryOutcome.Unreachable` so
      `TaskScheduler` can park the chat's tasks. That outcome type deliberately has no `Partial`: an item refused for
      its own content is retried through the fallback chain and can still land in another shape, so no adapter can
      honestly report one, and a caller branching on it would be branching on a lie.
      A refusal of one output kind ("not enough rights to send photos", or `VOICE_MESSAGES_FORBIDDEN` from a recipient
      who takes voice and video messages only from contacts) is deliberately *not* this, and keeps its normal fallback —
      which is why `isForbidden` matches only a leading `Forbidden:` and not the word wherever it appears.
    - **Forum topics** — every send names the topic it belongs to (`ChatTarget`, carried through the sender and the
      raw builders). A reply anchored to a message would land in the right topic on its own, but nothing else would: a
      scheduled fire, a notice, an item sent after the anchor turned out to be gone, and the live status bubble all
      address the chat directly. The id comes from `Message.forumTopicIdOrNull`, which is `message_thread_id` *only*
      when `is_topic_message` is set — outside a forum the same field identifies a reply chain, and sending with one of
      those is rejected. `ScheduledTasksTable.creatorThreadId` is what lets a task keep firing into its topic after the
      message that created it is gone, and it also rides into the turn as `RequestContext.chat.threadId`, so a
      follow-up that fire schedules inherits the topic instead of being anchored to General.
    - **Rate limits** — consecutive sends are paced (`INTER_MESSAGE_DELAY`) to stay under Telegram's per-chat limit, and
      a send that trips the limit anyway waits the number of seconds Telegram names in `parameters.retry_after` and goes
      again, once (`withFloodWaitRetry`, capped at `MAX_FLOOD_WAIT`). The fallbacks rethrow a 429 rather than degrading
      the payload, the same way they do for an unreachable chat: flood control says nothing about the content, so a
      photo turned into a document would only spend a second rejected request. The retry repeats the whole send because
      each attempt rebuilds its payload — the byte streams the first one consumed cannot be sent twice.
      Upstream, `BotOutbox` coalesces consecutive `sendMessage` text into the trailing bubble while it fits
      (`MAX_TEXT_MESSAGE_CHARS`), so a model that splits one answer into many messages produces few real sends, and caps
      the resulting bubbles (`MAX_TEXT_MESSAGES`) so a looping model cannot flood the chat. Consecutive tracks are
      coalesced the same way, into an `AudioGroup` album of up to ten: one track per tool call would otherwise arrive as
      one message per track, each repeating the reply quote. Anything queued between them ends the run.
    - **Sender split** — `TelegramOutputSender.kt` maps each `BotOutput` kind to a Bot API call and picks the fallback
      wrapping it, `TelegramSendFallbacks.kt` holds the output-kind-agnostic rejection handling (plain-text retry,
      media-to-document, text-as-document), `TelegramRequests.kt` the raw request builders.

## Background and side flows

- **Task scheduler** — `TaskScheduler.launchIn` polls the task store every 30 seconds. Due tasks run through
  `AgentRunner.handleQueued` (waits for the user lock instead of bailing), each fire in a job of its own
  (`common/runInOwnJob`): a fire is registered in `RunningTurns` under the conversation it belongs to, so `/stop` and
  the stop button reach it like any turn, and without a job to be canceled the one they would end is the scheduler's
  own loop. A stopped fire is not a failure — it is not retried, the chat gets the same "stopped" notice a stopped
  turn does, and the recurrence moves on. Fires are delivered through the
  `delivery/OutputDelivery` port, which is what keeps `tasks/` free of any messenger: it addresses a `Destination`
  (chat and optional thread), names the `UserRef` a DM-routed item belongs to, and answers a
  `DeliveryOutcome`. Chat facts come the same way, through `request/ChatProfileLookup`. A task runs with no incoming message behind it, so its `<message_context>` is
  rebuilt from what the task stored — the chat, and who set it up — instead of the live chat flavor, title, and
  description a normal turn carries. Tasks overdue beyond `TaskScheduler.MAX_LATENESS` (e.g. after downtime) get a
  "missed" notice and are advanced/disabled rather than fired. A failed run (`AgentResult.failed`, or a thrown error)
  delivers nothing, so it is repeated up to `MAX_ATTEMPTS` times with a short backoff, the retry prompt telling the
  agent that the earlier attempt delivered nothing; a failed *delivery* is never repeated, since part of the answer may
  already be in the chat. Once the attempts are spent the chat gets a "failed" notice. Either way the task is
  advanced afterward — or deleted, when the recurrence has no fire left — so a persistent error cannot re-fire it on
  every poll tick. A task that has fired for the last time is removed rather than kept switched off: `/tasks` never
  listed one and nothing else reads one. A tick reads every due task
  at once and fires them one after another, which leaves the owner of a task waiting behind a long fire time to pause,
  retime or delete it: each task is read again (`TasksRepository.findDue(id, now)`) at the moment it would fire, by
  the clock of that moment rather than the tick's — judged late by the tick's time, a task behind a long fire would be
  rescheduled to a slot already past and fire twice — and skipped if it is no longer due, and the advance afterward is
  conditional on the fire time the run started from, so a schedule its owner changed meanwhile is not overwritten. A
  stored row whose schedule or zone no longer parses is named in the log and left due rather than fired on a guess. A chat the bot cannot write
  to at all is the exception to that advance: rather than rescheduling one task, `TasksRepository.pauseAllInChat` pauses
  every task in that chat at once, because otherwise each of them would run a full agent turn on every fire and only
  discover at delivery that nothing can arrive. It is reached from either end — the delivery port reporting the fire
  (or even the missed/failed notice) as undeliverable, and `parkTasksOnLostAccess` acting on the `my_chat_member` update
  the moment the bot is removed or silenced. Paused rather than deleted, so the tasks stay listed in `/tasks` and their
  owners can resume them if the bot gets back in. Paused tasks remain stored and count toward the per-user task limit,
  but the due-task query skips them. A task whose owner and chat are outside `ALLOWED_IDS`, or on `BANNED_IDS`, is
  skipped the same way, ahead of the lateness check, so losing access produces no "missed" notices either. Recurrence
  math lives in `tasks/Recurrence.kt`.
- **Maintenance** — `infra/Maintenance` runs a pass of bounded deletes on the way up and every six hours after
  that: conversations past `CONVERSATION_RETENTION_DAYS`, group transcripts past `GROUP_LOG_RETENTION_DAYS` or over
  their row cap, polls past their retention, and diary entries older than a week. Each of these is also pruned where it is written, and that is enough
  for as long as somebody keeps writing — the pass exists for what nobody writes to any more: a conversation the person
  left, a chat the bot still sits in, a poll never followed by another. Every step bounds its own work (a hundred
  conversations or chats a round, the next round taking the rest) and reports what it removed, and a step that throws
  leaves the others to run. `Main` wires the steps, because one of them belongs to a messenger and `infra/` may not
  know that.
- **Self-initiated follow-ups** — `scheduleFollowUp` (`tools/tasks/FollowUpTools`) lets the agent set itself a single
  future turn when the conversation gives it a reason to come back ("ask how the exam went"). It is the one scheduling
  tool that stays visible while the rest wait behind the `scheduled_tasks` group: nobody asks to be checked on, so a
  tool the model would first have to load for something nobody asked for is a tool it never calls. The system prompt
  names it as the one action that needs no request. It is the same scheduler, store, and delivery path as
  `scheduleTask`, narrowed: one-time only, a limit of its own (`MAX_FOLLOW_UPS_PER_USER`) so the agent cannot spend the
  user's task quota, and a `self_initiated` flag on the row. In a group it fires anchored to the message that prompted
  it, and only when that message is gone does it fall back to a "following up with" notice instead of the "scheduled by"
  one, which would misattribute it to the user. The user sees and cancels them through `/tasks` like any other task.
- **Group chat log** — `agent/grouplog/` records what a group says, so a recap can be asked for later. Ingestion is
  `TelegramBotRunner.recordGroupLog`, a detached write that runs before the mention filter and outside private chats;
  the bot's own group messages are recorded from `TelegramDelivery.dispatch` after a send succeeds, skipping anything
  redirected to a DM. Text is collapsed and capped on write (harder for a forwarded post), media is reduced to a short
  label, and no file id is kept. Retention belongs to the maintenance pass below: it drops what is past
  `GROUP_LOG_RETENTION_DAYS` and trims a chat to `GroupLogConfig.maxMessagesPerChat`, taking the chats that have
  something to remove rather than the chats that happen to be busy. On read, `GroupLogReader` quotes the window when it fits the budget derived from
  `liveToolResultMaxChars`; when it does not and the window reaches back into a closed day, it splits by local day,
  replaces each **closed** day with a `GroupLogDigester` recap cached in `group_log_digests`, and leaves the current day
  quoted. A window lying inside today has no closed day to summarize, so it is truncated to its newest entries rather
  than widened to the whole day. Today is never cached — it is still being written to — which is what makes a repeated
  weekly question cost nothing after the first one. Every result leads with the window's exact message count, narrowed
  to one author when one was asked for, because the transcript under it may be only part of the window and a "how many"
  answer must not be a tally of quoted lines. The digest path counts over the day-snapped window it prints rather than
  the narrower one requested, and labels `<today>` with how many of its messages fit.
- **Diary** — `agent/presence/Diary` gives the bot a memory of what a group's days were like, which neither history
  (one person's exchanges with it) nor durable memory (facts somebody asked to be kept) holds. Every fifteen minutes it
  looks for chats people wrote in yesterday that have no entry for that day, and has `DiaryWriter` write one from the
  day's transcript: first person, in the deployment's own personality, with the two entries before it for continuity.
  Only a closed day is written, for the reason only a closed day is digested, and a day of fewer than fifteen messages
  gets none. A day whose writer keeps failing is given up on after three tries. `TurnSurroundings` then puts the newest three
  entries of the past week into every turn in that group as `<diary>` — defused like the transcript they came from —
  and `Initiative` reads the same block. Entries live in `chat_diary`, a week at most (`Maintenance`), and
  `GroupLogRepository.clear` drops them with the transcript they were written from. Unlike a digest, an entry is written
  with nobody having asked, so a whole day of a chat goes to the chat model unprompted: `DIARY_ENABLED=false` is the
  switch, and a chat the allowlist no longer names is never read.
- **Initiative** — `agent/presence/Initiative` is the bot speaking up without being called. Code decides whether to
  look, a model decides what comes of it. Once a minute it takes the chats people wrote in during the last quarter of
  an hour and, for each, checks the gates: outside the quiet hours, one to eight in the morning; a pause drawn at random between half and
  one and a half of thirty minutes has passed since the last look (a chat seen for the first time waits one
  out too, so a restart is not followed by the bot speaking everywhere); at least three messages from people since
  that look; nobody in the chat with a turn running or waiting (`AgentRunner.hasTurnUnderWayIn`) and no line of the
  bot's own in the last five minutes, since either way the conversation already has it — a request a turn is still
  answering reads to a look as one nobody answered, and a line of its own would land beside the answer; and
  something left of the day's budget. A look a gate turned away cost no model call, so it is tried again five to ten
  minutes later rather than after a whole pause. A look that passes sends `InitiativeMind` up to sixty lines of the last six
  hours, the diary block, the chat's sticker shortlist while a line of its own is still open today, how much it has
  already said today, and — from `GroupLogRepository.authorActivity` — the people who used to write here and have not
  for three days. The lines a person wrote since the last look — or since the bot's own last line, when that came later: answering
  someone is having read the chat up to there — carry a number, and a number is the only way to point at a message: message ids are never shown, so a decision cannot reach
  past what the look put in front of it. The answer is one JSON object — `silent`, `react` (one emoji from Telegram's
  free set on one numbered line), `say` (one short plain-text line, optionally a reply to a numbered line) or `sticker`
  (one entry of the chat's `<sticker_catalog>`, the shortlist a turn is shown, optionally under a numbered line; an id
  the catalog cannot resolve sends nothing) — and anything unreadable, failed or slower than ninety seconds is silence.
  What it writes is bounded per chat per day (four lines, a sticker being one, twenty reactions) and its own lines are at least ninety minutes apart, so a lively hour
  cannot take the whole day's count; a decision over either bound is dropped. The line goes out
  through `OutputDelivery.deliverUnprompted`, so it lands in the group transcript like any other bot line and a reply to
  it starts an ordinary turn with that line as `<reply_context>`. A chat that turns the bot away is left alone until a
  restart. State — the last look, the next one, today's counts — is process memory. Every look and every skipped one leaves a log line; see
  [Initiative](configuration.md#initiative).
- **Poll answers** — a vote on a poll the bot put in a group reaches it as a `poll_answer` update, which carries a poll
  id and option numbers and nothing else: not the question, not what the options said, not even the chat. `PollRegistry`
  writes what a sent poll said (`polls`) at the moment `TelegramOutputSender` gets an id back for it, and only where the
  answers can be read back — a poll redirected to a DM, or sent in a private chat, is not kept. The update itself is
  handled in `TelegramBotRunner.recordPollAnswer` and lands in the group transcript through
  `PollAnswer.toGroupLogEntry`, so the agent reads "answered: Kyiv (correct)" beside the messages around it. No
  `allowed_updates` parameter is involved and none should be added: `poll_answer` is already in the default set, and
  naming any type explicitly would silently drop `my_chat_member`, which `BotMembership` depends on.

  Only a **non-anonymous** poll produces these updates at all. `sendQuiz` is non-anonymous by default and reports its
  answers; `sendPoll` is anonymous by default and reports none unless the user asked for a public poll, which is the
  right way round — anonymity is usually the point of an ordinary poll.
- **Sticker catalog** — `telegram/tools/sticker/StickerCatalog` learns which sticker sets a chat uses. The Bot API has no sticker
  search, so a sticker can only be sent from a set known by name: `TelegramBotRunner` taps every sticker in an
  allowlisted chat — including ones the bot is not addressed in, which in a group is its only view of what people
  actually use — records the set and the individual sticker, and pulls the set in whole through `getStickerSet`. No
  message content or sender identity is stored. Pulling a set in is the only expensive step — up to 60 vision calls,
  paid once — so it is gated three times: a set is learned only on the second time a chat reaches for it, no chat may
  pull in more than three new sets a day, and the bot as a whole no more than six, so the worst day is bounded whatever
  the number of chats. None of the gates applies to a set already known from elsewhere, which costs nothing to offer.
  `STICKERS_ENABLED=false` leaves the catalog out entirely, vision or not. A background worker then describes each sticker's thumbnail once through the vision model and caches the result
  by `file_unique_id`. `TurnSurroundings` puts 24 ready-to-send entries into the current user turn, and an initiative look is shown
  the same block: six stickers the chat itself reached for lately, then a draw from everything the chat's sets hold, a
  set the chat uses often weighing more. The draw is fresh for every turn — the block rides in the user turn, outside the
  cached prefix — and leaves out what the bot sent there lately, which `TelegramDelivery` reports back to the catalog
  and the catalog keeps in memory. A group that forbids stickers gets no index at all, matching the catalog: the tools are gated on the same
  capability, so a shortlist there would be an offer with no tool behind it. `searchStickers` searches the descriptions
  and emoji across that full chat-scoped collection, while `sendSticker` accepts only an id belonging to it and resends
  the matching `file_id`. Without a vision runtime the catalog is never constructed and neither tool is registered,
  matching the vision tools. A sticker the model refuses to describe, or one that repeatedly fails, is counted out after
  `describe_attempts` so it neither enters the index nor blocks the queue behind it. The same worker re-reads each set a
  day after it was last checked: a `file_id` is only a handle and a set's owner can edit or delete it, so stickers that
  disappeared are dropped, changed handles are refreshed, and a set Telegram reports as gone (`STICKERSET_INVALID`) is
  forgotten entirely. Only that specific answer counts as gone — any other failure backs off instead of discarding
  descriptions already paid for. A send Telegram rejects for a bad `file_id` is fed back through `TelegramDelivery`'s
  `onStickerRejected` hook, which only marks that set for an early re-read: a send also fails for reasons that say
  nothing about the sticker (a chat where stickers are restricted, a rate limit), and the catalog is shared by every
  chat, so what gets deleted is still decided by asking Telegram about the set.
- **Task menu** — `/tasks` bypasses the LLM and asks `TaskMenuHandler` to render the caller's enabled tasks. Private
  chats show all of that user's tasks; groups show only their tasks created in that chat, as an ephemeral message for
  the caller alone (the open menu when Telegram refuses one: a client that does not yet know the command is ephemeral
  sends it plain, and only an administrator may answer a plain message for one person's eyes), whose buttons then edit
  it through `editEphemeralMessageText` — `EditableMessage` in
  `delivery/TelegramRequests` is the address a callback carries, ephemeral or not. Callback data carries the menu
  owner, every action checks ownership and group scope, and Telegram is always sent an `answerCallbackQuery`. Since
  the per-user cap is a constructor argument, the menu renders only as many tasks as fit Telegram's message limit and points
  at plain language for the rest; a rejected send falls back to the generic error reply rather than silence.
  Pause/resume edits the menu in place; cancel first renders a delete/back confirmation. Resuming an overdue recurring
  task advances it to the next future occurrence, while an overdue one-time task stays paused. The agent-callable
  `pauseTask` and `resumeTask` tools use the same repository operations and resume calculation, so plain-language
  requests match the button behavior. `editTask` can independently replace the prompt, title, schedule, or timezone
  while preserving the active/paused state; changing a cron timezone without replacing the expression recalculates its
  next occurrence. In groups, `listTasks`, edit, pause, resume, and cancel share the menu's current-chat scope instead
  of exposing tasks from private or unrelated chats.
- **Stopping a turn** — `/stop`, or the stop button on the turn's status message, cancels whatever the caller's
  conversation is running: the model call, the tool inside it, and everything that tool started, since all of them are
  children of the turn's job. It is the one path that must not take the conversation lock, because the turn it
  interrupts is holding it — so `AgentRunner` keeps each turn's `Job` in a small register (`RunningTurns`)
  alongside the lock, keyed the same way. A person's own messages are in it from the moment they join the line, so a
  stop takes the waiting ones along instead of letting the next start; a scheduled fire is in it only once it has
  actually started. Each interrupted turn reports itself rather than the command doing it: on cancellation it replies with the stopped notice under
  `NonCancellable`, and the status message closes itself on the way out. `/stop` answers only when there was nothing
  running. The button reaches the same `AgentRunner.stop` through `CallbackRouter` and `TurnStopHandler`, and exists
  because in a group the typed command has to be addressed (`/stop@bot`) to be seen at all — it sits on a message
  anyone in the chat can press, so the turn's owner travels in the callback data and a press by anyone else is refused.
  Work outside the process outlives the cancellation — a sandbox command keeps going on its own machine until its
  timeout, and the model can list and cancel those through the sandbox tools.
- **Direct history clear** — `/clear` bypasses the LLM, deletes the caller's conversation history **in the chat the
  command was sent from**, and sends a localized confirmation. Their history in other chats, and everyone else's in this
  one, are untouched: the wipe is as narrow as the conversation it belongs to, which is what keeps `/clear` in a group
  from destroying context that is not the caller's. It deliberately leaves long-term memory and scheduled tasks
  unchanged. The clear also advances that conversation's persisted history revision, which invalidates unanswered
  choices created before it. The command goes through `AgentRunner.clearConversation` so it waits for the
  conversation's turn lock; a turn already running would otherwise persist itself after the wipe. The persisted
  semantic recap is deleted with the raw transcript.
- **Agent-created inline choices** — `askWithButtons` enqueues a plain-text question plus two to ten answer buttons.
  Callback data carries the intended user id, history revision, option index, and the id of the user message the
  question was asked about; the labels remain in Telegram's message keyboard, while the current revision lives in
  `conversations`, so an unanswered choice survives a bot restart but becomes unavailable after the conversation it
  was asked in is cleared — a clear in another chat leaves it usable. `InlineChoiceHandler` verifies ownership and the
  revision, atomically claims the message, replaces its keyboard with the selected label, answers the callback, and
  wraps the question/selection as `<inline_choice>` for a queued `AgentRunner` turn. The question and its options are
  persisted as assistant history. The selection turn then runs as if it came from the message that started the exchange:
  that is what the answer replies to, what a reaction lands on, and what a task created by the selection is anchored to
  when it fires. A question asked by a turn with no message of its own (a selection answering an earlier question, a
  fired task) carries no such origin, and its answer replies to the choice message instead. A selection arrives as a
  callback with no message of its own, so an attachment the question was asked about ("edit this photo" → "which
  style?") would be gone by the time the answer runs. `AgentTurns` parks the turn's `AttachedFile` in the handler
  whenever the turn queued a choice, and clears that slot on any other turn; the selection turn picks it back up and
  re-announces it as `<attached_file>`.
- **History compaction** — `agent/conversation/ConversationPlanner` picks the history a prompt carries:
  `ConversationPlan.planConversation` token-budgets complete recent interactions, and `LlmConversationCompactor` rewrites the previous recap plus the next omitted interaction prefix into a
  standalone semantic recap and advances a database checkpoint only after that model call succeeds. It runs at most once
  per turn, because it is an extra LLM round trip in front of the user's reply; a prefix that still does not fit stays
  out of the prompt and gets its own recap on a later turn. Failed compaction never deletes source rows. Raw transcript
  retention and model-visible context are deliberately separate. The recap is injected at user priority so mixed
  user/assistant history is not mislabeled as an assistant instruction. An initial context-overflow failure retries once
  with recap only, but never after a tool ran, which avoids duplicated actions.
- **Live tool-result budget** — `ContextWindowPolicy.liveToolResultMaxChars` caps everything the tools return during one
  run, converting the agent reserve back to characters at the same ratio `estimateTokens` reads them. It scales
  with the window on purpose: a fixed ceiling starves a large-window model, since a single full-length YouTube
  transcript would consume the whole run and leave later tool results with nothing. What is left of it during a run
  lives in `agent/TurnToolBudget.kt`: the loop charges each result against it, `checkContextBudget`
  (`tools/context/`) is how the model reads it before deciding how much to ask for, and `TurnToolBudget.report()` is the
  single wording both that tool and the run's own low-reserve notice state it in.
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
  loop; a model that does not reason — the gpt-4 family, read off the id — is asked for none and takes no effort. `anthropic` asks for `thinking: adaptive` with `block_binding: drop_block` under its beta header — a thinking
  block is bound to the tools and messages before it, and `loadTools` widens the tool list mid-turn, so an
  account the API holds to that check gets the stale block dropped rather than a 400 — sends the configured
  `output_config.effort`, the model's whole output ceiling as `max_tokens`, and two cache breakpoints: the request-level
  one the API places on the last block, which every iteration of the loop reads back, and an hour-long one on the
  system block, which the next turn — whose history is replayed from storage in another shape — still reads, and which
  outlives the quiet stretches between a chat's messages. Every call is streamed and folded back into one message
  (`llm/anthropic/AnthropicStream`), as the vendor's SDKs do at a ceiling this size, so a stall trips the socket
  timeout where a connection idling through a long think would be dropped at the API's edge. A Claude model from
  before 4.6, which the API serves under a dated id, gets neither thinking nor an effort. `openai-compatible` speaks
  either OpenAI API under `LLM_BASE_URL` (`LLM_OPENAI_ENDPOINT`), sees images only when the server's model list says
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

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client → (only with
a model on `codex` — the chat, its fallback or a role) build the `CodexAuthStore` → preflight every configured model
(`config/ModelPreflight`): the Codex one proves the ChatGPT session works and fills the context window in from the
account's model catalog, an OpenAI one is confirmed against the vendor's list, an Anthropic one also brings its window
and what it takes back, and a compatible one reads whatever its server's list states — whether it sees, its window, its
efforts — so a typo fails here
→ create the LLM runtime, whose client everything downstream then shares — wrapped in `llm/FallbackLlmClient` when
`LLM_FALLBACK_PROVIDER` names a second runtime, so a spent subscription hands every call to it until the deadline its
refusal named → build repositories, context policy, conversation compactor, the
Telegram client and its `BotProfile` — one `getMe` call shared by the runner, which matches mentions against it, and `AgentFactory`, which puts the handle in the system prompt → (only when image
generation or `ELEVENLABS_API_KEY` is configured, the two things that use it) `resolveSelfImage`
(`tools/imagegen/SelfImage.kt`), which reads the reference photo self-portraits and round video messages are drawn from:
`SELF_IMAGE_FILE` when set, otherwise whatever avatar loader startup hands it — for Telegram, one
`getUserProfilePhotos` on the bot's own id (`telegram/BotAvatar.kt`), and a failure there is a warning rather than a
failed startup → (only with a vision runtime) the `StickerCatalog`, then `TelegramToolSets` over it and the client,
`ToolCatalogFactory`, `AgentFactory`, `AgentRunner` → create `TaskMenuHandler` and `InlineChoiceHandler`, and
optionally enable voice transcription → start `TelegramBotRunner`, which builds its own `AgentTurns` and
`CallbackRouter` over those, and launch `TaskScheduler`, the sticker description worker and — where switched on — the
`Diary` and `Initiative` loops, then block on the runner job
until shutdown (closing the executor, HTTP client, and DB in `finally`).

Public URL downloads have their own `createPublicHttpClient` (`infra/PublicHttp.kt`), also closed at shutdown. Its
OkHttp DNS resolver supplies only validated public addresses to the actual connection; literal IPs are guarded
separately because OkHttp bypasses DNS for them. Proxies and automatic redirects are disabled. `FileDownloadClient` owns
bounded streaming and explicit redirect validation, shared by file downloads, image search and Telegram channel
pages/images. Do not give these downloaders the internal-service HTTP client or replace connection-time enforcement with
a separate DNS preflight.

What that leaves in the log, in order: a `Starting Vusan <version>` banner (read from the jar manifest, `dev` on a
classpath run), then whatever the Codex preflight and the optional-tool checks have to say, then one summary block from
`logStartup` just before the runner starts — the LLM line (provider, model, reasoning effort, service tier), context
window, vision, database file, enabled tools. `TelegramBotRunner` closes it with the bot's own handle and
the allowlist.

The runner's first act is `publishCommandMenu` (`telegram/CommandMenu.kt`): a `setMyCommands` call per `Language`, so
the menu Telegram shows follows `dispatchText` without an operator step. It writes the same list BotFather's
`/setcommands` edits, which means a manual edit there is replaced on the next start. A rejected call is a warning, not a
failed startup — an out-of-date menu is not worth refusing to serve over.

Registration also wraps the session's `getUpdates` generator, so every poll cycle beats a `Heartbeat` (the `com.helltar:heartbeat` library), which keeps
`/tmp/health` fresh for as long as the loop turns; the image's `HEALTHCHECK` reads nothing but that file's age, and the
runner cancels the heartbeat when it shuts down. The hook belongs on the generator rather than the update consumer
because the session skips the consumer entirely when a batch comes back empty — a bot nobody writes to would otherwise
look dead within minutes. Freshness follows the loop rather than whether Telegram answers, so a brief outage upstream
does not mark every bot unhealthy over something a restart cannot fix — while a long one does, once the session's
backoff decays to a 15-minute retry interval and a restart becomes the thing that clears it. See
[Health check](configuration.md#health-check).

## Sandbox

The sandbox is a [Regolith](sandbox.md) server: a separate project with its own deployment, which
runs the commands and confines them. This repository holds only the client —
[`tools/sandbox/SandboxClient.kt`](../src/main/kotlin/com/helltar/vusan/tools/sandbox/SandboxClient.kt), the one
file that uses Regolith's Kotlin SDK, which is what speaks the `/v1` API. Docker, homes and network policy never
enter the bot's request flow.

Each `userId` is an alias on that server — `telegram:<userId>` — and the sandbox it stands for is the same in
every chat. The server makes the id everything is addressed by, and the bot keeps none of it. Conversation
history still uses `(userId, chatId)`; only the files and the commands running in them are shared.

- **`SandboxClient`** — `sandboxOf` gives a turn one handle for the person's sandbox, which asks the server for
  it on first use, created if it has none; the sandbox and site tools of that turn share it, so a reset by one is
  seen by the other. A command is an exec: it is started, then its output is read from a byte offset until the
  command ends, a page comes back empty or the call's ten seconds are up — each page carrying the exec as it
  stands, so no second request asks for its status — and the model continues from `nextOffset`. A file is read within the call's remaining transfer budget, which the
  server refuses to exceed before sending any of it. Refusals carry the server's error `code`, which decides
  what the model is told — capacity reads as "try again" with the wait the server asked for, an unavailable
  server as its own reason plus that wait, everything else as the server's own sentence; no answer at all reads
  as temporarily unavailable.
- **`SandboxTools`** — the model-facing surface: run a command and read its output, cancel it, write, read and edit
  a file, reset the sandbox, send files out. It copies each of
  the turn's files into a directory of the turn's own before the first command that might want them, and renders a
  command as text the model can act on — the exit code, and the session limit that explains it
  when the memory or process cap is what killed it. Before every call that writes or runs, `TurnShelf.copyToSandbox`
  brings `turns/<yyMMdd-HHmmss>/` up to date: the request's attachments as `00-1-<name>` on the first copy, every file a
  call kept as `05-1-<name>`, and the text of the answers their tools mark `@Tool(copiedToSandbox = true)` — searches,
  pages, transcripts, what vision saw — as `03-<tool>.txt`. The first copy of a turn also removes all but the last three
  turns' directories: a turn often picks up the one before it, and the rest would only fill a home of a fixed size. The
  mark is opt-in on purpose: the group log, tasks and memory never reach a home that outlives the turn and can be
  published, unless the model writes them there itself.
- **What the bot does not decide** — the sandbox image, memory, home size, idle stop, retention and network
  policy all belong to the server. The bot reads `GET /v1/info` for the limits it must respect, and trims a
  requested timeout to that ceiling instead of keeping a copy of the number.
- **What a person keeps** — their home, until the server's retention window passes or `resetSandbox` deletes
  the sandbox; while their site is up, retention leaves both alone. Processes do not outlive an idle stop;
  files do.

See [the sandbox guide](sandbox.md) for behavior, setup and limits.

## Publishing to the web

A person's site is published by the same Regolith server that holds their sandbox — this repository holds no site host.
[`tools/sites/SiteTools.kt`](../src/main/kotlin/com/helltar/vusan/tools/sites/SiteTools.kt) is the whole of it: `publishSite`
sends one directory's path, the server snapshots it out of the sandbox and answers with the address, and `siteStatus` and
`unpublishSite` read and remove it.

- **One site per person**, because a site belongs to the sandbox it was published from, and that sandbox is the
  person's in every chat.
- **The bot never builds the URL, and never learns how it was chosen.** The server picks an address that says nothing
  about the sandbox or the person, keeps it while the site is up, and hands it back from the publish call.
- **The one mistake worth a warning**: a directory with no `index.html` at its top publishes fine and its link then
  opens nothing. The server reports it as `hasIndex` on the publish answer, and the tool says so rather than handing
  over a dead link; nothing here lists the directory to find out.
- **Whether publishing exists at all** is the server's answer, not a setting here: `GET /v1/info` reports it, and a server
  without a pages role refuses the call in its own words.

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom | Start here |
|---|---|
| The same message is answered twice, or editing one to add the mention does nothing | `TelegramBotRunner.startsTurnOnEdit` (what an edit must pass to start a turn) + `TelegramBotRunner.isAccepted`/`AnsweredMessages` (one turn per message, per-process, empty after a restart) |
| Vusan ignores a message entirely | `TelegramBotRunner.passesAllowlist` and `request/AccessPolicy.kt` (the `ALLOWED_IDS` allowlist and the `BANNED_IDS` ban list, both platform-qualified, applied on the polling loop), then `telegram/inbound/MessageFilter.kt` (`shouldHandle` — group reply/mention rules) |
| Vusan answers a group message nobody tagged it in, or does not answer one that named it | `ambient verdict` lines in the log (gate, verdict, latency, never the text), then `agent/addressing/AmbientAddressing.kt` (the gate, the windows, the rate) + `LlmAddressingClassifier.kt` (the measured wording) + `telegram/inbound/AmbientCandidates.kt` (what is never asked about) |
| Container says `Up` but the bot answers nothing | the `com.helltar:heartbeat` library (the `/tmp/health` freshness signal, and the `ERROR` logged once when polling stalls) + `TelegramBotRunner.start` (the `getUpdates` generator hook that feeds it) |
| Reply says "still working on your previous request", or a second message is answered only after the first | `agent/ConversationLocks.kt` — one turn per conversation, three waiting behind it (`AgentRunner`), the next refused |
| A message goes unanswered after a restart or deploy, or one is answered twice | `telegram/UpdateSpool.kt` (what is kept, what is replayed, and `SPOOL_RETENTION`) + `telegram/TelegramBotRunner.kt` (the blocking spool write in the poll callback, and `settle` on pickup) + `telegram/AnsweredMessages.kt` (the one-turn-per-message claim) |
| Reply lands in the wrong chat, loses its reply anchor, or DM redirect misbehaves | `telegram/delivery/TelegramDelivery.kt` (routing/anchor/private-redirect *policy*) |
| Formatting renders wrong, message rejected, or media falls back to document/text | `agent/SystemPrompt.kt` (allowed HTML tags the agent emits), `telegram/delivery/TelegramOutputSender.kt` (which call and which fallback each output kind gets), `telegram/delivery/TelegramSendFallbacks.kt` (the fallback *mechanism* itself), `telegram/delivery/TelegramErrors.kt` (which provider errors trigger a fallback) |
| Vusan floods a chat or stalls on Telegram 429 over a long multi-message reply | `outbox/BotOutbox.kt` (text and album coalescing + `MAX_TEXT_MESSAGES` cap) + `telegram/delivery/TelegramDelivery.kt` (`INTER_MESSAGE_DELAY` pacing) + `telegram/delivery/TelegramSendFallbacks.kt` (`withFloodWaitRetry`, and the `MAX_FLOOD_WAIT` ceiling on what a turn will sit through) |
| A reply, a notice or a scheduled fire lands in a forum's General instead of the topic it belongs to | `telegram/delivery/TelegramRequests.kt` (`ChatTarget`, and which builders name the topic) + `telegram/inbound/MessageMetadata.kt` (`forumTopicIdOrNull`, and why `is_topic_message` decides) + `tasks/ScheduledTask.kt` (`creatorThreadId`) |
| A specific tool misbehaves | `tools/<feature>/<Feature>Tools.kt` for the tool surface, plus its `<Feature>Client.kt` for the external call |
| Vusan will not hand a file from the chat back, or sends it under the wrong name | `telegram/tools/ChatFileTools.sendChatFile` (the `file_id` path and `chatFilename`) + `telegram/TelegramApi.downloadFileById` (`getFile`, and the 20 MB limit on what Telegram serves a bot) |
| A command times out, says the sandbox is busy, or its output is cut short | `tools/sandbox/SandboxClient.kt` (the SDK calls, the refusals they turn into, and the output paging), then `tools/sandbox/SandboxTools.kt` (what the model is told); anything below that is the Regolith server's own log |
| A sandbox cannot reach the internet, reaches something it should not, or loses a background process | The Regolith server: its network policy and its guards. Nothing here configures either — the bot only says whose sandbox it wants |
| A sandbox loses files, or someone sees another person's | `request/RequestContext.personKeyOrNull` (the sender key a sandbox is filed under on the server; no address is built from it) and `telegram/inbound/MessageMetadata.toSenderContext` (the shared accounts that get nothing of their own) |
| Publishing a site fails, or the link shows nothing | `tools/sites/SiteTools.kt` (the missing `index.html` warning and what the model is told), then the Regolith server's own log: it owns the snapshot, the caps and the serving |
| A published page still serves its old files, or a site nobody wants is still up | the Regolith server owns the site: its releases, its caching and its takedown. `tools/sites/SiteTools.kt` only asks |
| Wrong language in a canned reply (busy/error/voice/start/task menu) | `i18n/Language.kt` (`ofText` on the message, `fromCode` on the client) + `telegram/inbound/MessageMetadata.kt` (`Message.language`) + the `i18n/*Messages.kt` files (the strings) |
| A turn's plan reaches the chat only after the work it announced, or arrives twice | `tools/message/MessageTools.announcePlan` (the tool and its one-per-turn rule) + `telegram/TurnStatus.kt` (`say`, and what survives `finish`) + `outbox/BotOutbox.kt` (`recordDelivered`, `hasDelivered`) + `telegram/delivery/TelegramDelivery.dispatch` (skipping an item already in the chat) |
| The typing indicator or the turn's status message is wrong, stale, or missing | `telegram/TelegramProgress.kt` (both tickers, and `statusGraceFor`, the per-activity gate deciding which turns get a message at all) + `telegram/TurnStatus.kt` (the message itself, the emoji beside each activity, its stop button, and how it ends) + `agent/ToolActivity.kt` (which tool means what) + `i18n/Messages.progressLabel` (the words) + `telegram/delivery/TelegramDelivery.chatActionFor` (the action) |
| A long research turn ends in the generic error reply or is answered mid-way | `agent/AgentTurn.kt` (`maxModelCalls`, `outOfModelCalls` and the wrap-up that lands the turn) + `agent/AgentRunner.kt` (delivering what the outbox holds when a run fails) |
| A long build dies on the context limit, or the model loses track of what it wrote earlier in the turn | `agent/TurnCompaction.kt` (`foldedToFit`: which result batches are folded, and which arguments dropped) + `agent/AgentTurn.kt` (`foldToFit`, run before every request) + `agent/AgentFactory.kt` (the ceiling, from the context budget) |
| The bot announces what it will do and then goes quiet | `agent/AgentTurn.kt` (`PROMISE_NUDGE`, and `record`, which notes what ran after `announcePlan`) + `agent/SystemPrompt.kt` (the contract line that says an announcement is followed by the work) |
| Two searches in one batch ran one after the other, or a tool ran alongside one it should have waited for | `tools/ToolSet.kt` (`@Tool(readOnly = true)`, the only thing that lets a call run beside its neighbors) + `agent/AgentTurn.kt` (`executeBatch` and `runs`) |
| An interim message never arrives, arrives twice, or leaves the status bubble above it | `tools/message/MessageTools.sendMessageNow` + `telegram/TurnStatus.kt` (`send`, which posts it and moves the bubble) + `outbox/BotOutbox.kt` (`recordDelivered` with `announcement = false`, `hasAnswered`) |
| A spent subscription still ends turns in "come back later", or the bot never returns to it | `llm/FallbackLlmClient.kt` (the outage deadline, the single probe back) + `agent/ProviderErrors.providerOutage` (the patterns and the reset time read from the body), then `LLM_FALLBACK_*` in [`configuration.md`](configuration.md#a-second-provider-behind-the-first) |
| The reply to a failed turn says nothing about what the provider did | `agent/AgentRunner.providerErrorReply` (which error body earns which canned reply: a content-policy refusal, a spent usage limit, a dead key, a 429/503 overload) + `i18n/Messages.kt` (the strings) |
| You need to see exactly what the model was sent this turn | `agent/PromptDump.kt` (the whole request rendered per message) — `agent/AgentTurn.kt` renders it before every model call and is switched by the `PromptDump` logger in [`logback.xml`](../src/main/resources/logback.xml) |
| Vusan forgets context or the history recap looks wrong | `agent/conversation/ConversationPlanner.kt` (one recap per turn, and what a failed or raced recap leaves) + `agent/conversation/ConversationPlan.kt` (budget/selection) + `agent/conversation/ConversationCompactor.kt` (semantic recap) + `agent/conversation/ConversationRepository.kt` (storage/checkpoint) |
| Nobody's answers to a quiz reach the agent, or the wrong option is named | `telegram/PollRegistry.kt` (what a sent poll stores, and for how long) + `telegram/inbound/GroupLogEntries.kt` (`PollAnswer.toGroupLogEntry`) + `tools/quiz/QuizTools.kt` / `tools/poll/PollTools.kt` (`isAnonymous`, which decides whether Telegram reports votes at all) |
| A group recap misses messages, or `readGroupLog` returns too little | `telegram/TelegramBotRunner.recordGroupLog` + `telegram/inbound/GroupLogEntries.kt` (what gets recorded at all), then `agent/grouplog/GroupLogReader.kt` (window budget, day split, digest cache) and `agent/grouplog/GroupLogRepository.kt` (retention and the per-chat row cap) |
| Vusan misreads what "that" refers to in a group, or parrots the group's chatter | `agent/TurnSurroundings.recentChatFor` (the `<recent_chat>` slice and its caps) + `agent/SystemPrompt.kt` (the `<recent_chat>` contract) |
| A channel recap misses posts, quotes the wrong text, or costs too much vision | `tools/tgchannel/TelegramChannelReader.kt` (the `?before=` walk, the window cutoff, the size budget, and which posts get vision) + `tools/tgchannel/TelegramChannelParser.kt` (own text vs the quote of a replied-to post, reactions, media kinds) |
| Vusan speaks up in a group too often, too rarely, or at the wrong moment | `initiative look` and `initiative skip` lines in the log (what it decided and why, or which gate kept it out), then `agent/presence/Initiative.kt` (the gates, the pause, the day's budget) + `agent/presence/InitiativeMind.kt` (what it is told it may do, and what counts as a readable decision) |
| Vusan does not remember yesterday in a group, or remembers it wrong | `diary entry written` lines in the log, then `agent/presence/Diary.kt` (which day is written, the fifteen-message floor, how many entries a turn is shown) + `agent/presence/DiaryWriter.kt` (the instructions) + `agent/SystemPrompt.kt` (the `<diary>` contract) |
| Voice/audio not transcribed | `telegram/inbound/VoiceTranscriber.kt` + `stt/OpenAiWhisperClient.kt` (needs `OPENAI_STT_API_KEY`); for a video's sound `tools/vision/VideoAudioTranscriber.kt` |
| Vusan cannot see what is in a video | `tools/vision/VisionTools.kt` (`describeVideo` guards and the preview-frame fallback), `tools/vision/VideoVisionClient.kt` (frames + transcript prompt), `tools/vision/VideoSampler.kt` (ffmpeg), `telegram/inbound/ReplyContext.kt` (which media becomes an `AttachedFile`) |
| Web search picks the wrong provider, or results are thin | the `@Tool` description text that ranks them: `tools/tavily/TavilyToolDescriptions.kt` (`webSearch`, the default), `tools/searxng/SearxngToolDescriptions.kt` (`metaSearch`, the fallback) and `tools/codexsearch/CodexSearchToolDescriptions.kt` (`answerFromWeb`, a researched answer on the ChatGPT plan) |
| A linked page reads as empty, as navigation, or in the wrong alphabet | `tools/page/PageReader.kt` (which elements are dropped, where the content root is looked for, the charset the download declared) + `tools/tavily/TavilyToolDescriptions.kt` and `tools/page/PageToolDescriptions.kt` (Tavily's `extractPageContent` reads first, `readPage` is the fallback) |
| Image search sends nothing, or sends irrelevant pictures | `tools/images/ImageSearchDelivery.kt` (candidate retries, size caps, media group) + `tools/images/ImageDownloadClient.kt` (user agent, format/dimension checks); for relevance, `SearxngTools.IMAGE_ENGINES` and `TavilyTools.imageExcludedDomains` |
| A selfie shows a stranger instead of the bot's avatar | `tools/imagegen/SelfImage.kt` (which reference photo is read at startup, and the prompt that keeps the face while dropping the rest of it) + `tools/imagegen/ImageGenToolDescriptions.SELF_PORTRAIT` (whether the model sets the flag at all) |
| Vusan sends a voice message instead of a round video, or offers no round video at all | `tools/voice/VideoNoteTools.kt` (synthesize → render → outbox, and the voice fallback when the render fails) + `tools/voice/VideoNoteRenderer.kt` (the ffmpeg graph; the waveform box stays inside the circle Telegram crops to) + `tools/ToolCatalogFactory.kt` (needs `ELEVENLABS_API_KEY` and a `SelfImage` reference photo; `can_send_video_notes` is checked by the tool at the send) |
| Vusan answers about a whole message when the user quoted one part of it | `telegram/inbound/ReplyContext.kt` (`quotedFragmentOrNull`, what the sender selected) + `agent/TurnInput.kt` (the `<quoted_fragment>` block, and when it is left out) + `agent/SystemPrompt.kt` (what the block means) |
| Vusan does not know what a reply is about, or cannot edit a picture it made itself | `telegram/AgentTurns.kt` (the reply summary and replied file are built for every reply) + `telegram/inbound/ReplyContext.kt` (`replySummaryOrNull`, who the `author` is, `repliedAttachedFileOrNull`) + `agent/TurnInput.kt` (the `<reply_context>` block itself) |
| A rich message reads as empty, `unknown`, or loses its structure | `telegram/inbound/RichMessageText.kt` (block tree → rich markdown), then `MessageMetadata.contentTypeName`/`textSnippetOrNull` and `ReplyContext.repliedTextOrNull` |
| Scheduled task fires late, not at all, or reports "missed"/"failed" | `tasks/TaskScheduler.kt` (polling, lateness, retries) + `tasks/Recurrence.kt` (next-run math) |
| A chat's tasks all went paused on their own, or one keeps firing into a chat the bot was removed from | `telegram/BotMembership.kt` (the `my_chat_member` path) + `telegram/delivery/TelegramErrors.kt` (`isChatUnreachable`) + `tasks/TaskScheduler.kt` (`parkTasksOfUnreachableChat`) |
| A tool is missing in one group but present elsewhere, or a chat restriction is stale | `telegram/ChatProfiles.kt` (`capabilitiesOf`, the cache and its `forget`) + `tools/ToolCatalogFactory.buildCatalog` (what is still gated at registration: reactions, polls, GIFs) + `telegram/tools/TelegramToolSets.kt` (the sticker gate) + `tools/CallShelf.kt` (`refusedByChat`, what a producing tool answers at a send the chat refuses) |
| The model answers that it cannot speak, schedule or publish something it has tools for | `tools/ToolCatalog.kt` (which groups are deferred, and the `loadTools` menu) + `agent/TurnPrompt.kt` (`<tool_groups>`, the menu it reads) + `agent/SystemPrompt.kt` (the rule that sends it to `loadTools`) + `agent/AgentTurn.kt` (`run`, which reads the visible tools afresh for every request, so a group loaded mid-turn is offered from the next one) |
| Tool results come back truncated or empty part-way through a turn | `agent/ContextWindowPolicy.kt` (how large the reserve is for this model) + `agent/TurnToolBudget.kt` (what is left of it) + `agent/AgentTurn.kt` (`boundedToolText`, which truncates and then omits) |
| A conversation loads the same group on every turn, or keeps offering one it no longer uses | `tools/LoadedToolGroups.kt` (per-scope memory, its cap and its LRU order) — it is process memory, so a restart empties it |
| `/tasks` or a plain-language task pause/resume/cancel fails | `telegram/callback/TaskMenuHandler.kt` (rendering, ownership, callbacks) + `tools/tasks/TaskTools.kt` (agent path) + `tasks/TasksRepository.kt` (shared scoped state changes) |
| `/stop` does not stop anything, or a turn leaves its status message on screen | `agent/RunningTurns.kt` (what is registered and canceled) + `agent/AgentRunner.kt` (`stop`, and the lock the command must not take) + `telegram/AgentTurns.kt` (the notice on cancellation) + `telegram/TelegramProgress.kt` (closing the status on the way out) + `telegram/callback/TurnStopHandler.kt` (the button and whose turn it may stop) |
| `/clear` reports success but history survives | `agent/AgentRunner.kt` (`clearConversation` and the turn lock that also guards the append) + `agent/conversation/ConversationRepository.kt` (shared storage operation) |
| An agent choice button does nothing, repeats, reaches the wrong user, loses the photo, or its answer replies to the bot's own question | `tools/choice/InlineChoiceTools.kt` (tool contract) + `telegram/callback/InlineChoiceHandler.kt` (callback ownership/consumption, origin message id, parked attachment) + `telegram/AgentTurns.kt` (the follow-up turn and its reply anchor) |
| An env var has no effect | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example) |
| Model / provider / request-timeout selection, or prompt-cache misses | `config/<Vendor>Provider.kt` (one provider's runtime: client, model, options — and its startup check of the model against the vendor) + `config/LlmRuntime.kt` and `config/ModelPreflight.kt` (the dispatch to it) + `llm/openai/OpenAiPromptCaching.kt` (GPT-5.6+: implicit caching with an explicit breakpoint on the system prefix, and none for a prompt that never repeats) + `llm/anthropic/AnthropicClient.kt` (the request-level breakpoint and the hour-long one on the system block); `cacheReadTokens` and `cacheWriteTokens` on each turn's usage line say what was read and written |
| "Sign in again" replies, ChatGPT-subscription auth, or a rejected `LLM_MODEL` on `codex` | `llm/codex/CodexAuth.kt` (token load/refresh/persist) + `llm/codex/CodexCatalog.kt` (which models the plan offers) + `llm/codex/CodexClientVersion.kt` (the CLI version that catalog is filtered by) + `config/CodexProvider.kt` (the configured model held against them) + `llm/codex/CodexHttpClient.kt` (per-request bearer and account headers) |
| `describeImage`/`describeVideo` missing from the tool list | `config/VisionRuntime.kt` (chat model vs `VISION_MODEL`), then `tools/ToolCatalogFactory.kt` (registration is skipped when there is no vision runtime) |
| A label or `sandbox:` reference resolves to the wrong thing or to nothing, or a file one call made is missing from the next call or from `turns/` | `agent/TurnShelf.kt` (labels, what resolves and what waits, `copyToSandbox`) + `tools/ToolSet.kt` (`decodeArgument`, and `takesReference`) + `tools/CallShelf.kt` (`keepOnShelf`) + `tools/sandbox/SandboxTools.kt` (`prepare`, before the work) |
| Garbled or empty tool calls from a flaky model | `agent/AgentTurn.kt` (`execute`: the empty-arguments guard and the unknown-tool answer) + `tools/ToolSet.kt` (decoding the arguments, and the complaint a missing or wrong-shaped one earns) |

## Adding a tool

A new agent tool typically touches these, in order:

1. **`tools/<feature>/<Feature>Tools.kt`** — `class <Feature>Tools(...) : ToolSet` whose constructor takes the
   `BotOutbox` and/or a client; each method is `@Tool(...) suspend fun … = suspendToolGuard { … }`, its arguments `@Arg(...)`.
2. **`tools/<feature>/<Feature>ToolDescriptions.kt`** — an `internal object` of `const val` descriptions referenced by
   the `@Tool` and `@Arg` descriptions (see the convention in `AGENTS.md`).
3. *(optional)* **`<Feature>Client.kt`** / **`<Feature>Models.kt`** — the external I/O and its DTOs.
4. **`tools/ToolCatalogFactory.kt`** — register it in `buildCatalog`; construct it only when the key it depends on is
   set (`config.fooApiKey?.let { … }`). Register it under a `ToolGroup` when a turn rarely needs it, and
   leave it visible when the model may need it without being asked for it by name; a new group also needs its one-line
   summary in `tools/ToolCatalog.kt`, since that line is all the model reads before loading it. A tool only one messenger can implement goes to that adapter's
   `PlatformToolSets` instead (`telegram/tools/TelegramToolSets.kt`), gated there on the same chat capability.
5. **Files and results between tools** — a parameter that takes a file is an `AttachedFile` (or a list of them),
   which the model names by label or `sandbox:` path, and which falls back to the request's attachment when omitted; a
   tool that makes a file calls `keepOnShelf` whether or not it sends it, and takes `send` when a file made without
   delivering it is worth having. A text parameter that carries a body worth not retyping — a message, a script, a
   file's contents — may declare `@Arg(takesReference = true)` and say so in its description. Mark an answer that is
   material to work on `copiedToSandbox`.
6. **Docs** — add the capability to the Features section of the [README](../README.md); document setup requirements and
   implicit dependencies in [`configuration.md`](configuration.md), and add any new env vars to both that file and
   [`.env.example`](../.env.example).

## Conventions

Coding conventions (logger placement, error handling, tool structure, DB/config access) are documented in
[`AGENTS.md`](../AGENTS.md) at the repo root.

### Deployment layouts

One deployment ships from this repository: the bot, a compose file and the `.env` beside it. Everything else it talks to is
somebody else's deployment — the Regolith server that runs sandboxes and publishes sites, wherever the operator put it,
reached with one URL and one token.

`vusan-egress` is the bot's only network. It connects out and nothing connects in, so the bot runs behind CGNAT as happily
as on a public machine.

In CI the deployment has to resolve from the example file a reader starts with: `.github/workflows/image.yml` copies
`.env.example` to `.env` and runs `docker compose config`.
