# OpenIM SDK — Kotlin Multiplatform core

Kotlin Multiplatform (KMP) port of the Go SDK core. Strategy, phasing, and
rationale live in
[`docs/kotlin-multiplatform-migration.md`](../docs/kotlin-multiplatform-migration.md).

## Supported platforms

**Android and iOS only.** One shared Kotlin codebase compiles to an Android
library (AAR) and an iOS framework. Web/WASM and desktop are explicitly out
of scope — they continue to use the original Go core. (A `jvm()` target also
exists, but only so the test suites can run in CI without emulators; it is
not a supported app platform.)

## How this SDK works (in simple terms)

This is the engine inside a chat app. The app's UI talks to the SDK; the SDK
talks to the OpenIM server and to a local database on the phone. The design
is **local-first**: the UI always reads from the local SQLite database, and
the SDK's whole job is to keep that database in sync with the server.

```
                ┌──────────────────────  your app (UI)  ─────────────────────┐
                │   calls (login, send message, list conversations)          │
                │   events (new message, conversation changed, unread count) │
                └──────────────────────────┬──────────────────────────────────┘
                                           │
   ┌───────────────────────────────  SDK core  ───────────────────────────────┐
   │  OpenIMSdk / OpenIMEngine  — wires everything together                   │
   │                                                                          │
   │  LongConnManager      one persistent websocket to the server             │
   │   └─ GobFrameCodec    encodes/decodes the wire format (gob + protobuf)   │
   │  MsgSyncer            decides WHICH messages are missing (seq numbers)   │
   │  MessageIngestor      stores pulled/pushed messages, handles duplicates  │
   │  ConversationTrigger  updates unread counts & conversation previews      │
   │  MessageSender        the outgoing path (send → ack → status update)     │
   │  Friend/Group/Conversation syncs   incremental contact-list sync (HTTP)  │
   └──────────────┬──────────────────────────────────────┬────────────────────┘
                  │ websocket (messages, real-time)       │ HTTPS (contacts,
                  ▼                                       ▼  groups, settings)
            OpenIM server                            OpenIM server API
                  ▲
                  │ everything lands in
                  ▼
        local SQLite database  (same file format as the Go SDK —
        an app upgrading from the Go core keeps all its chat history)
```

The key ideas, one at a time:

1. **Every message has a sequence number (seq).** The server numbers each
   conversation's messages 1, 2, 3… The SDK remembers the highest seq it has
   stored per conversation. Syncing is then simple arithmetic: if the server
   says the conversation is at seq 50 and we have 42, pull 43–50. If a
   pushed message arrives with seq 45 while we're at 42, that's a *gap* —
   pull 43–45 before showing anything, so messages never appear out of
   order or go missing.

2. **One long-lived websocket** carries real-time traffic. Requests
   (e.g. "pull messages 43–50") and the server's pushes both travel as small
   binary envelopes; the payload inside is protobuf. The connection
   heartbeats (ping/pong) and reconnects with backoff when it drops; every
   reconnect triggers a quick "what's the latest seq?" catch-up.

3. **Contacts sync incrementally over HTTPS.** Friends, groups, group
   members, and conversation settings each carry a server-side *version*.
   The SDK sends "I'm at version N" and gets back just the inserts/updates/
   deletes since then — or a "too far behind, resync everything" flag.

4. **The database is the source of truth for the UI.** Incoming messages are
   written to SQLite first (deduplicated, with placeholders for deleted
   messages), then conversation rows are updated (latest-message preview,
   unread count), and only then does the app get an event saying "this
   conversation changed". Killing the app loses nothing.

5. **Sending is optimistic.** An outgoing message is stored locally as
   *Sending* and recorded in a pending table before it goes on the wire; the
   server's ack upgrades it to *Sent* (with the server's timestamp and ID),
   and a failure marks it *Failed* so the UI can offer retry. If the network
   times out, the SDK checks whether the ack arrived by another route before
   declaring failure — so flaky networks don't cause duplicate sends.

6. **Wire- and disk-compatible with the Go SDK.** The websocket frames, the
   HTTP JSON, and the SQLite schema are byte-identical to what the original
   Go core produces (enforced by golden tests in CI), so this core can talk
   to existing OpenIM servers and open databases written by the Go SDK.

