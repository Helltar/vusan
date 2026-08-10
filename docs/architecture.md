# Architecture

This document is the orientation map for the codebase: the layers, how a message flows through them, and the background
flows that run alongside. The main application is Kotlin under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/); the code-execution sandbox is a separate
Deno service under [`sandbox/`](../sandbox/), see [Code execution service](#code-execution-service).

## Layers

```
Telegram ──► telegram/ ──► agent/ ──► tools/ ──► external services
                │            │           │
                │            │           └─ writes outputs into ─► outbox/
                │            ├─ reads/stores the dialogue via ─► agent/conversation/ ─► infra/
                │            └─ reads/stores memory via ──► agent/memory/ ──► infra/
                ├─ records every group message into ─► agent/grouplog/ ─► infra/
                └─ delivers outbox back to Telegram
```

- **`telegram/`** — Telegram I/O, split by direction. `TelegramBotRunner` at the root receives updates (text, voice,
  audio, sticker, photo, video, video note, GIF, document, album, callback query) and filters them by allowlist and
  ban list;
  `telegram/inbound/` normalizes an update into agent input; `telegram/delivery/` sends agent results back, including
  HTML-formatting, opt-in rich-message, reply-anchor, media/document, media-group, and private-message fallbacks;
  `telegram/callback/` owns the inline-button flows — `TaskMenuHandler` the deterministic `/tasks` UI, and
  `InlineChoiceHandler` the agent-created choice buttons, whose selection becomes an agent input.
- **`agent/`** — agent orchestration on top of Koog. `AgentRunner` serializes the turns of one conversation, assembles
  the current user turn (Telegram metadata + durable memory + request), and owns every history write for it, so no other
  layer appends or clears turns behind a running turn's back; `AgentFactory` builds the `AIAgent` (system prompt +
  history + tools) and budgets its model context; `SystemPrompt` keeps the deployment's customizable personality and the
  fixed delivery/tool contract in separate XML-delimited blocks. `agent/conversation/` groups turns into complete
  interactions, persists raw history, and maintains its semantic recap, all keyed by the `(userId, chatId)` pair — one
  person in one chat, so a private exchange can never be replayed as that person's own words inside a group, and what
  travels between chats is durable memory rather than raw turns; `agent/memory/` stores that memory (user or group
  scope), which survives a history clear and is injected as `<user_memory>`/`<group_memory>`; `agent/grouplog/` is the
  group transcript, keyed by chat alone, holding every message the bot saw in a group rather than only the turns it took
  part in.
  `GroupLogReader` answers a window from it under a character budget, falling back to cached per-day recaps produced by
  `GroupLogDigester` when the window is too wide to quote.
- **`tools/`** — agent-callable tools, one subpackage per capability (search, voice, vision, scheduled tasks, …).
  `ToolRegistryFactory` owns clients and builds a per-request registry from required tools plus optional tools whose
  env/config is present. See the Features section of the [README](../README.md). `tools/images/` is not a tool surface
  but the pipeline every image search shares: download a provider's candidates, drop what Telegram would refuse, and
  queue the survivors.
- **`outbox/`** — the output model. `BotOutput` is the immutable sealed set of Telegram outputs (text, inline choice,
  rich message, photo, voice, audio, video, document, poll, reaction, …); `BotOutbox` is the per-request queue tools
  write into, holding each `BotOutput` as an `OutboxItem` that captures its private-routing decision.
- **`request/`** — the request-scoped input model shared across layers: `RequestContext`
  (chat/user/message ids and sender info tools see), `ChatCapabilities` (what the chat lets the bot post, and its slow
  mode — defaulting to unrestricted so a failed lookup never removes an ability), and `AttachedFile` (photo, video, or document, from the current
  message or a replied-to message, that vision (`describeImage`, `describeVideo`) and code execution (`codeExecution`)
  can lazily download). Its `kind` (`IMAGE`/`VIDEO`/`OTHER`) decides which of those tools accepts it; a video also
  carries its duration and a loader for Telegram's own thumbnail.
- **`tasks/`** — scheduled-task subsystem: storage, persisted pause state, recurrence math, and the
  background `TaskScheduler`.
- **`budget/`** — the daily token ceiling and how the day is shared out. `TokenBudget` counts the day's spend, both in
  total and per person, and answers why someone cannot spend right now (`TokenBudgetStop`);
  `BudgetedPromptExecutor` is the `PromptExecutor` wrapper that does the counting, so one place covers every LLM call
  the bot makes; `BudgetOwner` is the coroutine-context element that says whose share a nested call comes out of.
  Inert unless `LLM_DAILY_TOKEN_BUDGET` is set.
- **`infra/`** — cross-cutting infrastructure: the SQLite/Exposed `Db` singleton and the Ktor
  `Http` client.
