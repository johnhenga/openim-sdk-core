package io.openim.core.sync

import kotlinx.coroutines.test.runTest
import openim.sdkws.MsgData
import openim.sdkws.PullMessageBySeqsReq
import openim.sdkws.PullMessageBySeqsResp
import openim.sdkws.PullMsgs
import openim.sdkws.PushMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral tests pinning MsgSyncer to internal/interaction/msg_sync.go:
 * on-connect catch-up, push handling (contiguous / gap / online-only),
 * reinstall, sync debounce, max-seq retry, and pull batching.
 */
class MsgSyncerTest {

    private class FakeTransport : MsgSyncTransport {
        var maxSeqs: Map<String, Long> = emptyMap()
        var maxSeqsFailuresRemaining = 0
        var maxSeqsCalls = 0
        val pullRequests = mutableListOf<PullMessageBySeqsReq>()
        var lastMessages: Map<String, MsgData> = emptyMap()

        override suspend fun getMaxSeqs(userID: String): Map<String, Long> {
            maxSeqsCalls++
            if (maxSeqsFailuresRemaining > 0) {
                maxSeqsFailuresRemaining--
                error("simulated network failure")
            }
            return maxSeqs
        }

        override suspend fun pullMessageBySeqs(req: PullMessageBySeqsReq): PullMessageBySeqsResp {
            pullRequests += req
            // Respond with one message per requested conversation at its end seq.
            return PullMessageBySeqsResp(
                msgs = req.seqRanges.associate { range ->
                    range.conversationID to PullMsgs(Msgs = listOf(MsgData(seq = range.end)))
                },
            )
        }

        override suspend fun getLastMessages(userID: String, conversationIDs: List<String>) = lastMessages

        override suspend fun getConversationsMaxSeq(userID: String, conversationIDs: List<String>) =
            maxSeqs.filterKeys { it in conversationIDs }
    }

    private class FakeStore(
        var conversationIDs: List<String> = emptyList(),
        var syncedSeqs: Map<String, Long> = emptyMap(),
        var installed: Boolean = true,
    ) : MsgSyncStore {
        var recordedNotificationSeqs: Map<String, Long>? = null
        var markedInstalled = false

        override suspend fun allConversationIDs() = conversationIDs
        override suspend fun maxSyncedSeq(conversationID: String) = syncedSeqs[conversationID] ?: 0L
        override suspend fun notificationSeqs(): Map<String, Long> = emptyMap()
        override suspend fun insertNotificationSeqs(seqs: Map<String, Long>) {
            recordedNotificationSeqs = seqs
        }
        override suspend fun isInstalled() = installed
        override suspend fun markInstalled() {
            markedInstalled = true
        }
    }

    private class FakeListener : MsgSyncListener {
        val conversationTriggers = mutableListOf<Map<String, PullMsgs>>()
        val notificationTriggers = mutableListOf<Map<String, PullMsgs>>()
        val reinstallTriggers = mutableListOf<Pair<Map<String, PullMsgs>, Int>>()
        val flags = mutableListOf<SyncFlag>()

