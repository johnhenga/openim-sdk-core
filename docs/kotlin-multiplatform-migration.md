# Kotlin Multiplatform Migration Strategy

**Scope:** Android and iOS only. Web continues to use the existing Go/WASM core
and is explicitly out of scope for this migration.

**Status:** Proposal / design document
**Last updated:** 2026-06-12

---

## 1. Goal and recommendation

Replace the gomobile-built mobile artifacts (`open_im_sdk.aar`,
`OpenIMCore.xcframework`) with a single **Kotlin Multiplatform (KMP)** codebase
that compiles to:

- an Android library (AAR) consumed as ordinary Kotlin/Java, and
- an iOS XCFramework consumed from Swift.

This is a **phased rewrite**, not a wrapper. Two properties of the current
codebase make it tractable:

1. The public API boundary is already JSON-strings-over-callbacks
   (`open_im_sdk/`, `open_im_sdk_callback/`), so a byte-compatible
   compatibility shim is cheap to build, and the Go and Kotlin cores can be
   swapped underneath existing app wrappers and diffed against each other.
2. Local storage is plain SQLite with a documented schema (`pkg/db/`), so an
   upgraded app can open its existing database in place — users keep their
   chat history.

### Alternatives considered and rejected

| Option | Why rejected |
|---|---|
| Two native rewrites (Kotlin for Android + Swift for iOS) | Duplicates the hardest code — the sync engine and seq gap-filling — forever. The entire purpose of this repo is a single cross-platform core. |
| Keep the Go core, wrap it in typed Kotlin/Swift facades | Improves ergonomics only. Keeps every real pain point: large gomobile binaries, opaque crashes across the cgo boundary, no debugger into the core, JSON-typed API, goroutine/ART thread friction. |
| KMP rewrite (chosen) | One codebase, typed API, native debugging on both platforms, smaller binaries, mature multiplatform equivalents for every dependency in `go.mod`. |

---

## 2. Inventory of what is being migrated

Approximately **28,000 LOC** of Go (excluding tests, WASM, and simulators)
across 204 files.

| Layer | Location | LOC | Responsibility |
|---|---|---|---|
| Public API | `open_im_sdk/` | ~2,100 | 134 exported functions; JSON-string args; gomobile binding (`caller.go` does reflection-based dispatch) |
| Callbacks | `open_im_sdk_callback/` | ~250 | ~10 listener interfaces apps implement (`Base`, `OnConnListener`, `OnAdvancedMsgListener`, …) |
| Business logic | `internal/` | ~14,500 | See module table below |
| Data + sync + utils | `pkg/` | ~7,500 | GORM/SQLite data layer (~3,200), generic version syncer (~650), network helpers, caches, context |
| Shared structs | `sdk_struct/` | ~350 | Message/conversation wire structs |

### Internal modules (port order is roughly bottom-up by dependency)

| Module | LOC | Responsibility |
|---|---|---|
| `internal/conversation_msg` | 6,548 | Messages and conversations: creation (17 message variants), send pipeline, storage, search, revoke, read receipts, incremental conversation sync, **seq gap-filling validation** (`message_check.go`, 636 LOC) |
| `internal/interaction` | 3,148 | WebSocket long-connection manager (`long_conn_mgr.go`, 974 LOC: read pump / write pump / heartbeat goroutines, exponential-backoff reconnect), message sync orchestration (`msg_sync.go`, 761 LOC) |
| `internal/group` | 1,952 | Group + member incremental sync, group operations |
| `internal/third` | 1,592 | Chunked file upload with progress, log upload, FCM token |
| `internal/relation` | 857 | Friend/blacklist incremental sync, friend applications |
| `internal/user` | 385 | Self/user info caching |

### External dependencies

- `github.com/openimsdk/protocol` — protobuf message/service definitions for
  all server APIs (websocket frames and HTTP bodies).
- `github.com/openimsdk/tools` — logging, error wrapping, context/operationID
  utilities, batch helpers.
- `gorm.io/gorm` + sqlite driver, `gorilla/websocket`, protobuf runtime,
  LRU/TTL caches, `jinzhu/copier`.

---

## 3. Target technology mapping

| Go today | Kotlin Multiplatform | Notes |
|---|---|---|
| GORM + SQLite (`pkg/db/`) | **SQLDelight** (+ raw-query escape hatch, see §4) | Same SQLite file format → in-place upgrade |
| `gorilla/websocket`, `pkg/network` HTTP | **Ktor client** (OkHttp engine on Android, Darwin engine on iOS) | WebSockets and HTTP from one client |
| `openimsdk/protocol` protobufs | **Wire** (Square) codegen from the same `.proto` sources | Wire has first-class KMP support; pbandk is the fallback |
| `encoding/json` (public API + config) | **kotlinx.serialization** | |
| Goroutines + channels | **Coroutines** (structured concurrency) | See §5 |
| Callback interfaces | `suspend` functions + `Flow` for event streams | Plus a callback-style compat shim (§6) |
| gzip frame compression | `okio` / platform zlib via expect–actual | Small, isolated |
| LRU / TTL caches (`golang-lru`, `go-cache`) | Small hand-rolled caches or `kotlinx` collections | Trivial |
| Structured logging (`tools/log`) | **Kermit** (Touchlab) or kotlin-logging | Keep operationID convention |
| iOS Swift interop | **SKIE** (Touchlab) | `suspend` → `async/await`, `Flow` → `AsyncSequence`, sealed classes → Swift enums |

