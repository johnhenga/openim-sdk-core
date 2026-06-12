package io.openim.core.api.compat

import io.openim.core.conversation.LocalConversation
import io.openim.core.conversation.toLocalChatLog
import io.openim.core.db.ChatLog
import io.openim.core.engine.EngineListener
import io.openim.core.engine.OpenIMEngine
import io.openim.core.sync.SyncFlag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import openim.sdkws.MsgData
import openim.sdkws.PullMsgs

/**
 * JSON shapes of the gomobile API — field names match the Go struct json
 * tags exactly (pkg/db/model_struct LocalConversation, LocalChatLog), so the
 * existing platform wrappers can parse the shim's output unchanged.
 */
@Serializable
data class ConversationJson(
    val conversationID: String,
    val conversationType: Int,
    val userID: String,
    val groupID: String,
    val showName: String,
    val faceURL: String,
    val recvMsgOpt: Int,
    val unreadCount: Int,
    val groupAtType: Int,
    val latestMsg: String,
    val latestMsgSendTime: Long,
    val draftText: String = "",
    val draftTextTime: Long = 0,
    val isPinned: Boolean,
    val isPrivateChat: Boolean,
    val burnDuration: Int,
    val isNotInGroup: Boolean,
    val updateUnreadCountTime: Long = 0,
    val attachedInfo: String,
    val ex: String,
    val maxSeq: Long = 0,
    val minSeq: Long = 0,
    val msgDestructTime: Long,
    val isMsgDestruct: Boolean,
)

internal fun LocalConversation.toJsonModel() = ConversationJson(
    conversationID = conversationID, conversationType = conversationType,
    userID = userID, groupID = groupID, showName = showName, faceURL = faceURL,
    recvMsgOpt = recvMsgOpt, unreadCount = unreadCount, groupAtType = groupAtType,
    latestMsg = latestMsg, latestMsgSendTime = latestMsgSendTime,
    isPinned = isPinned, isPrivateChat = isPrivateChat, burnDuration = burnDuration,
    isNotInGroup = isNotInGroup, attachedInfo = attachedInfo, ex = ex,
    msgDestructTime = msgDestructTime, isMsgDestruct = isMsgDestruct,
)

/** LocalChatLog json tags (pkg/db/model_struct). */
@Serializable
data class MessageJson(
    val clientMsgID: String,
    val serverMsgID: String,
    val sendID: String,
    val recvID: String,
    val senderPlatformID: Int,
    val senderNickname: String,
    val senderFaceURL: String,
    val sessionType: Int,
    val msgFrom: Int,
    val contentType: Int,
    val content: String,
    val isRead: Boolean,
    val status: Int,
    val seq: Long,
    val sendTime: Long,
    val createTime: Long,
    val attachedInfo: String,
    val ex: String,
    val localEx: String,
)

internal fun ChatLog.toJsonModel() = MessageJson(
    clientMsgID = clientMsgID, serverMsgID = serverMsgID ?: "", sendID = sendID ?: "",
    recvID = recvID ?: "", senderPlatformID = (senderPlatformID ?: 0).toInt(),
    senderNickname = senderNickname ?: "", senderFaceURL = senderFaceURL ?: "",
    sessionType = (sessionType ?: 0).toInt(), msgFrom = (msgFrom ?: 0).toInt(),
    contentType = (contentType ?: 0).toInt(), content = content ?: "",
    isRead = isRead, status = (status ?: 0).toInt(), seq = seq,
    sendTime = sendTime ?: 0, createTime = createTime ?: 0,
    attachedInfo = attachedInfo ?: "", ex = ex ?: "", localEx = localEx ?: "",
)

/**
 * The gomobile-compatible facade (design doc §6.2): JSON-string arguments
 * and Base/listener callbacks with the exact signatures of open_im_sdk/,
 * implemented over [OpenIMEngine]. Functions are added as their engine
 * support lands; each is a thin translation layer.
 */
