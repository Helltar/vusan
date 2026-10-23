# Group flows

What the bot keeps and does in a group beyond answering: the transcript, the diary, initiative, poll answers and
the sticker catalog. Part of the [architecture index](../architecture.md).

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
  gets none. A day whose writer keeps failing is given up on after three tries. The same pass then writes a look-back
  over the last settled Monday-to-Sunday week and calendar month, from that span's daily entries and the look-back
  before it; a span settles the day after its last day closes, once that day's own entry can no longer appear, and
  one with fewer than two entries gets none. `TurnSurroundings` then puts the newest month and week look-backs and
  the newest three entries of the past week into every turn in that group as `<diary>` — defused like the transcript
  they came from — and `Initiative` reads the same block. Entries live in `chat_diary` and look-backs in
  `chat_diary_look_back`, with no retention of their own, outliving the transcript, until
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
  past what the look put in front of it. A person who asked to be left alone (`LeaveAloneRepository`, written by the
  `leaveAlone` tool of the turn that heard them) is out of a look's reach by code: their lines carry no
  number, count as nothing new — a chat where only they write gets no look — are left out of the people asked after,
  and are named to the look under `<left_alone>` so a line of its own is not meant for them either. The answer is one JSON object — `silent`, `react` (one emoji from Telegram's
  free set on one numbered line), `say` (one short plain-text line, optionally a reply to a numbered line) or `sticker`
  (one entry of the chat's `<sticker_catalog>`, the shortlist a turn is shown, optionally under a numbered line; an id
  the catalog cannot resolve sends nothing) — and anything unreadable, failed or slower than ninety seconds is silence.
  What it writes is bounded per chat per day (four lines, a sticker being one, twenty reactions) and its own lines are at least ninety minutes apart, so a lively hour
  cannot take the whole day's count; a decision over either bound is dropped. The line goes out
  through `OutputDelivery.deliverUnprompted`, so it lands in the group transcript like any other bot line and a reply to
  it starts an ordinary turn with that line as `<reply_context>`. A chat that turns the bot away is left alone until a
  restart. State — the last look, the next one, today's counts — is process memory. Every look and every skipped one leaves a log line; see
  [Initiative](../configuration.md#initiative).
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
- **Sticker catalog** — `telegram/tools/sticker/StickerCatalog` learns which sticker sets a chat uses, over the rows
  `StickerRepository` keeps. The Bot API has no sticker
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
