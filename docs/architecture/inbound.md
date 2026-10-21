# Receiving a message

Steps 1–3 of the [request lifecycle](../architecture.md#request-lifecycle): from a Telegram update to the input a
turn starts from. Next: [running a turn](turn.md).

1. **Receive** — `TelegramBotRunner` long-polls via `TelegramBotsLongPollingApplication`, funnels updates into a
   channel, and dispatches each message by content (text/command, rich message, sticker, voice, audio, photo, video,
   video note, GIF, document). Album (media group) parts arrive as separate updates sharing a `media_group_id`; the
   runner buffers them until that album has been quiet for `ALBUM_QUIET_PERIOD` (or hits the ten-item cap) and handles
   the batch as one gallery message: the caption may sit on any album part, every inspectable item becomes an
   `AttachedFile` on the turn, the sandbox and image editing take them all, and the agent is told that vision sees only
   the first. Plain text messages one person sends in a row — a message the client split at its length cap, a thought
   typed as several messages — are buffered the same way (`LINES_QUIET_PERIOD`, per sender, chat and topic) and, when
   any of them calls the bot, answered as one turn under the last of them (`handleTextBatch`); a run nobody addressed
   is handled line by line as before, so ambient addressing judges what it always did. A command, a reply, a forward or
   a media message from that person flushes their waiting lines first, so nothing is answered out of order. `/start`, `/tasks`, `/clear` and `/stop` take direct paths in `TelegramCommands` that never enter the agent
   loop, once the runner has accepted the message like any other; so do task-menu callback queries. Every pressed button reaches `CallbackRouter`,
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

2. **Filter** — the allowlist (`ALLOWED_IDS`, and the `OWNER_ID` it never needs to list) comes first, in `TelegramBotRunner.passesAllowlist`, on the polling loop
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
   (`telegram/inbound/AmbientCandidates.kt` — typed text or a caption only, never a forward, command, bot or
   channel post; an edit counts only when it names the bot) and asks `agent/addressing/AmbientAddressing`. That puts it to a classifier model of its own only when
   the message names the bot, its author has a turn under way (`AgentRunner.hasTurnUnderWay`), or the bot spoke within
   five minutes and that line is among the six the classifier is shown, read from the group log; nothing else leaves
   the machine. The follow-up door is the bot's own guess, and it stays shut for a person who asked to be left alone
   (`agent/presence/LeaveAloneRepository`); the name door is theirs like anybody's. A failure, a timeout, an unreadable
   answer or a chat over twenty checks a minute all mean no, so it can
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