class OpenIMCompat(
    private val engine: OpenIMEngine,
    private val scope: CoroutineScope,
) : EngineListener {

    private val json = Json { encodeDefaults = true }

    private var conversationListener: OnConversationListener? = null
    private var advancedMsgListener: OnAdvancedMsgListener? = null

    fun setConversationListener(listener: OnConversationListener) {
        conversationListener = listener
    }

    fun setAdvancedMsgListener(listener: OnAdvancedMsgListener) {
        advancedMsgListener = listener
    }

    /** Go: GetAllConversationList — OnSuccess receives the JSON array. */
    fun getAllConversationList(callback: Base, operationID: String) {
        dispatch(callback) {
            json.encodeToString(
                ListSerializer(ConversationJson.serializer()),
                engine.conversations.getAll().map { it.toJsonModel() },
            )
        }
    }

    /** Go: CreateTextMessage — returns the message JSON. */
    fun createTextMessage(callback: Base, operationID: String, text: String) {
        dispatch(callback) {
            val draft = engine.sender.createTextMessage(text)
            // toLocalChatLog normalizes status for *received* server messages
            // (Go MsgDataToLocalChatLog); a local draft keeps Sending.
            json.encodeToString(
                MessageJson.serializer(),
                draft.toLocalChatLog().copy(status = draft.status.toLong()).toJsonModel(),
            )
        }
    }

    /** Go: SendMessage — message JSON in, final message JSON out. */
    fun sendMessage(
        callback: SendMsgCallBack,
        operationID: String,
        message: String,
        recvID: String,
        groupID: String,
        offlinePushInfo: String,
        isOnlineOnly: Boolean,
    ) {
        scope.launch {
            try {
                val draft = json.decodeFromString(MessageJson.serializer(), message)
                val sent = engine.sendMessage(
                    draftToMsgData(draft),
                    recvID = recvID,
                    groupID = groupID,
                    isOnlineOnly = isOnlineOnly,
                )
                callback.onSuccess(json.encodeToString(MessageJson.serializer(), sent.toJsonModel()))
            } catch (e: Throwable) {
                callback.onError(SDK_INTERNAL_ERROR, e.message ?: "send failed")
            }
        }
    }

    private fun draftToMsgData(m: MessageJson): MsgData = MsgData(
        clientMsgID = m.clientMsgID, sendID = m.sendID,
        senderPlatformID = m.senderPlatformID, senderNickname = m.senderNickname,
        senderFaceURL = m.senderFaceURL, sessionType = m.sessionType,
        msgFrom = m.msgFrom, contentType = m.contentType,
        content = m.content.encodeUtf8(),
        status = m.status, createTime = m.createTime, sendTime = m.sendTime,
    )

    private fun dispatch(callback: Base, block: suspend () -> String) {
        scope.launch {
            try {
                callback.onSuccess(block())
            } catch (e: Throwable) {
                callback.onError(SDK_INTERNAL_ERROR, e.message ?: "internal error")
            }
        }
    }

    // ---- EngineListener: engine events fan out to the registered
    // gomobile-style listeners with the Go JSON shapes ----

    override suspend fun onNewConversations(conversations: List<LocalConversation>) {
        conversationListener?.onNewConversation(
            json.encodeToString(
                ListSerializer(ConversationJson.serializer()),
                conversations.map { it.toJsonModel() },
            )
        )
    }

    override suspend fun onConversationsChanged(conversations: List<LocalConversation>) {
        conversationListener?.onConversationChanged(
            json.encodeToString(
                ListSerializer(ConversationJson.serializer()),
                conversations.map { it.toJsonModel() },
            )
        )
    }

    override suspend fun onTotalUnreadCountChanged() {
        val total = engine.conversations.getAll()
            .filter { it.recvMsgOpt < 2 }
            .sumOf { it.unreadCount }
        conversationListener?.onTotalUnreadMessageCountChanged(total)
    }

    override suspend fun onNewMessages(msgs: List<MsgData>) {
        val listener = advancedMsgListener ?: return
        for (msg in msgs) {
            listener.onRecvNewMessage(
                json.encodeToString(MessageJson.serializer(), msg.toLocalChatLog().toJsonModel())
            )
        }
    }

    override suspend fun onSyncFlag(flag: SyncFlag) {
        val l = conversationListener ?: return
        when (flag) {
            SyncFlag.MsgSyncBegin -> l.onSyncServerStart(false)
            SyncFlag.MsgSyncEnd -> l.onSyncServerFinish(false)
            SyncFlag.MsgSyncFailed -> l.onSyncServerFailed(false)
            SyncFlag.AppDataSyncStart -> l.onSyncServerStart(true)
            SyncFlag.AppDataSyncFinish -> l.onSyncServerFinish(true)
            SyncFlag.SyncData -> Unit
        }
    }

    override suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>) = Unit

    companion object {
        /** Go: sdkerrs SdkInternalError. */
        const val SDK_INTERNAL_ERROR = 10001
    }
}