## Layout

```
kmp/
  core/
    src/commonMain/kotlin/io/openim/core/
      api/        Typed API surface (OpenIMClient) + gomobile-compatible
                  callback shim (api/compat) mirroring open_im_sdk_callback/
      db/         GoSdkSchema (verbatim Go SDK DDL), ChatLogStore (dynamic
                  per-conversation chat_logs_* tables), driver factories
      network/    LongConnManager — coroutine port of
                  internal/interaction/long_conn_mgr.go
      sync/       Syncer + VersionSynchronizer — ports of pkg/syncer
    src/commonMain/sqldelight/   Typing-only .sq mirrors of the static tables
    src/androidMain, src/iosMain Platform drivers and actuals
    src/commonTest               Pure-logic tests (Syncer semantics, …)
```

## Database compatibility (the load-bearing invariant)

Databases must remain interchangeable with the Go SDK so upgraded apps keep
local chat history:

- File name: `OpenIM_v3_<loginUserID>.db` (same as `pkg/db/db_init.go`).
- Static schema: created from the **verbatim** GORM DDL embedded in
  `GoSdkSchema.kt`. The SQLDelight `.sq` files are typing-only mirrors and are
  **not** used to create the schema.
- Ground truth: `docs/schema/go-sdk-schema.sql`, regenerated from the Go
  models with `go run ./tools/schemagen docs/schema/go-sdk-schema.sql`
  (run from the repo root). If the Go models change, regenerate and update
  `GoSdkSchema.kt` + the `.sq` mirrors together.
- Messages live in dynamic per-conversation tables
  (`chat_logs_<conversationID>`), handled by `ChatLogStore` with DDL
  byte-identical to `pkg/db/chat_log_model.go initChatLog`.

## Building

Requires JDK 17+, Android SDK (for the Android target), and Xcode (for the
iOS targets, macOS host only).

```bash
cd kmp
gradle :core:allTests          # common logic tests
gradle :core:assembleRelease   # Android AAR
gradle :core:linkReleaseFrameworkIosArm64   # iOS framework
```

Dependency versions in `gradle/libs.versions.toml` are pinned at scaffold
time; verifying/bumping them is part of the Phase 0 CI setup. Note: the
Android Gradle Plugin resolves from Google Maven (`dl.google.com`) — build
environments must allow that host in addition to Maven Central.

## Status

Phase 0/1 foundation scaffold:

- [x] Migration design doc + ground-truth schema extraction
- [x] Project scaffold (Gradle, targets, SQLDelight, Ktor, coroutines)
- [x] Schema bootstrap byte-compatible with the Go SDK — **verified**: a
      database created by `GoSdkSchema` produces `sqlite_master` DDL
      byte-identical to `docs/schema/go-sdk-schema.sql` (all 21 objects)
- [x] Dynamic chat-log store (raw-query path) — **verified** against a real
      SQLite database (insert / getBySeqs / maxSeq / paging / lazy table +
      index creation)
- [x] Syncer port with tests — **verified**: insert/update/delete/unchanged,
      skipDeletion, skipNotice semantics match `pkg/syncer/syncer.go`
- [x] VersionSynchronizer — **verified**: faithful port of
      `version_synchronizer.go` IncrementalSync (server-driven full flag,
      id_list maintenance, local ⊕ changes ⊖ deletions reconciliation
      through Syncer, idOrderChanged refresh, version row untouched when
      nothing changed); pinned by tests instantiated for friends
- [x] LongConnManager coroutine prototype (Go constants mirrored);
      common sources compile-verified with kotlinc 2.1.21
- [x] Typed API + compat callback interfaces (signatures checked against
      `open_im_sdk_callback/callback_client.go`)
- [x] Frame codec (gob envelope + gzip) — **verified byte-identical** to
      Go's `internal/interaction/encoder.go` against golden vectors generated
      by `tools/gobgolden` (`core/testdata/gob-golden.txt`); gzip interops
      with Go `compress/gzip`. iOS zlib actual pending CI compilation.
