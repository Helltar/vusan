# Running a turn

Steps 4–6 of the [request lifecycle](../architecture.md#request-lifecycle): the agent loop, its tools and what a
finished turn leaves behind. Before it: [receiving a message](inbound.md); after it: [delivery](delivery.md).

4. **Run** — `AgentRunner.handle` first asks `agent/ChatFloor` whether this person may still be answered in this
   group: ten answers in a row to one person while nobody else wrote in the chat (or forum topic) close the floor
   to them, the tenth carrying `last_answer_of_stretch` in `<message_context>` so the model rounds the exchange off
   itself, and every message after it is turned away in silence — no reply, no reaction — until anybody else writes
   there or an hour passes without an answer. A turn is counted when it is admitted, so a turn that ends without a
   line of its own in the chat — turned away, silent, failed or stopped — gives its count back, and the next message
   carries the last answer instead. The adapter reports every person's line to the floor from the polling loop, ahead
   of the allowlist (`TelegramBotRunner.reportToFloor`, shared accounts and bots excepted), and asks it at the
   acceptance gate, before a message is claimed and before anything is transcribed, downloaded or classified for it
   (`TelegramBotRunner.isFloorClosed`); private chats and the `OWNER_ID` are never limited, and `handleQueued` — a
   scheduled fire or a pressed button — never asks. It then joins the conversation's line in
   `agent/ConversationLocks` and takes a place from `agent/TurnAdmission`. One turn per conversation runs at a time, because a turn reads the history when it
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
   still has one, and — where the deployment keeps a [diary](groups.md) — `<diary>`, the bot's own
   entries about the chat's last three written-up days. `<recent_chat>` leaves out the triggering message and this user's own exchanges with the bot, both of which the
   prompt already carries — the first as the request itself, the second as replayed `user`/`assistant` turns. Other
   people's messages and the bot's replies to *them* stay, since one person's conversation never contains those. An
   ambient turn keeps this user's exchanges too and cuts the slice right before the message (`recentChatSlice`): with
   no mention and no reply, the order of the lines is the only thing that says whether a bare "which one?" follows the
   bot's last reply or somebody else's line in between, and the replayed history carries no such order.
   `<current_time>` and the chat's `<sticker_catalog>` ride in that same user turn rather than in a system message of
   their own: a system message reads as a higher-priority instruction, which is wrong for context assembled out of what
   people sent; and where the Messages API does take a system message inside the conversation, OpenRouter hands its text
   to Claude as the assistant's own words (measured 2026-10-24), so `llm/anthropic/AnthropicClient` moves every system
   message into the top-level system field regardless of where it sat — the only one it sends inside is a tool offer. In a group the turn also carries what that chat lets the bot post, read through
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
   then admits only complete interactions. The part of them replayed as text, which the next turn replays the same way,
   and the whole of them are the request's stable prefixes (`ChatRequest.stablePrefixes`): a provider that takes cache
   breakpoints gets one at the end of each, since the current message is stored in a shorter shape than it is sent and the
   next turn's request stops repeating this one's right there. If an older prefix no longer fits or exceeds the configured recent count,
   `LlmConversationCompactor` merges it into the persisted `<conversation_recap>` before `AgentFactory.build` creates
   the `AgentTurn`. Every turn is capped on the way into that recap prompt, and a user turn budgets its
   `<user_message>` first: reply metadata and a quoted fragment are written ahead of the request and can outrun the cap
   between them, so capping from the front alone would recap what the user was replying to and not what they asked.
   An Anthropic model's window comes from the vendor's model list at startup, a compatible server's from that server's
   list where it states one, and an OpenAI model's is assumed to be its generation's; `LLM_CONTEXT_WINDOW_TOKENS`
   supplies what nobody stated or overrides any of them, and a chat model whose window nobody stated stops the
   startup rather than run on a guess. With a fallback provider the history is planned against the smaller of the
   two windows, since a fallback taking over mid-turn is sent the same prompt.
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
      flaky provider and still nudged. Silence chosen on purpose — the message asked the bot to be quiet, or is an
      insult it will not dignify — is declared instead, through `staySilent` (`MessageTools`) with a reason for the
      log, which `BotOutbox` accepts only while nothing is queued or in the chat; the turn ends with the batch that
      declared it, so the model is never asked again and cannot write a closing line into a reply it chose not to
      give. The runner stores such a turn in the history, the call and its reason with it, where an undeclared empty
      turn leaves no trace: the next message from that person is read knowing the bot was asked to be quiet;
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
