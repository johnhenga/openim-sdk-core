package io.openim.core.relation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.openim.core.network.ApiClient
import io.openim.core.network.ApiException
import io.openim.core.sync.VersionSyncState
import io.openim.core.sync.VersionSyncStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins ApiClient to pkg/network/http_client.go conventions and FriendSync to
 * internal/relation/incremental_sync.go, using a mock HTTP server.
 */
class FriendSyncTest {

    private class FakeVersionStore : VersionSyncStore {
        val states = mutableMapOf<Pair<String, String>, VersionSyncState>()
        override suspend fun get(tableName: String, entityID: String) = states[tableName to entityID]
        override suspend fun set(state: VersionSyncState) {
            states[state.tableName to state.entityID] = state
        }
    }

    private class FakeFriendStore(initial: List<LocalFriend> = emptyList()) : FriendStore {
        val friends = initial.associateBy { it.friendUserID }.toMutableMap()
        override suspend fun getAll(ownerUserID: String) = friends.values.toList()
        override suspend fun insert(friend: LocalFriend) { friends[friend.friendUserID] = friend }
        override suspend fun update(friend: LocalFriend) { friends[friend.friendUserID] = friend }
        override suspend fun delete(ownerUserID: String, friendUserID: String) { friends.remove(friendUserID) }
        override suspend fun deleteAll(ownerUserID: String) = friends.clear()
        override suspend fun batchInsert(friends: List<LocalFriend>) =
            friends.forEach { this.friends[it.friendUserID] = it }
    }

    private fun apiClient(engine: MockEngine) = ApiClient(
        httpClient = HttpClient(engine),
        baseUrl = "http://api.test",
        token = { "tok-1" },
        operationID = { "op-1" },
    )

    @Test
    fun incrementalSyncAppliesServerChanges() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            requests += request.url.encodedPath to request.body.toByteArray().decodeToString()
            assertEquals("tok-1", request.headers["token"])
            assertEquals("op-1", request.headers["operationID"])
            respond(
                content = """
                    {"errCode":0,"errMsg":"","errDlt":"","data":{
                      "version":7,"versionID":"v7","full":false,
                      "delete":["gone"],
                      "insert":[{"ownerUserID":"me","remark":"bff","createTime":123,
                                 "friendUser":{"userID":"new","nickname":"Nick","faceURL":"f.png"},
                                 "addSource":2,"operatorUserID":"admin","ex":"x","isPinned":true}],
                      "update":[{"ownerUserID":"me","remark":"renamed",
                                 "friendUser":{"userID":"old","nickname":"Old"}}],
                      "sortVersion":0}}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val versionStore = FakeVersionStore()
        versionStore.set(VersionSyncState("local_friends", "me", "v6", 6, idList = listOf("old", "gone")))
        val friendStore = FakeFriendStore(
            listOf(
                LocalFriend("me", "old", remark = "before"),
                LocalFriend("me", "gone"),
            )
        )

        FriendSync("me", apiClient(engine), versionStore, friendStore).incrementalSync()

        // request carried the stored version cursor
        val (path, body) = requests.single()
        assertEquals("/friend/get_incremental_friends", path)
        assertTrue(""""versionID":"v6"""" in body && """"version":6""" in body && """"userID":"me"""" in body)

        // server changes applied: insert new (with conversion), update old, delete gone
        assertEquals(setOf("old", "new"), friendStore.friends.keys)
        val new = friendStore.friends["new"]!!
        assertEquals("Nick", new.nickname)
        assertEquals("f.png", new.faceURL)
        assertEquals("bff", new.remark)
        assertTrue(new.isPinned)
        assertEquals("renamed", friendStore.friends["old"]!!.remark)

        // version row advanced, idList maintained
        val state = versionStore.get("local_friends", "me")!!
        assertEquals("v7", state.versionID)
        assertEquals(7L, state.version)
        assertEquals(listOf("old", "new"), state.idList)
    }

    @Test
    fun sortVersionRefreshesIdListFromFullIDsEndpoint() = runTest {
        val engine = MockEngine { request ->
            val content = if (request.url.encodedPath.endsWith("get_incremental_friends")) {
                """{"errCode":0,"data":{"version":2,"versionID":"v2","full":false,
                    "insert":[{"ownerUserID":"me","friendUser":{"userID":"b"}}],"sortVersion":3}}"""
            } else {
                assertEquals("/friend/get_full_friend_user_ids", request.url.encodedPath)
                """{"errCode":0,"data":{"userIDs":["b","a"]}}"""
            }
            respond(content, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val versionStore = FakeVersionStore()
        FriendSync("me", apiClient(engine), versionStore, FakeFriendStore()).incrementalSync()
        assertEquals(listOf("b", "a"), versionStore.get("local_friends", "me")!!.idList)
    }

    @Test
    fun apiErrorEnvelopeBecomesApiException() = runTest {
        val engine = MockEngine {
            respond(
                """{"errCode":1004,"errMsg":"token expired","errDlt":"detail"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val e = assertFailsWith<ApiException> {
            FriendSync("me", apiClient(engine), FakeVersionStore(), FakeFriendStore()).incrementalSync()
        }
        assertEquals(1004, e.errCode)
        assertEquals("token expired", e.errMsg)
    }
}
