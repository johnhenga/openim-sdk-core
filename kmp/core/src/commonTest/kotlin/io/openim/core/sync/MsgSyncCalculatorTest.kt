package io.openim.core.sync

import io.openim.core.sync.MsgSyncCalculator.SeqRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the port to the behavior of internal/interaction/msg_sync.go. */
class MsgSyncCalculatorTest {

    @Test
    fun normalSync() {
        val result = MsgSyncCalculator.computeNeedSync(
            syncedMaxSeqs = mapOf("si_a_b" to 10L, "sg_g1" to 5L, "si_c_d" to 7L),
            serverMaxSeqs = mapOf(
                "si_a_b" to 15L, // behind: pull 11..15
                "sg_g1" to 5L,   // up to date: nothing
                "si_c_d" to 3L,  // server lower (e.g. cleared): nothing
                "si_new" to 4L,  // unknown: pull 0..4
                "si_zero" to 0L, // unknown with seq 0: nothing
            ),
            reinstalled = false,
        )
        assertEquals(
            mapOf("si_a_b" to SeqRange(11, 15), "si_new" to SeqRange(0, 4)),
            result.ranges,
        )
        assertTrue(result.notificationSeqsToRecord.isEmpty())
    }

    @Test
    fun reinstallSkipsNotificationsAndRecordsTheirSeqs() {
        val result = MsgSyncCalculator.computeNeedSync(
            syncedMaxSeqs = emptyMap(),
            serverMaxSeqs = mapOf(
                "n_alice" to 42L,  // notification: recorded, not pulled
                "n_empty" to 0L,   // notification with seq 0: ignored entirely
                "si_a_b" to 9L,    // message conversation: pulled from 0
                "si_zero" to 0L,   // reinstall path has no zero-check (Go parity)
            ),
            reinstalled = true,
        )
        assertEquals(
            mapOf("si_a_b" to SeqRange(0, 9), "si_zero" to SeqRange(0, 0)),
            result.ranges,
        )
        assertEquals(mapOf("n_alice" to 42L), result.notificationSeqsToRecord)
    }

    @Test
    fun seqsNeedSyncEnumeratesGap() {
        assertEquals(listOf(6L, 7L, 8L), MsgSyncCalculator.seqsNeedSync(5, 8))
        assertEquals(emptyList(), MsgSyncCalculator.seqsNeedSync(8, 8))
        assertEquals(emptyList(), MsgSyncCalculator.seqsNeedSync(9, 8))
    }

    @Test
    fun splitSeqsMatchesGoBatching() {
        val seqs = (1L..250L).toList()
        val batches = MsgSyncCalculator.splitSeqs(MsgSyncCalculator.SPLIT_PULL_MSG_NUM, seqs)
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals(1L, batches[0].first())
        assertEquals(250L, batches[2].last())
        // At or under the split size: single batch (Go returns it unsplit).
        assertEquals(listOf(seqs.take(100)), MsgSyncCalculator.splitSeqs(100, seqs.take(100)))
        assertEquals(listOf(emptyList()), MsgSyncCalculator.splitSeqs(100, emptyList()))
    }

    @Test
    fun chunkConversationsGroupsPulls() {
        val ranges = (1..205).associate { "si_$it" to SeqRange(0, 1) }
        val chunks = MsgSyncCalculator.chunkConversations(ranges, MsgSyncCalculator.SYNC_MAX_CONVERSATIONS)
        assertEquals(listOf(100, 100, 5), chunks.map { it.size })
        assertEquals(205, chunks.sumOf { it.size })
        assertEquals(emptyList(), MsgSyncCalculator.chunkConversations(emptyMap(), 100))
    }

    @Test
    fun maxSeqRecorder() {
        val r = MaxSeqRecorder()
        assertEquals(0L, r.get("c1"))
        assertTrue(r.isNewMsg("c1", 1))
        r.set("c1", 5)
        assertEquals(5L, r.get("c1"))
        assertTrue(!r.isNewMsg("c1", 5))
        assertTrue(r.isNewMsg("c1", 6))
        r.incr("c1", 2)
        assertEquals(7L, r.get("c1"))
        r.incr("c2", 3)
        assertEquals(3L, r.get("c2"))
    }
}
