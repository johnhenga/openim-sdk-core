package io.openim.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import io.openim.core.conversation.ConversationStore
import io.openim.core.conversation.LocalConversation
import io.openim.core.group.GroupStore
import io.openim.core.group.LocalGroup
import io.openim.core.group.LocalGroupMember
import io.openim.core.relation.FriendStore
import io.openim.core.relation.LocalFriend
import io.openim.core.sync.VersionSyncState
import io.openim.core.sync.VersionSyncStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SQLite-backed stores for the domain sync modules, issued as raw statements
 * against the Go-compatible schema (see GoSdkSchema). Like the Go data layer,
 * sync updates touch only server-owned columns — local-side state (e.g.
 * conversation unread counts and drafts) is never clobbered by sync.
 */

private fun <R> SqlDriver.rows(
    sql: String,
    parameters: Int = 0,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
    map: (SqlCursor) -> R,
): List<R> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val out = mutableListOf<R>()
        while (cursor.next().value) out += map(cursor)
        QueryResult.Value(out.toList())
    },
    parameters = parameters,
    binders = binders,
).value

/** `local_sync_version`; id_list is JSON, matching Go's StringArray. */
class SqlVersionSyncStore(private val driver: SqlDriver) : VersionSyncStore {
    private val json = Json

    override suspend fun get(tableName: String, entityID: String): VersionSyncState? =
        driver.rows(
            "SELECT version_id, version, create_time, id_list FROM `local_sync_version` WHERE table_name = ? AND entity_id = ?",
            parameters = 2,
            binders = { bindString(0, tableName); bindString(1, entityID) },
        ) { c ->
            VersionSyncState(
                tableName = tableName,
                entityID = entityID,
                versionID = c.getString(0) ?: "",
                version = c.getLong(1) ?: 0,
                createTime = c.getLong(2) ?: 0,
                idList = c.getString(3)?.takeIf { it.isNotEmpty() }
                    ?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
                    ?: emptyList(),
            )
        }.firstOrNull()

    override suspend fun set(state: VersionSyncState) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO `local_sync_version` (table_name, entity_id, version_id, version, create_time, id_list) VALUES (?,?,?,?,?,?)",
            parameters = 6,
        ) {
            bindString(0, state.tableName)
            bindString(1, state.entityID)
            bindString(2, state.versionID)
            bindLong(3, state.version)
            bindLong(4, state.createTime)
            bindString(5, Json.encodeToString(ListSerializer(String.serializer()), state.idList))
        }
    }
}

/** `local_friends`. */
class SqlFriendStore(private val driver: SqlDriver) : FriendStore {

    override suspend fun getAll(ownerUserID: String): List<LocalFriend> =
        driver.rows(
            "SELECT owner_user_id, friend_user_id, remark, create_time, add_source, operator_user_id, name, face_url, ex, attached_info, is_pinned FROM `local_friends` WHERE owner_user_id = ?",
            parameters = 1,
            binders = { bindString(0, ownerUserID) },
        ) { c ->
            LocalFriend(
                ownerUserID = c.getString(0)!!,
                friendUserID = c.getString(1)!!,
                remark = c.getString(2) ?: "",
                createTime = c.getLong(3) ?: 0,
                addSource = (c.getLong(4) ?: 0).toInt(),
                operatorUserID = c.getString(5) ?: "",
                nickname = c.getString(6) ?: "",
                faceURL = c.getString(7) ?: "",
                ex = c.getString(8) ?: "",
                attachedInfo = c.getString(9) ?: "",
                isPinned = (c.getLong(10) ?: 0) != 0L,
            )
        }

    private fun write(friend: LocalFriend) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO `local_friends` (owner_user_id, friend_user_id, remark, create_time, add_source, operator_user_id, name, face_url, ex, attached_info, is_pinned) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            parameters = 11,
        ) {
            bindString(0, friend.ownerUserID)
            bindString(1, friend.friendUserID)
            bindString(2, friend.remark)
            bindLong(3, friend.createTime)
            bindLong(4, friend.addSource.toLong())
            bindString(5, friend.operatorUserID)
            bindString(6, friend.nickname)
            bindString(7, friend.faceURL)
            bindString(8, friend.ex)
            bindString(9, friend.attachedInfo)
            bindLong(10, if (friend.isPinned) 1 else 0)
        }
    }

    override suspend fun insert(friend: LocalFriend) = write(friend)
    override suspend fun update(friend: LocalFriend) = write(friend)
    override suspend fun batchInsert(friends: List<LocalFriend>) = friends.forEach(::write)

    override suspend fun delete(ownerUserID: String, friendUserID: String) {
        driver.execute(
            null,
            "DELETE FROM `local_friends` WHERE owner_user_id = ? AND friend_user_id = ?",
            2,
        ) { bindString(0, ownerUserID); bindString(1, friendUserID) }
    }

    override suspend fun deleteAll(ownerUserID: String) {
        driver.execute(null, "DELETE FROM `local_friends` WHERE owner_user_id = ?", 1) {
            bindString(0, ownerUserID)
        }
    }
}

