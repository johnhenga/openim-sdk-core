package io.openim.core.conversation

import io.openim.core.sync.MaxSeqRecorder

/**
 * Pure decision logic of the new-message conversation triggers: ports of
 * the snapshot/merge functions inside doMsgNew
 * (internal/conversation_msg/conversation_msg.go). The conversation module
 * orchestrator feeds these with ingested messages and applies the resulting
 * changed/new sets to the DB and listeners.
 */
object ConversationTrigger {

    /**
     * Go: updateConversation — folds a per-message conversation snapshot
     * into the accumulated set. Unread counts add up; the latest message
     * wins by sendTime.
     */
    fun accumulate(set: MutableMap<String, LocalConversation>, lc: LocalConversation) {
        val old = set[lc.conversationID]
        if (old == null) {
            set[lc.conversationID] = lc
            return
        }
        set[lc.conversationID] = if (lc.latestMsgSendTime > old.latestMsgSendTime) {
            old.copy(
                unreadCount = old.unreadCount + lc.unreadCount,
                latestMsg = lc.latestMsg,
                latestMsgSendTime = lc.latestMsgSendTime,
            )
        } else {
            old.copy(unreadCount = old.unreadCount + lc.unreadCount)
        }
    }

    /**
     * Go: the isUnreadCount branch of doMsgNew — a received message counts
     * as unread only if its seq is ahead of the recorder, which is then
     * advanced. Returns the unread delta (0 or 1).
     */
    fun unreadDelta(
        recorder: MaxSeqRecorder,
        conversationID: String,
        seq: Long,
        isUnreadCountOption: Boolean,
    ): Int {
        if (!isUnreadCountOption || !recorder.isNewMsg(conversationID, seq)) return 0
        recorder.incr(conversationID, 1)
        return 1
    }

    data class DiffResult(
        val changed: Map<String, LocalConversation>,
        val new: Map<String, LocalConversation>,
    )

    /**
     * Go: diff — merges accumulated snapshots into the locally stored
     * conversations. Existing conversations get unread added and the latest
     * message replaced only if newer; unknown conversations become the
     * new-set after [enrich] fills name/face from user/group info.
     */
    suspend fun diff(
        local: Map<String, LocalConversation>,
        generated: Map<String, LocalConversation>,
        enrich: suspend (List<LocalConversation>) -> List<LocalConversation> = { it },
    ): DiffResult {
        val changed = mutableMapOf<String, LocalConversation>()
        val newConversations = mutableListOf<LocalConversation>()
        for (generatedConv in generated.values) {
            val localConv = local[generatedConv.conversationID]
            if (localConv == null) {
                newConversations += generatedConv
                continue
            }
            changed[generatedConv.conversationID] = if (
                generatedConv.latestMsgSendTime > localConv.latestMsgSendTime
            ) {
                localConv.copy(
                    unreadCount = localConv.unreadCount + generatedConv.unreadCount,
                    latestMsg = generatedConv.latestMsg,
                    latestMsgSendTime = generatedConv.latestMsgSendTime,
                )
            } else {
                localConv.copy(unreadCount = localConv.unreadCount + generatedConv.unreadCount)
            }
        }
        return DiffResult(changed, enrich(newConversations).associateBy { it.conversationID })
    }

    data class PlaceholderMerge(
        /** New-set entries that matched a placeholder: stored as updates. */
        val changed: Map<String, LocalConversation>,
        /** Truly new conversations: stored as inserts. */
        val new: Map<String, LocalConversation>,
    )

    /**
     * Go: the hList loop of doMsgNew. A locally stored conversation with
     * latestMsgSendTime == 0 is a placeholder (created by conversation sync
     * before any message arrived). When its first message generates a
     * "new" conversation, the placeholder's settings are carried over and
     * the row is updated instead of inserted.
     */
    fun mergePlaceholders(
        placeholders: List<LocalConversation>,
        newSet: Map<String, LocalConversation>,
    ): PlaceholderMerge {
        val changed = mutableMapOf<String, LocalConversation>()
        for (placeholder in placeholders) {
            val fresh = newSet[placeholder.conversationID] ?: continue
            changed[placeholder.conversationID] = fresh.copy(
                recvMsgOpt = placeholder.recvMsgOpt,
                groupAtType = placeholder.groupAtType,
                isPinned = placeholder.isPinned,
                isPrivateChat = placeholder.isPrivateChat,
                burnDuration = if (placeholder.isPrivateChat) placeholder.burnDuration else fresh.burnDuration,
                unreadCount = if (placeholder.unreadCount != 0) placeholder.unreadCount else fresh.unreadCount,
                isNotInGroup = placeholder.isNotInGroup,
                attachedInfo = placeholder.attachedInfo,
                ex = placeholder.ex,
                isMsgDestruct = placeholder.isMsgDestruct,
                msgDestructTime = placeholder.msgDestructTime,
            )
        }
        val new = newSet.filterKeys { it !in changed }
        return PlaceholderMerge(changed, new)
    }

    /**
     * Go: the LocalConversation seeds built inside doMsgNew for a message in
     * a conversation. For received single-chat messages the sender's
     * nick/face seed the conversation display.
     */
    fun seedFromMessage(
        conversationID: String,
        sessionType: Int,
        sendID: String,
        recvID: String,
        groupID: String,
        senderNickname: String,
        senderFaceURL: String,
        latestMsgJson: String,
        sendTime: Long,
        sentByMe: Boolean,
        unreadCount: Int = 0,
    ): LocalConversation {
        val base = LocalConversation(
            conversationID = conversationID,
            conversationType = sessionType,
            latestMsg = latestMsgJson,
            latestMsgSendTime = sendTime,
            unreadCount = unreadCount,
        )
        return when (sessionType.toLong()) {
            SessionType.SINGLE_CHAT ->
                if (sentByMe) base.copy(userID = recvID)
                else base.copy(userID = sendID, showName = senderNickname, faceURL = senderFaceURL)
            SessionType.WRITE_GROUP_CHAT, SessionType.READ_GROUP_CHAT ->
                base.copy(groupID = groupID)
            SessionType.NOTIFICATION ->
                base.copy(userID = sendID)
            else -> base
        }
    }
}