Project layout (single Gradle project):

```
sdk/
  src/commonMain/        # ~95% of code: domain logic, sync engine, DB, network
  src/androidMain/       # SQLDelight Android driver, OkHttp engine, platform glue
  src/iosMain/           # SQLDelight native driver, Darwin engine, platform glue
  src/commonTest/        # conformance + golden tests (see §7)
```

---

## 4. Storage design

### Schema compatibility is a hard requirement

The single biggest user-facing win of this migration plan is that **existing
installs keep their local data**. SQLDelight opens the same SQLite file the Go
SDK wrote. To make that work:

- Reproduce the GORM-generated schema column-for-column: table names
  (`local_friends`, `local_groups`, `local_group_members`,
  `local_conversations`, `local_notification_seqs`, `local_uploads`,
  `local_stranger`, `local_sending_messages`, `local_sync_version`,
  `local_app_sdk_version`, …), column names, types, indexes, and primary keys.
- Write a schema-diff test: create a DB with the Go SDK, dump
  `sqlite_master`, and assert the Kotlin schema produces an identical dump.
- Verify whether any production deployments use SQLCipher-wrapped databases
  (some platform wrappers do); if so, use the SQLCipher drivers for
  SQLDelight on both platforms.

### The dynamic chat-log tables problem

Messages are **not** stored in one table. Each conversation gets its own
dynamically created table:

- `pkg/utils/utils.go:177` — `GetConversationTableName()` returns
  `"chat_logs_" + conversationID` (prefix defined at
  `pkg/constant/constant.go:162`).

SQLDelight is schema-static and cannot express dynamic table names in `.sq`
files. Two options:

1. **(Recommended for v1)** Keep the per-conversation tables. Define the
   chat-log row mapping once, and execute the dynamic-table queries through
   the SQLDelight driver's raw `execute`/`executeQuery` API behind a
   `ChatLogStore` interface. This preserves in-place upgrade with zero data
   migration and keeps behavior identical (including the per-table
   `max(seq)` queries used by gap-filling).
2. (Later, optional) Consolidate into a single `chat_logs` table keyed by
   `(conversation_id, seq)` with a one-time background migration. Cleaner and
   fully typed, but adds a risky data migration to an already-risky phase —
   defer until the Kotlin core is proven in production.

All store access goes behind interfaces mirroring
`pkg/db/db_interface/database.go` so the implementation choice stays swappable.

---

## 5. Concurrency design

The Go concurrency translates cleanly to structured coroutines — in several
places more cleanly than the original:

- **Connection lifecycle** (`long_conn_mgr.go`): the three goroutines
  (`readPump`, `writePump`, `heartbeat`) become three child coroutines of a
  supervised per-connection `CoroutineScope`. Disconnect = cancel the scope;
  the manual mutex-guarded teardown logic disappears. The reconnect loop is a
  plain `while` with `delay()` and exponential backoff.
- **Internal command channels** (`chan common.Cmd2Value`, buffered 1000) →
  Kotlin `Channel` with the same capacities, consumed by dedicated coroutines.
- **Per-request response channels** (`msg.Resp chan *GeneralWsResp`) →
  `CompletableDeferred<GeneralWsResp>` keyed by operationID, with the same
  timeout semantics via `withTimeout`.
- **Heartbeat**: ping every 24s, pong deadline 30s — a `while`/`delay` loop in
  the connection scope.
- **`MaxSeqRecorder` (RWMutex map)** → `Mutex`-guarded map or a single-writer
  actor coroutine.
- **Message batching / send-order lanes** → per-lane `Channel` consumed by one
  coroutine per lane, which gives the ordering guarantee for free.
- **Threading rule:** the SDK owns its dispatchers (IO for DB/network, a
  single-threaded dispatcher for sync-engine state). Listener events are
  delivered on a caller-configurable dispatcher (Android main looper / iOS
  main queue by default, matching current behavior of the platform wrappers).

---

## 6. Public API: two facades over one core

### 6.1 Modern typed API (the future)

```kotlin
interface OpenIMClient {
    suspend fun login(userID: String, token: String)
    val connectionState: StateFlow<ConnectionState>
    val conversationEvents: Flow<ConversationEvent>
    val messageEvents: Flow<MessageEvent>
    suspend fun sendMessage(message: Message, recvID: String?, groupID: String?,
                            offlinePush: OfflinePushInfo?): Flow<SendProgress>
    // ... typed equivalents of the 134 functions, grouped by domain
}
```

Exposed to Swift via SKIE as `async/await` + `AsyncSequence`.

### 6.2 Compatibility shim (the migration vehicle)

