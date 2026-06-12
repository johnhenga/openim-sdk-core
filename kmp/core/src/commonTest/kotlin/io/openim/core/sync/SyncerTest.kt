package io.openim.core.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the [Syncer] port against the semantics of
 * Go pkg/syncer/syncer.go Sync (insert / update / delete / unchanged,
 * skipDeletion, skipNotice).
 */
class SyncerTest {

    private data class Friend(val userID: String, val remark: String)

    private class Recorder {
        val inserted = mutableListOf<Friend>()
        val updated = mutableListOf<Pair<Friend, Friend>>()
        val deleted = mutableListOf<Friend>()
        val notices = mutableListOf<SyncState>()

        val syncer = Syncer<Friend, String>(
            uuid = { it.userID },
            insert = { inserted += it },
            update = { server, local -> updated += server to local },
            delete = { deleted += it },
        )

        suspend fun sync(server: List<Friend>, local: List<Friend>, args: Syncer.Args = Syncer.Args()) =
            syncer.sync(server, local, args) { state, _, _ -> notices += state }
    }

    @Test
    fun insertsServerOnlyItems() = runTest {
        val r = Recorder()
        r.sync(server = listOf(Friend("u1", "a")), local = emptyList())
        assertEquals(listOf(Friend("u1", "a")), r.inserted)
        assertEquals(listOf(SyncState.Insert), r.notices)
    }

    @Test
    fun updatesChangedItemsAndReportsUnchanged() = runTest {
        val r = Recorder()
        r.sync(
            server = listOf(Friend("u1", "new"), Friend("u2", "same")),
            local = listOf(Friend("u1", "old"), Friend("u2", "same")),
        )
        assertEquals(listOf(Friend("u1", "new") to Friend("u1", "old")), r.updated)
        assertEquals(listOf(SyncState.Update, SyncState.Unchanged), r.notices)
    }

    @Test
    fun deletesLocalOnlyItems() = runTest {
        val r = Recorder()
        r.sync(server = emptyList(), local = listOf(Friend("u1", "a")))
        assertEquals(listOf(Friend("u1", "a")), r.deleted)
        assertEquals(listOf(SyncState.Delete), r.notices)
    }

    @Test
    fun skipDeletionKeepsLocalOnlyItems() = runTest {
        val r = Recorder()
        r.sync(
            server = emptyList(),
            local = listOf(Friend("u1", "a")),
            args = Syncer.Args(skipDeletion = true),
        )
        assertEquals(emptyList(), r.deleted)
        assertEquals(emptyList(), r.notices)
    }

    @Test
    fun skipNoticeSuppressesCallbacksButAppliesChanges() = runTest {
        val r = Recorder()
        r.sync(
            server = listOf(Friend("u1", "a")),
            local = listOf(Friend("u2", "b")),
            args = Syncer.Args(skipNotice = true),
        )
        assertEquals(listOf(Friend("u1", "a")), r.inserted)
        assertEquals(listOf(Friend("u2", "b")), r.deleted)
        assertEquals(emptyList(), r.notices)
    }

    @Test
    fun bothEmptyIsANoOp() = runTest {
        val r = Recorder()
        r.sync(server = emptyList(), local = emptyList())
        assertEquals(emptyList(), r.notices)
    }
}