- **`config/`** — `.env` parsing (`AppConfig`), LLM provider/model resolution (`LlmRuntime`), and the ChatGPT
  subscription credentials the Codex CLI writes (`CodexAuth`).
  `VisionRuntime` resolves separately which model looks at images: the `OPENAI_VISION_*` model when configured, the chat
  model when it accepts images, and nothing at all otherwise — which leaves the vision tools and sticker catalog
  unavailable.
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
   video note, GIF, document). Album (media group) parts arrive as separate updates sharing a
   `media_group_id`; the runner buffers them until the update stream goes quiet (`ALBUM_QUIET_PERIOD`, or the ten-item
   album cap) and handles the batch as one gallery message: the caption may sit on any album part, only the first
   inspectable item becomes the `AttachedFile`, and the agent is told how many items it cannot see.
   `/tasks`, `/clear`, and task-menu callback queries take direct paths that never enter the agent loop. An
   agent-created inline-choice callback is different: it is validated and consumed by `InlineChoiceHandler`, then its
   selected option enters the agent loop as the user's next turn. Callback data no handler recognizes (a button from
   an older build) is still answered, so the caller's client stops spinning.
   A `my_chat_member` update — the bot's own membership changing, which Telegram delivers by default — carries no
   message and goes straight to `telegram/BotMembership.kt` instead of the dispatch below.
2. **Filter** — `MessageFilter.shouldHandle` drops messages the bot shouldn't answer (in groups:
   only replies, mentions, or targeted commands); `TelegramBotRunner` then checks the allowlist (`ALLOWED_IDS`) and
   rejects unknown chats/users. `BANNED_IDS` is checked first and wins over the allowlist, so a banned user is denied
   inside a chat that is otherwise open; `TaskScheduler` skips their scheduled tasks the same way, moving each fire on
   without running or announcing it. Two sinks run *before* this gate, on every allowlisted message, because what they
   collect is precisely what nobody addressed to the bot: `recordGroupLog` writes the group transcript row, and
   `learnSticker` teaches the catalog which sets the chat uses. Both sit ahead of album buffering too, so each part of
   a gallery is seen individually.
3. **Normalize** — text is sanitized (`MessageSanitizer`); voice/audio is transcribed (`VoiceTranscriber` → `stt/`);
   stickers become a metadata prompt; a rich message — which never carries `text` — is flattened back into rich
   markdown (`telegram/inbound/RichMessageText.kt`), both as its own input and when one is quoted in a reply, capped at
   `MAX_RICH_MESSAGE_CHARS` because Telegram allows it 32768 characters against plain text's 4096;
   replied-message context is wrapped in `<reply_context>`/`<user_message>`; current or replied photo, video, and
   document input becomes `AttachedFile`. A reply that quotes part of a message adds `<quoted_fragment>` right before
   the request — including when the reply targets one of the bot's own messages, which gets no `<reply_context>` at all
   because the history already carries it; there the fragment is the only record of which part was asked about.
   Text quoted from outside — the message itself, a transcript, a replied-to post — has this prompt's own block
   tags defused first, so a message containing `</user_message>` cannot end a block early.
   `TelegramBotRunner.dispatchToAgent` assembles the agent input and the shorter history input.
4. **Run** — `AgentRunner.handle` takes the conversation lock (or returns "busy"), turns the request away with a
   "come back later" reply when the day's token budget is already spent, loads durable memory
   (`agent/memory/MemoryRepository` — the sender's user memory always, plus the group's memory in non-private chats),
   and places it with `<message_context>` immediately before the current request in one user-role turn. That metadata
   also carries `last_exchange` — how long ago this user last spoke with Vusan in this chat — but only once the gap is
   long enough to be worth noticing, so ordinary back-and-forth stays free of it. In a group the turn also carries
   `<recent_chat>`: a hard-capped slice of what the chat was saying just before, so a question with no subject
   ("and what do you think?") still has one. It leaves out the triggering message and this user's own exchanges with
   the bot, both of which the prompt already carries — the first as the request itself, the second as replayed
   `user`/`assistant` turns. Other people's messages and the bot's replies to *them* stay, since one person's
   conversation never contains those. `<current_time>` and the chat's `<sticker_catalog>` ride in that same user turn
   rather than in a system message of their own: a system message reads as a higher-priority instruction, which is
   wrong for context assembled out of what people sent, and koog's Anthropic and Google clients hoist every system
   message into the top-level system field regardless of where it sat.
   In a group the turn also carries what that chat lets the bot post, read through `telegram/ChatProfile.kt`: one
   cached `getChat` + `getChatMember` pair yields the chat description, the permissions binding a bot that is a plain
   member (an administrator is bound by none of them, slow mode included), and the slow-mode delay. `ChatCapabilities`
   travels in `RequestContext` and reaches two places — `ToolRegistryFactory` leaves out the tools whose output the
   chat would refuse, so the model cannot spend an image generation or a download on something undeliverable, and
   `<message_context>` names the rest so the agent knows why and answers in one message under slow mode. Anything the
   lookup could not answer counts as unrestricted, since guessing "forbidden" would strip real abilities.
   `AgentFactory.prepare` builds the per-request tool registry and estimates the fixed system/tool/current-turn cost.
   The history planner reserves room for output, future tool calls, and estimation error, then admits only complete
   interactions. If an older prefix no longer fits or exceeds the configured recent count,
   `LlmConversationCompactor` merges it into the persisted `<conversation_recap>` before `AgentFactory.build` creates
   the Koog `AIAgent`. Native catalog context sizes are used automatically; `LLM_CONTEXT_WINDOW_TOKENS` supplies a
   missing value or overrides stale model metadata.
