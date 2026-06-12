package io.openim.core.conversation

import io.openim.core.sync.MaxSeqRecorder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import openim.sdkws.MsgData
import openim.sdkws.PullMsgs

/**
 * Per-message behavior switches (Go: pkg/constant option keys +
 * utils.GetSwitchFromOptions — note that an ABSENT option means true).
 */
object MsgOptions {
    const val IS_HISTORY = "history"
    const val IS_UNREAD_COUNT = "unreadCount"
    const val IS_CONVERSATION_UPDATE = "conversationUpdate"
    const val IS_NOT_PRIVATE = "notPrivate"
    const val IS_SENDER_CONVERSATION_UPDATE = "senderConversationUpdate"

    fun MsgData.option(key: String): Boolean = options[key] ?: true
}

/** Events the processor emits (Go: OnConversationListener + msg listener). */
interface ConversationEventSink {
    suspend fun onNewConversations(conversations: List<LocalConversation>)
    suspend fun onConversationsChanged(conversations: List<LocalConversation>)
    suspend fun onTotalUnreadCountChanged()
    suspend fun onNewMessages(msgs: List<MsgData>)
}

/**
 * The receive-path orchestrator: assembly of doMsgNew
 * (internal/conversation_msg/conversation_msg.go) from the verified parts —
 * [MessageIngestor] stores messages, [ConversationTrigger] computes the
 * conversation changes, the [ConversationStore] persists them, and
 * [ConversationEventSink] surfaces listener events.
 *
 * Wire this as the MsgSyncListener of MsgSyncer: both onConversationMsgs
 * and reinstall triggers route their per-conversation message maps here.
 *
 * Simplification vs Go (documented): message-content parsing into
 * MsgStruct (PopulateMsgStructByContentType) is not ported yet, so the
 * latest-message preview is produced by [latestMsgEncoder] from raw MsgData.
 */
class ConversationProcessor(
    private val loginUserID: String,
    private val ingestor: MessageIngestor,
    private val store: ConversationStore,
    private val recorder: MaxSeqRecorder,
    private val events: ConversationEventSink,
    /** Fills showName/faceURL of new conversations from user/group caches. */
    private val enrich: suspend (List<LocalConversation>) -> List<LocalConversation> = { it },
    private val latestMsgEncoder: (MsgData) -> String = ::defaultPreview,
) {

    suspend fun processNewMessages(allMsgs: Map<String, PullMsgs>) {
        if (allMsgs.isEmpty()) return

        val conversationSet = mutableMapOf<String, LocalConversation>()
        val newMessages = LinkedHashMap<String, MsgData>()
        var unreadChanged = false

        for ((conversationID, pullMsgs) in allMsgs) {
            if (conversationID.isEmpty()) continue
            val result = ingestor.ingest(conversationID, pullMsgs.Msgs)
            // Exception rows get rewritten IDs, so this set contains exactly
            // the newly stored regular messages.
            val storedNewIDs = result.inserted.map { it.clientMsgID }.toSet()

            for (msg in pullMsgs.Msgs) {
                if (msg.status.toLong() == MsgStatus.HAS_DELETED) continue
                with(MsgOptions) {
                    val isHistory = msg.option(MsgOptions.IS_HISTORY)
                    val isConversationUpdate = msg.option(MsgOptions.IS_CONVERSATION_UPDATE)
                    if (!isHistory) newMessages[msg.clientMsgID] = msg
                    if (msg.clientMsgID !in storedNewIDs) return@with // updated/duplicate

                    val sentByMe = msg.sendID == loginUserID
                    if (sentByMe) {
                        if (isConversationUpdate) {
                            if (msg.option(MsgOptions.IS_SENDER_CONVERSATION_UPDATE)) {
                                ConversationTrigger.accumulate(conversationSet, seed(conversationID, msg, sentByMe = true, unread = 0))
                            }
                            newMessages[msg.clientMsgID] = msg
                        }
                    } else {
                        // Go computes the unread delta before (and regardless
                        // of) the conversation-update switch.
                        val unread = ConversationTrigger.unreadDelta(
                            recorder, conversationID, msg.seq,
                            isUnreadCountOption = msg.option(MsgOptions.IS_UNREAD_COUNT),
                        )
                        if (unread > 0) unreadChanged = true
                        if (isConversationUpdate) {
                            ConversationTrigger.accumulate(conversationSet, seed(conversationID, msg, sentByMe = false, unread = unread))
                            newMessages[msg.clientMsgID] = msg
                        }
                    }
                }
            }
        }

        if (conversationSet.isNotEmpty()) {
            val local = store.getByIDs(conversationSet.keys.toList())
            val localMap = local.associateBy { it.conversationID }
            val placeholders = local.filter { it.latestMsgSendTime == 0L }

            val diff = ConversationTrigger.diff(localMap, conversationSet, enrich)
            val ph = ConversationTrigger.mergePlaceholders(placeholders, diff.new)

            store.batchUpdateFull((diff.changed.values + ph.changed.values).toList())
            store.batchInsertFull(ph.new.values.toList())

            // Go emits the full new-set (incl. placeholder-merged) as new,
            // and the changed-set (excl. placeholder-merged) as changed.
            if (diff.new.isNotEmpty()) events.onNewConversations(diff.new.values.toList())
            if (diff.changed.isNotEmpty()) events.onConversationsChanged(diff.changed.values.toList())
        }

        if (newMessages.isNotEmpty()) events.onNewMessages(newMessages.values.toList())
        if (unreadChanged) events.onTotalUnreadCountChanged()
    }

    private fun seed(conversationID: String, msg: MsgData, sentByMe: Boolean, unread: Int) =
        ConversationTrigger.seedFromMessage(
            conversationID = conversationID,
            sessionType = msg.sessionType,
            sendID = msg.sendID,
            recvID = msg.recvID,
            groupID = msg.groupID,
            senderNickname = msg.senderNickname,
            senderFaceURL = msg.senderFaceURL,
            latestMsgJson = latestMsgEncoder(msg),
            sendTime = msg.sendTime,
            sentByMe = sentByMe,
            unreadCount = unread,
        )

    companion object {
        /** Compact preview until the MsgStruct content parser is ported. */
        fun defaultPreview(msg: MsgData): String = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("clientMsgID", msg.clientMsgID)
                put("sendID", msg.sendID)
                put("senderNickname", msg.senderNickname)
                put("sessionType", msg.sessionType)
                put("contentType", msg.contentType)
                put("content", msg.content.utf8())
                put("seq", msg.seq)
                put("sendTime", msg.sendTime)
            },
        )
    }
}
