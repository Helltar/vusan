# Context budget

How a turn's history and tool results are fitted into the model's window. Part of the [architecture index](../architecture.md).

- **History compaction** — `agent/conversation/ConversationPlanner` picks the history a prompt carries:
  `ConversationPlan.planConversation` token-budgets complete recent interactions, and `LlmConversationCompactor` rewrites the previous recap plus the next omitted interaction prefix into a
  standalone semantic recap and advances a database checkpoint only after that model call succeeds. It runs at most once
  per turn, because it is an extra LLM round trip in front of the user's reply; a prefix that still does not fit stays
  out of the prompt and gets its own recap on a later turn. Failed compaction never deletes source rows. Raw transcript
  retention and model-visible context are deliberately separate. The recap is injected at user priority so mixed
  user/assistant history is not mislabeled as an assistant instruction. An initial context-overflow failure retries once
  with recap only, but never after a tool ran, which avoids duplicated actions.
- **Live tool-result budget** — `ContextWindowPolicy.liveToolResultMaxChars` caps everything the tools return during one
  run, converting the agent reserve back to characters at the same ratio `estimateTokens` reads them. It scales
  with the window on purpose: a fixed ceiling starves a large-window model, since a single full-length YouTube
  transcript would consume the whole run and leave later tool results with nothing. What is left of it during a run
  lives in `agent/TurnToolBudget.kt`: the loop charges each result against it, `checkContextBudget`
  (`tools/context/`) is how the model reads it before deciding how much to ask for, and `TurnToolBudget.report()` is the
  single wording both that tool and the run's own low-reserve notice state it in.
