package io.openim.core.conversation

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

/** Pins ConversationSync to internal/conversation_msg/incremental_sync.go. */
class ConversationSyncTest {

    private class FakeVersionStore : VersionSyncStore {
        val states = mutableMapOf<Pair<String, String>, VersionSyncState>()
        override suspend fun get(tableName: String, entityID: String) = states[tableName to entityID]
        override suspend fun set(state: VersionSyncState) {
            states[state.tableName to state.entityID] = state
        }
    }

    private class FakeConversationStore(initial: List<LocalConversation> = emptyList()) : ConversationStore {
        val rows = initial.associateBy { it.conversationID }.toMutableMap()
        var deletes = 0
        override suspend fun getAll() = rows.values.toList()
        override suspend fun insert(conversation: LocalConversation) {
            rows[conversation.conversationID] = conversation
        }
        override suspend fun update(conversation: LocalConversation) {
            rows[conversation.conversationID] = conversation
        }
        override suspend fun getByIDs(conversationIDs: List<String>) =
            conversationIDs.mapNotNull { rows[it] }
        override suspend fun batchUpdateFull(conversations: List<LocalConversation>) =
            conversations.forEach { rows[it.conversationID] = it }
        override suspend fun batchInsertFull(conversations: List<LocalConversation>) =
            conversations.forEach { rows[it.conversationID] = it }
    }

    @Test
    fun incrementalSyncNeverDeletesConversationRows() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += request.body.toByteArray().decodeToString()
            respond(
                """{"errCode":0,"data":{"version":4,"versionID":"cv4","full":false,
                    "delete":["si_gone"],
                    "insert":[{"conversationID":"sg_g1","conversationType":3,"groupID":"g1",
                               "isPinned":true,"burnDuration":60}],
                    "update":[{"conversationID":"si_a","conversationType":1,"userID":"a","recvMsgOpt":2}]}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val versionStore = FakeVersionStore()
        versionStore.set(
            VersionSyncState("local_conversations", "me", "cv3", 3, idList = listOf("si_a", "si_gone"))
        )
        val store = FakeConversationStore(
            listOf(
                LocalConversation("si_a", conversationType = 1, userID = "a"),
                LocalConversation("si_gone", conversationType = 1, userID = "g"),
            )
        )

        ConversationSync(
            "me",
            ApiClient(HttpClient(engine), "http://api.test", { "tok" }, { "op" }),
            versionStore,
            store,
        ).incrementalSync()

        // cursor sent
        assertTrue(""""versionID":"cv3"""" in bodies.single() && """"version":3""" in bodies.single())
        // insert + update applied with conversion
        assertEquals(2, store.rows["si_a"]!!.recvMsgOpt)
        val group = store.rows["sg_g1"]!!
        assertTrue(group.isPinned)
        assertEquals(60, group.burnDuration)
        assertEquals("g1", group.groupID)
        // skipDeletion: the deleted conversation row survives locally...
        assertTrue("si_gone" in store.rows.keys)
        // ...but is trimmed from the version id_list, and the cursor advances
        val state = versionStore.get("local_conversations", "me")!!
        assertEquals("cv4", state.versionID)
        assertEquals(listOf("si_a", "sg_g1"), state.idList)
    }
}
