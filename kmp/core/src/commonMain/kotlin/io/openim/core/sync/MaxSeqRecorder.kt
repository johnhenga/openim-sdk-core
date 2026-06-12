package io.openim.core.sync

/**
 * Tracks the maximum received seq per conversation. Port of
 * internal/conversation_msg/max_seq_recorder.go.
 *
 * The Go version guards the map with an RWMutex; in the Kotlin core all
 * sync-engine state is confined to the engine's single-threaded dispatcher
 * (design doc §5), so no locking is needed here. Do not touch this class
 * from other dispatchers.
 */
class MaxSeqRecorder {
    private val seqs = mutableMapOf<String, Long>()

    fun get(conversationID: String): Long = seqs[conversationID] ?: 0L

    fun set(conversationID: String, seq: Long) {
        seqs[conversationID] = seq
    }

    fun incr(conversationID: String, num: Long) {
        seqs[conversationID] = (seqs[conversationID] ?: 0L) + num
    }

    fun isNewMsg(conversationID: String, seq: Long): Boolean = seq > get(conversationID)
}
