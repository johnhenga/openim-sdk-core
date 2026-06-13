# Part 2 — Data Models & Callback Payloads

> Field names in these tables are the **exact JSON keys** crossing the SDK
> boundary — they are interop facts (an existing server and other clients depend
> on them) and are safe to reproduce. No source expression is carried across.
> Time fields are epoch integers (ms for message/send times). "Element" structs
> are mutually exclusive content variants selected by the message `contentType`.

## 1. Message envelope — `MsgStruct`

Exactly one content element is populated per `contentType`.

| Field | Type | Meaning |
|---|---|---|
| clientMsgID | string | Client-generated unique ID (idempotency / dedup key). |
| serverMsgID | string | Server-assigned ID, set after successful send. |
| createTime | int64 | Local creation timestamp. |
| sendTime | int64 | Server/send timestamp; primary chronological ordering key. |
| sessionType | int32 | Conversation kind discriminator (1:1, group, notification). |
| sendID | string | Sender user ID. |
| recvID | string | Receiver user ID (single-chat); empty for group. |
| msgFrom | int32 | Origin classification (user vs system/notification). |
| contentType | int32 | Content variant discriminator selecting the active `*Elem`. |
| senderPlatformID | int32 | Sending device platform. |
| senderNickname | string | Sender display name at send time. |
| senderFaceURL | string | Sender avatar URL at send time. |
| groupID | string | Target group ID (group sessions). |
| content | string | Raw/serialized content payload (transport form). |
| seq | int64 | Per-conversation monotonic sequence number. |
| isRead | bool | Whether local user has read this message. |
| status | int32 | Send/lifecycle status (sending, succeeded, failed). |
| offlinePush | object | Offline-push config (title/body/sound/extension hints). |
| attachedInfo | string | Serialized mirror of `attachedInfoElem`. |
| ex | string | Server-side extension. |
| localEx | string | **Local-only** extension; never synced to server. |
| *Elem fields | (see below) | One populated per `contentType`. |

Content elements present on the envelope: `textElem`, `cardElem`, `pictureElem`,
`soundElem`, `videoElem`, `fileElem`, `mergeElem`, `atTextElem`, `faceElem`,
`locationElem`, `customElem`, `quoteElem`, `notificationElem`, `advancedTextElem`,
`typingElem`, `attachedInfoElem`, `markdownTextElem`.

`NewMsgList` = ordered list of `MsgStruct`, ascending by `sendTime`.

## 2. Content element variants

**TextElem** / **MarkdownTextElem**: `content` (string).

**CardElem**: `userID`, `nickname`, `faceURL`, `ex` (strings).

**PictureElem**: `sourcePath` (string, local pre-upload), `sourcePicture`,
`bigPicture`, `snapshotPicture` (each a **PictureBaseInfo**).
**PictureBaseInfo**: `uuid` (string), `type` (string, MIME), `size` (int64),
`width` (int32), `height` (int32), `url` (string).

**SoundElem**: `uuid`, `soundPath` (local), `sourceUrl`, `dataSize` (int64),
`duration` (int64 sec), `soundType` (string).

**VideoElem**: `videoPath` (local), `videoUUID`, `videoUrl`, `videoType`,
`videoSize` (int64), `duration` (int64 sec), `snapshotPath` (local), `snapshotUUID`,
`snapshotSize` (int64), `snapshotUrl`, `snapshotWidth`/`snapshotHeight` (int32),
`snapshotType`.

**FileElem**: `filePath` (local), `uuid`, `sourceUrl`, `fileName`,
`fileSize` (int64), `fileType`.

**FaceElem**: `index` (int), `data` (string).

**LocationElem**: `description` (string), `longitude`/`latitude` (float64).

**CustomElem**: `data` (string, often JSON), `description`, `extension`.

**AtTextElem**: `text` (string), `atUserList` (string[], sentinel = "@all"),
`atUsersInfo` (**AtInfo**[]), `quoteMessage` (MsgStruct, optional), `isAtSelf` (bool).
**AtInfo**: `atUserID`, `groupNickname`.

**QuoteElem**: `text`, `quoteMessage` (MsgStruct), `messageEntityList` (MessageEntity[]).

