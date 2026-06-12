package io.openim.core.group

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.openim.core.network.ApiClient
import io.openim.core.sync.VersionSyncState
import io.openim.core.sync.VersionSyncStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins GroupSync to internal/group/incremental_sync.go. */
class GroupSyncTest {

    private class FakeVersionStore : VersionSyncStore {
        val states = mutableMapOf<Pair<String, String>, VersionSyncState>()
        override suspend fun get(tableName: String, entityID: String) = states[tableName to entityID]
        override suspend fun set(state: VersionSyncState) {
            states[state.tableName to state.entityID] = state
        }
    }

    private class FakeGroupStore : GroupStore {
        val groups = mutableMapOf<String, LocalGroup>()
        val members = mutableMapOf<Pair<String, String>, LocalGroupMember>()
        override suspend fun joinedGroups() = groups.values.toList()
        override suspend fun insertGroup(group: LocalGroup) { groups[group.groupID] = group }
        override suspend fun updateGroup(group: LocalGroup) { groups[group.groupID] = group }
        override suspend fun deleteGroup(groupID: String) { groups.remove(groupID) }
        override suspend fun membersOf(groupID: String) =
            members.filterKeys { it.first == groupID }.values.toList()
        override suspend fun insertMember(member: LocalGroupMember) {
            members[member.groupID to member.userID] = member
        }
        override suspend fun updateMember(member: LocalGroupMember) {
            members[member.groupID to member.userID] = member
        }
        override suspend fun deleteMember(groupID: String, userID: String) {
            members.remove(groupID to userID)
        }
    }

    @Test
    fun joinedGroupsAndMembersFullFlow() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            requests += request.url.encodedPath to body
            val content = when (request.url.encodedPath) {
                "/group/get_incremental_join_groups" ->
                    """{"errCode":0,"data":{"version":3,"versionID":"gv3","full":false,
                        "insert":[{"groupID":"g1","groupName":"Team","ownerUserID":"boss","memberCount":2}]}}"""
                "/group/get_incremental_group_members_batch" ->
                    """{"errCode":0,"data":{"respList":{"g1":{
                        "version":5,"versionID":"mv5","full":false,
                        "insert":[{"groupID":"g1","userID":"u1","nickname":"Alice","faceURL":"a.png","roleLevel":60},
                                  {"groupID":"g1","userID":"u2","nickname":"Bob"}],
                        "group":{"groupID":"g1","groupName":"Team Renamed","ownerUserID":"boss","memberCount":2}
                    }}}}"""
                else -> error("unexpected route ${request.url.encodedPath}")
            }
            respond(content, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val versionStore = FakeVersionStore()
        val store = FakeGroupStore()
        val sync = GroupSync(
            "me",
            ApiClient(HttpClient(engine), "http://api.test", { "tok" }, { "op" }),
            versionStore,
            store,
        )
        sync.syncJoinedGroupsAndMembers()

        // joined group inserted, then renamed by the piggybacked group info
        assertEquals("Team Renamed", store.groups["g1"]!!.name)
        // members applied with conversion (faceURL -> userGroupFaceURL)
        assertEquals(setOf("u1", "u2"), store.members.keys.map { it.second }.toSet())
        assertEquals("a.png", store.members["g1" to "u1"]!!.userGroupFaceURL)
        assertEquals(60, store.members["g1" to "u1"]!!.roleLevel)
        // both version cursors persisted under their Go table names
        assertEquals("gv3", versionStore.get("local_groups", "me")!!.versionID)
        assertEquals("mv5", versionStore.get("local_group_entities_version", "g1")!!.versionID)
        // member batch request carried the (empty) cursor for g1
        val batchBody = requests.first { it.first.endsWith("members_batch") }.second
        assertTrue(""""groupID":"g1"""" in batchBody && """"userID":"me"""" in batchBody)
    }

    @Test
    fun memberBatchSplitsOverMaxSyncPullNumber() = runTest {
        var batchCalls = 0
        val batchSizes = mutableListOf<Int>()
        val engine = MockEngine { request ->
            batchCalls++
            val body = request.body.toByteArray().decodeToString()
            batchSizes += Regex(""""groupID"""").findAll(body).count()
            respond(
                """{"errCode":0,"data":{"respList":{}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sync = GroupSync(
            "me",
            ApiClient(HttpClient(engine), "http://api.test", { "tok" }, { "op" }),
            FakeVersionStore(),
            FakeGroupStore(),
        )
        sync.syncMembers((1..650).map { "g$it" })
        assertEquals(2, batchCalls)
        assertEquals(listOf(500, 150), batchSizes)
    }

    @Test
    fun memberCursorIsSentOnSubsequentSyncs() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(
                """{"errCode":0,"data":{"respList":{}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val versionStore = FakeVersionStore()
        versionStore.set(VersionSyncState("local_group_entities_version", "g1", "mv9", 9))
        val sync = GroupSync(
            "me",
            ApiClient(HttpClient(engine), "http://api.test", { "tok" }, { "op" }),
            versionStore,
            FakeGroupStore(),
        )
        sync.syncMembers(listOf("g1"))
        assertTrue(""""versionID":"mv9"""" in bodies.single() && """"version":9""" in bodies.single())
    }
}
