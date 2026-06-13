# Part 3 — Transport, Sync Flow, REST API & Protocol Constants

> These are interop facts about talking to an existing OpenIM server. Numeric
> constants and wire shapes are facts, not creative expression, and are safe to
> reproduce. Inner request/response bodies follow the OpenIM `protocol` package
> (protobuf) — obtain those schemas from the independently-published OpenIM
> protocol definitions for the matching server version (they are not vendored here).

## ⚠️ Biggest interop risk — read first

The WebSocket **application envelope is serialized with Go `gob`**, then optionally
gzip-compressed. Go gob is a Go-specific self-describing binary format; a non-Go
client cannot trivially produce/consume it. The Kotlin team must either:
(a) implement a gob-compatible codec for this one fixed envelope struct, or
(b) coordinate with the server to use the JSON/text variant (`isMsgResp=true`),
whose envelope JSON keys are: request `{reqIdentifier, token, sendID, operationID,
msgIncr, data}`, response `{reqIdentifier, errCode, errMsg, msgIncr, operationID,
data}`. The inner `data` is always protobuf. **Confirm this against the target
server build before committing to an approach.**

## 1. Transport (long connection)

### 1.1 Model
- One long-lived WebSocket per logged-in user carries all real-time traffic
  (seq queries, message pulls, send, bg-status, online-status sub, inbound pushes).
- Two backends behind one interface: native WS and browser/WASM WS. Protocol
  identical; they differ only in ping/pong emulation (§1.6).
- Status: NotConnected / Closed / Connecting / Connected. Only Connected may write.

### 1.2 Handshake — connect URL query params
`sendID` (user ID), `token` (auth), `platformID`, `operationID` (fresh per attempt),
`isBackground` (`true`/`false`), `sdkVersion`, `compression` (=`gzip` when enabled;
default enabled). WASM adds `isMsgResp=true`. Auth is carried entirely in the URL
query; no separate post-connect auth frame on the native path.

### 1.3 Connect result / auth failures
- Native: HTTP 101 upgrade = connected. Dial failure returns JSON `{errCode, errMsg, errDlt}`.
- Fatal (no reconnect): token expired / invalid / malformed / not-yet-valid /
  unknown / not-exist / kicked. All other dial errors retryable.
- WASM: after open, server sends one `{errCode, errMsg, errDlt}` frame; `errCode==0`
  confirms usable.
- Immediately after (re)connect, before signaling success, the client re-sends
  pending online-status subscriptions as the first application frame.
- Connect success triggers the sync flow (§2).

### 1.4 Frame envelope
All application messages are WebSocket **binary** frames (text frames tear down
the connection). Payload = gob-encoded struct, then gzip-compressed if enabled
(inbound decompressed first).

**Request envelope:** `ReqIdentifier` (int, command id), `Token` (string, usually
empty), `SendID` (string), `OperationID` (string), `MsgIncr` (string correlation key,
format `<userID>_<generatedOpID>`), `Data` (bytes, protobuf body for the command).

**Response envelope:** `ReqIdentifier` (int, echo or push id), `ErrCode` (int, 0=ok),
`ErrMsg`, `MsgIncr` (echoes request key), `OperationID`, `Data` (protobuf body).

### 1.5 Request/response correlation
Each request registers a unique `MsgIncr` + one-shot reply channel; responses route
back by matching `MsgIncr`. Per-request timeout **10s**. Inbound read size limit
**1 MiB**. Write deadline per frame **10s**.

### 1.6 Heartbeat
- Read deadline (pong wait) **30s**, reset on any read/pong.
- Ping period **24s** (80% of pong wait).
- Native: WS control frames (ping=9, pong=10); ping payload carries a fresh
  operationID; reply pong to server pings; reset read deadline on pong or server ping.
- WASM: emulated as JSON text frames `{"type":"ping"|"pong","body":<json string>}`.
- No readable traffic within 30s → read fails → teardown + reconnect.

