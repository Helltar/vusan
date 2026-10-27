# Context budget

How a turn's history and tool results are fitted into the model's window. Part of the [architecture index](../architecture.md).

- **History compaction** — `agent/conversation/ConversationPlanner` picks the history a prompt carries:
  `ConversationPlan.planConversation` token-budgets complete recent interactions, and `LlmConversationCompactor` rewrites the previous recap plus the next omitted interaction prefix into a
  standalone semantic recap of at most 15,000 characters (12,000 asked) and advances a database checkpoint only after
  that model call succeeds. A recap is an extra LLM round trip, so it runs in front of the user's reply only when the
  history does not fit the prompt (`ConversationPlan.overBudget`), at most once per turn. One that only the count of
  40 raw interactions asks for is queued by `AgentRunner` once the turn is stored and made by the planner's worker
  (`launchRecapsIn`, started in `Main`), one conversation at a time, with nobody waiting on it; the checkpoint refuses
  it if a turn or a `/clear` got there first. A prefix that still does not fit stays out of the prompt and gets its own
  recap on a later turn. Failed compaction never deletes source rows. Raw transcript
  retention and model-visible context are deliberately separate. The recap is injected at user priority so mixed
  user/assistant history is not mislabeled as an assistant instruction. An initial context-overflow failure retries once
  with recap only, but never after a tool ran, which avoids duplicated actions.
- **Continuing the last request** — on a model whose provider keeps no cache breakpoints
  (`LlmModel.cachesOnlyRepeatedPrefix`: the Codex backend, DeepSeek's own API, Grok through OpenRouter) a turn rebuilt from storage reads nothing of the history
  back, since the stored history is shorter than what was sent. There `agent/conversation/SentConversations` keeps
  each conversation's last request exactly as it went, with what it returned, and the next turn sends that before
  its own message instead of the replayed history, so it only appends. It is a copy, never a second history: the
  stored one is still what recaps, other providers and every rebuilt turn read, and a kept request is continued only
  while the newest stored interaction is the one it ended with, the system prompt and the memory the turn carries
  are unchanged, it fits the history budget and it is at most 16,000 tokens larger than the stored history would
  be, and holds no stored turn past the raw history's retention — otherwise the turn is rebuilt and pays for the
  history once. A cleared group log drops every request kept for that chat, since their messages carry what it said. The earlier turns' results keep their labels in
  it, so the continuing turn numbers its calls on from there (`TurnShelf.numberFrom`) and an old label is refused.
  Kept in memory for the 128 conversations seen last; `/clear` drops it, and a restart rebuilds every conversation.
- **Live tool-result budget** — `ContextWindowPolicy.liveToolResultMaxChars` caps everything the tools return during one
  run, converting the agent reserve back to characters at the same ratio `estimateTokens` reads them. It scales
  with the window on purpose: a fixed ceiling starves a large-window model, since a single full-length YouTube
  transcript would consume the whole run and leave later tool results with nothing. What is left of it during a run
  lives in `agent/TurnToolBudget.kt`: the loop charges each result against it, `checkContextBudget`
  (`tools/context/`) is how the model reads it before deciding how much to ask for, and `TurnToolBudget.report()` is the
  single wording both that tool and the run's own low-reserve notice state it in.
