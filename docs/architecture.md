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
                │            ├─ reads/stores the dialogue via ─► agent/conversation/ ─► infra/
                │            └─ reads/stores memory via ──► agent/memory/ ──► infra/
                ├─ records every group message into ─► agent/grouplog/ ─► infra/
                └─ delivers outbox back to Telegram
```

`agent/` and `tools/` point both ways, and are one layer rather than two: the runner has to name the tool sets it
recognizes (`ToolActivity` maps a tool's own method reference to what the chat is shown while it runs, so a rename
cannot silently break the mapping), and a tool reads the store its capability is about — `MemoryTools` the memory
repository, `GroupLogTools` the transcript, `ConversationTools` the dialogue. Those stores sit under `agent/` because
that is who owns writing them, not because only `agent/` reads them. No other arrow here is bidirectional.

- **`telegram/`** — Telegram I/O, split by direction. `TelegramBotRunner` at the root receives updates (text, voice,
  audio, sticker, photo, video, video note, GIF, document, album, callback query), filters them by allowlist and ban
  list, and works out what each one says; `AgentTurns`, also at the root, takes it from there — the reply context, the
  `AgentRequest`, the progress indicator, the delivery and its fallback — so a turn started by a message and one started
  by a button follow the same path; `telegram/tools/` holds the tools only Telegram can implement — resending by
  `file_id`, and the sticker catalog — which reach the registry through the shared `PlatformToolSets` port rather than
  being registered centrally; `telegram/inbound/` normalizes an update into agent input; `telegram/delivery/`
  sends agent results back, including HTML-formatting, opt-in rich-message, reply-anchor, media/document, media-group,
  and private-message fallbacks; `telegram/callback/` owns the inline-button flows — `CallbackRouter` validates a
  pressed button and picks its flow, `TaskMenuHandler` runs the deterministic `/tasks` UI, and `InlineChoiceHandler` the
  agent-created choice buttons, whose selection becomes an agent input.
- **`agent/`** — agent orchestration on top of Koog. `AgentRunner` serializes the turns of one conversation, assembles
  the current user turn (chat metadata + durable memory + request), and owns every history write for it, so no other
  layer appends or clears turns behind a running turn's back. What it orchestrates sits beside it, one file per concern:
  `TurnPrompt` renders the blocks the model is shown for this turn, `TurnInput` writes the ones the request itself
  arrives in — what it replies to, the quoted fragment, the attachment, an album, a transcript, a pressed choice — so
  every adapter fills in the tags the contract describes instead of spelling its own, `TurnHistory` decides what the finished turn leaves
  behind, `ProviderErrors` reads a provider's refusal out of the message koog wrapped it in and picks the reply it
  earns, and `FallbackPromptExecutor` is the executor wrapper that hands every call to a second provider while the
  first is out, which is also what `TurnPrompt`'s `<current_model>` block and the status message's fallback line read
  to say who is answering. `AgentFactory` builds the `AIAgent` (system prompt + history + tools) and budgets its model context; `SystemPrompt` keeps the deployment's customizable personality and the
  fixed delivery/tool contract in separate XML-delimited blocks. `agent/conversation/` groups turns into complete
  interactions, persists raw history, and maintains its semantic recap, all keyed by a `ConversationScope` — one
  person in one chat, so a private exchange can never be replayed as that person's own words inside a group, and what
  travels between chats is durable memory rather than raw turns; `agent/memory/` stores that memory under a
  `MemoryOwner` (one person, or one group), which survives a history clear and is injected as
  `<user_memory>`/`<group_memory>`; `agent/grouplog/` is the group transcript, keyed by chat alone, holding every message the bot saw in a group rather than only the turns it took
  part in. `GroupLogReader` answers a window from it under a character budget, falling back to cached per-day recaps
  produced by `GroupLogDigester` when the window is too wide to quote.
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …).
  `ToolRegistryFactory` owns clients and builds a per-request `ToolCatalog` from required tools, optional tools whose
  env/config is present, and whatever the turn's messenger adds through the `PlatformToolSets` port — a tool only one
  messenger can implement lives in that adapter, so the factory never names one. The catalog splits what is registered
  from what the request carries: a set registered under a `ToolGroup` is in the registry from the first step, but its
  schemas are withheld until the model calls `loadTools` (`tools/catalog/`), which the `<tool_groups>` menu in the
  turn prompt tells it about. Deferring is what keeps the schemas of a dozen rarely-used capabilities out of the
  conversation budget; koog resolves a call against the registry, so a tool named before its group is loaded still
  runs. `LoadedToolGroups` then keeps the last few groups a conversation loaded and offers them again from its next
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
  (`runCommand` or `writeSandboxFile`, which copies it into a unique `inbox/` path) can lazily download). Its `kind`
  (`IMAGE`/`VIDEO`/`OTHER`) decides which of those tools accepts it; a video also carries its duration and a loader for
  Telegram's own thumbnail.
- **`delivery/`** — the shared output address and port: a `Destination` (chat plus optional thread — an anchor is not
  part of an address), the `Attribution` saying who a scheduled answer belongs to, where it hangs, and why the chat is
  hearing from the bot at all
  (`AttributionReason`) — the adapter writes the mention itself, since naming a person is platform syntax — and
  `OutputDelivery`, which an adapter implements so nothing outside one needs a messenger client to deliver a turn.
- **`tasks/`** — scheduled-task subsystem: storage, persisted pause state, recurrence math, and the background
  `TaskScheduler`. It knows no messenger: it delivers through `OutputDelivery` and reads chat facts through
  `ChatProfileLookup`.
- **`infra/`** — cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor `Http` client. Every
  table that holds state belonging to somebody carries a `platform` column beside the external id, so two messengers
  issuing the same number never read each other's rows. `Db.connect` creates a fresh database whole and stamps the version
  declared in `infra/Schema.kt` into SQLite's own `PRAGMA user_version`. Nothing is inferred by comparing declarations
  to what is there, and nothing is migrated in code: a database of any other version — from before versions existed, or
  from a newer build — stops startup instead of being reshaped, and is moved by hand.
- **`config/`** — `.env` parsing (`AppConfig`), LLM provider/model resolution (`LlmRuntime`), and the ChatGPT
  subscription credentials the Codex CLI writes (`CodexAuth`). `VisionRuntime` resolves separately which model looks at
  images: the `OPENAI_VISION_*` model when configured, the chat model when it accepts images, and nothing at all
  otherwise — which leaves the vision tools and sticker catalog unavailable.
- **`stt/`** — OpenAI speech-to-text client (`OpenAiWhisperClient`, default model `gpt-4o-transcribe`); used for voice
  transcription and for the sound of a video the vision tool watches, opt-in via `OPENAI_STT_API_KEY`.
- **`i18n/`** — user-facing message strings: the `Messages` interface, and one implementation per `Language` in a file
  of its own (English, Ukrainian, Russian, Spanish). `Language.fromCode` picks the language from the sender's Telegram
  language code, falling back to English. Adding a language is an enum entry plus a `Messages` file — the exhaustive
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
   `AttachedFile` on the turn, and the agent is told that only image editing takes them all while every other tool sees
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
   resumes the task instead of resurrecting the fires it missed. Two sinks then run on every allowlisted message, *before* the addressing check, because what they
   collect is precisely what nobody addressed to the bot: `recordGroupLog` writes the group transcript row, and
   `learnSticker` teaches the catalog which sets the chat uses. Both sit ahead of album buffering too, so each part of a
   gallery is seen individually. `MessageFilter.shouldHandle` then drops messages the bot shouldn't answer (in groups:
   only replies, mentions, or targeted commands), and past it `isAccepted` claims the message in `AnsweredMessages`; a
   message already claimed is dropped with a warning, because Telegram hands the same one over more than once — as an
   edit of it, and as a plain redelivery under a fresh update id, which the polling session's own duplicate filter does
   not catch.
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
   before it already in its history. `MAX_QUEUED_TURNS_PER_CONVERSATION` messages may wait that way, in the order they
   arrived, and the one after that is answered "busy". Admission is the ceiling every conversation shares —
   `MAX_CONCURRENT_TURNS` turns at once, the rest waiting the same way, and only a queue several times that long
   answered "overloaded" instead of queued; a queued turn (a scheduled fire, a pressed button) waits in both rather
   than being refused. The lock is taken **before** the place in every path: a turn that holds a place is running and
   waits for nothing, so nothing can wait in a circle, and a person's line costs everybody else no places.
   It then loads durable memory
   (`agent/memory/MemoryRepository` — the sender's user memory always, plus the group's memory in non-private chats),
   and places it with `<message_context>` immediately before the current request in one user-role turn. That
   metadata also carries
   `last_exchange` — how long ago this user last spoke with Vusan in this chat — but only once the gap is long enough to
   be worth noticing, so ordinary back-and-forth stays free of it. In a group the turn also carries `<recent_chat>`: a
   hard-capped slice of what the chat was saying just before, so a question with no subject ("and what do you think?")
   still has one. It leaves out the triggering message and this user's own exchanges with the bot, both of which the
   prompt already carries — the first as the request itself, the second as replayed `user`/`assistant` turns. Other
   people's messages and the bot's replies to *them* stay, since one person's conversation never contains those.
   `<current_time>` and the chat's `<sticker_catalog>` ride in that same user turn rather than in a system message of
   their own: a system message reads as a higher-priority instruction, which is wrong for context assembled out of what
   people sent, and koog's Anthropic and Google clients hoist every system message into the top-level system field
   regardless of where it sat. In a group the turn also carries what that chat lets the bot post, read through
   `telegram/ChatProfiles.kt`: one cached `getChat` + `getChatMember` pair yields the chat description, the permissions
   binding a bot that is a plain member (an administrator is bound by none of them, slow mode included), and the
   slow-mode delay. `ChatCapabilities` travels in `RequestContext.chat` and reaches three places — `ToolRegistryFactory` leaves
   out the tools whose output the chat would refuse, so the model cannot spend an image generation or a download on
   something undeliverable; `BotOutbox` refuses a queued output the chat does not accept, because registry gating alone
   proves nothing about the *mixed-output* paths (a text-first search queues photos, the sandbox sends whatever files
   it was asked for), and those tools report the refusal to the model rather than claiming a send nobody will see —
   image search checks first and never queries its provider at all; and `<message_context>` names the rest so the agent
   knows why and answers in one message under slow mode. Anything the lookup could not answer counts as unrestricted, since guessing "forbidden" would strip
   real abilities. The runner builds the per-request tool catalog before the turn text, because what the catalog
   defers goes into that text as `<tool_groups>`; `AgentFactory.prepare` then estimates the fixed
   system/tool/current-turn cost — from the visible tools alone, so a group loaded later is spent from the agent
   reserve rather than from the history budget. The history planner reserves room for output, future tool calls, and estimation error,
   then admits only complete interactions. If an older prefix no longer fits or exceeds the configured recent count,
   `LlmConversationCompactor` merges it into the persisted `<conversation_recap>` before `AgentFactory.build` creates
   the Koog `AIAgent`. Every turn is capped on the way into that recap prompt, and a user turn budgets its
   `<user_message>` first: reply metadata and a quoted fragment are written ahead of the request and can outrun the cap
   between them, so capping from the front alone would recap what the user was replying to and not what they asked.
   Native catalog context sizes are used automatically; `LLM_CONTEXT_WINDOW_TOKENS` supplies a missing value or
   overrides stale model metadata.
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`; tool calls/results are
   recorded for history. Live textual tool results share a cumulative bound derived from the reserved agent-growth
   budget before later LLM calls; the runner opens that bound as a `TurnToolBudget` the strategy spends and the
   `checkContextBudget` tool reports, so a turn can narrow a long read instead of discovering the ceiling by getting an
   empty result back. Once a quarter of the reserve is left the run states it without being asked, once, in a note that
   follows the batch of tool results — nothing may come between an assistant's tool call and that call's result. The custom `single_run` strategy (`AgentFactory`) guards against flaky models in two
   ways:
    - a tool call that arrives with no arguments at all for a tool that takes them (flaky models emit empty-arg siblings
      when they try to call tools in parallel) is short-circuited into a `ValidationError` result instead of being
      executed, so the run stays clean and the follow-up request stays well-formed. Only that shape: koog's generated
      schema lists Kotlin-defaulted parameters as required too, so checking them one by one turned away ordinary calls
      that koog decodes into the defaults;
    - a turn that ends having delivered nothing — no `sendMessage`, media, or reaction, and empty assistant text (flaky
      providers return an empty completion after a batch of tool results) — gets one nudge to actually deliver before
      finishing, so a full turn of research does not collapse into silence.

   It also lands a turn that runs long instead of letting it crash. Koog counts one iteration per graph node and throws
   `AIAgentMaxNumberOfIterationsReachedException` the moment `AGENT_MAX_ITERATIONS` is passed, which would throw away
   every search the turn already paid for. The strategy reads that live counter as each tool batch executes and, once
   only the reserve is left, sends the results to a wrap-up request carrying no tools at all: the model cannot spend the
   reserve on one more search, and its answer — written from what it gathered, saying what it could not finish — is
   queued into the outbox like any other message, so it survives even a turn that already reacted or sent something
   (trailing agent text is otherwise dropped as duplicate chatter).
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
      Koog's `onToolCallStarting` resolves the running tool to a neutral `ToolActivity` (`agent/ToolActivity.kt`, keyed
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
      Its writes are `NonCancellable`, because a send cancelled in flight can still have created the message, leaving a
      bubble whose id nobody holds. `finish` deletes it, or, when the model put its own words in it, edits those words
      to stand alone without the running line and without the button; either way that happens in `withLiveProgress`'s
      `finally`, so a turn cancelled by `/stop` still takes its bubble off the screen. In a slow-mode group the bot's messages are rationed, so an activity alone never opens one — only words
      the model chose to send do.
    - **Announcing a plan before the work** — every output a tool produces is queued and delivered when the turn is
      over, which for a long turn means the plan arrives after the thing it planned. `announcePlan` (`MessageTools`) is
      the way out: it writes into the live status through `TurnNarrator` — a neutral interface in `agent/`, so no
      Telegram type reaches `tools/` — and then records the text in the outbox as an already `delivered` `OutboxItem`.
      Delivery skips such an item instead of sending it twice, while still writing its transcript row, and the history
      carries it like any other assistant text. One announcement per turn (`BotOutbox.hasDelivered`): a second would
      rewrite what the user has already read. A turn with no live status — a scheduled run — queues the words with the
      rest of the reply instead.
    - **HTML and its fallbacks** — text and captions go out with Telegram's `HTML` parse mode; `agent/SystemPrompt.kt`
      instructs the agent to use only the supported tags and escape `<`/`>`/`&`. Models still slip in `<br>`, so
      `TelegramOutputSender` turns `<br>`-style tags into real newlines instead of letting Telegram reject the whole
      message. Rejected reply text is re-sent as a `message.html` document (`telegram/delivery/HtmlReplyDocument.kt` — a
      standalone, responsive, light/dark page with a no-script CSP) so the formatting still arrives; a rejected caption
      resends the media captionless and delivers the caption the same way; localized notices fall back to plain text.
    - **Rich messages** — opt-in Bot API 10.1 (`BotOutput.RichMessage`, github-flavored markdown) via the
      `sendRichMessage` tool, resent as a `message.md` document if rejected. Opt-in because some third-party clients
      (e.g. Telegram X) render rich messages as unsupported.
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
  `AgentRunner.handleScheduled` (waits for the user lock instead of bailing), each fire in a job of its own
  (`common/runInOwnJob`): a fire is registered in `RunningTurns` under the conversation it belongs to, so `/stop` and
  the stop button reach it like any turn, and without a job to be cancelled the one they would end is the scheduler's
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
  advanced afterwards — or deleted, when the recurrence has no fire left — so a persistent error cannot re-fire it on
  every poll tick. A task that has fired for the last time is removed rather than kept switched off: `/tasks` never
  listed one and nothing else reads one. A tick reads every due task
  at once and fires them one after another, which leaves the owner of a task waiting behind a long fire time to pause,
  retime or delete it: each task is read again (`TasksRepository.findDue(id, now)`) at the moment it would fire and
  skipped if it is no longer due, and the advance afterwards is conditional on the fire time the run started from, so a
  schedule its owner changed meanwhile is not overwritten. A chat the bot cannot write
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
  their row cap, and polls past their retention. Each of these is also pruned where it is written, and that is enough
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
  by `file_unique_id`. `AgentRunner` puts at most 16 ready-to-send entries into the current user turn: recently used
  individual stickers first, then frequent ones, with spare room filled round-robin across every described set the chat
  knows. A group that forbids stickers gets no index at all, matching the registry: the tools are gated on the same
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
  chats show all of that user's tasks; groups show only their tasks created in that chat. Callback data carries the menu
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
  unchanged, matching the agent-callable `clearConversation` tool. Both paths also advance that conversation's persisted
  history revision, which invalidates unanswered choices created before the clear. The command goes through
  `AgentRunner.clearConversation` so it waits for the conversation's turn lock; a turn already running would otherwise
  persist itself after the wipe. The tool runs inside a turn and so keeps calling the repository directly. The persisted
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
- **History compaction** — `agent/conversation/ConversationPlan.planConversation` token-budgets complete recent
  interactions. `LlmConversationCompactor` rewrites the previous recap plus the next omitted interaction prefix into a
  standalone semantic recap and advances a database checkpoint only after that model call succeeds. It runs at most once
  per turn, because it is an extra LLM round trip in front of the user's reply; a prefix that still does not fit stays
  out of the prompt and gets its own recap on a later turn. Failed compaction never deletes source rows. Raw transcript
  retention and model-visible context are deliberately separate. The recap is injected at user priority so mixed
  user/assistant history is not mislabeled as an assistant instruction. An initial context-overflow failure retries once
  with recap only, but never after a tool ran, which avoids duplicated actions.