- [x] Message sync foundation — **verified**: `MsgSyncCalculator` ports the
      seq-range arithmetic of `msg_sync.go` (need-sync computation incl. the
      reinstall/notification special case, gap enumeration, pull batching)
      and `MaxSeqRecorder`; behavior pinned by tests
- [x] Protocol layer — protos vendored from openimsdk/protocol at the
      Go-pinned tag (v0.0.73-alpha.12, `kmp/protocol/`), Wire plugin wired
      into the build. **Verified**: Wire 5.1.0 generates 504 Kotlin classes
      from the vendored set, all compile, and generated code decodes
      Go-protobuf-marshaled `sdkws` bytes and re-encodes them
      byte-identically (`tools/protocheck`)
- [x] Request routing — ReqIdentifier constants mirrored from
      `pkg/constant`; LongConnManager routes inbound envelopes like Go's
      `handleMessage` (push / kick / logout / pending-response)
- [x] WsMsgSyncTransport — **verified**: MsgSyncTransport over the gob
      envelope (protobuf in GeneralWsReq.data, errCode → ServerException),
      connecting MsgSyncer to LongConnManager; pinned by tests
- [x] MsgSyncer orchestrator — **verified**: port of `msg_sync.go` over
      injected transport/store/listener interfaces, using the Wire-generated
      protobuf types. Behavior pinned by fake-server tests: on-connect
      catch-up (connectPullNums=1, 3×-retry with backoff, sync flags),
      push handling (contiguous trigger / gap pull / seq-0 online-only),
      reinstall (notification seq recording + markInstalled), 5s sync
      debounce, and SplitPullMsgNum pull batching
- [x] History gap-check helpers — **verified**: `MessageGapCheck` ports the
      pure functions of `message_check.go` (have-seq scan ignoring seq-0,
      lost-seq computation capped at PullMsgNumForReadDiffusion=50 with
      direction-aware trimming, sendTime+seq ordered merge); pinned by tests
- [x] Message storage pipeline — **verified**: `MessageIngestor` ports
      `pullMessageIntoTable`/`handleExceptionMessages` (own-send seq
      backfill, [SEQ_GAP_+n]/[DELETED]/[SEQ_DUP]/[CLIENT_DUP] placeholders,
      group recvID rewrite, status normalization), with
      `ChatLogIngestStore` over the dynamic chat-log tables. Pinned by 8
      unit tests plus an end-to-end run against real SQLite
- [x] HTTP API client + friend incremental sync — **verified**: `ApiClient`
      mirrors `pkg/network/http_client.go` (operationID/token headers,
      `{errCode,errMsg,errDlt,data}` envelope → ApiException); `FriendSync`
      instantiates VersionSynchronizer over
      `/friend/get_incremental_friends` exactly like
      `internal/relation/incremental_sync.go` (incl. sortVersion → full-ID
      refresh). Pinned by mock-server tests
- [x] Group module — **verified**: `GroupSync` ports
      `internal/group/incremental_sync.go` (joined-group sync, batched
      member sync over `get_incremental_group_members_batch` with the
      500-cursor MaxSyncPullNumber split, piggybacked group info applied
      via the synchronizer's extraData hook, per-group version cursors
      under `local_group_entities_version`). Pinned by mock-server tests
- [x] Conversation sync — **verified**: `ConversationSync` ports
      `IncrSyncConversations` incl. the skipDeletion semantics (sync never
      deletes conversation rows; server delete keys only trim the version
      id_list). Pinned by mock-server tests
- [x] SQLite-backed stores — **verified end-to-end**: SqlVersionSyncStore
      (GORM-compatible JSON id_list), SqlFriendStore, SqlGroupStore,
      SqlConversationStore (sync updates touch only server-owned columns;
      unread/draft state preserved). Friend+group+conversation syncs run
      mock HTTP → stores → real SQLite with conversions and cursors checked
- [x] Conversation triggers — **verified**: `ConversationTrigger` ports the
      doMsgNew decision core (snapshot accumulation, MaxSeqRecorder-gated
      unread deltas, changed/new diff with enrichment hook, placeholder
      merge carrying settings, session-type conversation seeds);
      `LocalConversation` extended with the local trigger state. Pinned by
      tests
