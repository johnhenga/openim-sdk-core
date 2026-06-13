# OpenIM SDK Core — Clean-Room Functional Specification

> **Purpose.** This document is a *functional specification* produced by Team A
> (the "dirty"/spec team) from study of the AGPL-licensed `openim-sdk-core` Go
> implementation. It captures **ideas, interfaces, protocol facts, and observable
> behaviors** — never source expression, code structure, or verbatim comments.
> It exists so that a separate Team B can implement a Kotlin Multiplatform port
> **without ever reading the original source code**.
>
> **Clean-room wall.** Team B must work *only* from this specification and from
> independent sources (the OpenIM server API, public docs, the protobuf/protocol
> definitions where independently licensed). Team B members must not have had
> access to the Go source. Keep dated records of authorship on both sides.
>
> ⚠️ **License caveat.** The upstream `LICENSE` file is AGPL-3.0 while the README
> badge claims Apache-2.0. Resolve which license actually governs with counsel
> before relying on this exercise. This document is not legal advice.

---

## Part 1 — Public API Surface (interop contract)

### Conventions and calling model

The SDK exposes a flat set of top-level functions. Cross-language structured
data is passed as **JSON-encoded strings**; scalars (numbers, booleans) are
passed natively. Three invocation patterns:

- **Async callback functions** — first param is a `Base` callback object, second
  is `operationID` (caller-supplied trace/correlation string, must be non-empty).
  Returns immediately (`void`); results delivered later via
  `Base.OnSuccess(data: String)` (JSON) or `Base.OnError(errCode: Int32, errMsg: String)`.
  Runs on a background thread/coroutine.
- **Synchronous string functions** — take `operationID` plus args, return a JSON
  `String` directly (empty string on failure). Used mainly for message construction.
- **Listener registration functions** — take a single listener object, return
  `void`, register globally. Call after `InitSDK`.

`operationID` is mandatory and non-empty on every call; empty → immediate
argument error. Successful collection results that would be null are normalized
to empty (`{}` / `[]`) before serialization, so consumers never see `null` for
collections. A global SDK context holds login state; calls fail with an error
code if not initialized.

### Domain: Init / Login / Lifecycle

| Function | Params | Returns | Behavior |
|---|---|---|---|
| `GetSdkVersion` | — | `String` | SDK version string. |
| `InitSDK` | `listener: OnConnListener`, `operationID`, `config: String` (JSON `IMConfig`) | `Boolean` | Initialize with connection listener + config. Returns `false` if config JSON invalid, `platformID==0`, `apiAddr` not http(s), `wsAddr` not ws, or listener/config empty. `IMConfig`: `platformID`, `apiAddr`, `wsAddr`, `dataDir`, `logLevel`, `logRemainCount`, `isLogStandardOutput`, `logFilePath`, `systemType`, `isExternalExtensions`, etc. |
| `UnInitSDK` | `operationID` | `void` | Tear down SDK state. |
| `GetLoginUserID` | — | `String` | Current logged-in user ID, or empty. |
| `Login` | `callback: Base`, `operationID`, `userID`, `token` | async | Log in with ID + token; establish long connection, start sync. |
| `Logout` | `callback: Base`, `operationID` | async | Log out, close connection. |
| `SetAppBackgroundStatus` | `callback`, `operationID`, `isBackground: Boolean` | async | Inform SDK of app foreground/background. |
| `NetworkStatusChanged` | `callback`, `operationID` | async | Notify of network change; close/reconnect long connection. |
| `GetLoginStatus` | `operationID` | `Int` | Login status enum (logged-out / logging-in / logged-in). |

### Domain: Conversation

All async unless noted; structured args are JSON strings.