- **Live tool-result budget** — `ContextWindowPolicy.liveToolResultMaxChars` caps everything the tools return during one
  run, converting the agent reserve back to characters at the same ratio `estimateHistoryTokens` reads them. It scales
  with the window on purpose: a fixed ceiling starves a large-window model, since a single full-length YouTube
  transcript would consume the whole run and leave later tool results with nothing. What is left of it during a run
  lives in `agent/TurnToolBudget.kt`: the strategy charges each result against it, `checkContextBudget`
  (`tools/context/`) is how the model reads it before deciding how much to ask for, and `TurnToolBudget.report()` is the
  single wording both that tool and the run's own low-reserve notice state it in.
- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns `AppConfig.llmProvider` into a Koog
  client/model/params triple. Native clients cover OpenAI, Anthropic, Google, and DeepSeek — models are matched against
  each client's predefined catalog, except that OpenAI and Anthropic also take an id newer than koog's catalog, declared
  with the shape of their current generation (`openAiModel`, `anthropicModel`) and checked against the vendor's own
  model list at startup (`config/HostedModelCheck`). `openai-compatible` keeps a hand-declared model for any other server (llama.cpp,
  Ollama, …), with a configurable context size. Its endpoint capability and params type are declared as a pair
  (`OpenAIChatParams` → `/v1/chat/completions`, `OpenAIResponsesParams` → `/v1/responses`), because the Koog client
  reads the route off the params type and rejects params the model does not declare an endpoint for. Direct OpenAI
  requests carry a `prompt_cache_key` of their own conversation, since reads match the prefixes most recently written
  under a key and one key for the whole deployment would let busy chats evict each other; history recaps keep a single
  shared key, their tool-free prefix being identical everywhere. For GPT-5.6 and later, `config/OpenAiPromptCaching`
  marks two explicit breakpoints — the stable system/developer block, and the last user message when the request
  carries tools — which keeps the system prompt and tool schemas reusable while leaving history, memory and tool
  results out of billable cache writes. The adapter exists because Koog 1.3.0 cannot represent OpenAI's explicit
  breakpoint fields itself. Anthropic caches nothing implicitly, so its chat params ask for request-level
  `cache_control` and let the API place the breakpoint; the recap asks for none. Its chat and recap params both ask for the
  model's whole output ceiling as `max_tokens`, which koog otherwise sets to 2048 — less than a model that always thinks
  may spend before it answers.