5. **Act** — during the agent loop, tools run and push results into the request's `BotOutbox`; tool calls/results are
   recorded for history. Live textual tool results share a cumulative bound derived from the reserved agent-growth
   budget before later LLM calls. The custom `single_run` strategy (`AgentFactory`) guards against flaky models in two
   ways:
    - a tool call missing its declared required parameters (flaky models emit empty-arg siblings when they try to call
      tools in parallel) is short-circuited into a `ValidationError` result instead of being executed, so the run stays
      clean and the follow-up request stays well-formed;
    - a turn that ends having delivered nothing — no `sendMessage`, media, or reaction, and empty assistant text (flaky
      providers return an empty completion after a batch of tool results) — gets one nudge to actually deliver before
      finishing, so a full turn of research does not collapse into silence.

   It also lands a turn that runs long instead of letting it crash. Koog counts one iteration per graph node and throws
   `AIAgentMaxNumberOfIterationsReachedException` the moment `AGENT_MAX_ITERATIONS` is passed, which would throw away
   every search the turn already paid for. The strategy reads that live counter as each tool batch executes
   and, once only the reserve is left, sends the results to a wrap-up request carrying no tools at all: the model cannot
   spend the reserve on one more search, and its answer — written from what it gathered, saying what it could not
   finish — is queued into the outbox like any other message, so it survives even a turn that already reacted or sent
   something (trailing agent text is otherwise dropped as duplicate chatter).
6. **Collect** — `AgentRunner` persists the produced history as one interaction through `ConversationRepository` while
   it still holds the turn lock. Delivery tools whose payload already became assistant history are omitted;
   other tool events are stored as bounded complete call/result pairs. Only the newest two interactions replay those
   raw pairs, while older recent interactions replay user/assistant text. Summarized raw interactions are pruned by
   whole interaction after the configured count or age. Persisting inside the lock is
   what makes a concurrent `/clear` safe: no caller can append a turn into a history that was just wiped.
   A run that dies with outputs already queued is collected all the same: the queued answer is delivered and stored
   instead of being replaced by the canned failure reply, and the turn does not count as failed — so a scheduled task
   keeps what it produced rather than paying for the whole run again on a retry. A failure with an empty outbox still
   answers with the canned reply (`AgentResult.failed`).
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat, or to the user's private chat when a tool
   requested it, anchoring replies to the original message.
    - **Live progress** — an indicator runs through the whole turn (`telegram/TelegramProgress.kt`,
      `withLiveProgress`). Koog's `onToolCallStarting` resolves the running tool to a neutral `ToolActivity`
      (`agent/ToolActivity.kt`, keyed by `@Tool` method references), and the Telegram layer renders it one of two ways:
      in a private chat as a message draft carrying the words for it (`Messages.progressLabel`), everywhere else as a
      chat action (`chatActionFor`, e.g. `upload_photo` while an image generates). Only one is on screen — a draft
      announces the same turn the action would, so the action carries the turn until the first draft lands and then
      stands down, and it keeps the turn to itself if drafts are rejected. A tool too fast to read a caption for is left
      unmapped and reads as `null`: plain `typing`, and no draft. During delivery each item is still preceded by the
      action matching its own content (`botActionFor`).
    - **Progress drafts** — `sendMessageDraft` is Telegram's surface for a generating agent: a 30-second ephemeral
      preview, re-pushed every `DRAFT_REFRESH` and animated between updates that share a `draft_id` (derived per turn by
      `draftIdFor`, which Telegram requires to be non-zero). The Bot API accepts it for **private chats only**; a group
      turn keeps the chat action alone. A draft never touches `BotOutbox` or `TelegramDelivery` — it is not an output.
    - **Ending a draft** — a draft cannot be withdrawn, and it does not step aside for the reply: it *becomes* the
      message whose text starts with the draft's own text, and otherwise sits there until it expires. So the turn ends
      by handing the answer to the draft (`handOffProgressDraft`) immediately before delivery sends it, which requires
      the text that will land first (`draftHandoffText`: a queued `BotOutput.Text`, or the closing comment when nothing
      was queued). A reply that opens with media, a voice note or a rich message has nothing to hand over and leaves the
      last progress caption to expire — visible on Telegram Desktop, which keeps a stale draft on screen, while Android
      drops it as soon as the reply lands.
    - **Why a draft needs a named activity** — a live draft blocks the send button on mobile until it turns into a
      message, and a turn that answers with a reaction alone, or stays silent, has no message to release it and no way
      to withdraw it. Such a turn never resolves a tool to a named `ToolActivity` (`setReaction` is deliberately
      unmapped), so the draft opens on the first non-null activity instead of at the start of the turn, and never
      reverts to the placeholder afterwards. Pure thinking is carried by the chat action alone.
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
    - **Unreachable chats** — a chat that refuses the bot rather than the payload (kicked, left, blocked, deleted,
      write rights taken away — `TelegramErrors.isChatUnreachable`) is answered differently from every other rejection:
      no fallback can help, so the send cascade stops instead of buying one more rejection per degradation step, the
      rest of the queued outputs are abandoned, and `sendScheduled` reports it so `TaskScheduler` can park the chat's
      tasks. A refusal of one output kind ("not enough rights to send photos") is deliberately *not* this, and keeps
      its normal media-to-document fallback.
    - **Rate limits** — consecutive sends are paced (`INTER_MESSAGE_DELAY`) to stay under Telegram's per-chat limit.
      Upstream, `BotOutbox` coalesces consecutive `sendMessage` text into the trailing bubble while it fits
      (`MAX_TEXT_MESSAGE_CHARS`), so a model that splits one answer into many messages produces few real sends, and caps
      the resulting bubbles (`MAX_TEXT_MESSAGES`) so a looping model cannot flood the chat.
    - **Sender split** — `TelegramOutputSender.kt` maps each `BotOutput` kind to a Bot API call and picks the fallback
      wrapping it, `TelegramSendFallbacks.kt` holds the output-kind-agnostic rejection handling (plain-text retry,
      media-to-document, text-as-document), `TelegramRequests.kt` the raw request builders. Sandbox image previews opt
      out of photo-to-document fallback because their uncompressed document copy is already queued.

