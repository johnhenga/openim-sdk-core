# Part 4 — Local Persistence, Sync Engine & Caching

> Local DB schemas are facts and safe to specify as field/type/meaning tables.
> Algorithms below are behavioral descriptions, not code. Persistence is a local
> SQLite DB named `OpenIM_<bigVersion>_<loginUserID>.db` (per logged-in user).
> Connection pool guidance: max 3 open, 2 idle, idle timeout ~10 min, lifetime ~1 hour.
> A bootstrap table records the installed SDK version; first launch creates all
> tables and writes the version row; version change runs a light migration.

## 1. Table schemas

Types are logical: `string(N)` text with length, `int32/int64/uint32/uint64`, `bool`,
`bytes`. PK = primary key (composite noted). Indexes listed where defined.

> **Naming caveat:** tables without an explicit name override derive their name by
> snake_case-pluralizing the entity. **Pin these names explicitly** in Kotlin to
> avoid drift: `local_friend_requests`, `local_group_requests`,
> `local_admin_group_requests`, `local_blacks`, `local_chat_logs`,
> `local_chat_logs_reaction_extensions`, `local_conversation_unread_messages`,
> `local_seq_datas`, `local_seqs`.
> Also keep the misspelled column `app_manger_level` verbatim for DB-file compatibility.

### 1.1 `local_friends` — PK (owner_user_id, friend_user_id)
owner_user_id s(64), friend_user_id s(64), remark s(255), create_time i64,
add_source i32, operator_user_id s(64), name s(255) [nickname], face_url s(255),
ex s(1024), attached_info s(1024), is_pinned bool.

### 1.2 `local_friend_requests` — PK (from_user_id, to_user_id)
from_user_id s(64), from_nickname s(255), from_face_url s(255), to_user_id s(64),
to_nickname s(255), to_face_url s(255), handle_result i32, req_msg s(255),
create_time i64, handler_user_id s(64), handle_msg s(255), handle_time i64,
ex s(1024), attached_info s(1024).

### 1.3 `local_groups` — PK group_id
group_id s(64), name s(255), notification s(255), introduction s(255), face_url s(255),
create_time i64, status i32, creator_user_id s(64), group_type i32, owner_user_id s(64),
member_count i32, ex s(1024), attached_info s(1024), need_verification i32,
look_member_info i32, apply_member_friend i32, notification_update_time i64,
notification_user_id s(64).

### 1.4 `local_group_members` — PK (group_id, user_id); idx role_level, join_time
group_id s(64), user_id s(64), nickname s(255), user_group_face_url s(255),
role_level i32, join_time i64, join_source i32, inviter_user_id s(64),
mute_end_time i64 (0=not muted), operator_user_id s(64), ex s(1024), attached_info s(1024).

### 1.5 `local_group_requests` — PK (group_id, user_id)
group_id s(64), group_name s(255), notification s(255), introduction s(255),
face_url s(255), create_time i64, status i32, creator_user_id s(64), group_type i32,
owner_user_id s(64), member_count i32, user_id s(64), nickname s(255), user_face_url s(255),
handle_result i32, req_msg s(255), handle_msg s(255), req_time i64, handle_user_id s(64),
handle_time i64, ex s(1024), attached_info s(1024), join_source i32, inviter_user_id s(64).

### 1.6 `local_admin_group_requests`
Structurally identical to `local_group_requests` (same columns + composite PK). Holds
requests the local user must review as group admin.

### 1.7 `local_users` — PK user_id
user_id s(64), name s(255), face_url s(255), create_time i64, app_manger_level i32
(not JSON-exported), ex s(1024), attached_info s(1024), global_recv_msg_opt i32.

### 1.8 `local_stranger`
Same columns as `local_users`; singular table name. Profile info for non-friends.

### 1.9 `local_blacks` — PK (owner_user_id, block_user_id)
owner_user_id s(64), block_user_id s(64), nickname s(255), face_url s(255),
create_time i64, add_source i32, operator_user_id s(64), ex s(1024), attached_info s(1024).

