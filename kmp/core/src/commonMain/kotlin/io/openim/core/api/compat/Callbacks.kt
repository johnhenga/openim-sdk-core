package io.openim.core.api.compat

/**
 * The gomobile-compatible callback surface (design doc §6.2): exact mirrors
 * of the interfaces in open_im_sdk_callback/, with the same method names and
 * JSON payload shapes. Existing platform wrapper repos
 * (open-im-sdk-android / open-im-sdk-ios) implement these already, so they
 * can swap the Go engine for the Kotlin core with near-zero changes — and the
 * parity harness can drive both engines through identical inputs.
 */

/** Go: open_im_sdk_callback.Base. */
interface Base {
    fun onError(errCode: Int, errMsg: String)
    fun onSuccess(data: String)
}

/** Go: open_im_sdk_callback.SendMsgCallBack. */
interface SendMsgCallBack : Base {
    fun onProgress(progress: Int)
}

/** Go: open_im_sdk_callback.OnConnListener. */
interface OnConnListener {
    fun onConnecting()
    fun onConnectSuccess()
    fun onConnectFailed(errCode: Int, errMsg: String)
    fun onKickedOffline()
    fun onUserTokenExpired()
    fun onUserTokenInvalid(errMsg: String)
}

/** Go: open_im_sdk_callback.OnConversationListener. */
interface OnConversationListener {
    fun onSyncServerStart(reinstalled: Boolean)
    fun onSyncServerFinish(reinstalled: Boolean)
    fun onSyncServerProgress(progress: Int)
    fun onSyncServerFailed(reinstalled: Boolean)
    fun onNewConversation(conversationList: String)
    fun onConversationChanged(conversationList: String)
    fun onTotalUnreadMessageCountChanged(totalUnreadCount: Int)
    fun onConversationUserInputStatusChanged(change: String)
}

/** Go: open_im_sdk_callback.OnAdvancedMsgListener. */
interface OnAdvancedMsgListener {
    fun onRecvNewMessage(message: String)
    fun onRecvC2CReadReceipt(msgReceiptList: String)
    fun onNewRecvMessageRevoked(messageRevoked: String)
    fun onRecvOfflineNewMessage(message: String)
    fun onMsgDeleted(message: String)
    fun onRecvOnlineOnlyMessage(message: String)
}

/** Go: open_im_sdk_callback.OnFriendshipListener. */
interface OnFriendshipListener {
    fun onFriendApplicationAdded(friendApplication: String)
    fun onFriendApplicationDeleted(friendApplication: String)
    fun onFriendApplicationAccepted(friendApplication: String)
    fun onFriendApplicationRejected(friendApplication: String)
    fun onFriendAdded(friendInfo: String)
    fun onFriendDeleted(friendInfo: String)
    fun onFriendInfoChanged(friendInfo: String)
    fun onBlackAdded(blackInfo: String)
    fun onBlackDeleted(blackInfo: String)
}

/** Go: open_im_sdk_callback.OnGroupListener. */
interface OnGroupListener {
    fun onJoinedGroupAdded(groupInfo: String)
    fun onJoinedGroupDeleted(groupInfo: String)
    fun onGroupMemberAdded(groupMemberInfo: String)
    fun onGroupMemberDeleted(groupMemberInfo: String)
    fun onGroupApplicationAdded(groupApplication: String)
    fun onGroupApplicationDeleted(groupApplication: String)
    fun onGroupInfoChanged(groupInfo: String)
    fun onGroupDismissed(groupInfo: String)
    fun onGroupApplicationAccepted(groupApplication: String)
    fun onGroupApplicationRejected(groupApplication: String)
    fun onGroupMemberInfoChanged(groupMemberInfo: String)
}

/** Go: open_im_sdk_callback.OnUserListener. */
interface OnUserListener {
    fun onSelfInfoUpdated(userInfo: String)
    fun onUserStatusChanged(userOnlineStatus: String)
}

/** Go: open_im_sdk_callback.OnCustomBusinessListener. */
interface OnCustomBusinessListener {
    fun onRecvCustomBusinessMessage(businessMessage: String)
}

/** Go: open_im_sdk_callback.OnMessageKvInfoListener. */
interface OnMessageKvInfoListener {
    fun onMessageKvInfoChanged(messageChangedList: String)
}

/** Go: open_im_sdk_callback.UploadFileCallback. */
interface UploadFileCallback {
    fun open(size: Long)
    fun partSize(partSize: Long, num: Int)
    fun hashPartProgress(index: Int, size: Long, partHash: String)
    fun hashPartComplete(partsHash: String, fileHash: String)
    fun uploadID(uploadID: String)
    fun uploadPartComplete(index: Int, partSize: Long, partHash: String)
    fun uploadComplete(fileSize: Long, streamSize: Long, storageSize: Long)
    fun complete(size: Long, url: String, typ: Int)
}
