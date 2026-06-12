package io.openim.core.engine

import app.cash.sqldelight.db.SqlDriver
import io.openim.core.conversation.ChatLogIngestStore
import io.openim.core.conversation.ConversationEventSink
import io.openim.core.conversation.ConversationProcessor
import io.openim.core.conversation.ConversationSync
import io.openim.core.conversation.MessageIngestor
import io.openim.core.conversation.MessageSender
import io.openim.core.conversation.SelfInfo
import io.openim.core.conversation.SendTransport
import io.openim.core.db.ChatLogStore
import io.openim.core.db.GoSdkSchema
import io.openim.core.db.SqlConversationStore
import io.openim.core.db.SqlFriendStore
import io.openim.core.db.SqlGroupStore
import io.openim.core.db.SqlMsgSyncStore
import io.openim.core.db.SqlSendingMessagesStore
import io.openim.core.db.SqlVersionSyncStore
import io.openim.core.group.GroupSync
import io.openim.core.network.ApiClient
import io.openim.core.network.nowMillis
import io.openim.core.relation.FriendSync
import io.openim.core.sync.MaxSeqRecorder
import io.openim.core.sync.MsgSyncListener
import io.openim.core.sync.MsgSyncTransport
import io.openim.core.sync.MsgSyncer
import io.openim.core.sync.SyncFlag
import openim.sdkws.PullMsgs
import openim.sdkws.PushMessages

/** Engine-level events beyond [ConversationEventSink]. */
interface EngineListener : ConversationEventSink {
    suspend fun onSyncFlag(flag: SyncFlag)

    /** Notification-type messages (Go: triggerNotification → doNotificationNew). */
    suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>) {}
}

/**
 * The assembled SDK core: constructs the verified components over one
 * database and the injected transports, and wires the message flow
 * MsgSyncer → ConversationProcessor → stores → listener, mirroring the Go
 * login manager's composition (internal/login.go).
 *
 * Transports are injected so the engine is testable headlessly; production
 * wiring passes WsMsgSyncTransport/WsSendTransport over
 * LongConnManager::sendReqWaitResp and calls [onConnected]/[onWakeUp]/
 * [onPushMsg] from the connection events.
 */
class OpenIMEngine(
    private val loginUserID: String,
    platformID: Int,
    driver: SqlDriver,
    msgTransport: MsgSyncTransport,
    sendTransport: SendTransport,
    private val api: ApiClient,
    private val listener: EngineListener,
    selfInfo: suspend () -> SelfInfo,
    clock: () -> Long = ::nowMillis,
) {

    val chatLogs = ChatLogStore(driver)
    val conversations = SqlConversationStore(driver)
    private val versionStore = SqlVersionSyncStore(driver)
    private val friendStore = SqlFriendStore(driver)
    private val groupStore = SqlGroupStore(driver)
    private val sendingStore = SqlSendingMessagesStore(driver)
    private val recorder = MaxSeqRecorder()

    private val processor = ConversationProcessor(
        loginUserID = loginUserID,
        ingestor = MessageIngestor(loginUserID, ChatLogIngestStore(chatLogs)),
        store = conversations,
        recorder = recorder,
        events = listener,
    )

    private val msgSyncer = MsgSyncer(
        loginUserID = loginUserID,
        transport = msgTransport,
        store = SqlMsgSyncStore(driver, chatLogs),
        listener = object : MsgSyncListener {
            override suspend fun onConversationMsgs(msgs: Map<String, PullMsgs>) =
                processor.processNewMessages(msgs)

            override suspend fun onReinstallConversationMsgs(
                msgs: Map<String, PullMsgs>,
                totalConversations: Int,
            ) = processor.processNewMessages(msgs)

            override suspend fun onNotificationMsgs(msgs: Map<String, PullMsgs>) =
                listener.onNotificationMsgs(msgs)

            override suspend fun onSyncFlag(flag: SyncFlag) = listener.onSyncFlag(flag)
        },
        clock = clock,
    )

    val sender = MessageSender(
        loginUserID = loginUserID,
        platformID = platformID,
        selfInfo = selfInfo,
        chatLogs = chatLogs,
        sendingStore = sendingStore,
        transport = sendTransport,
        onMessageStatusChanged = { conversationID, message ->
            listener.onConversationsChanged(
                conversations.getByIDs(listOf(conversationID))
            )
        },
        clock = clock,
    )

    val friendSync = FriendSync(loginUserID, api, versionStore, friendStore)
    val groupSync = GroupSync(loginUserID, api, versionStore, groupStore)
    val conversationSync = ConversationSync(loginUserID, api, versionStore, conversations)

    companion object {
        /** Opens (or upgrades in place) the Go-compatible database. */
        fun prepareDatabase(driver: SqlDriver) {
            GoSdkSchema.createIfNotExists(driver)
        }
    }

    /** Go: LoadSeq at login — prime synced seqs, detect (re)install. */
    suspend fun login() {
        msgSyncer.loadSeq()
    }

    /** Connection established: catch-up message sync (Go: CmdConnSuccesss). */
    suspend fun onConnected() = msgSyncer.onConnected()

    /** App woke up (Go: CmdWakeUpDataSync). */
    suspend fun onWakeUp() = msgSyncer.onWakeUp()

    /** Real-time push from the read pump (Go: CmdPushMsg). */
    suspend fun onPushMsg(push: PushMessages) = msgSyncer.onPushMsg(push)

    /** Incremental server-data sync (Go: conversation/friend/group syncs). */
    suspend fun syncServerData() {
        conversationSync.incrementalSync()
        friendSync.incrementalSync()
        groupSync.syncJoinedGroupsAndMembers()
    }

    /** Convenience send (Go: CreateTextMessage + SendMessage). */
    suspend fun sendTextMessage(text: String, recvID: String = "", groupID: String = "") =
        sender.send(sender.createTextMessage(text), recvID = recvID, groupID = groupID)
}