- [x] Receive-path orchestrator — **verified end-to-end on real SQLite**:
      `ConversationProcessor` assembles doMsgNew from the verified parts
      (MessageIngestor → ConversationTrigger → SqlConversationStore →
      events), honoring the per-message option switches (absent = true).
      Scenarios pinned: conversation creation with unread + sender
      enrichment, accumulation, replayed-seq no-regress, own-device
      messages without unread, placeholder settings carry-over without
      duplicate rows. (MsgStruct content parsing pending; preview encoder
      is injectable)
- [x] Send pipeline — **verified end-to-end on real SQLite**:
      `MessageSender` ports initBasicInfo / sendMessageToServer /
      updateMsgStatusAndTriggerConversation: Sending row +
      local_sending_messages record in flight, ack applies
      serverMsgID/sendTime/SendSuccess, failures mark SendFailed, network
      timeouts double-check the DB for a raced ack, server-modified
      messages replace the draft, online-only sends persist nothing
- [x] Engine assembly — **verified**: `OpenIMEngine` composes all verified
      components over one database (MsgSyncer → ConversationProcessor →
      stores → listener; MessageSender; domain syncs), with
      WsSendTransport and SqlMsgSyncStore filling the last adapter gaps.
      Headless lifecycle integration test on real SQLite: fresh-install
      login (reinstall path with AppDataSync flags), connect catch-up
      pulling history, server-data sync, gap-push fill with unread, text
      send with ack, debounced re-connect with normal sync flags
- [x] Compat shim over the engine — **verified**: `OpenIMCompat` exposes
      gomobile-style JSON-string functions and listener fan-out with field
      names matching the Go struct json tags (createTextMessage,
      sendMessage with SendMsgCallBack, getAllConversationList,
      conversation/message listeners, sync flags as
      onSyncServerStart/Finish(reinstalled)). Full lifecycle driven through
      the shim against real SQLite. (Caught and fixed: draft status must
      not be normalized by the received-message conversion)
- [x] Typed event flows + production composition — **verified**:
      `EngineEventFlows` bridges engine events into the modern API's
      SharedFlows (sync flags, new/changed conversations, unread, messages;
      buffered, drop-oldest); `OpenIMSdk` is the LoginMgr-equivalent
      composition (per-user Go-compatible DB, ws URL with
      sendID/token/platformID/sdkVersion + optional compression=gzip,
      PushMsg envelope → sdkws.PushMessages → MsgSyncer, Connected →
      catch-up + server-data sync). Flows verified through the engine
      lifecycle; socket composition compile-verified (live ws needs CI)
- [x] **Live-socket integration** — the full SDK lifecycle verified over a
      real websocket speaking the Go wire protocol (in-process server using
      the golden-verified gob codec as msggateway stand-in): login URL
      params checked server-side, connect + catch-up history pull, server
      push decoded and stored, send round-trip acked — 5/5 deterministic
      runs. The test caught and fixed two architecture bugs in OpenIMSdk:
      push processing ran on the read pump (deadlock when a gap pull needs
      the pump to read its response — now queued through a cap-1000
      channel, Go's pushMsgAndMaxSeqCh pattern) and engine entry points ran
      on multiple threads (now confined to a single-parallelism dispatcher,
      Go's DoListener ownership)
- [x] JVM target + CI — `jvm()` target with platform actuals hosts the
      permanent conformance suites in `src/jvmTest`: SchemaCompatTest
      (byte-identical sqlite_master vs the Go dump), GobGoldenTest (full
      golden-vector file), LiveSocketLifecycleTest (the deadlock-catching
      live-socket lifecycle). **CI green** (run #3, all 3 jobs): the workflow regenerates
      and diffs the Go goldens, runs `:core:jvmTest`, compiles the Android
      target on ubuntu and the iOS arm64 klib on macOS
- [ ] Remaining: blacks/user syncs, full-sync paths, MsgStruct content
      parsing, broader compat surface, CI against a real OpenIM server —
      Phase 3/4
- [ ] Domain modules (user → relation → group → conversation → third) — Phase 3
- [ ] Golden replay + parity harness — Phases 0/4