A thin layer replicating the **exact** gomobile signatures — JSON-string
arguments, `Base { OnError(code, msg); OnSuccess(jsonData) }` callbacks, the
same listener interfaces and method names, the same JSON shapes on both
input and output. This lets:

- the existing `open-im-sdk-android` / `open-im-sdk-ios` wrapper repos switch
  engines with near-zero code change, and
- the parity harness (§7) drive the Go and Kotlin cores with identical inputs
  and diff their outputs.

The shim is ~mechanical to generate: `open_im_sdk/caller.go` already encodes
the JSON marshaling conventions in one place.

---

## 7. Test strategy: the Go code is the spec

There is no protocol specification document — the Go implementation *is* the
spec. Pin its behavior before porting:

1. **Conformance suite (Phase 0).** Extend `integration_test/` to run the Go
   SDK against a real OpenIM server and record golden artifacts: websocket
   frame transcripts, post-sync DB dumps, ordered listener-event logs for
   scripted scenarios (login, cold sync, message send/receive, reconnect with
   missed messages, group ops, friend ops).
2. **Replay tests.** A fake server in `commonTest` replays recorded WS
   transcripts at the Kotlin core; assert DB state and emitted events match
   the goldens. This is the primary safety net for the sync engine —
   including deliberately corrupted scenarios (gaps, reordering, duplicate
   seqs, mid-sync disconnect).
3. **Side-by-side parity harness (Phase 4).** Run Go core and Kotlin core in
   the same test app against the same server with the same account; drive both
   through the JSON-compat API; diff DB dumps and event streams.
4. **Schema-diff test.** Assert byte-level SQLite schema equality (§4).

Budget roughly half of total test effort on the message sync / gap-filling
logic; everything else is plumbing with good library coverage.

---

## 8. Phased plan

Estimated **4–6 months for 2–3 engineers** to reach parity
(~28k Go LOC → ~20k Kotlin LOC).

| Phase | Duration | Work | Exit criteria |
|---|---|---|---|
| **0 — Pin behavior** | 2–3 wks | Conformance suite + golden recordings; KMP skeleton; CI producing AAR + XCFramework; Wire codegen from `openimsdk/protocol` `.proto` sources (pin the protocol version for the whole migration window) | Goldens recorded for all core scenarios; empty SDK builds and links in sample apps on both platforms |
| **1 — Foundations** | 3–4 wks | Models; full SQLite schema in SQLDelight + dynamic chat-log store; Ktor HTTP client with token/operationID conventions; protobuf encode/decode + gzip | Schema-diff test green; HTTP calls verified against a live server |
| **2 — Connection + message sync** (highest risk) | 4–6 wks | Port `long_conn_mgr.go`, `msg_sync.go`, `message_check.go` per §5 | All replay tests green, including gap/reorder/reconnect scenarios |
| **3 — Domain modules** | 6–8 wks | In dependency order: `user` → `relation` → `group` → `conversation_msg` → `third` (file upload). The generic `VersionSynchronizer` (`pkg/syncer/`) ports almost mechanically to a Kotlin generic class | Module-level conformance tests green |
| **4 — API facades + parity** | 3–4 wks | Typed API + JSON compat shim; side-by-side parity harness; SKIE-polished Swift surface | Parity harness shows zero diffs on the scenario suite |
| **5 — Rollout** | ongoing | Integrate behind a flag in a real app; beta; watch crash and message-integrity metrics; deprecate gomobile artifacts | Kotlin core is the default engine |

---

## 9. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Sync/gap-filling correctness bugs (lost, duplicated, or stuck messages) | Critical, invisible until production | Golden replay tests including adversarial scenarios; side-by-side parity; staged rollout with message-integrity metrics |
| Schema drift breaks in-place DB upgrade | Users lose local history | Schema-diff test in CI; upgrade test that opens a Go-SDK-written DB with the Kotlin core |
| Protocol/server evolves during migration | Moving target | Pin `openimsdk/protocol` version in Phase 0; rebase once in Phase 5 |
| iOS background behavior (socket suspension, `SetAppBackgroundStatus`) | Reconnect storms / missed pushes | Test backgrounding explicitly on device early in Phase 2; Kotlin/Native's modern memory manager removes the historical KMP-on-iOS concerns |
| Binary size / startup regressions on iOS | Adoption blocker | Measure XCFramework size in Phase 0 CI from day one (expectation: smaller than the gomobile build) |
| SQLite concurrency differences (GORM's connection handling vs SQLDelight drivers) | Subtle data races | Single-writer discipline via the sync-engine dispatcher; WAL mode; stress tests |
| Per-conversation chat-log tables vs schema-static SQLDelight | Design friction | Raw-query `ChatLogStore` for v1 (§4); optional consolidation later |

---

## 10. Out of scope

- **Web/WASM** (`wasm/`, ~4.8k LOC, IndexedDB storage): continues on the
  existing Go core. Kotlin/Wasm is not yet a viable replacement.
- Desktop (Windows/macOS/Linux via the Go shared library): unchanged; can be
  revisited later since KMP also targets JVM desktop.
- Server-side protocol changes: none required — the Kotlin core speaks the
  existing protocol.