## Background and side flows

- **Task scheduler** — `TaskScheduler.launchIn` polls the task store every 30 seconds. Due tasks run through
  `AgentRunner.handleScheduled` (waits for the user lock instead of bailing) and are delivered with
  `TelegramDelivery.sendScheduled`. A task runs with no incoming message behind it, so its
  `<message_context>` is rebuilt from what the task stored — the chat, and who set it up — instead of the
  live chat flavor, title, and description a normal turn carries. Tasks overdue beyond
  `TASK_MAX_LATENESS_MINUTES` (e.g. after downtime) get a "missed" notice and are advanced/disabled rather than fired. A
  failed run (`AgentResult.failed`, or a thrown error) delivers nothing, so it is repeated up to `MAX_ATTEMPTS` times
  with a short backoff, the retry prompt telling the agent that the earlier attempt delivered nothing; a failed
  *delivery* is never repeated, since part of the answer may already be in the chat. Once the attempts are spent the
  chat gets a "failed" notice. Either way the task is advanced/disabled afterwards, so a persistent error cannot
  re-fire it on every poll tick. A chat the bot cannot write to at all is the exception to that advance: rather than
  rescheduling one task, `TasksRepository.pauseAllInChat` pauses every task in that chat at once, because otherwise
  each of them would run a full agent turn on every fire and only discover at delivery that nothing can arrive. It is
  reached from either end — `TelegramDelivery` reporting the fire (or even the missed/failed notice) as undeliverable,
  and `parkTasksOnLostAccess` acting on the `my_chat_member` update the moment the bot is removed or silenced. Paused
  rather than disabled, so the tasks stay listed in `/tasks` and their owners can resume them if the bot gets back in.
  Paused tasks remain stored and count toward the per-user task limit, but the due-task
  query skips them. A due task is also skipped, silently and without retries, while the daily token budget is spent —
  the same treatment an offline window gets, minus the notice, which would otherwise repeat for every task due until
  the budget resets. A task whose owner or chat is in `BANNED_IDS` is skipped the same way, ahead of the lateness
  check, so a ban produces no "missed" notices either.
  Recurrence math lives in `tasks/Recurrence.kt`.
- **Daily token budget** — with `LLM_DAILY_TOKEN_BUDGET` set, `budget/BudgetedPromptExecutor` wraps the executor every
  LLM caller shares, adds each completed call's input plus output tokens to the day's total in `token_usage` and to its
  author's row in `token_user_spend`, and refuses to start a call the budget has no room for. `TokenBudget` reloads the
  day whenever the budget date changes, so a restart resumes the same day and midnight in `LLM_TOKEN_BUDGET_TIMEZONE`
  starts a fresh one. The ceiling is checked before a call, never mid-call, so the day's last turn may overshoot by one
  turn. A turn that runs out mid-way ends with the same "come back later" reply as one that never started, and is not
  counted as a failure to retry.
  Past `LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT` of the day, a second rule joins the ceiling: a person over
  `budget ÷ users active in the last week` is turned away while everyone below their share keeps working, so one heavy
  user cannot take the end of the day from the rest. The divisor comes from `token_user_spend` rather than the
  allowlist — only people who actually used the bot count, so idle members reserve nothing. Attribution rides on the
  `BudgetOwner` coroutine-context element that `AgentRunner` installs around a turn, which is how a history recap or a
  vision call inside that turn lands on the same person; work started outside a turn (the sticker description worker)
  carries no owner and answers to the day's ceiling alone.
- **Self-initiated follow-ups** — `scheduleFollowUp` lets the agent set itself a single future turn when the
  conversation gives it a reason to come back ("ask how the exam went"). It is the same scheduler, store, and delivery
  path as `scheduleTask`, narrowed: one-time only, its own `MAX_FOLLOW_UPS_PER_USER` limit so the agent cannot spend the
  user's task quota, and a `self_initiated` flag on the row. In a group it fires anchored to the message that prompted
  it, and only when that message is gone does it fall back to a "following up with" notice instead of the
  "scheduled by" one, which would misattribute it to the user. The user sees and cancels them through `/tasks` like
  any other task.