### 1.7 Reconnect / backoff
- Read loop redials whenever not Connected.
- Backoff: cyclic exponential **seconds 1,2,4,8,16** then repeat; index wraps modulo
  length; reset on successful connect.
- Max reconnection attempts per send-with-retry: **300**; **1s** wait between failed
  binary-send attempts.
- "Get newest seq" while not Connected fails fast (network error).
- Fatal/no-reconnect: token errors; explicit logout; server `LogoutMsg`;
  server `KickOnlineMsg` (→ token-kicked error).
- On read error: close, clear cached config, notify subscription bookkeeping, reconnect.

### 1.8 Foreground/background
`SetBackgroundStatus` (id 2004) informs the server. Read+heartbeat loops can stop on
background, resume on foreground; write loop persists. Wake-up triggers fresh sync.

### 1.9 Outbound send ordering ("lanes")
Optional ordered delivery on two lanes — **text** and **media** — keyed by a
monotonic per-lane sequence (from 1); writer dispatches in order, buffering
out-of-order. A **3s** lane timer skips a persistent gap to avoid deadlock.
Unordered messages dispatch immediately. Internal send buffer depth 10.

## 2. Sync flow

### 2.1 Model
Server assigns per-conversation monotonic **seq** to each stored message. Client
tracks per-conversation highest synced seq (`syncedMaxSeq`). Conversation IDs
prefixed `n_` are **notification** conversations (special-cased). Local seq state
loaded at startup, parallelized in chunks of 20 conversations.

### 2.2 Triggers
Connected event (small batch=1/conv), Wake-up/foreground (default batch=10),
manual IM sync for a conversation set, server push (incremental). A guard allows
only one sync at a time; flag auto-clears after 5s.

### 2.3 Get max seqs
`GetNewestSeq` (1001), req `{UserID}` → `{MaxSeqs: map<conversationID,int64>}`.
Connected path retries up to **3×** with backoff from 2s; total failure → "sync failed".

### 2.4 What to pull (per conversation)
- local synced exists & `serverMax>localSynced` → pull `[localSynced+1, serverMax]`.
- no local synced & `serverMax!=0` → pull `[0, serverMax]`.
- `serverMax==0` → nothing.

### 2.5 Fresh-install (reinstall)
Detected when no local conversations and app/SDK-version record absent/not-installed.
On reinstall: notification conversations are NOT pulled — their server max seq is
recorded as synced directly; only normal conversations are pulled. After completion,
persist an "installed" version record and clear reinstall flag. Emits distinct
"msg sync in reinstall" event with total-conversation count + AppDataSync start/finish.

### 2.6 Incremental sync on push
Push delivers `PushMessages{Msgs, NotificationMsgs}`, each `map<conversationID, PullMsgs>`,
`PullMsgs` = `Msgs[]` + `IsEnd`/`EndSeq`. Per conversation: `Seq==0` messages trigger
immediately; if highest push seq == `localSynced+count` (contiguous, no gap) accept
directly and advance `localSynced` (no round-trip); on gap, schedule pull
`[localSynced+1, highestSeq]`.

### 2.7 Pulling
`PullMsgByRange` (1002), req `PullMessageBySeqsReq{UserID, SeqRanges[]}` where
`SeqRange={ConversationID, Begin, End, Num}` → `{Msgs, NotificationMsgs}`. Pulls are
batched across conversations until estimated count reaches **100** (`SplitPullMsgNum`),
then one combined pull, repeat. Normal conversations capped at pull batch size;
notification conversations contribute full range. After each batch, advance
`localSynced` to range end and dispatch. Single-conversation seq-list pull chunks at 100.

### 2.8 Reinstall last-valid-message backfill
If every pulled message in a conversation has status ≥ `MsgStatusHasDeleted` (4),
call `PullConvLastMessage` (1007), req `GetLastMessageReq{UserID, ConversationIDs}`.

### 2.9 Read-state sync
Manual sync uses `GetConvMaxReadSeq` (1006), req
`GetConversationsHasReadAndMaxSeqReq{UserID, ConversationIDs}` →
per-conversation `{MaxSeq, HasReadSeq}`.