| Function | Params | Behavior |
|---|---|---|
| `GetAllConversationList` | `cb, opID` | All local conversations. |
| `GetConversationListSplit` | `cb, opID, offset: Int, count: Int` | Paginated conversation list. |
| `GetOneConversation` | `cb, opID, sessionType: Int32, sourceID` | Get (create if needed) one conversation by session type + peer/group ID. |
| `GetMultipleConversation` | `cb, opID, conversationIDList: String` (JSON array) | Multiple conversations by ID. |
| `SetConversation` | `cb, opID, conversationID, req: String` (JSON fields) | Update conversation props (pin, recv-msg-opt, burn duration, ex). |
| `HideConversation` | `cb, opID, conversationID` | Hide a conversation. |
| `SetConversationDraft` | `cb, opID, conversationID, draftText` | Set/clear draft. |
| `GetTotalUnreadMsgCount` | `cb, opID` | Total unread across conversations. |
| `GetAtAllTag` | `opID` → `String` (sync) | Sentinel tag string for "@all". |
| `GetConversationIDBySessionType` | `opID, sourceID, sessionType: Int` → `String` (sync) | Deterministic conversation ID for peer/group + session type. |
| `MarkConversationMessageAsRead` | `cb, opID, conversationID` | Mark conversation read. |
| `MarkAllConversationMessageAsRead` | `cb, opID` | Mark all read. |
| `MarkMessagesAsReadByMsgID` | `cb, opID, conversationID, clientMsgIDs: String` (JSON array) | Mark specific messages read. |
| `HideAllConversations` | `cb, opID` | Hide all. |
| `ClearConversationAndDeleteAllMsg` | `cb, opID, conversationID` | Clear + delete its messages locally. |
| `DeleteConversationAndDeleteAllMsg` | `cb, opID, conversationID` | Delete entry + all its messages. |
| `SearchConversation` | `cb, opID, searchParam: String` | Search conversations by keyword. |
| `ChangeInputStates` | `cb, opID, conversationID, focus: Boolean` | Send typing/focus state. |
| `GetInputStates` | `cb, opID, conversationID, userID` | Query a user's typing state. |

### Domain: Message

**Construction (synchronous; return JSON message string; `operationID` first):**

`CreateTextMessage(text)`, `CreateAdvancedTextMessage(text, messageEntityList)`,
`CreateTextAtMessage(text, atUserList, atUsersInfo, message)`,
`CreateLocationMessage(description, longitude, latitude)`,
`CreateCustomMessage(data, extension, description)`,
`CreateQuoteMessage(text, message)`, `CreateAdvancedQuoteMessage(text, message, messageEntityList)`,
`CreateCardMessage(cardInfo)`,
`CreateImageMessage(imagePath)` / `…FromFullPath(imageFullPath)` /
`…ByURL(sourcePath, sourcePicture, bigPicture, snapshotPicture)`,
`CreateSoundMessage(soundPath, duration)` / `…FromFullPath` / `…ByURL(soundBaseInfo)`,
`CreateVideoMessage(videoPath, videoType, duration, snapshotPath)` / `…FromFullPath` / `…ByURL(videoBaseInfo)`,
`CreateFileMessage(filePath, fileName)` / `…FromFullPath` / `…ByURL(fileBaseInfo)`,
`CreateMergerMessage(messageList, title, summaryList)`,
`CreateFaceMessage(index, data)`, `CreateForwardMessage(m)`.

**Sending / history / mutation:**

| Function | Params | Behavior |
|---|---|---|
| `SendMessage` | `cb: SendMsgCallBack, opID, message, recvID, groupID, offlinePushInfo, isOnlineOnly: Boolean` | Send (single if `recvID`, group if `groupID`); uploads media to OSS first. `isOnlineOnly` = only online recipients. Progress via `OnProgress`. |
| `SendMessageNotOss` | same | Send without OSS upload (URLs pre-set). |
| `FindMessageList` | `cb, opID, findMessageOptions` (JSON) | Find messages by conversation/seq/clientMsgID. |
| `GetAdvancedHistoryMessageList` | `cb, opID, getMessageOptions` (JSON: conversationID, startClientMsgID, count) | Page of history (newest→older) + pagination meta. |
| `GetAdvancedHistoryMessageListReverse` | same | History older→newer. |
| `RevokeMessage` | `cb, opID, conversationID, clientMsgID` | Recall a sent message. |
| `TypingStatusUpdate` | `cb, opID, recvID, msgTip` | Typing-status hint to a peer. |
| `DeleteMessageFromLocalStorage` | `cb, opID, conversationID, clientMsgID` | Delete one message locally. |
| `DeleteMessage` | `cb, opID, conversationID, clientMsgID` | Delete locally + server. |
| `DeleteAllMsgFromLocalAndSvr` | `cb, opID` | Delete all locally + server. |
| `DeleteAllMsgFromLocal` | `cb, opID` | Delete all locally. |
| `InsertSingleMessageToLocalStorage` | `cb, opID, message, recvID, sendID` | Insert fabricated single-chat message (no send). |
| `InsertGroupMessageToLocalStorage` | `cb, opID, message, groupID, sendID` | Insert fabricated group message. |
| `SearchLocalMessages` | `cb, opID, searchParam` (JSON: keywords, types, time range, conversation) | Local message search. |
| `SetMessageLocalEx` | `cb, opID, conversationID, clientMsgID, localEx` | Set local-only extension on a message. |

