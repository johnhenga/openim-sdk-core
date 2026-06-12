package io.openim.core.conversation

import io.openim.core.network.ApiClient
import io.openim.core.sync.SyncState
import io.openim.core.sync.Syncer
import io.openim.core.sync.VersionSyncStore
import io.openim.core.sync.VersionSynchronizer
import kotlinx.serialization.Serializable

/**
 * Row of `local_conversations` (mirror of model_struct.LocalConversation).
 * The fields below the divider are local-side state maintained by the
 * conversation triggers (ConversationTrigger) — server sync neither sends
 * nor overwrites them (SqlConversationStore.update lists server columns
 * only).
 */
data class LocalConversation(
    val conversationID: String,
    val conversationType: Int = 0,
    val userID: String = "",
    val groupID: String = "",
    val recvMsgOpt: Int = 0,
    val groupAtType: Int = 0,
    val isPinned: Boolean = false,
    val burnDuration: Int = 0,
    val isPrivateChat: Boolean = false,
    val attachedInfo: String = "",
    val ex: String = "",
    val msgDestructTime: Long = 0,
    val isMsgDestruct: Boolean = false,
    // ---- local trigger state ----
    val showName: String = "",
    val faceURL: String = "",
    val latestMsg: String = "",
    val latestMsgSendTime: Long = 0,
    val unreadCount: Int = 0,
    val isNotInGroup: Boolean = false,
)

/** JSON DTO for conversation.proto Conversation (proto field names). */
@Serializable
data class ConversationDto(
    val ownerUserID: String = "",
    val conversationID: String = "",
    val recvMsgOpt: Int = 0,
    val conversationType: Int = 0,
    val userID: String = "",
    val groupID: String = "",
    val isPinned: Boolean = false,
    val attachedInfo: String = "",
    val isPrivateChat: Boolean = false,
    val groupAtType: Int = 0,
    val ex: String = "",
    val burnDuration: Int = 0,
    val msgDestructTime: Long = 0,
    val isMsgDestruct: Boolean = false,
)

/** Go: internal/conversation_msg/conversion.go ServerConversationToLocal. */
fun ConversationDto.toLocalConversation(): LocalConversation = LocalConversation(
    conversationID = conversationID,
    conversationType = conversationType,
    userID = userID,
    groupID = groupID,
    recvMsgOpt = recvMsgOpt,
    groupAtType = groupAtType,
    isPinned = isPinned,
    burnDuration = burnDuration,
    isPrivateChat = isPrivateChat,
    attachedInfo = attachedInfo,
    ex = ex,
    msgDestructTime = msgDestructTime,
    isMsgDestruct = isMsgDestruct,
)

@Serializable
data class GetIncrementalConversationReq(
    val userID: String,
    val versionID: String = "",
    val version: Long = 0,
)

@Serializable
data class GetIncrementalConversationResp(
    val version: Long = 0,
    val versionID: String = "",
    val full: Boolean = false,
    val delete: List<String> = emptyList(),
    val insert: List<ConversationDto> = emptyList(),
    val update: List<ConversationDto> = emptyList(),
)

@Serializable
data class GetFullOwnerConversationIDsReq(val userID: String)

@Serializable
data class GetFullOwnerConversationIDsResp(
    val version: Long = 0,
    val versionID: String = "",
    val equal: Boolean = false,
    val conversationIDs: List<String> = emptyList(),
)

/** Conversation table operations (over local_conversations). */
interface ConversationStore {
    suspend fun getAll(): List<LocalConversation>

    /** Sync-side insert/update: server-owned columns only. */
    suspend fun insert(conversation: LocalConversation)
    suspend fun update(conversation: LocalConversation)

    // ---- trigger-side operations (Go: GetMultipleConversationDB,
    // BatchUpdateConversationList, BatchInsertConversationList) ----
    suspend fun getByIDs(conversationIDs: List<String>): List<LocalConversation>
    suspend fun batchUpdateFull(conversations: List<LocalConversation>)
    suspend fun batchInsertFull(conversations: List<LocalConversation>)
}

object ConversationApiRoutes {
    const val GET_INCREMENTAL_CONVERSATIONS = "/conversation/get_incremental_conversations"
    const val GET_FULL_CONVERSATION_IDS = "/conversation/get_full_conversation_ids"
}

/**
 * Conversation incremental sync: port of
 * internal/conversation_msg/incremental_sync.go IncrSyncConversations.
 * Reconciliation runs with skipDeletion (Go passes `true`): conversation
 * rows are never deleted by sync — the server's delete keys only trim the
 * version id_list.
 */
class ConversationSync(
    private val loginUserID: String,
    private val api: ApiClient,
    versionStore: VersionSyncStore,
    private val store: ConversationStore,
    notice: suspend (state: SyncState, server: LocalConversation?, local: LocalConversation?) -> Unit = { _, _, _ -> },
) {

    private val synchronizer = VersionSynchronizer(
        tableName = "local_conversations",
        entityID = loginUserID,
        versionStore = versionStore,
        key = { it: LocalConversation -> it.conversationID },
        local = { store.getAll() },
        server = { stored ->
            val resp = api.post(
                ConversationApiRoutes.GET_INCREMENTAL_CONVERSATIONS,
                GetIncrementalConversationReq(loginUserID, stored?.versionID ?: "", stored?.version ?: 0),
                GetIncrementalConversationReq.serializer(),
                GetIncrementalConversationResp.serializer(),
            )
            VersionSynchronizer.Response(
                versionID = resp.versionID,
                version = resp.version,
                full = resp.full,
                deleteKeys = resp.delete,
                updates = resp.update.map { it.toLocalConversation() },
                inserts = resp.insert.map { it.toLocalConversation() },
            )
        },
        syncer = Syncer(
            uuid = { it.conversationID },
            insert = { store.insert(it) },
            update = { server, _ -> store.update(server) },
            delete = { /* unreachable with skipDeletion */ },
        ),
        fullSyncer = { TODO("full conversation sync — Phase 3 module assembly") },
        fullIDs = {
            api.post(
                ConversationApiRoutes.GET_FULL_CONVERSATION_IDS,
                GetFullOwnerConversationIDsReq(loginUserID),
                GetFullOwnerConversationIDsReq.serializer(),
                GetFullOwnerConversationIDsResp.serializer(),
            ).conversationIDs
        },
        notice = notice,
        syncArgs = Syncer.Args(skipDeletion = true),
    )

    suspend fun incrementalSync() = synchronizer.incrementalSync()
}
