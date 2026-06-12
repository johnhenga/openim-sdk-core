package io.openim.core.sync

import io.openim.core.db.ChatLog
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins MessageGapCheck to internal/conversation_msg/message_check.go. */
class MessageGapCheckTest {

    private fun msg(id: String, seq: Long, sendTime: Long = seq) =
        ChatLog(clientMsgID = id, seq = seq, sendTime = sendTime)

    @Test
    fun maxAndMinIgnoreSeqZero() {
        val have = MessageGapCheck.maxAndMinHaveSeqList(
            listOf(msg("a", 7), msg("b", 0), msg("c", 3), msg("d", 9))
        )
        assertEquals(9L, have.max)
        assertEquals(3L, have.min)
        assertEquals(listOf(7L, 3L, 9L), have.seqList)
        assertEquals(MessageGapCheck.HaveSeqs(0, 0, emptyList()),
            MessageGapCheck.maxAndMinHaveSeqList(listOf(msg("x", 0))))
    }

    @Test
    fun lostSeqsAreTheGaps() {
        assertEquals(
            listOf(4L, 6L),
            MessageGapCheck.lostSeqListWithLimitLength(3, 7, listOf(3, 5, 7), isReverse = false),
        )
        assertEquals(
            emptyList(),
            MessageGapCheck.lostSeqListWithLimitLength(3, 5, listOf(3, 4, 5), isReverse = false),
        )
    }

    @Test
    fun lostSeqsCappedKeepingNewestWhenReadingHistory() {
        // 1..100 all missing: backward (isReverse=false) keeps the LAST 50,
        // forward (isReverse=true) keeps the FIRST 50 — Go parity.
        val backward = MessageGapCheck.lostSeqListWithLimitLength(1, 100, emptyList(), isReverse = false)
        assertEquals(50, backward.size)
        assertEquals(51L, backward.first())
        assertEquals(100L, backward.last())

        val forward = MessageGapCheck.lostSeqListWithLimitLength(1, 100, emptyList(), isReverse = true)
        assertEquals(1L, forward.first())
        assertEquals(50L, forward.last())
    }

    @Test
    fun mergeOrdersBySendTimeThenSeqAndTruncates() {
        val local = listOf(msg("l1", 10, sendTime = 100), msg("l2", 8, sendTime = 80))
        val pulled = listOf(msg("p1", 9, sendTime = 100), msg("p2", 7, sendTime = 70))

        val desc = MessageGapCheck.mergeSortedArrays(local, pulled, n = 10, isDescending = true)
        // same sendTime 100: higher seq first in descending order
        assertEquals(listOf("l1", "p1", "l2", "p2"), desc.map { it.clientMsgID })

        val asc = MessageGapCheck.mergeSortedArrays(
            local.reversed(), pulled.reversed(), n = 10, isDescending = false,
        )
        assertEquals(listOf("p2", "l2", "p1", "l1"), asc.map { it.clientMsgID })

        val truncated = MessageGapCheck.mergeSortedArrays(local, pulled, n = 3, isDescending = true)
        assertEquals(3, truncated.size)
    }
}