/** `local_groups` + `local_group_members`. */
class SqlGroupStore(private val driver: SqlDriver) : GroupStore {

    override suspend fun joinedGroups(): List<LocalGroup> =
        driver.rows(
            "SELECT group_id, name, notification, introduction, face_url, create_time, status, creator_user_id, group_type, owner_user_id, member_count, ex, attached_info, need_verification, look_member_info, apply_member_friend, notification_update_time, notification_user_id FROM `local_groups`",
        ) { c ->
            LocalGroup(
                groupID = c.getString(0)!!,
                name = c.getString(1) ?: "",
                notification = c.getString(2) ?: "",
                introduction = c.getString(3) ?: "",
                faceURL = c.getString(4) ?: "",
                createTime = c.getLong(5) ?: 0,
                status = (c.getLong(6) ?: 0).toInt(),
                creatorUserID = c.getString(7) ?: "",
                groupType = (c.getLong(8) ?: 0).toInt(),
                ownerUserID = c.getString(9) ?: "",
                memberCount = (c.getLong(10) ?: 0).toInt(),
                ex = c.getString(11) ?: "",
                attachedInfo = c.getString(12) ?: "",
                needVerification = (c.getLong(13) ?: 0).toInt(),
                lookMemberInfo = (c.getLong(14) ?: 0).toInt(),
                applyMemberFriend = (c.getLong(15) ?: 0).toInt(),
                notificationUpdateTime = c.getLong(16) ?: 0,
                notificationUserID = c.getString(17) ?: "",
            )
        }

    private fun writeGroup(group: LocalGroup) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO `local_groups` (group_id, name, notification, introduction, face_url, create_time, status, creator_user_id, group_type, owner_user_id, member_count, ex, attached_info, need_verification, look_member_info, apply_member_friend, notification_update_time, notification_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            parameters = 18,
        ) {
            bindString(0, group.groupID)
            bindString(1, group.name)
            bindString(2, group.notification)
            bindString(3, group.introduction)
            bindString(4, group.faceURL)
            bindLong(5, group.createTime)
            bindLong(6, group.status.toLong())
            bindString(7, group.creatorUserID)
            bindLong(8, group.groupType.toLong())
            bindString(9, group.ownerUserID)
            bindLong(10, group.memberCount.toLong())
            bindString(11, group.ex)
            bindString(12, group.attachedInfo)
            bindLong(13, group.needVerification.toLong())
            bindLong(14, group.lookMemberInfo.toLong())
            bindLong(15, group.applyMemberFriend.toLong())
            bindLong(16, group.notificationUpdateTime)
            bindString(17, group.notificationUserID)
        }
    }

    override suspend fun insertGroup(group: LocalGroup) = writeGroup(group)
    override suspend fun updateGroup(group: LocalGroup) = writeGroup(group)

    override suspend fun deleteGroup(groupID: String) {
        driver.execute(null, "DELETE FROM `local_groups` WHERE group_id = ?", 1) {
            bindString(0, groupID)
        }
    }

    override suspend fun membersOf(groupID: String): List<LocalGroupMember> =
        driver.rows(
            "SELECT group_id, user_id, nickname, user_group_face_url, role_level, join_time, join_source, inviter_user_id, mute_end_time, operator_user_id, ex, attached_info FROM `local_group_members` WHERE group_id = ?",
            parameters = 1,
            binders = { bindString(0, groupID) },
        ) { c ->
            LocalGroupMember(
                groupID = c.getString(0)!!,
                userID = c.getString(1)!!,
                nickname = c.getString(2) ?: "",
                userGroupFaceURL = c.getString(3) ?: "",
                roleLevel = (c.getLong(4) ?: 0).toInt(),
                joinTime = c.getLong(5) ?: 0,
                joinSource = (c.getLong(6) ?: 0).toInt(),
                inviterUserID = c.getString(7) ?: "",
                muteEndTime = c.getLong(8) ?: 0,
                operatorUserID = c.getString(9) ?: "",
                ex = c.getString(10) ?: "",
                attachedInfo = c.getString(11) ?: "",
            )
        }

    private fun writeMember(member: LocalGroupMember) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO `local_group_members` (group_id, user_id, nickname, user_group_face_url, role_level, join_time, join_source, inviter_user_id, mute_end_time, operator_user_id, ex, attached_info) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            parameters = 12,
        ) {
            bindString(0, member.groupID)
            bindString(1, member.userID)
            bindString(2, member.nickname)
            bindString(3, member.userGroupFaceURL)
            bindLong(4, member.roleLevel.toLong())
            bindLong(5, member.joinTime)
            bindLong(6, member.joinSource.toLong())
            bindString(7, member.inviterUserID)
            bindLong(8, member.muteEndTime)
            bindString(9, member.operatorUserID)
            bindString(10, member.ex)
            bindString(11, member.attachedInfo)
        }
    }

    override suspend fun insertMember(member: LocalGroupMember) = writeMember(member)
    override suspend fun updateMember(member: LocalGroupMember) = writeMember(member)

    override suspend fun deleteMember(groupID: String, userID: String) {
        driver.execute(null, "DELETE FROM `local_group_members` WHERE group_id = ? AND user_id = ?", 2) {
            bindString(0, groupID); bindString(1, userID)
        }
    }
}