- **Group chat log** — `agent/grouplog/` records what a group says, so a recap can be asked for later. Ingestion is
  `TelegramBotRunner.recordGroupLog`, a detached write that runs before the mention filter and outside private chats;
  the bot's own group messages are recorded from `TelegramDelivery.dispatch` after a send succeeds, skipping anything
  redirected to a DM. Text is collapsed and capped on write (harder for a forwarded post), media is reduced to a short
  label, and no file id is kept. Retention is amortized over inserts rather than scheduled: every few hundred rows in a
  chat, `GroupLogRepository` drops what is past `GROUP_LOG_RETENTION_DAYS` and trims to `GROUP_LOG_MAX_MESSAGES_PER_CHAT`.
  On read, `GroupLogReader` quotes the window when it fits the budget derived from `liveToolResultMaxChars`; when it does
  not and the window reaches back into a closed day, it splits by local day, replaces each **closed** day with a
  `GroupLogDigester` recap cached in `group_log_digests`, and leaves the current day quoted. A window lying inside today
  has no closed day to summarize, so it is truncated to its newest entries rather than widened to the whole day. Today is never cached — it is still being written to — which is
  what makes a repeated weekly question cost nothing after the first one. Every result leads with the window's exact
  message count, narrowed to one author when one was asked for, because the transcript under it may be only part of the
  window and a "how many" answer must not be a tally of quoted lines. The digest path counts over the day-snapped window
  it prints rather than the narrower one requested, and labels `<today>` with how many of its messages fit.
- **Sticker catalog** — `tools/sticker/StickerCatalog` learns which sticker sets a chat uses. The Bot API has no
  sticker search, so a sticker can only be sent from a set known by name: `TelegramBotRunner` taps every sticker in an
  allowlisted chat — including ones the bot is not addressed in, which in a group is its only view of what people
  actually use — records the set, and pulls it in whole through `getStickerSet`. Only the set is stored, never message
  content. Pulling a set in is the only expensive step — up to 60 vision calls, paid once — so it is gated twice: a set
  is learned only on the second time a chat reaches for it, and no chat may pull in more than three new sets a day.
  Neither gate applies to a set already known from elsewhere, which costs nothing to offer. A background worker then
  describes each sticker's thumbnail once through the vision model and caches the result by `file_unique_id`;
  `AgentRunner` puts the described stickers for that chat into the current user turn, and `sendSticker` resends
  one by `file_id`. That index holds the chat's most recently used sets, filled round-robin so a set someone is using
  right now is never crowded out by one learned earlier. Without a vision runtime the catalog is never constructed and
  the tool is never registered, matching the vision tools. A sticker the model refuses to describe, or one that
  repeatedly fails, is counted out after `describe_attempts` so it neither enters the index nor blocks the queue behind
  it. The same worker re-reads each set a day after it was last checked: a `file_id` is only a handle and a set's owner
  can edit or delete it, so stickers that disappeared are dropped, changed handles are refreshed, and a set Telegram
  reports as gone (`STICKERSET_INVALID`) is forgotten entirely. Only that specific answer counts as gone — any other
  failure backs off instead of discarding descriptions already paid for. A send Telegram rejects for a bad `file_id`
  is fed back through `TelegramDelivery`'s `onStickerRejected` hook, which only marks that set for an early re-read: a
  send also fails for reasons that say nothing about the sticker (a chat where stickers are restricted, a rate limit),
  and the catalog is shared by every chat, so what gets deleted is still decided by asking Telegram about the set.
- **Task menu** — `/tasks` bypasses the LLM and asks `TaskMenuHandler` to render the caller's enabled tasks. Private
  chats show all of that user's tasks; groups show only their tasks created in that chat. Callback data carries the
  menu owner, every action checks ownership and group scope, and Telegram is always sent an `answerCallbackQuery`.
  Since `MAX_TASKS_PER_USER` is configurable, the menu renders only as many tasks as fit Telegram's message limit and
  points at plain language for the rest; a rejected send falls back to the generic error reply rather than silence.
  Pause/resume edits the menu in place; cancel first renders a delete/back confirmation. Resuming an overdue recurring
  task advances it to the next future occurrence, while an overdue one-time task stays paused. The agent-callable
  `pauseTask` and `resumeTask` tools use the same repository operations and resume calculation, so plain-language
  requests match the button behavior. `editTask` can independently replace the prompt, title, schedule, or timezone
  while preserving the active/paused state; changing a cron timezone without replacing the expression recalculates
  its next occurrence. In groups, `listTasks`, edit, pause, resume, and cancel share the menu's current-chat scope
  instead of exposing tasks from private or unrelated chats.
- **Direct history clear** — `/clear` bypasses the LLM, deletes the caller's conversation history **in the chat the
  command was sent from**, and sends a localized confirmation. Their history in other chats, and everyone else's in
  this one, are untouched: the wipe is as narrow as the conversation it belongs to, which is what keeps `/clear` in a
  group from destroying context that is not the caller's. It deliberately leaves long-term memory and scheduled tasks
  unchanged, matching the agent-callable `clearConversation` tool. Both paths also advance that conversation's persisted
  history revision, which invalidates unanswered choices created before the clear. The command goes through
  `AgentRunner.clearConversation` so it waits for the conversation's turn lock; a turn already running would otherwise
  persist itself after the wipe. The tool runs inside a turn and so keeps calling the repository directly. The persisted
  semantic recap is deleted with the raw transcript.
