package io.openim.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Mirror of Go internal/interaction long_conn_mgr.go connection constants. */
object ConnConstants {
    /** Time allowed to write a message to the peer (Go: writeWait). */
    val WRITE_WAIT: Duration = 10.seconds

    /** Time allowed to read the next pong from the peer (Go: pongWait). */
    val PONG_WAIT: Duration = 30.seconds

    /** Ping period; must be less than PONG_WAIT (Go: pingPeriod = pongWait*8/10). */
    val PING_PERIOD: Duration = PONG_WAIT * 8 / 10

    /** Maximum inbound message size (Go: maxMessageSize). */
    const val MAX_MESSAGE_SIZE: Long = 1024L * 1024L

    /** Maximum reconnection attempts (Go: maxReconnectAttempts). */
    const val MAX_RECONNECT_ATTEMPTS: Int = 300

    /** Request/response wait (Go: sendAndWaitTime). */
    val SEND_AND_WAIT_TIME: Duration = 10.seconds

    /** Outbound channel capacity (Go: send chan Message, cap 10). */
    const val SEND_CHANNEL_CAPACITY: Int = 10
}

/** Mirror of Go connection status ints (DefaultNotConnect/Closed/Connecting/Connected). */
sealed interface ConnectionState {
    data object NotConnected : ConnectionState
    data class Closed(val reason: String?) : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class KickedOffline(val reason: String) : ConnectionState
    data object TokenExpired : ConnectionState
}

/** Mirror of Go interaction.GeneralWsReq. */
data class GeneralWsReq(
    val reqIdentifier: Int,
    val token: String,
    val sendID: String,
    val operationID: String,
    val msgIncr: String,
    val data: ByteArray,
)

/** Mirror of Go interaction.GeneralWsResp. */
data class GeneralWsResp(
    val reqIdentifier: Int,
    val msgIncr: String,
    val operationID: String,
    val errCode: Int,
    val errMsg: String,
    val data: ByteArray,
)

/** Encodes/decodes websocket frames (gob+gzip in Go; protobuf here). */
interface FrameCodec {
    fun encode(req: GeneralWsReq): ByteArray
    fun decode(payload: ByteArray): GeneralWsResp
}

/**
 * WebSocket long-connection manager: the coroutine port of
 * Go internal/interaction/long_conn_mgr.go.
 *
 * Go's three goroutines (readPump / writePump / heartbeat) become three child
 * coroutines of a per-connection [coroutineScope]; any failure cancels the
 * scope, which tears down all three pumps and the socket, and control returns
 * to the reconnect loop in [run] — replacing the Go version's manual
 * mutex-guarded teardown.
 *
 * Request/response correlation: Go's per-request `Resp chan *GeneralWsResp`
 * becomes a [CompletableDeferred] keyed by msgIncr (Go: WsRespAsyn).
 */
class LongConnManager(
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val wsUrl: suspend () -> String,
    private val codec: FrameCodec,
    private val reconnectBackoff: (attempt: Int) -> Duration = ::defaultBackoff,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NotConnected)
    val state: StateFlow<ConnectionState> = _state

    private val send = Channel<GeneralWsReq>(ConnConstants.SEND_CHANNEL_CAPACITY)
    private val pending = mutableMapOf<String, CompletableDeferred<GeneralWsResp>>()
    private var msgIncr = 0L

    /** Push frames not correlated to a request (new messages, max-seq, kick…). */
    var onPushMessage: suspend (GeneralWsResp) -> Unit = {}

    fun start() {
        scope.launch { run() }
    }

    /** Go: LongConnMgr.SendReqWaitResp. */
    suspend fun sendReqWaitResp(req: GeneralWsReq): GeneralWsResp {
        val incr = "${++msgIncr}"
        val deferred = CompletableDeferred<GeneralWsResp>()
        pending[incr] = deferred
        try {
            send.send(req.copy(msgIncr = incr))
            return withTimeout(ConnConstants.SEND_AND_WAIT_TIME) { deferred.await() }
        } finally {
            pending.remove(incr)
        }
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive && attempt < ConnConstants.MAX_RECONNECT_ATTEMPTS) {
            _state.value = ConnectionState.Connecting
            try {
                val session = httpClient.webSocketSession(urlString = wsUrl())
                _state.value = ConnectionState.Connected
                attempt = 0
                runPumps(session)
            } catch (e: Exception) {
                _state.value = ConnectionState.Closed(e.message)
            }
            attempt++
            delay(reconnectBackoff(attempt))
        }
    }

    /** One connection lifetime: read, write, heartbeat as sibling coroutines. */
    private suspend fun runPumps(session: WebSocketSession): Unit = coroutineScope {
        val lastPong = MutableStateFlow(0L)

        launch { // readPump
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val resp = codec.decode(frame.readBytes())
                        val waiter = pending.remove(resp.msgIncr)
                        if (waiter != null) waiter.complete(resp) else onPushMessage(resp)
                    }
                    is Frame.Pong -> lastPong.value = nowMillis()
                    else -> Unit
                }
            }
            throw ConnectionClosedException("server closed connection")
        }

        launch { // writePump
            for (req in send) {
                withTimeout(ConnConstants.WRITE_WAIT) {
                    session.send(Frame.Binary(true, codec.encode(req)))
                }
            }
        }

        launch { // heartbeat
            lastPong.value = nowMillis()
            while (true) {
                session.send(Frame.Ping(ByteArray(0)))
                delay(ConnConstants.PING_PERIOD)
                if (nowMillis() - lastPong.value > ConnConstants.PONG_WAIT.inWholeMilliseconds) {
                    session.close()
                    throw ConnectionClosedException("pong timeout")
                }
            }
        }
    }

    companion object {
        internal fun defaultBackoff(attempt: Int): Duration {
            val capped = minOf(attempt, 6)
            return (500L shl capped).milliseconds // 1s, 2s, 4s … capped at 32s
        }
    }
}

class ConnectionClosedException(message: String) : Exception(message)

internal expect fun nowMillis(): Long
