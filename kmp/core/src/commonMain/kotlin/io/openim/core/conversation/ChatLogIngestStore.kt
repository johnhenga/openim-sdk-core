package io.openim.core.conversation

import io.openim.core.db.ChatLog
import io.openim.core.db.ChatLogStore

/**
 * [MessageIngestStore] over the dynamic per-conversation chat-log tables —
 * the storage half of Go's pullMessageIntoTable (batchInsertMessageList /
 * batchUpdateMessageList over the conversation table).
 */
class ChatLogIngestStore(private val store: ChatLogStore) : MessageIngestStore {

    override suspend fun getMessagesByClientMsgIDs(
        conversationID: String,
        clientMsgIDs: List<String>,
    ): List<ChatLog> = store.getByClientMsgIDs(conversationID, clientMsgIDs)

    override suspend fun batchInsertMessages(conversationID: String, msgs: List<ChatLog>) {
        msgs.forEach { store.insert(conversationID, it) }
    }

    override suspend fun batchUpdateMessages(conversationID: String, msgs: List<ChatLog>) {
        msgs.forEach { store.update(conversationID, it) }
    }
}