- **Agent-created inline choices** — `askWithButtons` enqueues a plain-text question plus two to ten answer buttons.
  Callback data carries the intended user id, history revision, and option index; the labels remain in Telegram's
  message keyboard, while the current revision lives in `conversation_state`, so an unanswered choice survives a bot
  restart but becomes unavailable after the conversation it was asked in is cleared — a clear in another chat leaves it
  usable. `InlineChoiceHandler` verifies ownership
  and the revision, atomically claims the message, replaces its keyboard with the selected label, answers the callback,
  and wraps the question/selection as `<inline_choice>` for a queued `AgentRunner` turn. The question and its options
  are persisted as assistant history, and the follow-up answer is delivered as a reply to that choice message.
  A selection arrives as a callback with no message of its own, so an attachment the question was asked about ("edit
  this photo" → "which style?") would be gone by the time the answer runs. `TelegramBotRunner` parks the turn's
  `AttachedFile` in the handler whenever the turn queued a choice, and clears that slot on any other turn; the selection
  turn picks it back up and re-announces it as `<attached_file>`.
- **History compaction** — `agent/conversation/ConversationPlan.planConversation` token-budgets complete recent interactions.
  `LlmConversationCompactor` rewrites the previous recap plus the next omitted interaction prefix into a standalone
  semantic recap and advances a database checkpoint only after that model call succeeds. It runs at most once per turn,
  because it is an extra LLM round trip in front of the user's reply; a prefix that still does not fit stays out of the
  prompt and gets its own recap on a later turn. Failed compaction never deletes source rows. Raw transcript retention
  and model-visible context are deliberately separate. The recap is injected at user priority so mixed user/assistant
  history is not mislabeled as an assistant instruction. An initial context-overflow failure retries once with recap
  only, but never after a tool ran, which avoids duplicated actions.
- **Live tool-result budget** — `ContextWindowPolicy.liveToolResultMaxChars` caps everything the tools return during
  one run, converting the agent reserve back to characters at the same ratio `estimateHistoryTokens` reads them. It
  scales with the window on purpose: a fixed ceiling starves a large-window model, since a single full-length YouTube
  transcript would consume the whole run and leave later tool results with nothing.
- **LLM provider resolution** — `config/LlmRuntime.resolveLlmRuntime` turns
  `AppConfig.llmProvider` into a Koog client/model/params triple. Native clients cover OpenAI, Anthropic, Google, and
  DeepSeek — models are matched against each client's predefined catalog. `openai-compatible` keeps a hand-declared
  model for any other server (llama.cpp, Ollama, …), with a configurable context size. Its endpoint capability and
  params type are declared as a pair (`OpenAIChatParams` → `/v1/chat/completions`, `OpenAIResponsesParams` →
  `/v1/responses`), because the Koog client reads the route off the params type and rejects params the model does not
  declare an endpoint for. Direct OpenAI requests share a stable `prompt_cache_key`, and history recaps get their own
  so their tool-free prefix does not dilute the chat one; for GPT-5.6 and later, `config/OpenAiPromptCaching` marks the
  first stable system/developer content block as the only explicit cache breakpoint. This keeps the system prompt and
  tool schemas reusable while excluding timestamps, history, memory, Telegram metadata, user input, and tool results
  from billable cache writes. The adapter exists because Koog 1.1.1 cannot represent OpenAI's explicit breakpoint
  fields itself.
- **ChatGPT subscription (`codex`)** — the same Koog OpenAI client pointed at the Codex backend's Responses API, with
  no API key. `config/CodexAuth.CodexAuthStore` owns the credentials `codex login` writes to `~/.codex/auth.json`
  (or `$CODEX_HOME`), refreshing them a few minutes before expiry and writing the rotated refresh token back, serialized
  on a mutex so concurrent turns cannot burn one another's single-use refresh token. Because Koog bakes the
  `Authorization` header into the client at construction, `config/CodexHttpClient` re-resolves the bearer token and
  account header on every request instead — per-request headers replace configured ones. Signing in, out, device-code
  and keyring storage all stay the CLI's job; Vusan never implements OAuth itself.

  The backend accepts streaming requests only (`stream=false` and `store=true` are both rejected), and its final
  `response.completed` event carries an empty `output`. So `CodexHttpClient` answers Koog's ordinary non-streaming
  `post` by streaming the call and folding the `response.output_item.done` items back into the response object the
  non-streaming API would have returned. Bridging at the transport keeps Koog's own parsing of tool calls, reasoning
  items and usage, and leaves `AgentRunner` and the token budget unaware that this provider streams.

  Model discovery runs at startup through `config/CodexCatalog`: the account's own catalog decides which ids and context
  window are valid, since Codex and the Platform API expose different model sets. A catalog that cannot be read is a
  warning, not a failure — the endpoint is undocumented, so a shape change there must not take a working bot down — but a
  model the account plainly cannot run stops startup with the list of ones it can.

  The same session also covers image generation. `resolveImageRoute` picks `PLATFORM` whenever `OPENAI_IMAGE_API_KEY` is
  set and `CODEX` otherwise, so a paid key keeps billing separately instead of spending the conversation's own
  subscription allowance. `OpenAiImageClient` takes an `ImageAuth` telling it which: the generation call differs only by
  URL and credentials, but the edit call genuinely forks — the Platform endpoint takes a multipart upload while the Codex
  one takes JSON with the source inlined as a data URL and infers the output size from it.

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

`SANDBOX_TIMEOUT_SECONDS` is read by both sides: the service enforces it per run, while `SandboxClient` budgets its HTTP
wait around the same value.

## Startup

`Main.kt` wires everything in order: load `AppConfig` → connect `Db` → create the `Http` client → (only with
`LLM_PROVIDER=codex`) build the `CodexAuthStore` and run `codexPreflight`, which proves the ChatGPT session works and
fills the context window in from the account's model catalog before any message is served → create the LLM runtime →
wrap the executor in the `TokenBudget` meter, which everything downstream then uses → build
repositories, context policy, conversation compactor, the Telegram client and its `BotProfile` — one `getMe`
call shared by the runner, which matches mentions against it, and `AgentFactory`, which puts the handle in the
system prompt → (only with a vision runtime) the
`StickerCatalog`, `ToolRegistryFactory`, `AgentFactory`, `AgentRunner` → create `TaskMenuHandler` and
`InlineChoiceHandler`, and optionally enable voice transcription → start `TelegramBotRunner` and launch
`TaskScheduler` and the sticker description worker, then block on the runner job until shutdown (closing the executor,
HTTP client, and DB in `finally`).

The runner's first act is `publishCommandMenu` (`telegram/CommandMenu.kt`): a `setMyCommands` call per `Language`, so
the menu Telegram shows follows `dispatchText` without an operator step. It writes the same list BotFather's
`/setcommands` edits, which means a manual edit there is replaced on the next start. A rejected call is a warning, not
a failed startup — an out-of-date menu is not worth refusing to serve over.

## Where to look when…

A symptom-to-source map for finding the right file fast. Paths are under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/).

