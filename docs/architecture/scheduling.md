# Scheduled work

What runs on a clock rather than on a message: the task scheduler, maintenance and the agent's own follow-ups.
Part of the [architecture index](../architecture.md).

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
  description a normal turn carries. A follow-up the bot set itself (`selfInitiated`), in a group or a private chat, is dropped instead of
  fired while its person's request to be left alone holds (`LeaveAloneRepository`): it is the one fire that writes to
  them unasked, and a task they asked for fires as usual. Tasks overdue beyond `TaskScheduler.MAX_LATENESS` (e.g. after downtime) get a
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
  their row cap, polls past their retention, diary entries older than a week, and leave-alone requests past their end. Each of these is also pruned where it is written, and that is enough
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