### 2.10 Inbound push aggregation
Pushes merged per conversation (append `Msgs`, carry `IsEnd`/`EndSeq`). Adaptive flush
over 10s sliding window: low load (<20 recent) flush immediately; else buffer, flush at
**400** buffered or when delay elapses; aggregation delay scales linearly **50ms**
(≤20 recent) → **1s** (≥200 recent). Flush + detach on logout/kick.

## 3. REST API

### 3.1 Conventions
**POST**, JSON body, to `<apiAddr><path>`. Headers: `Content-Type: application/json`,
`operationID` (required), `token`, `Accept-Encoding: gzip`. Response envelope
`{errCode, errMsg, errDlt, data}`; `errCode==0` = success, payload in `data`. gzip
auto-decompressed. Default HTTP timeout **10s** (generic helper 30s). Pagination via
`Pagination{PageNumber, ShowNumber}`, 1-based, default page 50 or 200; stop when a
page returns fewer than `ShowNumber`.

### 3.2 Endpoint catalog

**Auth:** `/auth/parse_token`, `/auth/get_admin_token`, `/auth/get_user_token`.

**User:** `/user/get_users_info`, `/user/update_user_info`, `/user/update_user_info_ex`,
`/user/user_register`, `/user/get_user_client_config`.

**Friend/relation:** `/friend/add_friend`, `/delete_friend`, `/get_friend_apply_list`,
`/get_self_friend_apply_list`, `/get_self_unhandled_apply_count`, `/import_friend`,
`/get_designated_friend_apply`, `/get_friend_list`, `/get_designated_friends`,
`/add_friend_response`, `/update_friends`, `/get_incremental_friends`,
`/get_full_friend_user_ids`, `/add_black`, `/remove_black`, `/get_black_list`.

**Message:** `/msg/clear_conversation_msg`, `/user_clear_all_msg`, `/delete_msgs`,
`/revoke_msg`, `/mark_msgs_as_read`, `/get_conversations_has_read_and_max_seq`,
`/mark_conversation_as_read`, `/set_conversation_has_read_seq`, `/send_msg`,
`/get_server_time`.

**Group:** `/group/create_group`, `/set_group_info_ex`, `/join_group`, `/quit_group`,
`/get_groups_info`, `/get_group_member_list`, `/get_group_members_info`,
`/invite_user_to_group`, `/get_joined_group_list`, `/kick_group`, `/transfer_group`,
`/get_recv_group_applicationList`, `/get_user_req_group_applicationList`,
`/get_group_application_unhandled_count`, `/group_application_response`,
`/dismiss_group`, `/mute_group_member`, `/cancel_mute_group_member`, `/mute_group`,
`/cancel_mute_group`, `/set_group_member_info`, `/get_incremental_join_groups`,
`/get_incremental_group_members_batch`, `/get_full_join_group_ids`,
`/get_full_group_member_user_ids`.

**Conversation:** `/conversation/get_conversations`, `/get_all_conversations`,
`/set_conversations`, `/get_incremental_conversations`, `/get_full_conversation_ids`,
`/get_owner_conversation`, `/jssdk/get_active_conversations`.

**Third-party/object:** `/third/fcm_update_token`, `/set_app_badge`, `/logs/upload`,
`/object/part_limit`, `/initiate_multipart_upload`, `/auth_sign`,
`/complete_multipart_upload`, `/access_url`.

## 4. Protocol constants (wire facts)

### 4.1 WS command ids (`ReqIdentifier`)
Client→server: GetNewestSeq 1001, PullMsgByRange 1002, SendMsg 1003, SendSignalMsg
1004, PullMsgBySeqList 1005, GetConvMaxReadSeq 1006, PullConvLastMessage 1007.
Server↔client: PushMsg 2001, KickOnlineMsg 2002, LogoutMsg 2003, SetBackgroundStatus
2004 (c→s), WsSubUserOnlineStatus 2005 (sub + push).

### 4.2 WS control opcodes
Text 1, Binary 2, Close 8, Ping 9, Pong 10. (Application uses Binary only.)