        override suspend fun onConversationMsgs(msgs: Map<String, PullMsgs>) {
            conversationTriggers += msgs
        }
        override suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>) {
            if (msgs.isNotEmpty()) notificationTriggers += msgs
        }
        override suspend fun onReinstallConversationMsgs(msgs: Map<String, PullMsgs>, totalConversations: Int) {
            reinstallTriggers += msgs to totalConversations
        }
        override suspend fun onSyncFlag(flag: SyncFlag) {
            flags += flag
        }
    }

    private class Harness(
        store: FakeStore = FakeStore(),
        var now: Long = 100_000L,
    ) {
        val transport = FakeTransport()
        val storeRef = store
        val listener = FakeListener()
        val syncer = MsgSyncer("u1", transport, store, listener, clock = { now })
    }

    @Test
    fun connectedPullsMissingRangesWithConnectPullNums() = runTest {
        val h = Harness()
        h.syncer.loadSeq()
        h.transport.maxSeqs = mapOf("si_a" to 5L)
        h.syncer.onConnected()

        assertEquals(listOf(SyncFlag.MsgSyncBegin, SyncFlag.MsgSyncEnd), h.listener.flags)
        assertEquals(1, h.transport.pullRequests.size)
        val range = h.transport.pullRequests.single().seqRanges.single()
        assertEquals("si_a", range.conversationID)
        assertEquals(0L, range.begin)
        assertEquals(5L, range.end)
        assertEquals(MsgSyncCalculator.CONNECT_PULL_NUMS, range.num)
        assertEquals(5L, h.syncer.syncedMaxSeqsView["si_a"])
        assertEquals(1, h.listener.conversationTriggers.size)
    }

    @Test
    fun contiguousPushTriggersWithoutPull() = runTest {
        val h = Harness(FakeStore(conversationIDs = listOf("si_a"), syncedSeqs = mapOf("si_a" to 5L)))
        h.syncer.loadSeq()
        h.syncer.onPushMsg(
            PushMessages(msgs = mapOf("si_a" to PullMsgs(Msgs = listOf(MsgData(seq = 6), MsgData(seq = 7)))))
        )
        assertTrue(h.transport.pullRequests.isEmpty(), "contiguous push must not pull")
        assertEquals(7L, h.syncer.syncedMaxSeqsView["si_a"])
        assertEquals(listOf(6L, 7L), h.listener.conversationTriggers.single()["si_a"]!!.Msgs.map { it.seq })
    }

    @Test
    fun gapInPushTriggersPullFromSyncedPlusOne() = runTest {
        val h = Harness(FakeStore(conversationIDs = listOf("si_a"), syncedSeqs = mapOf("si_a" to 5L)))
        h.syncer.loadSeq()
        h.syncer.onPushMsg(
            PushMessages(msgs = mapOf("si_a" to PullMsgs(Msgs = listOf(MsgData(seq = 8)))))
        )
        val range = h.transport.pullRequests.single().seqRanges.single()
        assertEquals(6L, range.begin)
        assertEquals(8L, range.end)
        assertEquals(MsgSyncCalculator.DEFAULT_PULL_NUMS, range.num)
        assertEquals(8L, h.syncer.syncedMaxSeqsView["si_a"])
    }

    @Test
    fun seqZeroPushIsTriggeredImmediately() = runTest {
        val h = Harness()
        h.syncer.loadSeq()
        h.syncer.onPushMsg(
            PushMessages(msgs = mapOf("si_a" to PullMsgs(Msgs = listOf(MsgData(seq = 0)))))
        )
        assertTrue(h.transport.pullRequests.isEmpty())
        assertEquals(1, h.listener.conversationTriggers.size)
        assertEquals(null, h.syncer.syncedMaxSeqsView["si_a"], "seq-0 must not advance synced seq")
    }

    @Test
    fun reinstallRecordsNotificationSeqsAndMarksInstalled() = runTest {
        val h = Harness(FakeStore(installed = false))
        h.syncer.loadSeq()
        h.transport.maxSeqs = mapOf("n_x" to 9L, "si_a" to 4L)
        h.syncer.onConnected()

        assertEquals(listOf(SyncFlag.AppDataSyncStart, SyncFlag.AppDataSyncFinish), h.listener.flags)
        assertEquals(mapOf("n_x" to 9L), h.storeRef.recordedNotificationSeqs)
        assertTrue(h.storeRef.markedInstalled)
        val range = h.transport.pullRequests.single().seqRanges.single()
        assertEquals("si_a", range.conversationID)
        assertEquals(1, h.listener.reinstallTriggers.size)
        assertEquals(1, h.listener.reinstallTriggers.single().second) // total conversations
        assertEquals(9L, h.syncer.syncedMaxSeqsView["n_x"], "notification seq recorded as synced")
    }

    @Test
    fun connectedEventsAreDebouncedForFiveSeconds() = runTest {
        val h = Harness()
        h.syncer.loadSeq()
        h.syncer.onConnected()
        h.now += 1_000
        h.syncer.onConnected() // within debounce: ignored
        assertEquals(1, h.transport.maxSeqsCalls)
        h.now += 5_000
        h.syncer.onConnected()
        assertEquals(2, h.transport.maxSeqsCalls)
    }

    @Test
    fun maxSeqFetchIsRetriedThenSucceeds() = runTest {
        val h = Harness()
        h.syncer.loadSeq()
        h.transport.maxSeqs = mapOf("si_a" to 2L)
        h.transport.maxSeqsFailuresRemaining = 2
        h.syncer.onConnected()
        assertEquals(3, h.transport.maxSeqsCalls)
        assertEquals(listOf(SyncFlag.MsgSyncBegin, SyncFlag.MsgSyncEnd), h.listener.flags)
    }

    @Test
    fun maxSeqFetchFailureReportsSyncFailed() = runTest {
        val h = Harness()
        h.syncer.loadSeq()
        h.transport.maxSeqsFailuresRemaining = 3
        h.syncer.onConnected()
        assertEquals(listOf(SyncFlag.MsgSyncBegin, SyncFlag.MsgSyncFailed), h.listener.flags)
        assertTrue(h.transport.pullRequests.isEmpty())
    }

    @Test
    fun largeSyncIsSplitIntoBatchedPulls() = runTest {
        // 30 conversations, each 50 messages behind. With defaultPullNums=10
        // a normal conversation contributes min(50, 10) = 10 to the batch
        // estimate, so the flush threshold of 100 is hit every 10
        // conversations: expect 3 pulls of 10 ranges each.
        val h = Harness()
        h.syncer.loadSeq()
        h.transport.maxSeqs = (1..30).associate { "si_$it" to 50L }
        // bypass connect (uses pullNums=1): use wake-up which uses 10
        h.syncer.onWakeUp()
        assertEquals(listOf(10, 10, 10), h.transport.pullRequests.map { it.seqRanges.size })
        assertEquals(50L, h.syncer.syncedMaxSeqsView["si_17"])
    }
}
