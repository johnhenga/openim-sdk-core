package io.openim.core.conversation

import io.openim.core.db.ChatLog
import io.openim.core.network.nowMillis
import openim.sdkws.MsgData
import kotlin.random.Random

/** Message status constants (pkg/constant). */
object MsgStatus {
    const val SEND_SUCCESS = 2L
    const val HAS_DELETED = 4L
}

/** Session type constants (pkg/constant). */
object SessionType {
    const val SINGLE_CHAT = 1L
    const val WRITE_GROUP_CHAT = 2L
    const val READ_GROUP_CHAT = 3L
    const val NOTIFICATION = 4L
}

/** Go: conversion.go MsgDataToLocalChatLog. */
fun MsgData.toLocalChatLog(): ChatLog = ChatLog(
    clientMsgID = clientMsgID,
    serverMsgID = serverMsgID,
    sendID = sendID,
    recvID = if (sessionType.toLong() == SessionType.WRITE_GROUP_CHAT ||
        sessionType.toLong() == SessionType.READ_GROUP_CHAT
    ) groupID else recvID,
    senderPlatformID = senderPlatformID.toLong(),
    senderNickname = senderNickname,
    senderFaceURL = senderFaceURL,
    sessionType = sessionType.toLong(),
    msgFrom = msgFrom.toLong(),
    contentType = contentType.toLong(),
    content = content.utf8(),
    isRead = isRead,
    status = if (status >= MsgStatus.HAS_DELETED) status.toLong() else MsgStatus.SEND_SUCCESS,
    seq = seq,
    sendTime = sendTime,
    createTime = createTime,
    attachedInfo = attachedInfo,
    ex = ex,
)

/** Storage operations the ingest pipeline needs. */
interface MessageIngestStore {
    suspend fun getMessagesByClientMsgIDs(conversationID: String, clientMsgIDs: List<String>): List<ChatLog>
    suspend fun batchInsertMessages(conversationID: String, msgs: List<ChatLog>)
    suspend fun batchUpdateMessages(conversationID: String, msgs: List<ChatLog>)
}

/**
 * Ingests pulled/pushed server messages into the local store: the port of
 * internal/conversation_msg/message_check.go pullMessageIntoTable +
 * handleExceptionMessages.
 *
 * Categories, mirroring Go:
 * - cloud-deleted messages become local placeholders ([SEQ_GAP_+seq] when
 *   the server sent no ClientMsgID, [DELETED] otherwise)
 * - my own message already stored with seq 0 (sent from this device, ack
 *   seq not yet recorded) is updated in place with the server seq
 * - duplicates (same ClientMsgID stored, or repeated within the batch) are
 *   marked [SEQ_DUP]/[CLIENT_DUP], deleted-status, and inserted under a
 *   uniquified ID
 * - everything else is inserted (self-from-other-device or from others)
 *
 * Deliberate divergence from Go: the Go loop accumulates the per-category
 * slices across conversations without resetting them, re-submitting earlier
 * conversations' messages on every later iteration (the duplicate inserts
 * fail silently). This port processes each conversation independently,
 * which is the evident intent.
 */
class MessageIngestor(
    private val loginUserID: String,
    private val store: MessageIngestStore,
    private val genMsgID: () -> String = { defaultMsgID(loginUserID) },
    private val randomSuffix: () -> String = ::defaultRandomSuffix,
) {

    /**
     * @property inserted rows written, per conversation (incl. placeholders).
     * @property updated rows updated in place (seq backfill of own sends);
     *   callers must drop these ClientMsgIDs from any new-message trigger
     *   list (Go filters `list` the same way).
     * @property exceptions the subset of inserted rows that were exception
     *   placeholders/duplicates.
     */
    data class Result(
        val inserted: List<ChatLog>,
        val updated: List<ChatLog>,
        val exceptions: List<ChatLog>,
    )

    suspend fun ingest(conversationID: String, msgs: List<MsgData>): Result {
        val localMessages = store.getMessagesByClientMsgIDs(
            conversationID,
            msgs.map { it.clientMsgID },
        ).associateBy { it.clientMsgID }

        val inserted = mutableListOf<ChatLog>()
        val updated = mutableListOf<ChatLog>()
        val exceptions = mutableListOf<ChatLog>()
        val processed = mutableMapOf<String, ChatLog>()

        for (serverMsg in msgs) {
            var msg = serverMsg.toLocalChatLog()

            val inBatch = processed[serverMsg.clientMsgID]
            if (inBatch != null) {
                msg = markException(existing = inBatch, message = msg)
                exceptions += msg
                inserted += msg
                continue
            }
            if (serverMsg.status.toLong() == MsgStatus.HAS_DELETED) {
                msg = markException(existing = null, message = msg)
                exceptions += msg
                inserted += msg
                continue
            }

            val existing = localMessages[msg.clientMsgID]
            if (serverMsg.sendID == loginUserID) {
                when {
                    existing == null -> inserted += msg // sent from another device
                    existing.seq == 0L -> updated += msg // seq backfill of own send
                    else -> {
                        msg = markException(existing = existing, message = msg)
                        exceptions += msg
                        inserted += msg
                    }
                }
            } else {
                if (existing == null) {
                    inserted += msg
                } else {
                    msg = markException(existing = existing, message = msg)
                    exceptions += msg
                    inserted += msg
                }
            }
            processed[msg.clientMsgID] = msg
        }

        if (updated.isNotEmpty()) store.batchUpdateMessages(conversationID, updated)
        if (inserted.isNotEmpty()) store.batchInsertMessages(conversationID, inserted)
        return Result(inserted, updated, exceptions)
    }

    /** Go: handleExceptionMessages — returns the rewritten placeholder row. */
    internal fun markException(existing: ChatLog?, message: ChatLog): ChatLog {
        var clientMsgID = message.clientMsgID
        val prefix = if (existing == null) {
            if (message.status == MsgStatus.HAS_DELETED) {
                if (clientMsgID.isEmpty()) {
                    clientMsgID = genMsgID()
                    "[SEQ_GAP_+${message.seq}]"
                } else {
                    "[DELETED]"
                }
            } else {
                "[UNKNOWN]"
            }
        } else if (existing.seq == message.seq) {
            "[SEQ_DUP]"
        } else {
            "[CLIENT_DUP]"
        }
        return message.copy(
            status = MsgStatus.HAS_DELETED,
            clientMsgID = prefix + clientMsgID + "_" + randomSuffix(),
        )
    }

    companion object {
        private const val CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun defaultRandomSuffix(): String =
            buildString(8) { repeat(8) { append(CHARSET[Random.nextInt(CHARSET.length)]) } }

        /** Stand-in for Go utils.GetMsgID (md5 of time+user+rand). */
        fun defaultMsgID(loginUserID: String): String =
            "${nowMillis()}-$loginUserID-${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}
