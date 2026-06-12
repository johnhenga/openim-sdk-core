# OpenIM SDK — Kotlin Multiplatform core

Kotlin Multiplatform (KMP) port of the Go SDK core, targeting **Android and
iOS only**. Strategy, phasing, and rationale live in
[`docs/kotlin-multiplatform-migration.md`](../docs/kotlin-multiplatform-migration.md).

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
- [ ] Remaining domain modules (blacks, user — same template), full-sync
      paths, conversation triggers (unread/latest-msg), send pipeline —
      Phase 3
- [ ] Domain modules (user → relation → group → conversation → third) — Phase 3
- [ ] Golden replay + parity harness — Phases 0/4