**MergeElem**: `title`, `abstractList` (string[]), `multiMessage` (MsgStruct[]),
`messageEntityList` (MessageEntity[]).

**AdvancedTextElem**: `text`, `messageEntityList` (MessageEntity[]).
**MessageEntity**: `type` (string: mention/url/custom), `offset`/`length` (int32),
`url` (string), `ex` (string).

**NotificationElem**: `detail` (string, serialized payload).
**TypingElem**: `msgTips` (string).

## 3. Attached metadata

**AttachedInfoElem**: `groupHasReadInfo` (GroupHasReadInfo), `isPrivateChat` (bool),
`burnDuration` (int32 sec), `hasReadTime` (int64), `messageEntityList`,
`isEncryption` (bool), `inEncryptStatus` (bool), `uploadProgress` (UploadProgress).

**GroupHasReadInfo**: `hasReadUserIDList` (string[]), `hasReadCount` (int32),
`groupMemberCount` (int32).

**UploadProgress**: `total`, `save`, `current` (int64), `uploadID` (string).

## 4. Reactions & receipts

**MessageReaction**: `clientMsgID`, `reactionType` (int), `counter` (int32),
`userID`, `groupID`, `sessionType` (int32), `info`.

**ReactionElem**: `counter` (int32), `type` (int), `userReactionList`
(UserReactionElem[]), `canRepeat` (bool), `info`.
**UserReactionElem**: `userID`, `counter` (int32), `info`.

**MessageReceipt**: `groupID`, `userID`, `msgIDList` (string[]), `readTime` (int64),
`msgFrom`/`contentType`/`sessionType` (int32).

**MessageRevoked**: `revokerID`, `revokerRole` (int32), `clientMsgID`,
`revokerNickname`, `revokeTime` (int64), `sourceMessageSendTime` (int64),
`sourceMessageSendID`, `sourceMessageSenderNickname`, `sessionType` (int32),
`seq` (int64), `ex`, `isAdminRevoke` (bool).

## 5. Conversation — `LocalConversation`

Delivered as JSON in conversation callbacks; the chat-list aggregate.

| Field | Type | Meaning |
|---|---|---|
| conversationID | string | Stable conversation key (primary identity). |
| conversationType | int32 | 1:1 / group / notification kind. |
| userID | string | Peer ID (single chats). |
| groupID | string | Group ID (group chats). |
| showName | string | Chat-list display name. |
| faceURL | string | Chat-list avatar. |
| recvMsgOpt | int32 | Receive/notification option (normal, muted…). |
| unreadCount | int32 | Unread count. |
| groupAtType | int32 | Pending @ flag (none / @me / @all / notice). |
| latestMsg | string | Serialized latest message (preview). |
| latestMsgSendTime | int64 | Latest message time; chat-list ordering key. |
| draftText | string | Saved unsent draft. |
| draftTextTime | int64 | Draft last-edited time. |
| isPinned | bool | Pinned to top. |
| isPrivateChat | bool | Burn-after-reading mode. |
| burnDuration | int32 | Burn timer seconds (**default 30**). |
| isNotInGroup | bool | Local user no longer a member. |
| updateUnreadCountTime | int64 | Last unread recompute time. |
| attachedInfo | string | Serialized extra metadata. |
| ex | string | Extension. |
| maxSeq | int64 | Highest known sequence. |
| minSeq | int64 | Lowest retained sequence. |
| msgDestructTime | int64 | Auto-destruct interval (**default 604800 = 7d**). |
| isMsgDestruct | bool | Scheduled auto-destruction enabled. |

## 6. Relationship / user models

**LocalFriend**: `ownerUserID`, `userID`, `remark`, `createTime` (int64),
`addSource` (int32), `operatorUserID`, `nickname`, `faceURL`, `ex`,
`attachedInfo`, `isPinned` (bool).

**LocalFriendRequest**: `fromUserID`, `fromNickname`, `fromFaceURL`, `toUserID`,
`toNickname`, `toFaceURL`, `handleResult` (int32: pending/accepted/rejected),
`reqMsg`, `createTime` (int64), `handlerUserID`, `handleMsg`, `handleTime` (int64),
`ex`, `attachedInfo`.

