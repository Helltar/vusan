# Delivery

Step 7 of the [request lifecycle](../architecture.md#request-lifecycle): how a turn's outbox reaches the chat.
Before it: [running a turn](turn.md).

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
      by the action matching its own content (`botActionFor`). A turn with nothing to show for a while — one that joins
      the person's line behind a running turn, or one in a slow-mode chat, where no bubble opens for an activity — marks
      its message as received with a 👀 reaction the moment it is admitted (`TurnStatus.acknowledge`) and takes it off
      when the status closes; the reply, and any reaction the model chose, land after that.
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
    - **A send nobody answered** — a timeout or a dropped connection with no API error behind it
      (`TelegramErrors.isUnanswered`) is not a rejection: Telegram may have delivered the message and only the answer
      was lost, which is common for a video it is still processing. The fallbacks rethrow it rather than send the
      payload again in another shape, and `TelegramDelivery` treats the item as possibly arrived — no transcript row,
      and the caption that rode on it is not repeated as a message. The client waits `TELEGRAM_IO_TIMEOUT` (`Main.kt`)
      for an answer instead of OkHttp's ten seconds, so this stays rare.
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
      media-to-document, text-as-document, an ephemeral reply sent again without the anchor Telegram refused), `TelegramRequests.kt` the raw request builders.
