package io.openim.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins VersionSynchronizer to pkg/syncer/version_synchronizer.go
 * IncrementalSync, instantiated for friends — the exemplar for the
 * relation/group/conversation modules.
 */
class VersionSynchronizerTest {

    private data class Friend(val userID: String, val remark: String)

    private class FakeVersionStore : VersionSyncStore {
        val states = mutableMapOf<Pair<String, String>, VersionSyncState>()
        override suspend fun get(tableName: String, entityID: String) =
            states[tableName to entityID]
        override suspend fun set(state: VersionSyncState) {
            states[state.tableName to state.entityID] = state
        }
    }

    private class Harness(localFriends: List<Friend>) {
        val versionStore = FakeVersionStore()
        val db = localFriends.associateBy { it.userID }.toMutableMap()
        var fullSynced = false
        val notices = mutableListOf<Pair<SyncState, String>>()
        var response: VersionSynchronizer.Response<Friend> =
            VersionSynchronizer.Response("v2", 2, full = false)
        var requestedWith: VersionSyncState? = null

        val sync = VersionSynchronizer(
            tableName = "local_friends",
            entityID = "me",
            versionStore = versionStore,
            key = { it.userID },
            local = { db.values.toList() },
            server = { stored -> requestedWith = stored; response },
            syncer = Syncer(
                uuid = { it.userID },
                insert = { db[it.userID] = it },
                update = { server, _ -> db[server.userID] = server },
                delete = { db.remove(it.userID) },
            ),
            fullSyncer = { fullSynced = true },
            fullIDs = { db.keys.sorted() },
            notice = { state, server, local ->
                notices += state to (server ?: local)!!.userID
            },
        )
    }

    @Test
    fun appliesInsertsUpdatesAndDeletes() = runTest {
        val h = Harness(listOf(Friend("a", "old"), Friend("b", "keep-me")))
        h.versionStore.set(VersionSyncState("local_friends", "me", "v1", 1, idList = listOf("a", "b")))
        h.response = VersionSynchronizer.Response(
            versionID = "v1", version = 2, full = false,
            deleteKeys = listOf("b"),
            updates = listOf(Friend("a", "new")),
            inserts = listOf(Friend("c", "fresh")),
        )
        h.sync.incrementalSync()

        assertEquals("v1", h.requestedWith?.versionID)
        assertEquals(mapOf("a" to Friend("a", "new"), "c" to Friend("c", "fresh")), h.db)
        // notices: update a, insert c, delete b (order: server items then deletions)
        assertTrue(SyncState.Update to "a" in h.notices)
        assertTrue(SyncState.Insert to "c" in h.notices)
        assertTrue(SyncState.Delete to "b" in h.notices)
        val state = h.versionStore.get("local_friends", "me")!!
        assertEquals(2L, state.version)
        assertEquals(listOf("a", "c"), state.idList)
        assertTrue(!h.fullSynced)
    }

    @Test
    fun noChangesLeavesVersionRowUntouched() = runTest {
        val h = Harness(listOf(Friend("a", "x")))
        h.response = VersionSynchronizer.Response("v9", 9, full = false)
        h.sync.incrementalSync()
        assertNull(h.versionStore.get("local_friends", "me"))
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun serverFullFlagTriggersFullSyncAndRebuildsIdList() = runTest {
        val h = Harness(listOf(Friend("z", "x"), Friend("a", "y")))
        h.response = VersionSynchronizer.Response("v5", 5, full = true)
        h.sync.incrementalSync()
        assertTrue(h.fullSynced)
        val state = h.versionStore.get("local_friends", "me")!!
        assertEquals("v5", state.versionID)
        assertEquals(listOf("a", "z"), state.idList) // rebuilt from fullIDs
    }

    @Test
    fun idOrderChangedRefreshesIdList() = runTest {
        val h = Harness(listOf(Friend("a", "x")))
        h.versionStore.set(VersionSyncState("local_friends", "me", "v1", 1, idList = listOf("a")))
        h.response = VersionSynchronizer.Response(
            "v1", 2, full = false,
            inserts = listOf(Friend("b", "new")),
            idOrderChanged = true,
        )
        h.sync.incrementalSync()
        assertEquals(listOf("a", "b"), h.versionStore.get("local_friends", "me")!!.idList)
    }

    @Test
    fun firstSyncWithNoStoredVersionInsertsEverything() = runTest {
        val h = Harness(emptyList())
        h.response = VersionSynchronizer.Response(
            "v1", 1, full = false,
            inserts = listOf(Friend("a", "1"), Friend("b", "2")),
        )
        h.sync.incrementalSync()
        assertNull(h.requestedWith)
        assertEquals(2, h.db.size)
        assertEquals(listOf("a", "b"), h.versionStore.get("local_friends", "me")!!.idList)
    }
}