### 1.10 `local_chat_logs` — PK client_msg_id; idx recv_id, content_type, seq, send_time
client_msg_id s(64), server_msg_id s(64), send_id s(64), recv_id s(64),
sender_platform_id i32, sender_nick_name s(255), sender_face_url s(255), session_type i32,
msg_from i32, content_type i32, content s(1000), is_read bool, status i32, seq i64
(default 0), send_time i64, create_time i64, attached_info s(1024), ex s(1024),
local_ex s(1024).
> May be sharded per conversation; the row shape above is canonical. One table or
> per-conversation tables both acceptable, but preserve columns + indexes.

### 1.11 `local_chat_logs_reaction_extensions` — PK client_msg_id
client_msg_id s(64), local_reaction_extensions bytes.

### 1.12 `local_conversations` — PK conversation_id; idx latest_msg_send_time
conversation_id s(128), conversation_type i32, user_id s(64), group_id s(128),
show_name s(255), face_url s(255), recv_msg_opt i32, unread_count i32, group_at_type i32,
latest_msg s(1000), latest_msg_send_time i64, draft_text s, draft_text_time i64,
is_pinned bool, is_private_chat bool, burn_duration i32 (default 30), is_not_in_group bool,
update_unread_count_time i64, attached_info s(1024), ex s(1024), max_seq i64, min_seq i64,
msg_destruct_time i64 (default 604800 = 7d), is_msg_destruct bool (default false).

### 1.13 `local_conversation_unread_messages` — PK (conversation_id, client_msg_id)
conversation_id s(128), client_msg_id s(64), send_time i64, ex s(1024).

### 1.14 `local_sending_messages` — PK (conversation_id, client_msg_id)
conversation_id s(128), client_msg_id s(64), ex s(1024). Tracks unconfirmed sends
(for retry/resume).

### 1.15 `local_notification_seqs` — PK conversation_id
conversation_id s(128), seq i64 (last processed notification seq).

### 1.16 `local_seq_datas` / `local_seqs` (auxiliary cursors, optional)
LocalSeqData: user_id s(64) PK, seq u32. LocalSeq: id s(64) PK, min_seq u32.

### 1.17 `local_uploads` — PK part_hash (resumable upload state)
part_hash s, upload_id s(1000), upload_info s(2000), expire_time i64, create_time i64.

### 1.18 `local_sync_version` — PK (table_name, entity_id) — incremental-sync cursors
table_name s(255), entity_id s(255), version_id s (server continuity token),
version u64 (monotonic counter), create_time i64, id_list text (JSON string[] = full
ordered local key set for the scope). **Backbone of incremental sync (§2.2).**

### 1.19 `local_app_sdk_version` — PK version
version s(255), installed bool. Drives first-time creation vs. upgrade migration.

> Auto-created on a fresh DB: app SDK version, friends, groups, group members, users,
> blacks, conversations, notification seqs, chat logs, chat-log reaction extensions,
> uploads, stranger, sending messages, sync version. Request tables + seq bookkeeping
> are managed by their own code paths, not the base migration list.

## 2. Sync engine

Two layers: (A) a generic list reconciler that diffs a server snapshot vs. local and
applies insert/update/delete; (B) a version-based driver that decides the server
snapshot using server-issued version tokens, then hands the result to A.

### 2.1 Generic list reconciler
Parameterized over type `T`, key type `V`, callbacks: `key(T)→V`, `equal(server,local)→bool`
(default deep compare), `insert(server)`, `update(server,local)`, `delete(local)`,
optional `notice(state,server,local)`. State codes: Unchanged 0, Insert 1, Update 2,
Delete 3.

Algorithm given `serverData[]`, `localData[]`:
1. Both empty → nothing.
2. Build map of local keyed by `key()`.
3. For each server item: look up by key.
   - Not found → `insert(server)`; notice(Insert, server, zero). 
   - Found → remove from local map (mark seen). If `equal` → notice(Unchanged, **local, server**)
     (note swapped order). Else → `update(server,local)`; notice(Update, server, local).
4. Deletion pass: each key remaining in local map → `delete(local)`; notice(Delete, zero, local).