- **ChatGPT subscription (`codex`)** — the same Koog OpenAI client pointed at the Codex backend's Responses API, with no
  API key. `config/CodexAuth.CodexAuthStore` owns the credentials `codex login` writes to `~/.codex/auth.json` (or
  `$CODEX_HOME`). `AppConfig` resolves that path into the Codex provider config, and the store rereads the file per
  request so an external login, logout, sandbox switch, or CLI refresh takes effect without a restart. It refreshes
  OAuth sessions a few minutes before expiry and replaces the file atomically through an owner-only temporary file,
  preserving CLI-owned fields and refusing to overwrite a version that changed during refresh. A mutex keeps concurrent
  bot turns from spending the same single-use refresh token. Because Koog bakes the `Authorization` header into the
  client at construction, `config/CodexHttpClient` re-resolves the bearer token and account header on every request
  instead. Signing in, out, and device-code stay the CLI's job; this bridge requires file-backed credentials and cannot
  read the OS keyring. `CODEX_SERVICE_TIER` rides along as the Responses `service_tier` field and in the
  `x-codex-routing-hint` header the CLI sends beside it, both fixed for the process at startup.

  The backend accepts streaming requests only (`stream=false` and `store=true` are both rejected), and its final
  `response.completed` event carries an empty `output`. So `CodexHttpClient` answers Koog's ordinary non-streaming
  `post` by streaming the call and folding the `response.output_item.done` items back into the response object the
  non-streaming API would have returned, while preserving a non-empty completed output if the backend supplies one and
  rejecting failed, incomplete, or cancelled terminal events. Codex requests use `store=false` and explicitly request
  encrypted reasoning content, so reasoning items can be echoed through a stateless multi-step tool loop. Bridging at
  the transport keeps Koog's own parsing of tool calls, reasoning items and usage, and leaves `AgentRunner` unaware
  that this provider streams.

  Model discovery runs at startup through `config/CodexCatalog`: the account's own catalog decides which ids and context
  window are valid, since Codex and the Platform API expose different model sets. Input modalities decide whether the
  model may be reused for vision, advertised reasoning efforts validate `LLM_REASONING_EFFORT`, and advertised service
  tiers validate `CODEX_SERVICE_TIER`; older catalog entries without those fields keep the compatibility defaults. A
  catalog that cannot be read is a warning, not a failure — the endpoint is undocumented, so a shape change there must
  not take a working bot down — but a model the account plainly cannot run stops startup with the list of ones it can.
  `OPENAI_VISION_API_KEY` still explicitly selects a separate OpenAI vision model.

  The same session also covers image generation. `resolveImageRoute` picks `PLATFORM` whenever `OPENAI_IMAGE_API_KEY`
  is set and `CODEX` otherwise, so a paid key keeps billing separately instead of spending the conversation's own
  subscription allowance; `CODEX_IMAGE_GENERATION_ENABLED=false` drops the `CODEX` route, leaving only the key, as
  on every other provider. `OpenAiImageClient` takes an `ImageAuth` telling it which: the generation call differs only
  by URL and credentials, but the edit call genuinely forks — the Platform endpoint takes a multipart upload while the
  Codex one takes JSON with the source inlined as a data URL and infers the output size from it. The fork extends to
  what each request may carry: only the Platform one sends `OPENAI_IMAGE_MODERATION`, jpeg output, and high
  `input_fidelity` on the models that accept it, because the Codex backend's request has none of those fields. Both
  routes answer a refusal the same way: an error body naming OpenAI's content filter becomes an
  `ImageModerationBlocked`, so the tool tells the model to rewrite the description or give up instead of handing it a
  failed HTTP call to interpret. An edit takes every image the turn carries, which is what makes an album one picture;
  the first source is the one both routes hold closest to the original, so a picture of the bot itself puts its
  reference photo there.

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client → (only with
`LLM_PROVIDER=codex`) build the `CodexAuthStore` and run `codexPreflight`, which proves the ChatGPT session works and
fills the context window in from the account's model catalog before any message is served → (only for an `openai` or
`anthropic` model koog's catalog lacks) ask the vendor whether the id exists, so a typo fails here → create the LLM runtime,
whose executor everything downstream then shares — wrapped in `agent/FallbackPromptExecutor` when `LLM_FALLBACK_PROVIDER`
names a second runtime, so a spent subscription hands every call to it until the deadline its refusal named → build repositories, context policy, conversation compactor, the
Telegram client and its `BotProfile` — one `getMe` call shared by the runner, which matches mentions against it, and `AgentFactory`, which puts the handle in the system prompt → (only when image
generation or `ELEVENLABS_API_KEY` is configured, the two things that use it) `resolveSelfImage`
(`tools/imagegen/SelfImage.kt`), which reads the reference photo self-portraits and round video messages are drawn from:
`SELF_IMAGE_FILE` when set, otherwise whatever avatar loader startup hands it — for Telegram, one
`getUserProfilePhotos` on the bot's own id (`telegram/BotAvatar.kt`), and a failure there is a warning rather than a
failed startup → (only with a vision runtime) the `StickerCatalog`, then `TelegramToolSets` over it and the client,
`ToolRegistryFactory`, `AgentFactory`, `AgentRunner` → create `TaskMenuHandler` and `InlineChoiceHandler`, and
optionally enable voice transcription → start `TelegramBotRunner`, which builds its own `AgentTurns` and
`CallbackRouter` over those, and launch `TaskScheduler` and the sticker description worker, then block on the runner job
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
  what the model is told — capacity and availability read as "try again", everything else as the server's own
  sentence; no answer at all reads as temporarily unavailable.
