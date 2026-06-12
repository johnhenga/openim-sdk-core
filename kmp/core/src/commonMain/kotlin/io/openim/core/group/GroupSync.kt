package io.openim.core.group

import io.openim.core.network.ApiClient
import io.openim.core.sync.SyncState
import io.openim.core.sync.Syncer
import io.openim.core.sync.VersionSyncStore
import io.openim.core.sync.VersionSynchronizer
import kotlinx.serialization.Serializable

/** Row of `local_groups` (mirror of model_struct.LocalGroup). */
data class LocalGroup(
    val groupID: String,
    val name: String = "",
    val notification: String = "",
    val introduction: String = "",
    val faceURL: String = "",
    val createTime: Long = 0,
    val status: Int = 0,
    val creatorUserID: String = "",
    val groupType: Int = 0,
    val ownerUserID: String = "",
    val memberCount: Int = 0,
    val ex: String = "",
    val attachedInfo: String = "",
    val needVerification: Int = 0,
    val lookMemberInfo: Int = 0,
    val applyMemberFriend: Int = 0,
    val notificationUpdateTime: Long = 0,
    val notificationUserID: String = "",
)

/** Row of `local_group_members` (mirror of model_struct.LocalGroupMember). */
data class LocalGroupMember(
    val groupID: String,
    val userID: String,
    val nickname: String = "",
    val userGroupFaceURL: String = "",
    val roleLevel: Int = 0,
    val joinTime: Long = 0,
    val joinSource: Int = 0,
    val inviterUserID: String = "",
    val muteEndTime: Long = 0,
    val operatorUserID: String = "",
    val ex: String = "",
    val attachedInfo: String = "",
)

/** JSON DTOs following the proto field names (sdkws.proto, group.proto). */
@Serializable
data class GroupInfoDto(
    val groupID: String = "",
    val groupName: String = "",
    val notification: String = "",
    val introduction: String = "",
    val faceURL: String = "",
    val ownerUserID: String = "",
    val createTime: Long = 0,
    val memberCount: Int = 0,
    val ex: String = "",
    val status: Int = 0,
    val creatorUserID: String = "",
    val groupType: Int = 0,
    val needVerification: Int = 0,
    val lookMemberInfo: Int = 0,
    val applyMemberFriend: Int = 0,
    val notificationUpdateTime: Long = 0,
    val notificationUserID: String = "",
)

@Serializable
data class GroupMemberFullInfoDto(
    val groupID: String = "",
    val userID: String = "",
    val roleLevel: Int = 0,
    val joinTime: Long = 0,
    val nickname: String = "",
    val faceURL: String = "",
    val joinSource: Int = 0,
    val operatorUserID: String = "",
    val ex: String = "",
    val muteEndTime: Long = 0,
    val inviterUserID: String = "",
)

/** Go: internal/group/conversion.go ServerGroupToLocalGroup. */
fun GroupInfoDto.toLocalGroup(): LocalGroup = LocalGroup(
    groupID = groupID, name = groupName, notification = notification,
    introduction = introduction, faceURL = faceURL, createTime = createTime,
    status = status, creatorUserID = creatorUserID, groupType = groupType,
    ownerUserID = ownerUserID, memberCount = memberCount, ex = ex,
    needVerification = needVerification, lookMemberInfo = lookMemberInfo,
    applyMemberFriend = applyMemberFriend,
    notificationUpdateTime = notificationUpdateTime,
    notificationUserID = notificationUserID,
)

/** Go: ServerGroupMemberToLocalGroupMember (userGroupFaceURL from faceURL). */
fun GroupMemberFullInfoDto.toLocalGroupMember(): LocalGroupMember = LocalGroupMember(
    groupID = groupID, userID = userID, nickname = nickname,
    userGroupFaceURL = faceURL, roleLevel = roleLevel, joinTime = joinTime,
    joinSource = joinSource, inviterUserID = inviterUserID,
    muteEndTime = muteEndTime, operatorUserID = operatorUserID, ex = ex,
)

@Serializable
data class GetIncrementalJoinGroupReq(
    val userID: String,
    val versionID: String = "",
    val version: Long = 0,
)

@Serializable
data class GetIncrementalJoinGroupResp(
    val version: Long = 0,
    val versionID: String = "",
    val full: Boolean = false,
    val delete: List<String> = emptyList(),
    val insert: List<GroupInfoDto> = emptyList(),
    val update: List<GroupInfoDto> = emptyList(),
    val sortVersion: Long = 0,
)

@Serializable
data class GetFullJoinGroupIDsReq(val userID: String)