Flags: **skipDeletion** (skip step 4 — append-only/partial sync); **skipNotice** (silent).
Callback errors abort and propagate. Notices fire only after the DB mutation succeeds;
the per-call `notice` runs after the syncer's built-in one.

**FullSync variant:** delete all local rows for a scope, page through the server API,
batch-insert. Used when incremental state is missing/invalid or server signals "full".

### 2.2 Version-based incremental driver
Maintains `local_sync_version` cursors and produces the server snapshot. Callbacks
extract from a response `R`: `version→(versionID, version)`, `delete→[]key`,
`update→[]V`, `insert→[]V`, `full→bool`, optional `extraData`/processor,
`idOrderChanged→bool`; plus `fullSyncer`, `fullID` (recompute ordered key list), `key(V)`.

**IncrementalSync (pull-driven):**
1. Read stored cursor (absent = fresh: version 0, empty version_id, empty id_list).
2. Obtain `R` (call server with cursor, or use pushed response).
3. Extract delIDs, changes, inserts, full flag, extraData.
4. All empty & not full & no extraData → done.
5. If full → run full syncer, recompute id_list via `fullID`.
6. Else merge: (a) remove delIDs from id_list; (b) merge inserts into changes, append
   new keys to id_list; (c) read local, map by key; (d) apply changes/inserts (overwrite),
   remove delIDs; (e) map values = reconstructed server snapshot → pass to reconciler
   (§2.1) which does the DB writes + notices; (f) run extraDataProcessor; (g) if
   `idOrderChanged` recompute id_list.
7. Persist updated cursor (versionID, version, id_list).

**CheckVersionSync (push-driven):**
1. Read cursor; extract pushed versionID/version + diffs.
2. Nothing & not full → return (anomalous).
3. **versionID mismatch** → data diverged/tampered → fall back to fresh IncrementalSync (pull).
4. **storedVersion+1 == pushedVersion** → apply diff directly (same merge+reconcile), persist.
5. **pushedVersion ≤ storedVersion** → stale/duplicate → ignore.
6. **pushedVersion > storedVersion+1** (gap) → fall back to fresh IncrementalSync.

**Version contract:** `version_id` is a continuity token — any change means lineage
reset → full pull. `version` is strictly increasing — direct apply only when it advances
by exactly 1; gaps force pull; equal-or-lower ignored. `id_list` is authoritative ordered
membership, rebuilt on full syncs or whenever ordering changes.

### 2.3 Change notifications
The reconciler is the single emission point (`notice(state, server, local)`). Higher
layers translate these into outward listener events (friend added/updated/deleted,
conversation changed, group member changed, …). Unchanged notices still fire (unless
skipNotice) so callers can refresh derived/display state.

## 3. Caching

In-memory only, concurrency-safe, read-through; a performance layer over the DB with no
independent persistence and no TTL eviction.

- **Base cache:** generic concurrent key→value map. Ops: Load, Store, conditional Store,
  StoreAll (bulk via key-extractor), LoadOrStore, Delete, DeleteAll, conditional Delete,
  RangeAll, conditional Range. No size bound, no auto-expiry — entries live until deleted.
- **User cache (read-through):** wraps base with batch-DB, single-DB, and remote-query
  loaders. `Fetch(key)`: cache → DB → remote, then store. `BatchFetch(keys)`: hits from
  cache; misses via batch DB, then remote for any DB lacked; unsatisfiable miss → "user
  not found". "Special user" namespace: `"special_"`-prefixed keys for injected/override
  records (system/notification accounts) — never falls through to DB/remote.
- **Conversation seq context cache:** per-conversation "end seq" cursor by view type
  (ViewHistory 0, ViewSearch 1); key `<conversationID>::viewType::<n>` → int64 seq.
  Remembers pagination position so history vs. search don't interfere.
- **Lifecycle:** process-lifetime, lazily populated, explicitly updated/invalidated by
  write paths and sync notices. **Clear on logout / user switch** (DB is per-user; a new
  login starts fresh). No background eviction — correctness relies on writers updating or
  deleting affected keys.
