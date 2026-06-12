package io.openim.core.sync

import io.openim.core.network.nowMillis
import io.openim.core.sync.MsgSyncCalculator.SeqRange
import kotlinx.coroutines.delay
import openim.sdkws.MsgData
import openim.sdkws.PullMessageBySeqsReq
import openim.sdkws.PullMessageBySeqsResp
import openim.sdkws.PullMsgs
import openim.sdkws.PushMessages
import openim.sdkws.SeqRange as ProtoSeqRange
import kotlin.time.Duration.Companion.seconds

/** Go: pkg/constant MsgStatusHasDeleted. */
private const val MSG_STATUS_HAS_DELETED = 4

/** Sync progress flags dispatched to the conversation module (pkg/constant). */
enum class SyncFlag { MsgSyncBegin, MsgSyncEnd, MsgSyncFailed, AppDataSyncStart, AppDataSyncFinish, SyncData }

/** Server interactions of the sync engine (over LongConnManager + protobuf). */
interface MsgSyncTransport {
    /** Go: GetNewestSeq (1001) — server max seq per conversation. */
    suspend fun getMaxSeqs(userID: String): Map<String, Long>

    /** Go: PullMsgByRange (1002). */
    suspend fun pullMessageBySeqs(req: PullMessageBySeqsReq): PullMessageBySeqsResp

    /** Go: PullConvLastMessage (1007) — latest valid message per conversation. */
    suspend fun getLastMessages(userID: String, conversationIDs: List<String>): Map<String, MsgData>

    /** Go: GetConvMaxReadSeq (1006) — max seq for specific conversations. */
    suspend fun getConversationsMaxSeq(userID: String, conversationIDs: List<String>): Map<String, Long>
}

/** Local storage the sync engine needs (subset of db_interface.DataBase). */
interface MsgSyncStore {
    suspend fun allConversationIDs(): List<String>

    /** Go: CheckConversationNormalMsgSeq — max contiguous stored seq. */
    suspend fun maxSyncedSeq(conversationID: String): Long

    /** Go: GetNotificationAllSeqs. */
    suspend fun notificationSeqs(): Map<String, Long>

    /** Go: BatchInsertNotificationSeq. */
    suspend fun insertNotificationSeqs(seqs: Map<String, Long>)

    /** Go: GetAppSDKVersion().Installed (false/absent means reinstalled). */
    suspend fun isInstalled(): Boolean

    /** Go: SetAppSDKVersion(Installed: true) after the reinstall sync. */
    suspend fun markInstalled()
}

/** Downstream triggers into the conversation module. */
interface MsgSyncListener {
    suspend fun onConversationMsgs(msgs: Map<String, PullMsgs>)
    suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>)
    suspend fun onReinstallConversationMsgs(msgs: Map<String, PullMsgs>, totalConversations: Int)
    suspend fun onSyncFlag(flag: SyncFlag)
}

/**
 * Message sync orchestrator: port of internal/interaction/msg_sync.go
 * MsgSyncer. Coordinates real-time push handling, the on-connect catch-up
 * sync, wake-up sync, and seq gap-filling, delegating the pure range
 * arithmetic to [MsgSyncCalculator].
 *
 * Like its Go counterpart (whose state is touched only from the DoListener
 * goroutine), all entry points must be called from the engine's
 * single-threaded dispatcher.
 */