@Serializable
data class GetFullJoinGroupIDsResp(val groupIDs: List<String> = emptyList())

@Serializable
data class GetIncrementalGroupMemberReq(
    val groupID: String,
    val versionID: String = "",
    val version: Long = 0,
)

@Serializable
data class GetIncrementalGroupMemberResp(
    val version: Long = 0,
    val versionID: String = "",
    val full: Boolean = false,
    val delete: List<String> = emptyList(),
    val insert: List<GroupMemberFullInfoDto> = emptyList(),
    val update: List<GroupMemberFullInfoDto> = emptyList(),
    val group: GroupInfoDto? = null,
    val sortVersion: Long = 0,
)

@Serializable
data class BatchGetIncrementalGroupMemberReq(
    val userID: String,
    val reqList: List<GetIncrementalGroupMemberReq>,
)

@Serializable
data class BatchGetIncrementalGroupMemberResp(
    val respList: Map<String, GetIncrementalGroupMemberResp> = emptyMap(),
)

@Serializable
data class GetFullGroupMemberUserIDsReq(val groupID: String)

@Serializable
data class GetFullGroupMemberUserIDsResp(val userIDs: List<String> = emptyList())

/** Group table operations (over local_groups / local_group_members). */
interface GroupStore {
    suspend fun joinedGroups(): List<LocalGroup>
    suspend fun insertGroup(group: LocalGroup)
    suspend fun updateGroup(group: LocalGroup)
    suspend fun deleteGroup(groupID: String)
    suspend fun membersOf(groupID: String): List<LocalGroupMember>
    suspend fun insertMember(member: LocalGroupMember)
    suspend fun updateMember(member: LocalGroupMember)
    suspend fun deleteMember(groupID: String, userID: String)
}

object GroupApiRoutes {
    const val GET_INCREMENTAL_JOIN_GROUPS = "/group/get_incremental_join_groups"
    const val GET_INCREMENTAL_GROUP_MEMBERS_BATCH = "/group/get_incremental_group_members_batch"
    const val GET_FULL_JOIN_GROUP_IDS = "/group/get_full_join_group_ids"
    const val GET_FULL_GROUP_MEMBER_USER_IDS = "/group/get_full_group_member_user_ids"
}

/**
 * Joined-group + group-member incremental sync: port of
 * internal/group/incremental_sync.go.
 *
 * [syncJoinedGroupsAndMembers] mirrors SyncAllJoinedGroupsAndMembersWithLock:
 * first the joined-group list, then members of every joined group via the
 * batch endpoint (up to [MAX_SYNC_PULL_NUMBER] version cursors per request).
 * The member response may piggyback updated group info, which is applied to
 * `local_groups` through the synchronizer's extraData hook, as in Go.
 */
