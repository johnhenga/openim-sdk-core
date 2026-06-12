package io.openim.core.sync

import io.openim.core.db.ChatLog

/**
 * Pure gap-detection and merge helpers behind history-view gap filling:
 * ports of the standalone functions in
 * internal/conversation_msg/message_check.go. The (to be ported)
 * conversation module uses these when loading message pages to decide which
 * seqs must be fetched from the server and how to merge the result with the
 * local page.
 */
object MessageGapCheck {

    /** Go: pkg/constant PullMsgNumForReadDiffusion. */
    const val PULL_MSG_NUM_FOR_READ_DIFFUSION = 50

    data class HaveSeqs(val max: Long, val min: Long, val seqList: List<Long>)

    /**
     * Go: getMaxAndMinHaveSeqList — min/max and list of the seqs present in
     * a local page, ignoring seq-0 (locally inserted / online-only) rows.
     */
    fun maxAndMinHaveSeqList(messages: List<ChatLog>): HaveSeqs {
        var max = 0L
        var min = 0L
        val seqList = mutableListOf<Long>()
        for (message in messages) {
            val seq = message.seq
            if (seq != 0L) {
                seqList += seq
                if (min == 0L && max == 0L) {
                    min = seq
                    max = seq
                }
                if (seq < min) min = seq
                if (seq > max) max = seq
            }
        }
        return HaveSeqs(max, min, seqList)
    }

    /**
     * Go: getLostSeqListWithLimitLength — seqs missing from
     * [minSeq, maxSeq], capped at [PULL_MSG_NUM_FOR_READ_DIFFUSION]:
     * keep the first N when reading forward (isReverse), the last N when
     * reading history backward.
     */
    fun lostSeqListWithLimitLength(
        minSeq: Long,
        maxSeq: Long,
        haveSeqList: List<Long>,
        isReverse: Boolean,
    ): List<Long> {
        val have = haveSeqList.toHashSet()
        val lost = ArrayList<Long>()
        var i = minSeq
        while (i <= maxSeq) {
            if (i !in have) lost += i
            i++
        }
        if (lost.size > PULL_MSG_NUM_FOR_READ_DIFFUSION) {
            return if (isReverse) {
                lost.subList(0, PULL_MSG_NUM_FOR_READ_DIFFUSION).toList()
            } else {
                lost.subList(lost.size - PULL_MSG_NUM_FOR_READ_DIFFUSION, lost.size).toList()
            }
        }
        return lost
    }

    /**
     * Go: mergeSortedArrays — merges a local page with fetched messages,
     * ordered by sendTime then seq, truncated to [n] entries.
     */
    fun mergeSortedArrays(
        arr1: List<ChatLog>,
        arr2: List<ChatLog>,
        n: Int,
        isDescending: Boolean,
    ): List<ChatLog> {
        val result = ArrayList<ChatLog>(minOf(arr1.size + arr2.size, n))
        var i = 0
        var j = 0

        fun before(a: ChatLog, b: ChatLog): Boolean {
            val aTime = a.sendTime ?: 0L
            val bTime = b.sendTime ?: 0L
            return if (isDescending) {
                aTime > bTime || (aTime == bTime && a.seq > b.seq)
            } else {
                aTime < bTime || (aTime == bTime && a.seq < b.seq)
            }
        }

        while (i < arr1.size && j < arr2.size && result.size < n) {
            if (before(arr1[i], arr2[j])) result += arr1[i++] else result += arr2[j++]
        }
        while (i < arr1.size && result.size < n) result += arr1[i++]
        while (j < arr2.size && result.size < n) result += arr2[j++]
        return result
    }
}
