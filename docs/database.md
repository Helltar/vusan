# The database

Vusan keeps everything in one SQLite file (`DATABASE_PATH`, `data/vusan.db` by default), opened in WAL
mode. [`infra/Database.kt`](../src/main/kotlin/com/helltar/vusan/infra/Database.kt) is the only place
that opens it, and application code reaches it through `Db.dbTransaction { … }`.

## Schema versions

The shape the code expects lives in
[`infra/Schema.kt`](../src/main/kotlin/com/helltar/vusan/infra/Schema.kt): the tables, the current
`VERSION`, and a `Migration` per version above the first. The database carries the version it actually
has in SQLite's own `PRAGMA user_version`, which starts at `0` and is stamped as part of the same
transaction that changes the schema.

`Db.connect` reads that number and does one of four things:

| `user_version` | What happens |
|---|---|
| equal to `Schema.VERSION` | nothing — the database already has this shape |
| `0`, no tables | every table is created and the version stamped |
| `0`, tables present | **startup fails**: this is a database from before versions, see below |
| below `VERSION` | each missing `Migration` runs in order, stamping as it goes |
| above `VERSION` | **startup fails**: an older build must not write a newer schema |

Nothing is inferred by comparing declarations to what is there. Adding a column that way worked;
changing a key, a type or a table's name never did, and two installations running the same code could
end up with different physical schemas.

## Changing the schema

1. Change the table objects under `infra/tables/`.
2. Raise `Schema.VERSION` by one.
3. Add the `Migration` that reaches it — the SQL that turns the previous shape into the new one, applied
   to a database that already holds someone's data.
4. Cover both paths in `DatabaseMigrationTest`: a fresh database, and one at the previous version.

A migration runs inside the connect transaction: if it throws, the database keeps the version it had.

## Moving a pre-versioning database across

Databases written before this existed have `user_version = 0` and cannot be reshaped automatically, so
the bot refuses to open one. The move is a copy into a fresh database rather than a rewrite in place —
the new file gets its keys, column types and version from the code itself.

1. Stop the bot and copy `vusan.db` somewhere safe.
2. Start the new build once with `DATABASE_PATH` pointing at a **new** file, so it creates and stamps
   an empty database of the current shape, then stop it again.
3. Look at the old schema (`sqlite3 vusan-old.db .schema`) before copying: a table that predates
   [platform-qualified owners](architecture.md#layers) has no `platform` column, and `'TELEGRAM'` is
   what belongs in its place in the `SELECT` below.
4. Copy the rows in, with `sqlite3 vusan-new.db`:

```sql
ATTACH 'vusan-old.db' AS old;
BEGIN;

INSERT INTO conversation_messages
  (id, platform, user_id, chat_id, interaction_id, role, content, tool_call_id, tool_name, tool_is_error, created_at)
SELECT id, platform, user_id, chat_id, interaction_id, role, content, tool_call_id, tool_name, tool_is_error, created_at
FROM old.conversation_messages;

-- conversation_state and conversation_summaries are one row per conversation now
INSERT INTO conversations
  (platform, user_id, chat_id, revision, summary, summarized_through_message_id, summarized_at)
SELECT k.platform, k.user_id, k.chat_id,
       COALESCE(st.revision, 0), su.content, COALESCE(su.through_message_id, 0), su.updated_at
FROM (SELECT platform, user_id, chat_id FROM old.conversation_state
      UNION
      SELECT platform, user_id, chat_id FROM old.conversation_summaries) AS k
LEFT JOIN old.conversation_state st
  ON st.platform = k.platform AND st.user_id = k.user_id AND st.chat_id = k.chat_id
LEFT JOIN old.conversation_summaries su
  ON su.platform = k.platform AND su.user_id = k.user_id AND su.chat_id = k.chat_id;

INSERT INTO group_log
  (id, platform, chat_id, message_id, thread_id, sender_id, sender_username, sender_name,
   kind, text, descriptor, forward_from, reply_to_message_id, sent_at)
SELECT id, platform, chat_id, message_id, thread_id, sender_id, sender_username, sender_name,
       kind, text, descriptor, forward_from, reply_to_message_id, sent_at
FROM old.group_log;

-- the surrogate id is gone from the cache and the sticker link tables: their own columns are the key
INSERT INTO group_log_digests (platform, chat_id, day, message_count, content, created_at)
SELECT platform, chat_id, day, message_count, content, created_at FROM old.group_log_digests;

-- a task that has fired for the last time is deleted now, so the switched-off ones are not carried over
INSERT INTO scheduled_tasks
  (id, platform, user_id, chat_id, title, prompt, recurrence, timezone, next_fire_at, paused, created_at,
   self_initiated, chat_is_private, language, creator_message_id, creator_thread_id, creator_username, creator_display_name)
SELECT id, platform, user_id, chat_id, title, prompt, recurrence, timezone, next_fire_at, paused, created_at,
       self_initiated, chat_is_private, language, creator_message_id, creator_thread_id, creator_username, creator_display_name
FROM old.scheduled_tasks WHERE enabled = 1;

INSERT INTO memories (id, platform, scope, owner_id, content, created_at)
SELECT id, platform, scope, owner_id, content, created_at FROM old.memories;

INSERT INTO stickers
  (id, file_unique_id, file_id, set_name, emoji, thumbnail_file_id, description, describe_attempts, created_at)
SELECT id, file_unique_id, file_id, set_name, emoji, thumbnail_file_id, description, describe_attempts, created_at
FROM old.stickers;

INSERT INTO sticker_sets (name, refreshed_at) SELECT name, refreshed_at FROM old.sticker_sets;

INSERT INTO chat_sticker_sets (chat_id, set_name, seen_count, last_seen_at, learned_at)
SELECT chat_id, set_name, seen_count, last_seen_at, learned_at FROM old.chat_sticker_sets;

INSERT INTO chat_stickers (chat_id, file_unique_id, seen_count, last_seen_at)
SELECT chat_id, file_unique_id, seen_count, last_seen_at FROM old.chat_stickers;

INSERT INTO token_usage (day, input_tokens, output_tokens, updated_at)
SELECT day, input_tokens, output_tokens, updated_at FROM old.token_usage;

INSERT INTO token_user_spend (day, platform, user_id, input_tokens, output_tokens, updated_at)
SELECT day, platform, user_id, input_tokens, output_tokens, updated_at FROM old.token_user_spend;

INSERT INTO polls (poll_id, chat_id, options, correct_option_index, created_at)
SELECT poll_id, chat_id, options, correct_option_index, created_at FROM old.polls;

COMMIT;
DETACH old;
```

`pending_updates` is deliberately not copied: it is the spool of updates the bot has not started yet, and
after a stop worth migrating for, replaying them is not what anyone wants.

5. `PRAGMA integrity_check;` and `PRAGMA user_version;` on the new file — the second must print the
   version the build expects — then put it where `DATABASE_PATH` points and start the bot.

The copy also settles what the old file could not: a reference the platform issued (a message id, a
reply anchor) lands in a `VARCHAR` column here rather than the numeric one it used to share with real
numbers, so nothing about it is rounded or reinterpreted on the way back out.
