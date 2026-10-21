# Commands and buttons

Flows that bypass the model or come back from a pressed button: `/tasks`, `/stop`, `/clear` and the agent's own
inline choices. Part of the [architecture index](../architecture.md).

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
