# Architecture

The orientation map for the codebase. The application is Kotlin under
[`src/main/kotlin/com/helltar/vusan/`](../src/main/kotlin/com/helltar/vusan/), and it is the whole deployment; the
sandbox and the sites people publish belong to a Regolith server this bot is a client of.

This page is an index: open only the pages the task needs. Chasing a symptom rather than reading for orientation?
Start at [Where to look when…](#where-to-look-when) below — it maps a symptom to the file that owns it, and beats
searching the tree.

## Pages

- [Layers](architecture/layers.md) — the packages, what each owns, and the one pair that points both ways
  (`agent/` and `tools/`).
- [Receiving a message](architecture/inbound.md) — lifecycle steps 1–3: long polling, the allowlist and group
  rules, albums, edits, and turning an update into agent input.
- [Running a turn](architecture/turn.md) — steps 4–6: the floor, the conversation queue, the prompt, the agent
  loop and its guards, tools, and what a finished turn persists.
- [Delivery](architecture/delivery.md) — step 7: routing, reply anchors, private-message redirects, formatting
  and the send fallbacks.
- [Scheduled work](architecture/scheduling.md) — the task scheduler, maintenance, and the agent's own follow-ups.
- [Group flows](architecture/groups.md) — the group log, diary, initiative, poll answers and the sticker
  catalog.
- [Commands and buttons](architecture/commands.md) — `/tasks`, `/stop`, `/clear` and agent-created inline
  choices.
- [Context budget](architecture/context.md) — history compaction and the live budget for tool results.
- [Model providers](architecture/models.md) — provider resolution, the fallback, and the ChatGPT subscription.
- [Startup and deployment](architecture/startup.md) — `Main.kt` wiring, public downloads, the startup log, the
  command menu, the heartbeat, and what ships as a deployment.
- [Sandbox and publishing](architecture/sandbox-and-sites.md) — the Regolith client and site publishing.
- [Adding a tool](architecture/adding-a-tool.md) — the files a new tool touches, in order.

Coding conventions — logger placement, error handling, tool structure, DB and config access — are in
[`AGENTS.md`](../AGENTS.md).

## Request lifecycle

A normal user message travels:

1. **Receive** — `TelegramBotRunner` long-polls and funnels updates in. [inbound](architecture/inbound.md)
2. **Filter** — the allowlist, the ban list and the group rules decide whether to answer.
   [inbound](architecture/inbound.md)
3. **Normalize** — text is sanitized, voice transcribed, attachments and reply context gathered.
   [inbound](architecture/inbound.md)
4. **Run** — `AgentRunner.handle` checks the floor, queues the conversation and starts the turn.
   [turn](architecture/turn.md)
5. **Act** — the agent loop calls tools, which push results into the `BotOutbox`. [turn](architecture/turn.md)
6. **Collect** — the turn's history is persisted as one interaction. [turn](architecture/turn.md)
7. **Deliver** — `TelegramDelivery.send` routes each `BotOutput` to the chat. [delivery](architecture/delivery.md)

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
| One person's messages in a group go unanswered for a while, with no notice | `floor closed` lines in the log, then `agent/ChatFloor.kt` (`ANSWERS_PER_STRETCH`, `COOLING_PERIOD`, what reopens it), `TelegramBotRunner.reportToFloor` (whose lines count as somebody else writing) and `AgentRunner.handle` (which turns give their count back) |
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
| You need to see exactly what the model was sent this turn | `agent/PromptDump.kt` (the whole request rendered per message) — `agent/AgentTurn.kt` renders it before every model call and is switched by the `PromptDump` logger in [`logback.xml`](../src/main/resources/logback.xml); what every turn starts from — the system prompt and the tool definitions, without running the bot — is `./gradlew -q exportPrompt` |
| Vusan forgets context or the history recap looks wrong | `agent/conversation/ConversationPlanner.kt` (a recap before the reply only when history does not fit, the rest by its worker after the turn, and what a failed or raced recap leaves) + `agent/conversation/ConversationPlan.kt` (budget/selection) + `agent/conversation/ConversationCompactor.kt` (semantic recap) + `agent/conversation/ConversationRepository.kt` (storage/checkpoint) |
| Nobody's answers to a quiz reach the agent, or the wrong option is named | `telegram/PollRegistry.kt` (what a sent poll stores, and for how long) + `telegram/inbound/GroupLogEntries.kt` (`PollAnswer.toGroupLogEntry`) + `tools/quiz/QuizTools.kt` / `tools/poll/PollTools.kt` (`isAnonymous`, which decides whether Telegram reports votes at all) |
| A group recap misses messages, or `readGroupLog` returns too little | `telegram/TelegramBotRunner.recordGroupLog` + `telegram/inbound/GroupLogEntries.kt` (what gets recorded at all), then `agent/grouplog/GroupLogReader.kt` (window budget, day split, closed days from the diary) and `agent/grouplog/GroupLogRepository.kt` (retention and the per-chat row cap) |
| Vusan misreads what "that" refers to in a group, or parrots the group's chatter | `agent/TurnSurroundings.recentChatFor` (the `<recent_chat>` slice and its caps) + `agent/SystemPrompt.kt` (the `<recent_chat>` contract) |
| A channel recap misses posts, quotes the wrong text, or costs too much vision | `tools/tgchannel/TelegramChannelReader.kt` (the `?before=` walk, the window cutoff, the size budget, and which posts get vision) + `tools/tgchannel/TelegramChannelParser.kt` (own text vs the quote of a replied-to post, reactions, media kinds) |
| Vusan writes to somebody who told it not to, or leaves somebody unanswered who never asked for that | `staying silent … why=[…]` and `ambient check skipped, the author asked to be left alone` lines in the log, then `leave_alone` in the database (who asked, where, until when), `tools/leavealone/LeaveAloneTools.kt` (what the model records and for how long), `agent/presence/Initiative.kt` (what a look may not touch), `agent/addressing/AmbientAddressing.kt` (the follow-up door), `tasks/TaskScheduler.kt` (a dropped follow-up) and `tools/message/MessageTools.staySilent` with `agent/SystemPrompt.kt` (when silence is an answer) |
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
| The model answers that it cannot speak, schedule or publish something it has tools for | `tools/ToolCatalog.kt` (which groups are deferred, and the `loadTools` menu) + `agent/TurnPrompt.kt` (`<tool_groups>`, the menu it reads) + `agent/SystemPrompt.kt` (the rule that sends it to `loadTools`) + `agent/AgentTurn.kt` (`offerLoadedTools`, which offers a group loaded mid-turn in the conversation before the next request; the client lists it up front where the model takes no offer, `LlmModel.offersToolsMidConversation`) |
| Tool results come back truncated or empty part-way through a turn | `agent/ContextWindowPolicy.kt` (how large the reserve is for this model) + `agent/TurnToolBudget.kt` (what is left of it) + `agent/AgentTurn.kt` (`boundedToolText`, which truncates and then omits) |
| A conversation loads the same group on every turn, or keeps offering one it no longer uses | `tools/LoadedToolGroups.kt` (per-scope memory, its cap and its LRU order) — it is process memory, so a restart empties it |
| `/tasks` or a plain-language task pause/resume/cancel fails | `telegram/callback/TaskMenuHandler.kt` (rendering, ownership, callbacks) + `tools/tasks/TaskTools.kt` (agent path) + `tasks/TasksRepository.kt` (shared scoped state changes) |
| `/stop` does not stop anything, or a turn leaves its status message on screen | `agent/RunningTurns.kt` (what is registered and canceled) + `agent/AgentRunner.kt` (`stop`, and the lock the command must not take) + `telegram/AgentTurns.kt` (the notice on cancellation) + `telegram/TelegramProgress.kt` (closing the status on the way out) + `telegram/callback/TurnStopHandler.kt` (the button and whose turn it may stop) |
| `/clear` reports success but history survives | `agent/AgentRunner.kt` (`clearConversation` and the turn lock that also guards the append) + `agent/conversation/ConversationRepository.kt` (shared storage operation) |
| An agent choice button does nothing, repeats, reaches the wrong user, loses the photo, or its answer replies to the bot's own question | `tools/choice/InlineChoiceTools.kt` (tool contract) + `telegram/callback/InlineChoiceHandler.kt` (callback ownership/consumption, origin message id, parked attachment) + `telegram/AgentTurns.kt` (the follow-up turn and its reply anchor) |
| An env var has no effect | `config/AppConfig.kt` (parsing) — and check it is documented in [`configuration.md`](configuration.md) + [`.env.example`](../.env.example) |
| Model / provider / request-timeout selection, or prompt-cache misses | `config/llm/<Vendor>Provider.kt` (one provider's runtime: client, model, options — and its startup check of the model against the vendor) + `config/llm/LlmRuntime.kt` and `config/llm/ModelPreflight.kt` (the dispatch to it) + `llm/openai/OpenAiPromptCaching.kt` (GPT-5.6+: implicit caching with an explicit twelve-hour breakpoint on the system prefix, and none for a prompt that never repeats) + `llm/anthropic/AnthropicClient.kt` (the request-level breakpoint and the hour-long ones on the system block and the replayed history) + `agent/AgentFactory.kt` (the history's stable prefixes, which every client marks) + `agent/conversation/SentConversations.kt` (where a provider takes no breakpoints, whether a turn continued the last request, and the `last request not continued` line saying why not); `cacheReadTokens` and `cacheWriteTokens` on each turn's usage line say what was read and written, and `cacheWriteLongTokens` how much of the writing was for the longer lifetime |
| "Sign in again" replies, ChatGPT-subscription auth, or a rejected `LLM_MODEL` on `codex` | `llm/codex/CodexAuth.kt` (token load/refresh/persist) + `llm/codex/CodexCatalog.kt` (which models the plan offers) + `llm/codex/CodexClientVersion.kt` (the CLI version that catalog is filtered by) + `config/llm/CodexProvider.kt` (the configured model held against them) + `llm/codex/CodexHttpClient.kt` (per-request bearer and account headers) |
| `describeImage`/`describeVideo` missing from the tool list | `config/llm/VisionRuntime.kt` (chat model vs `VISION_MODEL`), then `tools/ToolCatalogFactory.kt` (registration is skipped when there is no vision runtime) |
| A label or `sandbox:` reference resolves to the wrong thing or to nothing, or a file one call made is missing from the next call or from `turns/` | `agent/TurnShelf.kt` (labels, what resolves and what waits, `copyToSandbox`) + `tools/ToolSet.kt` (`decodeArgument`, and `takesReference`) + `tools/CallShelf.kt` (`keepOnShelf`) + `tools/sandbox/SandboxTools.kt` (`prepare`, before the work) |
| Garbled or empty tool calls from a flaky model | `agent/AgentTurn.kt` (`execute`: the empty-arguments guard and the unknown-tool answer) + `tools/ToolSet.kt` (decoding the arguments, and the complaint a missing or wrong-shaped one earns) |

