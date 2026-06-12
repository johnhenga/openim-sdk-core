package io.openim.core.conversation

import io.openim.core.db.ChatLog
import io.openim.core.db.ChatLogStore
import io.openim.core.network.nowMillis
import okio.ByteString.Companion.encodeUtf8
import openim.sdkws.MsgData
import kotlin.random.Random

/** Content type and msg-from constants used by the send path (pkg/constant). */
object ContentType {
    const val TEXT = 101
}

object MsgFrom {
    const val USER = 100
}

/** Go: pkg/utils GetConversationIDByMsg. */
fun conversationIDForMessage(sessionType: Int, sendID: String, recvID: String, groupID: String): String =
    when (sessionType.toLong()) {
        SessionType.SINGLE_CHAT -> "si_" + listOf(sendID, recvID).sorted().joinToString("_")
        SessionType.WRITE_GROUP_CHAT -> "g_$groupID"
        SessionType.READ_GROUP_CHAT -> "sg_$groupID"
        SessionType.NOTIFICATION -> "sn_" + listOf(sendID, recvID).sorted().joinToString("_")
        else -> error("unknown session type $sessionType")
    }

/** Server ack for a sent message (Go: msg.SendMsgResp). */
data class SendMsgAck(
    val serverMsgID: String,
    val clientMsgID: String,
    val sendTime: Long,
    /** Set when the server rewrote the message (content moderation etc.). */
    val modify: MsgData? = null,
)

/** WS send of a message (Go: SendReqWaitResp with ReqIdentifier SendMsg). */
fun interface SendTransport {
    suspend fun sendMsg(msg: MsgData): SendMsgAck
}

/** `local_sending_messages` — pending sends awaiting the server ack. */
interface SendingMessagesStore {
    suspend fun insert(conversationID: String, clientMsgID: String)
    suspend fun delete(conversationID: String, clientMsgID: String)
}

/** Sender display info (Go: user.GetUserInfoWithCache). */
data class SelfInfo(val nickname: String, val faceURL: String)

/**
 * The send pipeline: port of internal/conversation_msg/api.go
 * initBasicInfo + sendMessageToServer + updateMsgStatusAndTriggerConversation.
 *
 * Lifecycle (non online-only): the draft is stored with status Sending and
 * recorded in local_sending_messages; on ack the row gets
 * serverMsgID/sendTime/SendSuccess and the pending record is removed; on
 * failure the row is marked SendFailed (pending record also removed, as Go
 * does). A network timeout double-checks the DB first — the ack may have
 * raced in through the push path.
 */
class MessageSender(
    private val loginUserID: String,
    private val platformID: Int,
    private val selfInfo: suspend () -> SelfInfo,
    private val chatLogs: ChatLogStore,
    private val sendingStore: SendingMessagesStore,
    private val transport: SendTransport,
    /** Conversation trigger hook (Go: DispatchUpdateConversation AddConOrUpLatMsg). */
    private val onMessageStatusChanged: suspend (conversationID: String, message: ChatLog) -> Unit = { _, _ -> },
    private val isTimeout: (Throwable) -> Boolean = { false },
    private val clock: () -> Long = ::nowMillis,
    private val msgIDGenerator: () -> String = { defaultMsgID(loginUserID) },
) {

    /** Go: initBasicInfo + CreateTextMessage. */
    suspend fun createTextMessage(text: String): MsgData {
        val now = clock()
        val self = selfInfo()
        return MsgData(
            clientMsgID = msgIDGenerator(),
            sendID = loginUserID,
            senderPlatformID = platformID,
            senderNickname = self.nickname,
            senderFaceURL = self.faceURL,
            createTime = now,
            sendTime = now,
            status = MsgStatus.SENDING.toInt(),
            msgFrom = MsgFrom.USER,
            contentType = ContentType.TEXT,
            content = text.encodeUtf8(),
        )
    }

    /**
     * Sends [draft] to [recvID] (single chat) or [groupID] (group chat).
     * Returns the final message (ack applied, or status SendFailed before
     * the error is rethrown).
     */
    suspend fun send(
        draft: MsgData,
        recvID: String = "",
        groupID: String = "",
        isOnlineOnly: Boolean = false,
    ): ChatLog {
        require((recvID.isEmpty()) != (groupID.isEmpty())) { "exactly one of recvID/groupID" }
        val sessionType =
            if (groupID.isNotEmpty()) SessionType.READ_GROUP_CHAT.toInt() else SessionType.SINGLE_CHAT.toInt()
        val addressed = draft.copy(
            recvID = recvID,
            groupID = groupID,
            sessionType = sessionType,
            options = if (isOnlineOnly) ONLINE_ONLY_OPTIONS else draft.options,
        )
        val conversationID =
            conversationIDForMessage(sessionType, loginUserID, recvID, groupID)

        if (!isOnlineOnly) {
            chatLogs.insert(conversationID, addressed.toLocalChatLog().copy(status = MsgStatus.SENDING))
            sendingStore.insert(conversationID, addressed.clientMsgID)
        }

        val ack = try {
            // Go zeroes SendTime on the wire; the server assigns it.
            transport.sendMsg(addressed.copy(sendTime = 0))
        } catch (e: Throwable) {
            if (isTimeout(e) && !isOnlineOnly) {
                // The ack may have arrived through the push path meanwhile.
                val stored = chatLogs.getByClientMsgIDs(conversationID, listOf(addressed.clientMsgID))
                    .firstOrNull()
                if (stored != null && stored.status == MsgStatus.SEND_SUCCESS) {
                    sendingStore.delete(conversationID, addressed.clientMsgID)
                    return stored
                }
            }
            finalize(
                conversationID, addressed, serverMsgID = "",
                sendTime = addressed.createTime, status = MsgStatus.SEND_FAILED,
                isOnlineOnly = isOnlineOnly,
            )
            throw e
        }

        return finalize(
            conversationID,
            ack.modify ?: addressed,
            serverMsgID = ack.serverMsgID,
            sendTime = ack.sendTime,
            status = MsgStatus.SEND_SUCCESS,
            isOnlineOnly = isOnlineOnly,
        )
    }

    /** Go: updateMsgStatusAndTriggerConversation (+ server-modify handling). */
    private suspend fun finalize(
        conversationID: String,
        message: MsgData,
        serverMsgID: String,
        sendTime: Long,
        status: Long,
        isOnlineOnly: Boolean,
    ): ChatLog {
        val final = message.toLocalChatLog().copy(
            serverMsgID = serverMsgID,
            sendTime = sendTime,
            status = status,
        )
        if (isOnlineOnly) return final
        chatLogs.update(conversationID, final)
        sendingStore.delete(conversationID, final.clientMsgID)
        onMessageStatusChanged(conversationID, final)
        return final
    }

    companion object {
        /** Go: the option switches sendMessageToServer turns off. */
        val ONLINE_ONLY_OPTIONS: Map<String, Boolean> = mapOf(
            MsgOptions.IS_HISTORY to false,
            "persistent" to false,
            "senderSync" to false,
            MsgOptions.IS_CONVERSATION_UPDATE to false,
            MsgOptions.IS_SENDER_CONVERSATION_UPDATE to false,
            MsgOptions.IS_UNREAD_COUNT to false,
            "offlinePush" to false,
        )

        /** Stand-in for Go utils.GetMsgID (md5 of nanos+sendID+rand). */
        fun defaultMsgID(sendID: String): String =
            "${nowMillis()}-$sendID-${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}