**LocalBlack**: `ownerUserID`, `blockUserID`, `nickname`, `faceURL`,
`createTime` (int64), `addSource` (int32), `operatorUserID`, `ex`, `attachedInfo`.

Also flowing through group/friend listeners as JSON: **LocalGroup** (groupID,
groupName, notification, introduction, faceURL, ownerUserID, memberCount,
needVerification, lookMemberInfo, applyMemberFriend, …), **LocalGroupMember**
(groupID, userID, nickname, faceURL, roleLevel, joinTime, muteEndTime,
inviterUserID, …), **LocalGroupRequest**, **LocalUser**.

**PublicUser**: `userID`, `nickname`, `faceURL`, `ex`, `createTime` (int64).

## 7. SDK configuration — `IMConfig`

| Field | Type | Meaning |
|---|---|---|
| systemType | string | Host OS/system identifier. |
| platformID | int32 | Platform code. |
| apiAddr | string | REST API base URL. |
| wsAddr | string | WebSocket gateway URL. |
| dataDir | string | Local data/cache directory. |
| logLevel | uint32 | Logging verbosity. |
| isLogStandardOutput | bool | Mirror logs to stdout. |
| logFilePath | string | Log file directory. |
| logRemainCount | uint32 | Rotated log files kept. |
| stopGoroutineOnBackground | bool | Suspend background work when backgrounded (iOS watchdog mitigation). |

## 8. Callback / listener interfaces

**Base** — `OnError(errCode: int32, errMsg: string)`, `OnSuccess(data: string)`.
**SendMsgCallBack** (extends Base) — adds `OnProgress(progress: int)` (0–100 for
attachment upload), then `OnSuccess` (sent `MsgStruct`) or `OnError`.

(Full per-method firing semantics for OnConnListener, OnConversationListener,
OnAdvancedMsgListener, OnFriendshipListener, OnGroupListener, OnUserListener,
OnCustomBusinessListener, OnMessageKvInfoListener are in Part 1. Additional
interfaces below were not in Part 1.)

**OnListenerForService** (reduced, server/bot integrations; JSON params):
`OnGroupApplicationAdded`, `OnGroupApplicationAccepted`, `OnFriendApplicationAdded`,
`OnFriendApplicationAccepted`, `OnRecvNewMessage` — semantics match the full listeners.

**OnSignalingListener** (RTC/calling; JSON params): `OnReceiveNewInvitation`,
`OnInviteeAccepted`, `OnInviteeAcceptedByOtherDevice`, `OnInviteeRejected`,
`OnInviteeRejectedByOtherDevice`, `OnInvitationCancelled`, `OnInvitationTimeout`,
`OnHangUp`, `OnRoomParticipantConnected`, `OnRoomParticipantDisconnected`.

**UploadFileCallback** (invoked in order): `Open(size)` → `PartSize(partSize, num)`
→ `HashPartProgress(index, size, partHash)` → `HashPartComplete(partsHash, fileHash)`
→ `UploadID(uploadID)` → `UploadPartComplete(index, partSize, partHash)` →
`UploadComplete(fileSize, streamSize, storageSize)` → `Complete(size, url, typ)`.

**UploadLogProgress**: `OnProgress(current: int64, size: int64)`.

## Kotlin reimplementation notes

- Model content as a **sealed/variant type keyed on `contentType`**; only the
  matching element is non-null. Preserve exact JSON field names for wire/interop.
- Listeners are JSON strings at the FFI boundary today; in Kotlin expose typed
  listeners and serialize only at any platform bridge (the Go code already has a
  typed friendship sibling `OnFriendshipListenerSdk` that wraps the string one).
- `localEx` and `*Path` fields are local-only — never send to server. `attachedInfo`
  / `ex` are serialized companions of structured `attachedInfoElem` / extension data.
- Ordering: messages ascending by `sendTime`; conversations by `latestMsgSendTime`.
- Preserve defaults: conversation `burnDuration` = 30, `msgDestructTime` = 604800.