- **`SandboxTools`** — the model-facing surface: run, read, cancel, write, delete, reset, send. It copies the
  turn's attachment into `inbox/<unique-id>/<name>` before the first command that might want it, once per turn,
  and renders a command as text the model can act on — the exit code, and the session limit that explains it
  when the memory or process cap is what killed it.
- **What the bot does not decide** — the sandbox image, memory, home size, idle stop, retention and network
  policy all belong to the server. The bot reads `GET /v1/info` for the limits it must respect, and trims a
  requested timeout to that ceiling instead of keeping a copy of the number.
- **What a person keeps** — their home, until the server's retention window passes or `resetSandbox` deletes
  the sandbox; while their site is up, retention leaves both alone. Processes do not outlive an idle stop;
  files do.

See [the sandbox guide](sandbox.md) for behaviour, setup and limits.

## Publishing to the web

A person's site is published by the same Regolith server that holds their sandbox — this repository holds no site host.
[`tools/sites/SiteTools.kt`](../src/main/kotlin/com/helltar/vusan/tools/sites/SiteTools.kt) is the whole of it: `publishSite`
sends one directory's path, the server snapshots it out of the sandbox and answers with the address, and `siteStatus` and
`unpublishSite` read and remove it.

- **One site per person**, because a site belongs to the sandbox it was published from, and that sandbox is the
  person's in every chat.