### Domain: Group

All async (`cb: Base`, `opID`). JSON-string structured args.

`CreateGroup(groupReqInfo)`, `JoinGroup(groupID, reqMsg, joinSource: Int32, ex)`,
`QuitGroup(groupID)`, `DismissGroup(groupID)`,
`ChangeGroupMute(groupID, isMute)`, `ChangeGroupMemberMute(groupID, userID, mutedSeconds: Int)`,
`TransferGroupOwner(groupID, newOwnerUserID)`,
`KickGroupMember(groupID, reason, userIDList)`,
`SetGroupInfo(groupInfo)`, `SetGroupMemberInfo(groupMemberInfo)`,
`GetJoinedGroupList()`, `GetJoinedGroupListPage(offset, count)`,
`GetSpecifiedGroupsInfo(groupIDList)`, `SearchGroups(searchParam)`,
`GetGroupMemberOwnerAndAdmin(groupID)`,
`GetGroupMemberListByJoinTimeFilter(groupID, offset, count, joinTimeBegin, joinTimeEnd, filterUserIDList)`,
`GetSpecifiedGroupMembersInfo(groupID, userIDList)`,
`GetGroupMemberList(groupID, filter: Int32, offset, count)` (filter = role enum),
`GetGroupApplicationListAsRecipient(req)`, `GetGroupApplicationListAsApplicant(req)`,
`SearchGroupMembers(searchParam)`, `IsJoinGroup(groupID)`,
`GetUsersInGroup(groupID, userIDList)`, `InviteUserToGroup(groupID, reason, userIDList)`,
`AcceptGroupApplication(groupID, fromUserID, handleMsg)`,
`RefuseGroupApplication(groupID, fromUserID, handleMsg)`,
`CheckLocalGroupFullSync()`, `CheckGroupMemberFullSync(groupID)`,
`GetGroupApplicationUnhandledCount(req)`.

### Domain: Relation / Friend

All async (`cb: Base`, `opID`).

`GetSpecifiedFriendsInfo(userIDList, filterBlack: Boolean)`,
`GetFriendList(filterBlack)`, `GetFriendListPage(offset, count, filterBlack)`,
`SearchFriends(searchParam)`, `CheckFriend(userIDList)`,
`AddFriend(userIDReqMsg)`, `UpdateFriends(req)`, `DeleteFriend(friendUserID)`,
`GetFriendApplicationListAsRecipient(req)`, `GetFriendApplicationListAsApplicant(req)`,
`AcceptFriendApplication(userIDHandleMsg)`, `RefuseFriendApplication(userIDHandleMsg)`,
`AddBlack(blackUserID, ex)`, `GetBlackList()`, `RemoveBlack(removeUserID)`,
`GetFriendApplicationUnhandledCount(req)`.

### Domain: User

`GetUsersInfo(userIDs)`, `SetSelfInfo(userInfo)`, `GetSelfUserInfo()`,
`GetUserClientConfig()` — all async.

### Domain: Online / Presence

`SubscribeUsersStatus(userIDs)`, `UnsubscribeUsersStatus(userIDs)`,
`GetSubscribeUsersStatus()`, `GetUserStatus(userIDs)` — all async.
(Treat `GetUserStatus` as a non-subscribing query in the reimplementation.)

### Domain: Third-Party / File / Logging

`UpdateFcmToken(fcmToken, expireTime: Int64)`, `SetAppBadge(appUnreadCount: Int32)`,
`UploadLogs(line: Int, ex, progress: UploadLogProgress)`,
`Logs(logLevel, file, line, msgs, err, keyAndValue)`,
`UploadFile(req, progress: UploadFileCallback)` (chunked/resumable, hashing).

### Listener registration entry points

Register a listener globally; take only the listener, return `void`; call after
`InitSDK`. Default no-op implementations log a warning when unset.

