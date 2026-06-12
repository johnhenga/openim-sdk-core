package io.openim.core.conversation

import io.openim.core.sync.MaxSeqRecorder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins ConversationTrigger to the doMsgNew logic in conversation_msg.go. */
class ConversationTriggerTest {

    private fun conv(
        id: String,
        sendTime: Long = 0,
        unread: Int = 0,
        latestMsg: String = "",
    ) = LocalConversation(
        conversationID = id, latestMsgSendTime = sendTime,
        unreadCount = unread, latestMsg = latestMsg,
    )

    @Test
    fun accumulateSumsUnreadAndKeepsNewestMessage() {
        val set = mutableMapOf<String, LocalConversation>()
        ConversationTrigger.accumulate(set, conv("c1", sendTime = 100, unread = 1, latestMsg = "a"))
        ConversationTrigger.accumulate(set, conv("c1", sendTime = 300, unread = 1, latestMsg = "c"))
        ConversationTrigger.accumulate(set, conv("c1", sendTime = 200, unread = 1, latestMsg = "b"))
        val out = set["c1"]!!
        assertEquals(3, out.unreadCount)
        assertEquals("c", out.latestMsg)
        assertEquals(300L, out.latestMsgSendTime)
    }

    @Test
    fun unreadDeltaOnlyCountsSeqsAheadOfRecorder() {
        val recorder = MaxSeqRecorder()
        recorder.set("c1", 5)
        assertEquals(0, ConversationTrigger.unreadDelta(recorder, "c1", 5, isUnreadCountOption = true))
        assertEquals(1, ConversationTrigger.unreadDelta(recorder, "c1", 6, isUnreadCountOption = true))
        assertEquals(6L, recorder.get("c1"))
        // replayed seq after increment: no double count
        assertEquals(0, ConversationTrigger.unreadDelta(recorder, "c1", 6, isUnreadCountOption = true))
        // option off: never counts
        assertEquals(0, ConversationTrigger.unreadDelta(recorder, "c1", 9, isUnreadCountOption = false))
    }

    @Test
    fun diffSplitsChangedAndNewAndMergesUnread() = runTest {
        val local = mapOf(
            "c1" to conv("c1", sendTime = 100, unread = 2, latestMsg = "old"),
            "c2" to conv("c2", sendTime = 500, unread = 0, latestMsg = "newer-local"),
        )
        val generated = mapOf(
            "c1" to conv("c1", sendTime = 300, unread = 1, latestMsg = "fresh"),
            "c2" to conv("c2", sendTime = 400, unread = 1, latestMsg = "older"),
            "c3" to conv("c3", sendTime = 50, unread = 1, latestMsg = "first"),
        )
        val result = ConversationTrigger.diff(local, generated) { newOnes ->
            newOnes.map { it.copy(showName = "enriched-${it.conversationID}") }
        }
        // newer generated message replaces latest msg and adds unread
        val c1 = result.changed["c1"]!!
        assertEquals(3, c1.unreadCount)
        assertEquals("fresh", c1.latestMsg)
        // older generated message adds unread but keeps local latest msg
        val c2 = result.changed["c2"]!!
        assertEquals(1, c2.unreadCount)
        assertEquals("newer-local", c2.latestMsg)
        assertEquals(500L, c2.latestMsgSendTime)
        // unknown conversation lands in new-set, enriched
        assertEquals("enriched-c3", result.new["c3"]!!.showName)
    }

    @Test
    fun placeholderConversationsBecomeUpdatesCarryingSettings() {
        val placeholder = LocalConversation(
            conversationID = "c1", recvMsgOpt = 2, isPinned = true,
            isPrivateChat = true, burnDuration = 60, unreadCount = 0,
            ex = "keep", latestMsgSendTime = 0,
        )
        val fresh = conv("c1", sendTime = 100, unread = 1, latestMsg = "hello")
            .copy(burnDuration = 30)
        val merge = ConversationTrigger.mergePlaceholders(
            listOf(placeholder),
            mapOf("c1" to fresh, "c2" to conv("c2", sendTime = 1)),
        )
        val merged = merge.changed["c1"]!!
        assertEquals(2, merged.recvMsgOpt)
        assertTrue(merged.isPinned)
        assertEquals(60, merged.burnDuration) // private chat keeps placeholder burn
        assertEquals("keep", merged.ex)
        assertEquals("hello", merged.latestMsg)
        assertEquals(1, merged.unreadCount) // placeholder unread 0 -> fresh wins
        assertEquals(setOf("c2"), merge.new.keys)
    }

    @Test
    fun seedsFollowSessionTypeRules() {
        val received = ConversationTrigger.seedFromMessage(
            "si_a_me", SessionType.SINGLE_CHAT.toInt(), sendID = "a", recvID = "me",
            groupID = "", senderNickname = "Alice", senderFaceURL = "a.png",
            latestMsgJson = "{}", sendTime = 9, sentByMe = false, unreadCount = 1,
        )
        assertEquals("a", received.userID)
        assertEquals("Alice", received.showName)

        val sent = ConversationTrigger.seedFromMessage(
            "si_a_me", SessionType.SINGLE_CHAT.toInt(), sendID = "me", recvID = "a",
            groupID = "", senderNickname = "Me", senderFaceURL = "",
            latestMsgJson = "{}", sendTime = 9, sentByMe = true,
        )
        assertEquals("a", sent.userID)
        assertEquals("", sent.showName)

        val group = ConversationTrigger.seedFromMessage(
            "sg_g1", SessionType.READ_GROUP_CHAT.toInt(), sendID = "a", recvID = "",
            groupID = "g1", senderNickname = "Alice", senderFaceURL = "",
            latestMsgJson = "{}", sendTime = 9, sentByMe = false,
        )
        assertEquals("g1", group.groupID)
        assertEquals("", group.userID)
    }
}