class GroupSync(
    private val loginUserID: String,
    private val api: ApiClient,
    private val versionStore: VersionSyncStore,
    private val store: GroupStore,
    private val groupNotice: suspend (state: SyncState, server: LocalGroup?, local: LocalGroup?) -> Unit = { _, _, _ -> },
    private val memberNotice: suspend (state: SyncState, server: LocalGroupMember?, local: LocalGroupMember?) -> Unit = { _, _, _ -> },
) {

    private val groupSyncer = Syncer<LocalGroup, String>(
        uuid = { it.groupID },
        insert = { store.insertGroup(it) },
        update = { server, _ -> store.updateGroup(server) },
        delete = { store.deleteGroup(it.groupID) },
    )

    suspend fun syncJoinedGroupsAndMembers() {
        syncJoinedGroups()
        val groupIDs = store.joinedGroups().map { it.groupID }
        syncMembers(groupIDs)
    }

    /** Go: IncrSyncJoinGroup over /group/get_incremental_join_groups. */
    suspend fun syncJoinedGroups() {
        VersionSynchronizer(
            tableName = GROUP_TABLE_NAME,
            entityID = loginUserID,
            versionStore = versionStore,
            key = { it: LocalGroup -> it.groupID },
            local = { store.joinedGroups() },
            server = { stored ->
                val resp = api.post(
                    GroupApiRoutes.GET_INCREMENTAL_JOIN_GROUPS,
                    GetIncrementalJoinGroupReq(loginUserID, stored?.versionID ?: "", stored?.version ?: 0),
                    GetIncrementalJoinGroupReq.serializer(),
                    GetIncrementalJoinGroupResp.serializer(),
                )
                VersionSynchronizer.Response(
                    versionID = resp.versionID,
                    version = resp.version,
                    full = resp.full,
                    deleteKeys = resp.delete,
                    updates = resp.update.map { it.toLocalGroup() },
                    inserts = resp.insert.map { it.toLocalGroup() },
                    idOrderChanged = resp.sortVersion > 0,
                )
            },
            syncer = groupSyncer,
            fullSyncer = { TODO("full joined-group sync — Phase 3 module assembly") },
            fullIDs = {
                api.post(
                    GroupApiRoutes.GET_FULL_JOIN_GROUP_IDS,
                    GetFullJoinGroupIDsReq(loginUserID),
                    GetFullJoinGroupIDsReq.serializer(),
                    GetFullJoinGroupIDsResp.serializer(),
                ).groupIDs
            },
            notice = groupNotice,
        ).incrementalSync()
    }

    /**
     * Go: IncrSyncGroupAndMember — batches each group's stored version
     * cursor into /group/get_incremental_group_members_batch and applies
     * every per-group response through a VersionSynchronizer.
     *
     * Deliberate divergence from Go: after a batch, requested groups absent
     * from the response are dropped from the pending set (Go only drops them
     * when the whole response is empty, re-requesting partial misses
     * forever).
     */
    suspend fun syncMembers(groupIDs: List<String>) {
        val pending = ArrayDeque(groupIDs.distinct())
        while (pending.isNotEmpty()) {
            val batch = buildList {
                while (pending.isNotEmpty() && size < MAX_SYNC_PULL_NUMBER) {
                    val groupID = pending.removeFirst()
                    val stored = versionStore.get(MEMBER_VERSION_TABLE_NAME, groupID)
                    add(GetIncrementalGroupMemberReq(groupID, stored?.versionID ?: "", stored?.version ?: 0))
                }
            }
            val resp = api.post(
                GroupApiRoutes.GET_INCREMENTAL_GROUP_MEMBERS_BATCH,
                BatchGetIncrementalGroupMemberReq(loginUserID, batch),
                BatchGetIncrementalGroupMemberReq.serializer(),
                BatchGetIncrementalGroupMemberResp.serializer(),
            )
            for ((groupID, memberResp) in resp.respList) {
                syncGroupMembers(groupID, memberResp)
            }
        }
    }

    /** Go: syncGroupAndMember — one group's member reconciliation. */
    suspend fun syncGroupMembers(groupID: String, resp: GetIncrementalGroupMemberResp) {
        VersionSynchronizer(
            tableName = MEMBER_VERSION_TABLE_NAME,
            entityID = groupID,
            versionStore = versionStore,
            key = { it: LocalGroupMember -> it.userID },
            local = { store.membersOf(groupID) },
            server = {
                VersionSynchronizer.Response(
                    versionID = resp.versionID,
                    version = resp.version,
                    full = resp.full,
                    deleteKeys = resp.delete,
                    updates = resp.update.map { it.toLocalGroupMember() },
                    inserts = resp.insert.map { it.toLocalGroupMember() },
                    idOrderChanged = resp.sortVersion > 0,
                    extraData = resp.group,
                )
            },
            syncer = Syncer(
                uuid = { it.userID },
                insert = { store.insertMember(it) },
                update = { server, _ -> store.updateMember(server) },
                delete = { store.deleteMember(it.groupID, it.userID) },
            ),
            fullSyncer = { TODO("full group-member sync — Phase 3 module assembly") },
            fullIDs = {
                api.post(
                    GroupApiRoutes.GET_FULL_GROUP_MEMBER_USER_IDS,
                    GetFullGroupMemberUserIDsReq(groupID),
                    GetFullGroupMemberUserIDsReq.serializer(),
                    GetFullGroupMemberUserIDsResp.serializer(),
                ).userIDs
            },
            notice = memberNotice,
            extraDataProcessor = { extra ->
                // Go ExtraDataProcessor: the piggybacked group info is
                // reconciled into local_groups (update-or-insert, no deletes).
                val groupInfo = (extra as GroupInfoDto).toLocalGroup()
                val local = store.joinedGroups()
                val expected = local.associateBy { it.groupID }.toMutableMap()
                expected[groupInfo.groupID] = groupInfo
                groupSyncer.sync(expected.values.toList(), local) { state, server, localRow ->
                    groupNotice(state, server, localRow)
                }
            },
        ).incrementalSync()
    }

    companion object {
        /** Go: protocol constant.MaxSyncPullNumber. */
        const val MAX_SYNC_PULL_NUMBER = 500

        const val GROUP_TABLE_NAME = "local_groups"

        /** Go: groupAndMemberVersionTableName. */
        const val MEMBER_VERSION_TABLE_NAME = "local_group_entities_version"
    }
}