class MsgSyncer(
    private val loginUserID: String,
    private val transport: MsgSyncTransport,
    private val store: MsgSyncStore,
    private val listener: MsgSyncListener,
    private val clock: () -> Long = ::nowMillis,
) {
    private val syncedMaxSeqs = mutableMapOf<String, Long>()
    private var reinstalled = false
    private var syncingSince = 0L

    /** Go: startSync — debounce window during which repeat events are ignored. */
    private val syncDebounce = 5.seconds

    /** Go: doConnected retry policy for GetMaxSeqs. */
    private val maxSeqRetries = 3
    private val maxSeqRetryBase = 2.seconds

    internal val syncedMaxSeqsView: Map<String, Long> get() = syncedMaxSeqs

    /** Go: LoadSeq — prime synced seqs from local storage; detect reinstall. */
    suspend fun loadSeq() {
        val conversationIDs = store.allConversationIDs()
        if (conversationIDs.isEmpty() && !store.isInstalled()) {
            reinstalled = true
        }
        // Go parallelizes this 20 ways as an optimization; sequential is
        // semantically identical.
        for (conversationID in conversationIDs) {
            syncedMaxSeqs[conversationID] = store.maxSyncedSeq(conversationID)
        }
        syncedMaxSeqs.putAll(store.notificationSeqs())
    }

    /** Go: handlePushMsgAndEvent(CmdConnSuccesss) + doConnected. */
    suspend fun onConnected() {
        if (!startSync()) return
        listener.onSyncFlag(if (reinstalled) SyncFlag.AppDataSyncStart else SyncFlag.MsgSyncBegin)
        val wasReinstalled = reinstalled

        var maxSeqs: Map<String, Long>? = null
        var retryInterval = maxSeqRetryBase
        for (attempt in 0 until maxSeqRetries) {
            if (attempt > 0) {
                delay(retryInterval)
                retryInterval *= 2
            }
            maxSeqs = runCatching { transport.getMaxSeqs(loginUserID) }.getOrNull()
            if (maxSeqs != null) break
        }
        if (maxSeqs == null) {
            listener.onSyncFlag(SyncFlag.MsgSyncFailed)
            return
        }

        compareSeqsAndBatchSync(maxSeqs, MsgSyncCalculator.CONNECT_PULL_NUMS)
        listener.onSyncFlag(if (wasReinstalled) SyncFlag.AppDataSyncFinish else SyncFlag.MsgSyncEnd)
    }

    /** Go: handlePushMsgAndEvent(CmdWakeUpDataSync) + doWakeupDataSync. */
    suspend fun onWakeUp() {
        if (!startSync()) return
        listener.onSyncFlag(SyncFlag.SyncData)
        val maxSeqs = runCatching { transport.getMaxSeqs(loginUserID) }.getOrNull() ?: return
        compareSeqsAndBatchSync(maxSeqs, MsgSyncCalculator.DEFAULT_PULL_NUMS)
    }

    /** Go: doIMMessageSync — manual sync for specific conversations. */
    suspend fun onIMMessageSync(conversationIDs: List<String>) {
        val maxSeqs = runCatching {
            transport.getConversationsMaxSeq(loginUserID, conversationIDs)
        }.getOrNull() ?: return
        compareSeqsAndBatchSync(maxSeqs, MsgSyncCalculator.DEFAULT_PULL_NUMS)
    }

    /** Go: doPushMsg — real-time pushed messages and notifications. */
    suspend fun onPushMsg(push: PushMessages) {
        pushTriggerAndSync(push.msgs) { listener.onConversationMsgs(it) }
        pushTriggerAndSync(push.notificationMsgs) { listener.onNotificationMsgs(it) }
    }

    private fun startSync(): Boolean {
        val now = clock()
        if (now - syncingSince < syncDebounce.inWholeMilliseconds) return false
        syncingSince = now
        return true
    }

    /**
     * Go: pushTriggerAndSync. Seq-0 messages (online-only) are triggered
     * immediately; a contiguous batch (lastSeq == synced + count) is
     * triggered and advances the synced seq; anything else with newer seqs
     * is a gap and is pulled from synced+1 to lastSeq.
     */
    private suspend fun pushTriggerAndSync(
        pushMessages: Map<String, PullMsgs>,
        trigger: suspend (Map<String, PullMsgs>) -> Unit,
    ) {
        if (pushMessages.isEmpty()) return

        val needSync = mutableMapOf<String, SeqRange>()
        val toTrigger = mutableMapOf<String, PullMsgs>()

        for ((conversationID, msgs) in pushMessages) {
            var lastSeq = 0L
            val storageMsgs = mutableListOf<MsgData>()
            for (msg in msgs.Msgs) {
                if (msg.seq == 0L) {
                    trigger(mapOf(conversationID to PullMsgs(Msgs = listOf(msg))))
                    continue
                }
                lastSeq = msg.seq
                storageMsgs += msg
            }
            if (storageMsgs.isEmpty()) continue

            val synced = syncedMaxSeqs[conversationID] ?: 0L
            if (lastSeq == synced + storageMsgs.size) {
                toTrigger[conversationID] = PullMsgs(Msgs = storageMsgs)
                syncedMaxSeqs[conversationID] = lastSeq
            } else if (lastSeq > synced) {
                needSync[conversationID] = SeqRange(synced + 1, lastSeq)
            }
        }

        if (toTrigger.isNotEmpty()) trigger(toTrigger)
        syncAndTriggerMsgs(needSync, MsgSyncCalculator.DEFAULT_PULL_NUMS)
    }

    /** Go: compareSeqsAndBatchSync (both branches). */
    private suspend fun compareSeqsAndBatchSync(serverMaxSeqs: Map<String, Long>, pullNums: Long) {
        val needSync = MsgSyncCalculator.computeNeedSync(syncedMaxSeqs, serverMaxSeqs, reinstalled)
        if (!reinstalled) {
            syncAndTriggerMsgs(needSync.ranges, pullNums)
            return
        }
        if (needSync.notificationSeqsToRecord.isNotEmpty()) {
            store.insertNotificationSeqs(needSync.notificationSeqsToRecord)
            syncedMaxSeqs.putAll(needSync.notificationSeqsToRecord)
        }
        try {
            syncAndTriggerReinstallMsgs(needSync.ranges, pullNums)
        } finally {
            store.markInstalled()
            reinstalled = false
        }
    }

    /**
     * Go: syncAndTriggerMsgs. Accumulates conversations into one pull until
     * the estimated message count reaches SplitPullMsgNum, then flushes.
     * Notification conversations count their full range; normal ones count
     * at most [syncMsgNum] (the server caps per-conversation messages).
     */
    private suspend fun syncAndTriggerMsgs(seqMap: Map<String, SeqRange>, syncMsgNum: Long) {
        if (seqMap.isEmpty()) return

        var batch = mutableMapOf<String, SeqRange>()
        var msgNum = 0L

        suspend fun flush() {
            val resp = transport.pullMessageBySeqs(buildPullReq(batch, syncMsgNum))
            listener.onConversationMsgs(resp.msgs)
            listener.onNotificationMsgs(resp.notificationMsgs)
            for ((conversationID, range) in batch) {
                syncedMaxSeqs[conversationID] = range.end
            }
        }

        for ((conversationID, range) in seqMap) {
            val oneConversationNum = range.end - range.begin + 1
            batch[conversationID] = range
            msgNum += if (MsgSyncCalculator.isNotificationConversation(conversationID)) {
                oneConversationNum
            } else {
                minOf(oneConversationNum, syncMsgNum)
            }
            if (msgNum >= MsgSyncCalculator.SPLIT_PULL_MSG_NUM) {
                flush()
                batch = mutableMapOf()
                msgNum = 0
            }
        }
        if (batch.isNotEmpty()) flush()
    }

    /**
     * Go: syncAndTriggerReinstallMsgs. Same batching, but pulled messages go
     * through [checkMessagesAndGetLastMessage] and the reinstall trigger.
     * Faithful Go quirk: the final flush is skipped when msgNum == 0.
     */
    private suspend fun syncAndTriggerReinstallMsgs(seqMap: Map<String, SeqRange>, syncMsgNum: Long) {
        if (seqMap.isEmpty()) return
        val total = seqMap.size

        var batch = mutableMapOf<String, SeqRange>()
        var msgNum = 0L

        suspend fun flush() {
            val resp = transport.pullMessageBySeqs(buildPullReq(batch, syncMsgNum))
            val msgs = checkMessagesAndGetLastMessage(resp.msgs)
            listener.onReinstallConversationMsgs(msgs, total)
            listener.onNotificationMsgs(resp.notificationMsgs)
            for ((conversationID, range) in batch) {
                syncedMaxSeqs[conversationID] = range.end
            }
        }

        for ((conversationID, range) in seqMap) {
            val oneConversationNum = minOf(range.end - range.begin + 1, syncMsgNum)
            batch[conversationID] = range
            if (oneConversationNum > 0) {
                msgNum += minOf(oneConversationNum, syncMsgNum)
            }
            if (msgNum >= MsgSyncCalculator.SPLIT_PULL_MSG_NUM) {
                flush()
                batch = mutableMapOf()
                msgNum = 0
            }
        }
        if (batch.isNotEmpty() && msgNum > 0) flush()
    }

    /**
     * Go: checkMessagesAndGetLastMessage. Conversations whose pulled
     * messages are all deleted/invalid get their latest valid message
     * fetched instead, so the conversation list still shows something.
     */
    private suspend fun checkMessagesAndGetLastMessage(
        messages: Map<String, PullMsgs>,
    ): Map<String, PullMsgs> {
        val allInvalid = messages.filterValues { pulled ->
            pulled.Msgs.none { it.status < MSG_STATUS_HAS_DELETED }
        }.keys.toList()
        if (allInvalid.isEmpty()) return messages

        val latest = runCatching {
            transport.getLastMessages(loginUserID, allInvalid)
        }.getOrNull() ?: return messages

        val patched = messages.toMutableMap()
        for ((conversationID, msg) in latest) {
            patched[conversationID] = PullMsgs(Msgs = listOf(msg))
        }
        return patched
    }

    /** Go: pullMsgBySeqRange request construction. */
    private fun buildPullReq(seqMap: Map<String, SeqRange>, syncMsgNum: Long) =
        PullMessageBySeqsReq(
            userID = loginUserID,
            seqRanges = seqMap.map { (conversationID, range) ->
                ProtoSeqRange(
                    conversationID = conversationID,
                    begin = range.begin,
                    end = range.end,
                    num = syncMsgNum,
                )
            },
        )
}