| Registration | Interface |
|---|---|
| via `InitSDK` | `OnConnListener` |
| `SetGroupListener` | `OnGroupListener` |
| `SetConversationListener` | `OnConversationListener` |
| `SetAdvancedMsgListener` | `OnAdvancedMsgListener` |
| `SetUserListener` | `OnUserListener` |
| `SetFriendListener` | `OnFriendshipListener` |
| `SetCustomBusinessListener` | `OnCustomBusinessListener` |
| `SetMessageKvInfoListener` | `OnMessageKvInfoListener` |

### Listener interface contracts (payloads are JSON strings unless typed)

**OnConnListener:** `OnConnecting()`, `OnConnectSuccess()`,
`OnConnectFailed(errCode, errMsg)`, `OnKickedOffline()`,
`OnUserTokenExpired()`, `OnUserTokenInvalid(errMsg)`.

**OnGroupListener:** `OnJoinedGroupAdded/Deleted(groupInfo)`,
`OnGroupMemberAdded/Deleted/InfoChanged(groupMemberInfo)`,
`OnGroupApplicationAdded/Deleted/Accepted/Rejected(groupApplication)`,
`OnGroupInfoChanged(groupInfo)`, `OnGroupDismissed(groupInfo)`.

**OnFriendshipListener:** `OnFriendApplicationAdded/Deleted/Accepted/Rejected(friendApplication)`,
`OnFriendAdded/Deleted/InfoChanged(friendInfo)`, `OnBlackAdded/Deleted(blackInfo)`.

**OnConversationListener:** `OnSyncServerStart/Finish/Failed(reinstalled: Boolean)`,
`OnSyncServerProgress(progress: Int)`, `OnNewConversation(list)`,
`OnConversationChanged(list)`, `OnTotalUnreadMessageCountChanged(count: Int32)`,
`OnConversationUserInputStatusChanged(change)`.
(`reinstalled` = fresh-install full sync.)

**OnAdvancedMsgListener (required six):** `OnRecvNewMessage(message)`,
`OnRecvOnlineOnlyMessage(message)`, `OnRecvOfflineNewMessage(message)`,
`OnRecvC2CReadReceipt(msgReceiptList)`, `OnNewRecvMessageRevoked(messageRevoked)`,
`OnMsgDeleted(message)`. (Optional/extended: `OnMsgEdited`,
`OnRecvGroupReadReceipt`, message-extension change/add/delete.)

**OnUserListener:** `OnSelfInfoUpdated(userInfo)`, `OnUserStatusChanged(userOnlineStatus)`.

**OnCustomBusinessListener:** `OnRecvCustomBusinessMessage(businessMessage)`.

**OnMessageKvInfoListener:** `OnMessageKvInfoChanged(messageChangedList)`.

**SendMsgCallBack** (extends `Base`): `OnError`, `OnSuccess`, `OnProgress(progress: Int)`.

**UploadFileCallback:** `Open(size)`, `PartSize(partSize, num)`,
`HashPartProgress(index, size, partHash)`, `HashPartComplete(partsHash, fileHash)`,
`UploadID(uploadID)`, `UploadPartComplete(index, partSize, partHash)`,
`UploadComplete(fileSize, streamSize, storageSize)`, `Complete(size, url, typ)`.

**UploadLogProgress:** `OnProgress(current: Int64, size: Int64)`.

### Connection error → listener mapping (behavioral contract)

The SDK maps specific server error codes onto `OnConnListener` callbacks, firing
each **at most once** (idempotent latch per state), then dispatches an automatic
internal logout:

- Token expired → `OnUserTokenExpired()`
- Token invalid / malformed / not-yet-valid / unknown / not-exist → `OnUserTokenInvalid(errMsg)`
- Token kicked (logged in elsewhere) → `OnKickedOffline()`

Reproduce once-only semantics and the automatic logout dispatch.

### Notes for the Kotlin team

- **Threading:** callback functions return immediately, invoke callback later off
  the calling thread. `Create*` / sync getters block and return JSON.
- **Marshaling:** structured params arrive as JSON strings, deserialized into typed
  requests internally; scalars/booleans native. Boundary = "JSON strings + primitives
  in, JSON strings via callbacks out."
- `operationID` mandatory/non-empty; empty → argument error.
- Normalize null collections to `{}` / `[]` before serialization.