/**
 * `local_conversations`. Sync writes only the server-owned columns; local
 * trigger state (unread_count, draft_text, latest_msg, …) keeps the schema
 * defaults on insert and is preserved on update.
 */
class SqlConversationStore(private val driver: SqlDriver) : ConversationStore {

    override suspend fun getAll(): List<LocalConversation> =
        driver.rows(
            "SELECT conversation_id, conversation_type, user_id, group_id, recv_msg_opt, group_at_type, is_pinned, burn_duration, is_private_chat, attached_info, ex, msg_destruct_time, is_msg_destruct FROM `local_conversations`",
        ) { c ->
            LocalConversation(
                conversationID = c.getString(0)!!,
                conversationType = (c.getLong(1) ?: 0).toInt(),
                userID = c.getString(2) ?: "",
                groupID = c.getString(3) ?: "",
                recvMsgOpt = (c.getLong(4) ?: 0).toInt(),
                groupAtType = (c.getLong(5) ?: 0).toInt(),
                isPinned = (c.getLong(6) ?: 0) != 0L,
                burnDuration = (c.getLong(7) ?: 0).toInt(),
                isPrivateChat = (c.getLong(8) ?: 0) != 0L,
                attachedInfo = c.getString(9) ?: "",
                ex = c.getString(10) ?: "",
                msgDestructTime = c.getLong(11) ?: 0,
                isMsgDestruct = (c.getLong(12) ?: 0) != 0L,
            )
        }

    override suspend fun insert(conversation: LocalConversation) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO `local_conversations` (conversation_id, conversation_type, user_id, group_id, recv_msg_opt, group_at_type, is_pinned, burn_duration, is_private_chat, attached_info, ex, msg_destruct_time, is_msg_destruct) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            parameters = 13,
        ) { bindConversation(conversation) }
    }

    override suspend fun update(conversation: LocalConversation) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE `local_conversations` SET
                    conversation_type = ?, user_id = ?, group_id = ?,
                    recv_msg_opt = ?, group_at_type = ?, is_pinned = ?,
                    burn_duration = ?, is_private_chat = ?, attached_info = ?,
                    ex = ?, msg_destruct_time = ?, is_msg_destruct = ?
                WHERE conversation_id = ?
            """.trimIndent(),
            parameters = 13,
        ) {
            bindLong(0, conversation.conversationType.toLong())
            bindString(1, conversation.userID)
            bindString(2, conversation.groupID)
            bindLong(3, conversation.recvMsgOpt.toLong())
            bindLong(4, conversation.groupAtType.toLong())
            bindLong(5, if (conversation.isPinned) 1 else 0)
            bindLong(6, conversation.burnDuration.toLong())
            bindLong(7, if (conversation.isPrivateChat) 1 else 0)
            bindString(8, conversation.attachedInfo)
            bindString(9, conversation.ex)
            bindLong(10, conversation.msgDestructTime)
            bindLong(11, if (conversation.isMsgDestruct) 1 else 0)
            bindString(12, conversation.conversationID)
        }
    }

    private fun SqlPreparedStatement.bindConversation(c: LocalConversation) {
        bindString(0, c.conversationID)
        bindLong(1, c.conversationType.toLong())
        bindString(2, c.userID)
        bindString(3, c.groupID)
        bindLong(4, c.recvMsgOpt.toLong())
        bindLong(5, c.groupAtType.toLong())
        bindLong(6, if (c.isPinned) 1 else 0)
        bindLong(7, c.burnDuration.toLong())
        bindLong(8, if (c.isPrivateChat) 1 else 0)
        bindString(9, c.attachedInfo)
        bindString(10, c.ex)
        bindLong(11, c.msgDestructTime)
        bindLong(12, if (c.isMsgDestruct) 1 else 0)
    }
}
