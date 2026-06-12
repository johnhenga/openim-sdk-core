package io.openim.core.relation

import io.openim.core.network.ApiClient
import io.openim.core.network.ApiRoutes
import io.openim.core.sync.SyncState
import io.openim.core.sync.Syncer
import io.openim.core.sync.VersionSyncStore
import io.openim.core.sync.VersionSynchronizer
import kotlinx.serialization.Serializable

/** Row of `local_friends` (mirror of model_struct.LocalFriend). */
data class LocalFriend(
    val ownerUserID: String,
    val friendUserID: String,
    val remark: String = "",
    val createTime: Long = 0,
    val addSource: Int = 0,
    val operatorUserID: String = "",
    val nickname: String = "",
    val faceURL: String = "",
    val ex: String = "",
    val attachedInfo: String = "",
    val isPinned: Boolean = false,
)

/**
 * JSON DTOs for the HTTP API. The Go server marshals its protobuf structs
 * with encoding/json, so field names are the proto field names
 * (relation.proto getIncrementalFriendsReq/Resp, sdkws.proto FriendInfo).
 */
@Serializable
data class UserInfoDto(
    val userID: String = "",
    val nickname: String = "",
    val faceURL: String = "",
    val ex: String = "",
)

@Serializable
data class FriendInfoDto(
    val ownerUserID: String = "",
    val remark: String = "",
    val createTime: Long = 0,
    val friendUser: UserInfoDto = UserInfoDto(),
    val addSource: Int = 0,
    val operatorUserID: String = "",
    val ex: String = "",
    val isPinned: Boolean = false,
)

/** Go: internal/relation/conversion.go ServerFriendToLocalFriend. */
fun FriendInfoDto.toLocalFriend(): LocalFriend = LocalFriend(
    ownerUserID = ownerUserID,
    friendUserID = friendUser.userID,
    remark = remark,
    createTime = createTime,
    addSource = addSource,
    operatorUserID = operatorUserID,
    nickname = friendUser.nickname,
    faceURL = friendUser.faceURL,
    ex = ex,
    isPinned = isPinned,
)

@Serializable
data class GetIncrementalFriendsReq(
    val userID: String,
    val versionID: String = "",
    val version: Long = 0,
)

@Serializable
data class GetIncrementalFriendsResp(
    val version: Long = 0,
    val versionID: String = "",
    val full: Boolean = false,
    val delete: List<String> = emptyList(),
    val insert: List<FriendInfoDto> = emptyList(),
    val update: List<FriendInfoDto> = emptyList(),
    val sortVersion: Long = 0,
)

@Serializable
data class GetFullFriendUserIDsReq(val userID: String)

@Serializable
data class GetFullFriendUserIDsResp(
    val version: Long = 0,
    val versionID: String = "",
    val equal: Boolean = false,
    val userIDs: List<String> = emptyList(),
)

/** Friend table operations (implemented over local_friends in SQLDelight). */
interface FriendStore {
    suspend fun getAll(ownerUserID: String): List<LocalFriend>
    suspend fun insert(friend: LocalFriend)
    suspend fun update(friend: LocalFriend)
    suspend fun delete(ownerUserID: String, friendUserID: String)
    suspend fun deleteAll(ownerUserID: String)
    suspend fun batchInsert(friends: List<LocalFriend>)
}

/**
 * Friend incremental sync: the Kotlin counterpart of
 * internal/relation/incremental_sync.go IncrSyncFriends — a
 * [VersionSynchronizer] instantiation over `local_friends` and the
 * `/friend/get_incremental_friends` endpoint. This is the template the
 * group/conversation modules follow.
 */
class FriendSync(
    private val loginUserID: String,
    private val api: ApiClient,
    versionStore: VersionSyncStore,
    private val friendStore: FriendStore,
    notice: suspend (state: SyncState, server: LocalFriend?, local: LocalFriend?) -> Unit = { _, _, _ -> },
) {

    private val synchronizer = VersionSynchronizer(
        tableName = "local_friends",
        entityID = loginUserID,
        versionStore = versionStore,
        key = { it.friendUserID },
        local = { friendStore.getAll(loginUserID) },
        server = { stored ->
            val resp = api.post(
                ApiRoutes.GET_INCREMENTAL_FRIENDS,
                GetIncrementalFriendsReq(
                    userID = loginUserID,
                    versionID = stored?.versionID ?: "",
                    version = stored?.version ?: 0,
                ),
                GetIncrementalFriendsReq.serializer(),
                GetIncrementalFriendsResp.serializer(),
            )
            VersionSynchronizer.Response(
                versionID = resp.versionID,
                version = resp.version,
                full = resp.full,
                deleteKeys = resp.delete,
                updates = resp.update.map { it.toLocalFriend() },
                inserts = resp.insert.map { it.toLocalFriend() },
                idOrderChanged = resp.sortVersion > 0,
            )
        },
        syncer = Syncer(
            uuid = { it.friendUserID },
            insert = { friendStore.insert(it) },
            update = { server, _ -> friendStore.update(server) },
            delete = { friendStore.delete(it.ownerUserID, it.friendUserID) },
        ),
        fullSyncer = {
            // Go FullSync re-fetches the page set and replaces local data;
            // ported minimally as wipe + batch insert from the full list.
            TODO("full friend sync (Go: friendSyncer.FullSync) — Phase 3")
        },
        fullIDs = {
            api.post(
                ApiRoutes.GET_FULL_FRIEND_USER_IDS,
                GetFullFriendUserIDsReq(userID = loginUserID),
                GetFullFriendUserIDsReq.serializer(),
                GetFullFriendUserIDsResp.serializer(),
            ).userIDs
        },
        notice = notice,
    )

    suspend fun incrementalSync() = synchronizer.incrementalSync()
}
