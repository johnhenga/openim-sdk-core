package io.openim.core.sync

/**
 * Pure seq-range arithmetic of the message sync engine: a port of the
 * decision logic in internal/interaction/msg_sync.go, separated from I/O so
 * it can be tested exhaustively. The (to be ported) MsgSyncer orchestrator
 * calls these functions and performs the pulls/DB writes they prescribe.
 */
object MsgSyncCalculator {

    /** Mirror of the msg_sync.go constants. */
    const val CONNECT_PULL_NUMS = 1L
    const val DEFAULT_PULL_NUMS = 10L
    const val SPLIT_PULL_MSG_NUM = 100
    const val PULL_MSG_CONCURRENCY_LIMIT = 10
    const val MAX_CONVERSATIONS = 500
    const val SYNC_MAX_CONVERSATIONS = 100

    /** Go: interaction.IsNotification. */
    fun isNotificationConversation(conversationID: String): Boolean =
        conversationID.startsWith("n_")

    /** Inclusive seq range still missing locally: [begin, end]. */
    data class SeqRange(val begin: Long, val end: Long)

    /**
     * @property ranges conversations that need pulling, with their ranges.
     * @property notificationSeqsToRecord on reinstall, notification
     *   conversations are not pulled; their server max seqs must instead be
     *   persisted (Go: BatchInsertNotificationSeq) and recorded as synced.
     */
    data class NeedSync(
        val ranges: Map<String, SeqRange>,
        val notificationSeqsToRecord: Map<String, Long> = emptyMap(),
    )

    /**
     * Computes what needs syncing given the locally synced max seqs and the
     * server's max seqs. Port of getNeedSyncConversations /
     * compareSeqsAndBatchSync range computation, including the reinstall
     * special case (skip notification history; record its seqs as synced).
     */
    fun computeNeedSync(
        syncedMaxSeqs: Map<String, Long>,
        serverMaxSeqs: Map<String, Long>,
        reinstalled: Boolean,
    ): NeedSync {
        val ranges = mutableMapOf<String, SeqRange>()
        if (!reinstalled) {
            for ((conversationID, maxSeq) in serverMaxSeqs) {
                val synced = syncedMaxSeqs[conversationID]
                if (synced != null) {
                    if (maxSeq > synced) ranges[conversationID] = SeqRange(synced + 1, maxSeq)
                } else if (maxSeq != 0L) { // seq 0 means nothing to sync
                    ranges[conversationID] = SeqRange(0, maxSeq)
                }
            }
            return NeedSync(ranges)
        }

        val notificationSeqs = mutableMapOf<String, Long>()
        for ((conversationID, maxSeq) in serverMaxSeqs) {
            if (isNotificationConversation(conversationID)) {
                if (maxSeq != 0L) notificationSeqs[conversationID] = maxSeq
                continue
            }
            val synced = syncedMaxSeqs[conversationID]
            if (synced != null) {
                if (maxSeq > synced) ranges[conversationID] = SeqRange(synced + 1, maxSeq)
            } else {
                // Unlike the normal path, Go does not zero-check here.
                ranges[conversationID] = SeqRange(0, maxSeq)
            }
        }
        return NeedSync(ranges, notificationSeqs)
    }

    /** Go: getSeqsNeedSync — every missing seq in (syncedMaxSeq, maxSeq]. */
    fun seqsNeedSync(syncedMaxSeq: Long, maxSeq: Long): List<Long> =
        if (maxSeq <= syncedMaxSeq) emptyList()
        else ((syncedMaxSeq + 1)..maxSeq).toList()

    /** Go: splitSeqs — batches of [split], last batch may be smaller. */
    fun splitSeqs(split: Int, seqsNeedSync: List<Long>): List<List<Long>> {
        if (seqsNeedSync.size <= split) return listOf(seqsNeedSync)
        return seqsNeedSync.chunked(split)
    }

    /**
     * Splits a need-sync map into groups of at most [groupSize]
     * conversations, mirroring the tempSeqMap batching in
     * syncAndTriggerMsgs (SYNC_MAX_CONVERSATIONS per pull when the total
     * exceeds MAX_CONVERSATIONS).
     */
    fun chunkConversations(
        ranges: Map<String, SeqRange>,
        groupSize: Int,
    ): List<Map<String, SeqRange>> {
        if (ranges.isEmpty()) return emptyList()
        return ranges.entries.chunked(groupSize) { chunk ->
            chunk.associate { it.key to it.value }
        }
    }
}
