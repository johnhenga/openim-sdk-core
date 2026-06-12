package io.openim.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.openim.core.api.ConversationEvent
import io.openim.core.db.DriverFactory
import io.openim.core.network.ConnectionState
import io.openim.core.network.GeneralWsResp
import io.openim.core.network.ReqIdentifier
import io.openim.core.network.gob.GobFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.encodeUtf8
import openim.msg.SendMsgResp
import openim.sdkws.GetMaxSeqReq
import openim.sdkws.GetMaxSeqResp
import openim.sdkws.MsgData
import openim.sdkws.PullMessageBySeqsReq
import openim.sdkws.PullMessageBySeqsResp
import openim.sdkws.PullMsgs
import openim.sdkws.PushMessages
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full SDK lifecycle over a real websocket. The in-process server
 * speaks the Go wire protocol using the gob codec that the golden suite
 * proves byte-identical to Go — a faithful msggateway stand-in.
 *
 * This test is what caught the read-pump deadlock and the engine
 * dispatcher-confinement race; it must stay deterministic.
 */
class LiveSocketLifecycleTest {

    private val serverCodec = GobFrameCodec()

    @Test
    fun fullLifecycleOverLiveSocket() = runBlocking {
        val tmpDir = File.createTempFile("openim_live", "").let {
            it.delete(); it.mkdirs(); it
        }
        val port = 18000 + (System.nanoTime() % 1000).toInt()
        val receivedOnServer = mutableListOf<String>()
        var pushChannel: (suspend (ByteArray) -> Unit)? = null

        val server = embeddedServer(ServerCIO, port = port) {
            install(ServerWebSockets)
            routing {
                webSocket("/") {
                    check(call.request.queryParameters["sendID"] == "me")
                    check(call.request.queryParameters["token"] == "tok-live")
                    check(call.request.queryParameters["platformID"] == "2")
                    pushChannel = { bytes -> send(Frame.Binary(true, bytes)) }
                    for (frame in incoming) {
                        if (frame !is Frame.Binary) continue
                        val req = serverCodec.decodeReq(frame.readBytes())
                        val data: ByteArray = when (req.reqIdentifier) {
                            ReqIdentifier.GET_NEWEST_SEQ -> {
                                check(GetMaxSeqReq.ADAPTER.decode(req.data).userID == "me")
                                GetMaxSeqResp.ADAPTER.encode(
                                    GetMaxSeqResp(maxSeqs = mapOf("si_alice_me" to 2L))
                                )
                            }
                            ReqIdentifier.PULL_MSG_BY_RANGE -> {
                                val pull = PullMessageBySeqsReq.ADAPTER.decode(req.data)
                                PullMessageBySeqsResp.ADAPTER.encode(PullMessageBySeqsResp(
                                    msgs = pull.seqRanges.associate { r ->
                                        r.conversationID to PullMsgs(Msgs = (r.begin..r.end).map { seq ->
                                            MsgData(
                                                clientMsgID = "h$seq", sendID = "alice", recvID = "me",
                                                sessionType = 1, seq = seq, sendTime = seq * 10,
                                                content = "history $seq".encodeUtf8(),
                                            )
                                        })
                                    }
                                ))
                            }
                            ReqIdentifier.SEND_MSG -> {
                                val m = MsgData.ADAPTER.decode(req.data)
                                receivedOnServer += m.content.utf8()
                                SendMsgResp.ADAPTER.encode(
                                    SendMsgResp(serverMsgID = "srv-live", clientMsgID = m.clientMsgID, sendTime = 4242)
                                )
                            }
                            else -> ByteArray(0)
                        }
                        send(Frame.Binary(true, serverCodec.encodeResp(
                            GeneralWsResp(req.reqIdentifier, 0, "", req.msgIncr, req.operationID, data)
                        )))
                    }
                }
            }
        }.start(wait = false)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val httpClient = HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }
        val sdk = OpenIMSdk(
            config = SdkConfig(
                apiAddr = "http://127.0.0.1:$port", wsAddr = "ws://127.0.0.1:$port",
                dataDir = tmpDir.absolutePath, platformID = 2,
            ),
            driverFactory = DriverFactory(),
            httpClient = httpClient,
            parentScope = scope,
        )

        val syncEvents = mutableListOf<ConversationEvent>()
        val collector = scope.launch { sdk.events.conversationEvents.collect { syncEvents += it } }

        try {
            withTimeout(20_000) {
                sdk.login("me", "tok-live")
                sdk.connectionState.first { it is ConnectionState.Connected }
                while (sdk.engine.chatLogs.maxSeq("si_alice_me") < 2L) delay(50)
            }

            withTimeout(10_000) {
                pushChannel!!.invoke(serverCodec.encodeResp(GeneralWsResp(
                    ReqIdentifier.PUSH_MSG, 0, "", "", "op-push",
                    PushMessages.ADAPTER.encode(PushMessages(msgs = mapOf(
                        "si_alice_me" to PullMsgs(Msgs = listOf(
                            MsgData(
                                clientMsgID = "p3", sendID = "alice", recvID = "me", sessionType = 1,
                                seq = 3, sendTime = 30, content = "pushed!".encodeUtf8(),
                            )
                        ))
                    )))
                )))
                while (sdk.engine.chatLogs.maxSeq("si_alice_me") < 3L) delay(50)
            }

            val sent = withTimeout(10_000) {
                sdk.engine.sendTextMessage("hello over the wire", recvID = "alice")
            }
            assertEquals("srv-live", sent.serverMsgID)
            assertEquals(4242L, sent.sendTime)
            assertEquals(listOf("hello over the wire"), receivedOnServer)

            val conv = sdk.engine.conversations.getByIDs(listOf("si_alice_me")).single()
            assertEquals(3, conv.unreadCount)
            assertTrue(ConversationEvent.SyncServerStart in syncEvents)
            assertTrue(ConversationEvent.SyncServerFinish in syncEvents)
        } finally {
            collector.cancel()
            sdk.logout()
            scope.cancel()
            server.stop(100, 100)
            tmpDir.deleteRecursively()
        }
    }
}