| Symptom                                                                          | Start here                                                                                                                                                                                                                                                                                            |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Vusan ignores a message entirely                                                 | `telegram/inbound/MessageFilter.kt` (`shouldHandle` — group reply/mention rules), then `TelegramBotRunner.isAccepted`/`isIdAllowed` (the `ALLOWED_IDS` allowlist and the `BANNED_IDS` ban list)                                                                                                                                               |
| Reply says "still working on your previous request"                              | `agent/AgentRunner.kt` — the per-conversation `Mutex` rejects a second concurrent turn in the same chat                                                                                                                                                                                                                      |
| Reply lands in the wrong chat, loses its reply anchor, or DM redirect misbehaves | `telegram/delivery/TelegramDelivery.kt` (routing/anchor/private-redirect *policy*)                                                                                                                                                                                                                             |
| Formatting renders wrong, message rejected, or media falls back to document/text | `agent/SystemPrompt.kt` (allowed HTML tags the agent emits), `telegram/delivery/TelegramOutputSender.kt` (which call and which fallback each output kind gets), `telegram/delivery/TelegramSendFallbacks.kt` (the fallback *mechanism* itself), `telegram/delivery/TelegramErrors.kt` (which provider errors trigger a fallback) |
| Vusan floods a chat or stalls on Telegram 429 over a long multi-message reply    | `outbox/BotOutbox.kt` (text coalescing + `MAX_TEXT_MESSAGES` cap) + `telegram/delivery/TelegramDelivery.kt` (`INTER_MESSAGE_DELAY` pacing)                                                                                                                                                                     |
| A specific tool misbehaves                                                       | `tools/<feature>/<Feature>Tools.kt` for the tool surface, plus its `<Feature>Client.kt` for the external call                                                                                                                                                                                         |
| Code execution times out, says "busy", or loses produced files                   | `tools/sandbox/SandboxClient.kt` (HTTP call + wait budget), then the service in [`sandbox/`](../sandbox/): `main.ts` (worker pool, `503` when none free) and `worker.ts` (Pyodide setup, output caps)                                                                                                 |
| Wrong language in a canned reply (busy/error/voice/start/task menu)              | `i18n/Language.kt` (language selection) + `i18n/Messages.kt` (the strings)                                                                                                                                                                                                                            |
| The typing indicator or the progress draft is wrong, stale, or missing           | `telegram/TelegramProgress.kt` (both tickers, the private-chat gate, the named-activity gate, `handOffProgressDraft`) + `agent/ToolActivity.kt` (which tool means what) + `i18n/Messages.progressLabel` (the words) + `telegram/delivery/TelegramDelivery.chatActionFor` (the action)                                                     |
| A long research turn ends in the generic error reply or is answered mid-way      | `agent/AgentFactory.kt` (`maxIterations`, `outOfToolBudget` and the wrap-up node that lands the turn) + `agent/AgentRunner.kt` (delivering what the outbox holds when a run fails)                                                                                                                     |
| Vusan forgets context or the history recap looks wrong                           | `agent/conversation/ConversationPlan.kt` (budget/selection) + `agent/conversation/ConversationCompactor.kt` (semantic recap) + `agent/conversation/ConversationRepository.kt` (storage/checkpoint)                                                                                                                         |
| A group recap misses messages, or `readGroupLog` returns too little              | `telegram/TelegramBotRunner.recordGroupLog` + `telegram/inbound/GroupLogEntries.kt` (what gets recorded at all), then `agent/grouplog/GroupLogReader.kt` (window budget, day split, digest cache) and `agent/grouplog/GroupLogRepository.kt` (retention and the per-chat row cap)                              |
| Vusan misreads what "that" refers to in a group, or parrots the group's chatter | `agent/AgentRunner.recentChatFor` (the `<recent_chat>` slice and its caps) + `agent/SystemPrompt.kt` (the `<recent_chat>` contract)                                                                                                                                                                            |
| Voice/audio not transcribed                                                      | `telegram/inbound/VoiceTranscriber.kt` + `stt/OpenAiWhisperClient.kt` (needs `OPENAI_STT_API_KEY`); for a video's sound `tools/vision/VideoAudioTranscriber.kt`                                                                                                                                                                                                            |
| Vusan cannot see what is in a video                                              | `tools/vision/VisionTools.kt` (`describeVideo` guards and the preview-frame fallback), `tools/vision/VideoVisionClient.kt` (frames + transcript prompt), `tools/vision/VideoSampler.kt` (ffmpeg), `telegram/inbound/ReplyContext.kt` (which media becomes an `AttachedFile`)                                  |
| Web search picks the wrong provider, or results are thin                        | the `@LLMDescription` text that ranks them: `tools/tavily/TavilyToolDescriptions.kt` (`webSearch`, the default) and `tools/searxng/SearxngToolDescriptions.kt` (`metaSearch`, the fallback)                                                                                                           |
| Image search sends nothing, or sends irrelevant pictures                        | `tools/images/ImageSearchDelivery.kt` (candidate retries, size caps, media group) + `tools/images/ImageDownloadClient.kt` (user agent, format/dimension checks); for relevance, `SearxngTools.IMAGE_ENGINES` and `TavilyTools.imageExcludedDomains`                                                   |
| Vusan answers about a whole message when the user quoted one part of it          | `telegram/inbound/ReplyContext.kt` (`quotedFragmentOrNull` and the `<quoted_fragment>` block) + `agent/SystemPrompt.kt` (what the block means)                                                                                                                                                        |
| A rich message reads as empty, `unknown`, or loses its structure                 | `telegram/inbound/RichMessageText.kt` (block tree → rich markdown), then `MessageMetadata.contentTypeName`/`textSnippetOrNull` and `ReplyContext.repliedTextOrNull`                                                                                                                                           |
| Scheduled task fires late, not at all, or reports "missed"/"failed"              | `tasks/TaskScheduler.kt` (polling, lateness, retries) + `tasks/Recurrence.kt` (next-run math)                                                                                                                                                                                                                   |
| A chat's tasks all went paused on their own, or one keeps firing into a chat the bot was removed from | `telegram/BotMembership.kt` (the `my_chat_member` path) + `telegram/delivery/TelegramErrors.kt` (`isChatUnreachable`) + `tasks/TaskScheduler.kt` (`parkTasksOfUnreachableChat`)                                                                                                                |
| A tool is missing in one group but present elsewhere, or a chat restriction is stale                | `telegram/ChatProfile.kt` (`capabilitiesOf`, the cache and its `forget`) + `tools/ToolRegistryFactory.buildRegistry` (which capability gates which tool)                                                                                                                                       |
| `/tasks` or a plain-language task pause/resume/cancel fails                      | `telegram/callback/TaskMenuHandler.kt` (rendering, ownership, callbacks) + `tools/tasks/TaskTools.kt` (agent path) + `tasks/TasksRepository.kt` (shared scoped state changes)                                                                                                                                   |
| `/clear` reports success but history survives                                    | `agent/AgentRunner.kt` (`clearConversation` and the turn lock that also guards the append) + `tools/conversation/ConversationTools.kt` (agent path) + `agent/conversation/ConversationRepository.kt` (shared storage operation)                                                                                            |
| An agent choice button does nothing, repeats, reaches the wrong user, or its answer lost the photo | `tools/choice/InlineChoiceTools.kt` (tool contract) + `telegram/callback/InlineChoiceHandler.kt` (callback ownership/consumption, parked attachment) + `TelegramBotRunner.dispatchInlineChoiceCallback` (agent follow-up)                                                                             |
| An env var has no effect                                                         | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example)                                                                                                                                                            |
| Model / provider / request-timeout selection or OpenAI prompt-cache misses       | `config/LlmRuntime.kt` (provider → client/model/params) + `config/OpenAiPromptCaching.kt` (GPT-5.6+ explicit cache breakpoints on the system prefix and the current turn)                                                                                                                              |
| "Sign in again" replies, ChatGPT-subscription auth, or a rejected `LLM_MODEL` on `codex` | `config/CodexAuth.kt` (token load/refresh/persist) + `config/CodexCatalog.kt` (which models the plan offers) + `config/CodexHttpClient.kt` (per-request bearer and account headers)                                                                                                                    |
| `describeImage`/`describeVideo` missing from the tool list                       | `config/VisionRuntime.kt` (chat model vs `OPENAI_VISION_API_KEY`), then `tools/ToolRegistryFactory.kt` (registration is skipped when there is no vision runtime)                                                                                                                                       |
| Garbled or empty tool-call crashes from a flaky model                            | `agent/AgentFactory.kt` — `vusanSingleRunStrategy` and `missingRequiredArgs` short-circuit them                                                                                                                                                                                                       |
| Vusan answers "come back later", or a scheduled task never fires                 | `budget/TokenBudget.kt` (the day's spend, the per-person share, the reset zone) + `budget/BudgetedPromptExecutor.kt` (what is counted) + `budget/TokenBudgetStop.kt` (day ceiling vs personal share, and the `BudgetOwner` attribution), then `LLM_DAILY_TOKEN_BUDGET` in [`configuration.md`](configuration.md) |

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
5. **Docs** — add the capability to the Features section of the [README](../README.md); document setup requirements
   and implicit dependencies in [`configuration.md`](configuration.md), and add any new env vars to both that file and
   [`.env.example`](../.env.example).

## Conventions

Coding conventions (logger placement, error handling, tool structure, DB/config access) are documented in
[`AGENTS.md`](../AGENTS.md) at the repo root.