### 4.3 Message content types
Text 101, Picture 102, Sound 103, Video 104, File 105, AtText 106, Merger 107,
Card 108, Location 109, Custom 110, Typing 113, Quote 114, Face 115, AdvancedText 117,
MarkdownText 118, CustomMsgNotTriggerConversation 119, CustomMsgOnlineOnly 120.

### 4.4 Notification content types
Begin 1000, End 5000. Friend 1200–1299 (ApplicationApproved 1201, ApplicationRejected
1202, Application 1203, FriendAdded 1204, FriendDeleted 1205, RemarkSet 1206, BlackAdded
1207, BlackDeleted 1208, FriendInfoUpdated 1209, FriendsInfoUpdate 1210).
ConversationChange 1300. User 1301–1399 (UserInfoUpdated 1303, UserStatusChange 1304,
CommandAdd 1305, CommandDelete 1306, CommandUpdate 1307). Group 1500–1599 (GroupCreated
1501, GroupInfoSet 1502, JoinApplication 1503, MemberQuit 1504, ApplicationAccepted 1505,
ApplicationRejected 1506, OwnerTransferred 1507, MemberKicked 1508, MemberInvited 1509,
MemberEnter 1510, GroupDismissed 1511, MemberMuted 1512, MemberCancelMuted 1513,
GroupMuted 1514, GroupCancelMuted 1515, MemberInfoSet 1516, MemberSetToAdmin 1517,
MemberSetToOrdinary 1518, SetAnnouncement 1519, SetName 1520). ConversationPrivateChat
1701, ClearConversation 1703. Business 2001 (separate namespace from PushMsg), Revoke
2101, DeleteMsgs 2102, HasReadReceipt 2200.

### 4.5 Session types
SingleChat 1, WriteGroupChat 2 (disabled), ReadGroupChat 3, NotificationChat 4.

### 4.6 Message-from
UserMsgType 100, SysMsgType 200.

### 4.7 Message status
Sending 1, SendSuccess 2, SendFailed 3, HasDeleted 4, Filtered 5. (≥4 = not displayable.)

### 4.8 Online / recv option
Online 1, Offline 0. RecvMsgOpt: Receive 0, NotReceive 1 (disabled), ReceiveNotNotify 2.

### 4.9 Roles & filters
Owner 100, Admin 60, OrdinaryUser 20. Member-list filter: All 0, Owner 1, Admin 2,
Ordinary 3, AdminAndOrdinary 4, OwnerAndAdmin 5. Group response: Agree 1, Refuse -1.
Friend response: Agree 1, Refuse -1, Default 0.

### 4.10 Group status / types
Status: Ok 0, BanChat 1, Dismissed 2, Muted 3. Types: Normal 0, Super 1, Working 2.
Relationship: Black 0, Friend 1.

### 4.11 @-mention modes
AtNormal 0, AtMe 1, AtAll 2, AtAllAtMe 3; all-tag string = `AtAllTag`.

### 4.12 Sync status flags (UI progress)
MsgSyncBegin 1001, MsgSyncProcessing 1002, MsgSyncEnd 1003, MsgSyncFailed 1004,
AppDataSyncStart 1005, AppDataSyncFinish 1006.

### 4.13 Pull tuning
SplitPullMsgNum 100, PullMsgNumForReadDiffusion 50; connect pull 1, default pull 10;
per-conversation pull-goroutine limit 10; max conversations 500 (sync chunk 100,
seq-load chunk 20).

### 4.14 Online-status subscription
`WsSubUserOnlineStatus` (2005), body `SubUserOnlineStatus{SubscribeUserID[],
UnsubscribeUserID[]}`. Server pushes under 2005, body `SubUserOnlineStatusTips{Subscribers[]}`
with `UserID` + `OnlinePlatformIDs[]` (empty ⇒ offline). On reconnect, re-subscribe
current set as first frame. `GetUserOnlinePlatformIDs` waits up to 5s, sending only the
not-yet-subscribed delta.