- **The bot never builds the URL, and never learns how it was chosen.** The server picks an address that says nothing
  about the sandbox or the person, keeps it while the site is up, and hands it back from the publish call.
- **The one check worth making locally**: a directory with no `index.html` at its top publishes fine and its link then
  opens nothing, so the tool lists the directory first and says so rather than handing over a dead link.
- **Whether publishing exists at all** is the server's answer, not a setting here: `GET /v1/info` reports it, and a server
  without a pages role refuses the call in its own words.

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom | Start here |
|---|---|
| The same message is answered twice, or editing one to add the mention does nothing | `TelegramBotRunner.startsTurnOnEdit` (what an edit must pass to start a turn) + `TelegramBotRunner.isAccepted`/`AnsweredMessages` (one turn per message, per-process, empty after a restart) |
| Vusan ignores a message entirely | `TelegramBotRunner.passesAllowlist` and `request/AccessPolicy.kt` (the `ALLOWED_IDS` allowlist and the `BANNED_IDS` ban list, both platform-qualified, applied on the polling loop), then `telegram/inbound/MessageFilter.kt` (`shouldHandle` — group reply/mention rules) |
| Container says `Up` but the bot answers nothing | the `com.helltar:heartbeat` library (the `/tmp/health` freshness signal, and the `ERROR` logged once when polling stalls) + `TelegramBotRunner.start` (the `getUpdates` generator hook that feeds it) |
| Reply says "still working on your previous request", or a second message is answered only after the first | `agent/ConversationLocks.kt` — one turn per conversation, `MAX_QUEUED_TURNS_PER_CONVERSATION` waiting behind it, the next refused |
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
| Wrong language in a canned reply (busy/error/voice/start/task menu) | `i18n/Language.kt` (language selection) + `i18n/Messages.kt` (the strings) |
| A turn's plan reaches the chat only after the work it announced, or arrives twice | `tools/message/MessageTools.announcePlan` (the tool and its one-per-turn rule) + `telegram/TurnStatus.kt` (`say`, and what survives `finish`) + `outbox/BotOutbox.kt` (`recordDelivered`, `hasDelivered`) + `telegram/delivery/TelegramDelivery.dispatch` (skipping an item already in the chat) |
| The typing indicator or the turn's status message is wrong, stale, or missing | `telegram/TelegramProgress.kt` (both tickers, and `statusGraceFor`, the per-activity gate deciding which turns get a message at all) + `telegram/TurnStatus.kt` (the message itself, the emoji beside each activity, its stop button, and how it ends) + `agent/ToolActivity.kt` (which tool means what) + `i18n/Messages.progressLabel` (the words) + `telegram/delivery/TelegramDelivery.chatActionFor` (the action) |
| A long research turn ends in the generic error reply or is answered mid-way | `agent/AgentFactory.kt` (`maxIterations`, `outOfToolBudget` and the wrap-up node that lands the turn) + `agent/AgentRunner.kt` (delivering what the outbox holds when a run fails) |
| A spent subscription still ends turns in "come back later", or the bot never returns to it | `agent/FallbackPromptExecutor.kt` (which failures switch, the outage deadline, the single probe back) + `agent/ProviderErrors.providerOutage` (the patterns and the reset time read from the body), then `LLM_FALLBACK_*` in [`configuration.md`](configuration.md#a-second-provider-behind-the-first) |
| The reply to a failed turn says nothing about what the provider did | `agent/AgentRunner.providerErrorReply` (which error body earns which canned reply: a content-policy refusal, a spent usage limit, a dead key, a 429/503 overload) + `i18n/Messages.kt` (the strings) |
| You need to see exactly what the model was sent this turn | `agent/PromptDump.kt` (the whole request rendered per message) — it hangs on koog's `onLLMCallStarting` in `agent/AgentFactory.kt` and is switched by the `PromptDump` logger in [`logback.xml`](../src/main/resources/logback.xml) |
| Vusan forgets context or the history recap looks wrong | `agent/conversation/ConversationPlan.kt` (budget/selection) + `agent/conversation/ConversationCompactor.kt` (semantic recap) + `agent/conversation/ConversationRepository.kt` (storage/checkpoint) |
| Nobody's answers to a quiz reach the agent, or the wrong option is named | `telegram/PollRegistry.kt` (what a sent poll stores, and for how long) + `telegram/inbound/GroupLogEntries.kt` (`PollAnswer.toGroupLogEntry`) + `tools/quiz/QuizTools.kt` / `tools/poll/PollTools.kt` (`isAnonymous`, which decides whether Telegram reports votes at all) |
| A group recap misses messages, or `readGroupLog` returns too little | `telegram/TelegramBotRunner.recordGroupLog` + `telegram/inbound/GroupLogEntries.kt` (what gets recorded at all), then `agent/grouplog/GroupLogReader.kt` (window budget, day split, digest cache) and `agent/grouplog/GroupLogRepository.kt` (retention and the per-chat row cap) |
| Vusan misreads what "that" refers to in a group, or parrots the group's chatter | `agent/AgentRunner.recentChatFor` (the `<recent_chat>` slice and its caps) + `agent/SystemPrompt.kt` (the `<recent_chat>` contract) |
| A channel recap misses posts, quotes the wrong text, or costs too much vision | `tools/tgchannel/TelegramChannelReader.kt` (the `?before=` walk, the window cutoff, the size budget, and which posts get vision) + `tools/tgchannel/TelegramChannelParser.kt` (own text vs the quote of a replied-to post, reactions, media kinds) |
| Voice/audio not transcribed | `telegram/inbound/VoiceTranscriber.kt` + `stt/OpenAiWhisperClient.kt` (needs `OPENAI_STT_API_KEY`); for a video's sound `tools/vision/VideoAudioTranscriber.kt` |
| Vusan cannot see what is in a video | `tools/vision/VisionTools.kt` (`describeVideo` guards and the preview-frame fallback), `tools/vision/VideoVisionClient.kt` (frames + transcript prompt), `tools/vision/VideoSampler.kt` (ffmpeg), `telegram/inbound/ReplyContext.kt` (which media becomes an `AttachedFile`) |
| Web search picks the wrong provider, or results are thin | the `@LLMDescription` text that ranks them: `tools/tavily/TavilyToolDescriptions.kt` (`webSearch`, the default) and `tools/searxng/SearxngToolDescriptions.kt` (`metaSearch`, the fallback) |
| A linked page reads as empty, as navigation, or in the wrong alphabet | `tools/page/PageReader.kt` (which elements are dropped, where the content root is looked for, the charset the download declared) + `tools/tavily/TavilyToolDescriptions.kt` and `tools/page/PageToolDescriptions.kt` (Tavily's `extractPageContent` reads first, `readPage` is the fallback) |
| Image search sends nothing, or sends irrelevant pictures | `tools/images/ImageSearchDelivery.kt` (candidate retries, size caps, media group) + `tools/images/ImageDownloadClient.kt` (user agent, format/dimension checks); for relevance, `SearxngTools.IMAGE_ENGINES` and `TavilyTools.imageExcludedDomains` |
| A selfie shows a stranger instead of the bot's avatar | `tools/imagegen/SelfImage.kt` (which reference photo is read at startup, and the prompt that keeps the face while dropping the rest of it) + `tools/imagegen/ImageGenToolDescriptions.SELF_PORTRAIT` (whether the model sets the flag at all) |
| Vusan sends a voice message instead of a round video, or offers no round video at all | `tools/voice/VideoNoteTools.kt` (synthesize → render → outbox, and the voice fallback when the render fails) + `tools/voice/VideoNoteRenderer.kt` (the ffmpeg graph; the waveform box stays inside the circle Telegram crops to) + `tools/ToolRegistryFactory.kt` (needs `ELEVENLABS_API_KEY`, `can_send_video_notes`, and a `SelfImage` reference photo) |
| Vusan answers about a whole message when the user quoted one part of it | `telegram/inbound/ReplyContext.kt` (`quotedFragmentOrNull`, what the sender selected) + `agent/TurnInput.kt` (the `<quoted_fragment>` block, and when it is left out) + `agent/SystemPrompt.kt` (what the block means) |
| Vusan does not know what a reply is about, or cannot edit a picture it made itself | `telegram/AgentTurns.kt` (the reply summary and replied file are built for every reply) + `telegram/inbound/ReplyContext.kt` (`replySummaryOrNull`, who the `author` is, `repliedAttachedFileOrNull`) + `agent/TurnInput.kt` (the `<reply_context>` block itself) |
| A rich message reads as empty, `unknown`, or loses its structure | `telegram/inbound/RichMessageText.kt` (block tree → rich markdown), then `MessageMetadata.contentTypeName`/`textSnippetOrNull` and `ReplyContext.repliedTextOrNull` |
| Scheduled task fires late, not at all, or reports "missed"/"failed" | `tasks/TaskScheduler.kt` (polling, lateness, retries) + `tasks/Recurrence.kt` (next-run math) |
| A chat's tasks all went paused on their own, or one keeps firing into a chat the bot was removed from | `telegram/BotMembership.kt` (the `my_chat_member` path) + `telegram/delivery/TelegramErrors.kt` (`isChatUnreachable`) + `tasks/TaskScheduler.kt` (`parkTasksOfUnreachableChat`) |
| A tool is missing in one group but present elsewhere, or a chat restriction is stale | `telegram/ChatProfiles.kt` (`capabilitiesOf`, the cache and its `forget`) + `tools/ToolRegistryFactory.buildCatalog` (which capability gates which tool) + `telegram/tools/TelegramToolSets.kt` (the same gate for Telegram's own tools) |
| The model answers that it cannot draw, speak, schedule or publish something it has tools for | `tools/ToolCatalog.kt` (which groups are deferred, and the `loadTools` menu) + `agent/TurnPrompt.kt` (`<tool_groups>`, the menu it reads) + `agent/SystemPrompt.kt` (the rule that sends it to `loadTools`) + `agent/AgentFactory.kt` (`sendVisibleTools`, which re-sends the tool list after a group is loaded) |
| Tool results come back truncated or empty part-way through a turn | `agent/ContextWindowPolicy.kt` (how large the reserve is for this model) + `agent/TurnToolBudget.kt` (what is left of it) + `agent/AgentFactory.kt` (`boundedForLiveContext`, which truncates and then omits) |
| A conversation loads the same group on every turn, or keeps offering one it no longer uses | `tools/LoadedToolGroups.kt` (per-scope memory, its cap and its LRU order) — it is process memory, so a restart empties it |
| `/tasks` or a plain-language task pause/resume/cancel fails | `telegram/callback/TaskMenuHandler.kt` (rendering, ownership, callbacks) + `tools/tasks/TaskTools.kt` (agent path) + `tasks/TasksRepository.kt` (shared scoped state changes) |
| `/stop` does not stop anything, or a turn leaves its status message on screen | `agent/RunningTurns.kt` (what is registered and cancelled) + `agent/AgentRunner.kt` (`stop`, and the lock the command must not take) + `telegram/AgentTurns.kt` (the notice on cancellation) + `telegram/TelegramProgress.kt` (closing the status on the way out) + `telegram/callback/TurnStopHandler.kt` (the button and whose turn it may stop) |
| `/clear` reports success but history survives | `agent/AgentRunner.kt` (`clearConversation` and the turn lock that also guards the append) + `tools/conversation/ConversationTools.kt` (agent path) + `agent/conversation/ConversationRepository.kt` (shared storage operation) |
| An agent choice button does nothing, repeats, reaches the wrong user, loses the photo, or its answer replies to the bot's own question | `tools/choice/InlineChoiceTools.kt` (tool contract) + `telegram/callback/InlineChoiceHandler.kt` (callback ownership/consumption, origin message id, parked attachment) + `telegram/AgentTurns.kt` (the follow-up turn and its reply anchor) |
| An env var has no effect | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example) |
| Model / provider / request-timeout selection or OpenAI prompt-cache misses | `config/LlmRuntime.kt` (provider → client/model/params) + `config/OpenAiPromptCaching.kt` (GPT-5.6+ explicit cache breakpoints on the system prefix and the current turn) |
| "Sign in again" replies, ChatGPT-subscription auth, or a rejected `LLM_MODEL` on `codex` | `config/CodexAuth.kt` (token load/refresh/persist) + `config/CodexCatalog.kt` (which models the plan offers) + `config/CodexHttpClient.kt` (per-request bearer and account headers) |
| `describeImage`/`describeVideo` missing from the tool list | `config/VisionRuntime.kt` (chat model vs `OPENAI_VISION_API_KEY`), then `tools/ToolRegistryFactory.kt` (registration is skipped when there is no vision runtime) |
| Garbled or empty tool-call crashes from a flaky model | `agent/AgentFactory.kt` — `vusanSingleRunStrategy` and `missingRequiredArgs` short-circuit them |

## Adding a tool

A new agent tool typically touches these, in order:

1. **`tools/<feature>/<Feature>Tools.kt`** — `class <Feature>Tools(...) : ToolSet` whose constructor takes the
   `BotOutbox` and/or a client; each method is `@Tool @LLMDescription(...) suspend fun … = suspendToolGuard { … }`.
2. **`tools/<feature>/<Feature>ToolDescriptions.kt`** — an `internal object` of `const val` descriptions referenced by
   the `@LLMDescription` annotations (see the convention in `AGENTS.md`).
3. *(optional)* **`<Feature>Client.kt`** / **`<Feature>Models.kt`** — the external I/O and its DTOs.
4. **`tools/ToolRegistryFactory.kt`** — register it in `buildCatalog`; wrap construction in the `optional(...)` helper
   when it depends on an API key that may be unset. Register it under a `ToolGroup` when a turn rarely needs it, and
   leave it visible when the model may need it without being asked for it by name; a new group also needs its one-line
   summary in `tools/ToolCatalog.kt`, since that line is all the model reads before loading it. A tool only one messenger can implement goes to that adapter's
   `PlatformToolSets` instead (`telegram/tools/TelegramToolSets.kt`), gated there on the same chat capability.
5. **Docs** — add the capability to the Features section of the [README](../README.md); document setup requirements and
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
