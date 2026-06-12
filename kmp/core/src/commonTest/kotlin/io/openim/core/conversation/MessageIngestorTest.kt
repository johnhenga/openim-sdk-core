package io.openim.core.conversation

import io.openim.core.db.ChatLog
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import openim.sdkws.MsgData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins MessageIngestor to message_check.go pullMessageIntoTable /
 * handleExceptionMessages semantics.
 */
class MessageIngestorTest {

    private class FakeStore : MessageIngestStore {
        val local = mutableMapOf<String, ChatLog>()
        val insertedBatches = mutableListOf<List<ChatLog>>()
        val updatedBatches = mutableListOf<List<ChatLog>>()

        override suspend fun getMessagesByClientMsgIDs(conversationID: String, clientMsgIDs: List<String>) =
            clientMsgIDs.mapNotNull { local[it] }

        override suspend fun batchInsertMessages(conversationID: String, msgs: List<ChatLog>) {
            insertedBatches += msgs
        }

        override suspend fun batchUpdateMessages(conversationID: String, msgs: List<ChatLog>) {
            updatedBatches += msgs
        }
    }

    private fun harness(): Pair<MessageIngestor, FakeStore> {
        val store = FakeStore()
        val ingestor = MessageIngestor(
            loginUserID = "me",
            store = store,
            genMsgID = { "GEN-ID" },
            randomSuffix = { "RAND" },
        )
        return ingestor to store
    }

    private fun serverMsg(
        id: String,
        seq: Long,
        sendID: String = "other",
        status: Int = MsgStatus.SEND_SUCCESS.toInt(),
        sessionType: Int = SessionType.SINGLE_CHAT.toInt(),
        groupID: String = "",
    ) = MsgData(
        clientMsgID = id, sendID = sendID, recvID = "me", seq = seq,
        status = status, sessionType = sessionType, groupID = groupID,
        content = "hello".encodeUtf8(), sendTime = seq * 100,
    )

    @Test
    fun newMessagesFromOthersAreInserted() = runTest {
        val (ingestor, store) = harness()
        val result = ingestor.ingest("si_a", listOf(serverMsg("m1", 1), serverMsg("m2", 2)))
        assertEquals(listOf("m1", "m2"), result.inserted.map { it.clientMsgID })
        assertTrue(result.updated.isEmpty() && result.exceptions.isEmpty())
        assertEquals(1, store.insertedBatches.size)
        // conversion checks: status normalized to SEND_SUCCESS, content decoded
        assertEquals(MsgStatus.SEND_SUCCESS, result.inserted[0].status)
        assertEquals("hello", result.inserted[0].content)
    }

    @Test
    fun groupMessageRecvIDBecomesGroupID() = runTest {
        val (ingestor, _) = harness()
        val result = ingestor.ingest(
            "sg_g1",
            listOf(serverMsg("m1", 1, sessionType = SessionType.READ_GROUP_CHAT.toInt(), groupID = "g1")),
        )
        assertEquals("g1", result.inserted.single().recvID)
    }

    @Test
    fun ownSendWithLocalSeqZeroIsUpdatedNotInserted() = runTest {
        val (ingestor, store) = harness()
        store.local["m1"] = ChatLog(clientMsgID = "m1", seq = 0) // local row awaiting seq
        val result = ingestor.ingest("si_a", listOf(serverMsg("m1", 7, sendID = "me")))
        assertEquals(listOf("m1"), result.updated.map { it.clientMsgID })
        assertEquals(7L, result.updated.single().seq)
        assertTrue(result.inserted.isEmpty())
        assertEquals(1, store.updatedBatches.size)
        assertTrue(store.insertedBatches.isEmpty())
    }

    @Test
    fun ownDuplicateWithDifferentSeqIsClientDup() = runTest {
        val (ingestor, _) = harness()
        val (ingestor2, store) = harness()
        store.local["m1"] = ChatLog(clientMsgID = "m1", seq = 5)
        val result = ingestor2.ingest("si_a", listOf(serverMsg("m1", 9, sendID = "me")))
        val exception = result.exceptions.single()
        assertEquals("[CLIENT_DUP]m1_RAND", exception.clientMsgID)
        assertEquals(MsgStatus.HAS_DELETED, exception.status)
        assertTrue(ingestor !== ingestor2)
    }

    @Test
    fun duplicateFromOthersWithSameSeqIsSeqDup() = runTest {
        val (ingestor, store) = harness()
        store.local["m1"] = ChatLog(clientMsgID = "m1", seq = 5)
        val result = ingestor.ingest("si_a", listOf(serverMsg("m1", 5)))
        assertEquals("[SEQ_DUP]m1_RAND", result.exceptions.single().clientMsgID)
    }

    @Test
    fun duplicateWithinBatchIsException() = runTest {
        val (ingestor, _) = harness()
        val result = ingestor.ingest("si_a", listOf(serverMsg("m1", 5), serverMsg("m1", 5)))
        assertEquals(2, result.inserted.size)
        assertEquals("m1", result.inserted[0].clientMsgID)
        assertEquals("[SEQ_DUP]m1_RAND", result.inserted[1].clientMsgID)
    }

    @Test
    fun cloudDeletedWithoutIDBecomesSeqGapPlaceholder() = runTest {
        val (ingestor, _) = harness()
        val result = ingestor.ingest(
            "si_a",
            listOf(serverMsg("", 42, status = MsgStatus.HAS_DELETED.toInt())),
        )
        val placeholder = result.exceptions.single()
        assertEquals("[SEQ_GAP_+42]GEN-ID_RAND", placeholder.clientMsgID)
        assertEquals(MsgStatus.HAS_DELETED, placeholder.status)
    }

    @Test
    fun cloudDeletedWithIDIsMarkedDeleted() = runTest {
        val (ingestor, _) = harness()
        val result = ingestor.ingest(
            "si_a",
            listOf(serverMsg("m9", 9, status = MsgStatus.HAS_DELETED.toInt())),
        )
        assertEquals("[DELETED]m9_RAND", result.exceptions.single().clientMsgID)
    }
}
