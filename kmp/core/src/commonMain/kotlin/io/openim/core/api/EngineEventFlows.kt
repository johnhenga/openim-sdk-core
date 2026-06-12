package io.openim.core.api

import io.openim.core.conversation.LocalConversation
import io.openim.core.engine.EngineListener
import io.openim.core.sync.SyncFlag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import openim.sdkws.MsgData
import openim.sdkws.PullMsgs

/**
 * Bridges [EngineListener] callbacks into the typed event flows of the
 * modern API (design doc §6.1). Buffered so the engine never suspends on a
 * slow collector; on overflow the oldest events are dropped (UI state is
 * re-readable from the store).
 */
class EngineEventFlows : EngineListener {

    private val _conversationEvents = MutableSharedFlow<ConversationEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val conversationEvents: SharedFlow<ConversationEvent> = _conversationEvents

    private val _newMessages = MutableSharedFlow<MsgData>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val newMessages: SharedFlow<MsgData> = _newMessages

    private val _newConversations = MutableSharedFlow<List<LocalConversation>>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Typed payloads for callers that want models instead of IDs. */
    val newConversations: SharedFlow<List<LocalConversation>> = _newConversations

    private val _changedConversations = MutableSharedFlow<List<LocalConversation>>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val changedConversations: SharedFlow<List<LocalConversation>> = _changedConversations

    override suspend fun onNewConversations(conversations: List<LocalConversation>) {
        _newConversations.emit(conversations)
        _conversationEvents.emit(
            ConversationEvent.NewConversation(conversations.map { it.conversationID })
        )
    }

    override suspend fun onConversationsChanged(conversations: List<LocalConversation>) {
        _changedConversations.emit(conversations)
        _conversationEvents.emit(
            ConversationEvent.ConversationChanged(conversations.map { it.conversationID })
        )
    }

    override suspend fun onTotalUnreadCountChanged() {
        // The count itself is store-derived; emit with -1 as "recompute".
        _conversationEvents.emit(ConversationEvent.TotalUnreadCountChanged(-1))
    }

    override suspend fun onNewMessages(msgs: List<MsgData>) {
        msgs.forEach { _newMessages.emit(it) }
    }

    override suspend fun onSyncFlag(flag: SyncFlag) {
        val event = when (flag) {
            SyncFlag.MsgSyncBegin, SyncFlag.AppDataSyncStart -> ConversationEvent.SyncServerStart
            SyncFlag.MsgSyncEnd, SyncFlag.AppDataSyncFinish -> ConversationEvent.SyncServerFinish
            SyncFlag.MsgSyncFailed -> ConversationEvent.SyncServerFailed
            SyncFlag.SyncData -> return
        }
        _conversationEvents.emit(event)
    }

    override suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>) = Unit
}
