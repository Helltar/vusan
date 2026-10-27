# Vusan in a chat

How Vusan behaves with people, in private chats and in groups. None of it needs a setting unless a
section points to one in [the configuration](configuration.md).

## History and memory

History is kept per person **and per chat**, and holds only the exchanges Vusan took part in.
Someone active in a DM and two groups has three threads, each with its own recap.

- **What the model sees** — the last 40 interactions word for word, as many as fit the window; older
  ones are folded into a running recap.
- **`/clear`** — removes the transcript and recap of the chat it was sent in, for that person only.
  Memory and scheduled tasks stay.
- **Memory** — outlives `/clear`. Personal entries follow a person into every chat; group entries
  belong to the chat. Each holds up to twenty entries and drops the oldest past that. An entry is at
  most 500 characters; a longer one is refused rather than cut, so Vusan writes it again shorter.
- **Across chats** — what someone said in a DM is not replayed in a group, and two groups never see
  each other's exchanges, so asking Vusan to remember something is how it follows you everywhere. A
  person's [sandbox](sandbox.md) is shared across chats too.
- **Shared accounts** — neither memory nor the sandbox is offered to an anonymous group admin or a
  linked channel.

## Scheduled tasks

The agent schedules tasks in three forms:

- **`once <datetime>`** — fires once, then is disabled.
- **`every <interval>`** — a fixed interval of at least 5 minutes, independent of time zone.
- **`cron <UNIX expr>`** — clock times, in the task's time zone.

How they run:

- **How many** — ten per person. The agent's own follow-ups ("ask how the exam went") have a limit
  of their own, three per person, and appear in `/tasks` where they can be canceled.
- **Missed runs** — a task that could not fire within an hour of its time, because Vusan was offline,
  is not run late: the chat gets a missed notice and the schedule moves on.
- **Access** — a task whose owner and chat both lost access does not fire. A recurring one skips
  those runs and resumes when access returns; a one-time one waits, or is reported missed if that is
  over an hour late.
- **`/tasks` in a group** — shows only the tasks its sender made in that chat, seen by them alone.

## While it is busy

- **One person, one at a time** — one person's messages in a chat are answered in order, so a
  follow-up sent mid-answer starts with that answer already known. Up to three wait like that; the
  next is told to hold on.
- **Many people** — past the limit on requests at once, people wait their turn with the usual typing
  indicator. Only when the queue is several times the limit does Vusan say it is overloaded.
- **Scheduled tasks** — always wait instead of being refused, since refusing one would skip the run.
- **`/stop`** — ends a running answer, and stays visible in a group so the chat sees why Vusan went
  quiet; `/tasks` and `/clear` there are seen only by whoever used them.

## In a group

### What it hears

With the [group log](configuration.md#group-log) on, Vusan records every message it receives.

- **Every turn** — a group turn carries the last fifteen messages of the past hour.
- **Long ranges** — a range too big for the context is read through daily recaps, made once per
  closed day and kept. The current day is never cached.
- **Searching** — asked whether or when something came up, Vusan searches every recorded message
  for it, whatever the case, and quotes what it finds instead of a recap.
- **What a row holds** — the text (up to 2,000 characters, 1,000 for a forwarded post), sender and
  time, a short label for anything else (`🐤 UtyaDuck`, `0:14`, `report.pdf`), where a forward came
  from and what it replied to. Vusan's own replies are recorded; replies sent to someone's DMs are not.
- **Forgetting** — asking the agent to forget the group log wipes the chat's messages and recaps.
  `/clear` does not touch it: the log belongs to the group, not to whoever ran the command.

### Answering without a mention

With [answering without a mention](configuration.md#answering-without-a-mention) on:

- **Names** — a name matches at the start of a word, in any case, so a grammatical ending still
  counts.
- **What is asked about** — a message that names Vusan, one from someone it is still answering, and
  any message within five minutes of its reply while that reply is among the last six lines.
- **What is skipped** — voice messages, stickers, forwards, commands, and messages from bots or
  channels. An edit counts only when it adds the name, within the same five minutes.
- **Limits** — at most twenty verdicts a minute per chat; past that, on an error or a verdict slower
  than five seconds, Vusan stays out.
- **While Vusan is busy** — a message that called it waits its turn like a mention. If the answer
  before already covered it, or it was for someone else, Vusan may stay silent.

### Speaking on its own

With [initiative](configuration.md#initiative) on, most looks end in nothing, which is the point: it
should feel like a member of the chat, not a notification.

- **When it looks** — at a chat someone wrote in during the last fifteen minutes, after a random
  pause of fifteen to forty-five minutes, with at least three new messages since its last look or
  line. Not while it is answering someone there, and not within five minutes of its own last line.
- **What it may do** — nothing, one reaction, one short message — a joke on what was just said, an
  opinion, a question about someone who has gone quiet — or one sticker from the chat's catalog.
- **How often** — at most four lines a day per chat, at least ninety minutes apart, and twenty
  reactions; no looks from one to eight in the morning.
- **Telling it to stop** — it reads the chat first and stays out when someone asked it to be quiet.
- **After a restart** — the day's counts start over, and the first look waits one pause.
- **Forum groups** — a reply lands in that message's topic; a standalone line goes to General.

### One person alone

Somebody chatting with Vusan alone at four in the morning leaves the rest of the group a hundred
messages to scroll past at breakfast. So once Vusan has answered one person ten times in a row with
nobody else writing in that chat or topic, its tenth reply rounds things off and says it will pick up
later, and that person's next messages go unanswered.

The floor opens again as soon as anybody else writes in the chat, or after an hour without an answer
from Vusan. Scheduled tasks, initiative, commands and buttons are not affected. Private chats are
never limited, and neither is the owner.

### What the group allows

Before each turn Vusan reads what the group lets it post: a chat that forbids photos is never offered
image generation, one that forbids polls gets no poll tools, and slow mode makes the answer one
message instead of several Telegram would drop. A lookup that fails counts as unrestricted, so a
Telegram hiccup never strips tools. What a group admin can change is in
[Rights in a group](configuration.md#rights-in-a-group).

## Leaving someone alone

Anyone can tell Vusan not to write to them — «leave me alone», «stop writing to me» — and it keeps to
that in the chat it was said in: until the end of the day and for at least twelve hours, or for as
long as they asked, up to a week. Meanwhile nothing of its own reaches them: no reactions to their
messages, no unprompted replies, no follow-ups on their lines, and planned check-ins are dropped. In a
private chat, the check-ins are what stops.

- **Talking to it anyway** — a message they send it themselves is answered as usual.
- **Lifting it** — «you can write to me again» ends the request early. It survives a restart.
- **Silence as an answer** — told to be quiet, or insulted, Vusan may also answer with nothing at
  all, and the next message from that person is read knowing it was asked to be quiet.

## Banned people

A [banned](configuration.md#who-vusan-answers) person gets no reply, dead buttons, nothing in the
group log and no stickers learned; their tasks stay in place but do not fire. Lifting the ban
restores all of it.
