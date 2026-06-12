package io.openim.core

import io.ktor.client.HttpClient
import io.openim.core.api.EngineEventFlows
import io.openim.core.api.LoginStatus
import io.openim.core.conversation.SelfInfo
import io.openim.core.db.DriverFactory
import io.openim.core.engine.OpenIMEngine
import io.openim.core.network.ApiClient
import io.openim.core.network.ConnectionState
import io.openim.core.network.FrameCodec
import io.openim.core.network.GeneralWsResp
import io.openim.core.network.LongConnManager
import io.openim.core.network.ReqIdentifier
import io.openim.core.network.WsMsgSyncTransport
import io.openim.core.network.WsSendTransport
import io.openim.core.network.gob.GobFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import openim.sdkws.PushMessages

/** SDK configuration (Go: the InitSDK config JSON). */
data class SdkConfig(
    val apiAddr: String,
    val wsAddr: String,
    val dataDir: String,
    val platformID: Int,
    val sdkVersion: String = "kmp-dev",
    val isCompression: Boolean = false,
)

/**
 * Production composition of the SDK: the Kotlin counterpart of Go's
 * LoginMgr (internal/login.go) + InitSDK/Login. Opens the Go-compatible
 * per-user database, builds [OpenIMEngine] over websocket transports, and
 * wires the connection lifecycle:
 *
 * - ws URL carries sendID/token/platformID/operationID/sdkVersion (and
 *   compression=gzip when enabled), as long_conn_mgr.go builds it
 * - inbound PushMsg envelopes decode to sdkws.PushMessages and feed
 *   MsgSyncer; Connected state triggers the catch-up sync and the
 *   incremental server-data syncs
 *
 * The network paths reuse the verified codec/transport components; this
 * class is composition only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenIMSdk(
    private val config: SdkConfig,
    private val driverFactory: DriverFactory,
    private val httpClient: HttpClient,
    parentScope: CoroutineScope,
    private val codec: FrameCodec = GobFrameCodec(compression = config.isCompression),
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    /**
     * All engine entry points run here, serially — the engine's state is
     * dispatcher-confined (like Go's DoListener goroutine owning MsgSyncer).
     */
    private val engineDispatcher =
        kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /**
     * Pushes are queued off the read pump (Go: pushMsgAndMaxSeqCh, cap
     * 1000). Processing a push can itself issue WS requests (gap pulls);
     * running it on the read pump would deadlock waiting for the response
     * the pump can no longer read.
     */
    private val pushQueue = Channel<GeneralWsResp>(capacity = 1000)

    val events = EngineEventFlows()

    private val _loginStatus = MutableStateFlow(LoginStatus.Empty)
    val loginStatus: StateFlow<LoginStatus> = _loginStatus

    private var conn: LongConnManager? = null
    private var engineOrNull: OpenIMEngine? = null

    val engine: OpenIMEngine
        get() = engineOrNull ?: error("not logged in")

    val connectionState: StateFlow<ConnectionState>
        get() = conn?.state ?: error("not logged in")

    /** Go: Login — open DB, prime sync state, start the long connection. */
    suspend fun login(userID: String, token: String) {
        check(_loginStatus.value != LoginStatus.Logged) { "already logged in" }
        _loginStatus.value = LoginStatus.Logging

        val driver = driverFactory.createDriver(config.dataDir, userID)
        OpenIMEngine.prepareDatabase(driver)

        val connection = LongConnManager(
            scope = scope,
            httpClient = httpClient,
            wsUrl = {
                val base = "${config.wsAddr}?sendID=$userID&token=$token" +
                    "&platformID=${config.platformID}" +
                    "&operationID=${WsMsgSyncTransport.generateOperationID()}" +
                    "&isBackground=false&sdkVersion=${config.sdkVersion}"
                if (config.isCompression) "$base&compression=gzip" else base
            },
            codec = codec,
        )
        conn = connection

        val newEngine = OpenIMEngine(
            loginUserID = userID,
            platformID = config.platformID,
            driver = driver,
            msgTransport = WsMsgSyncTransport(userID, { token }, connection::sendReqWaitResp),
            sendTransport = WsSendTransport(userID, { token }, connection::sendReqWaitResp),
            api = ApiClient(httpClient, config.apiAddr, { token }),
            listener = events,
            // TODO(user module): self info from local_users with API fallback.
            selfInfo = { SelfInfo(nickname = userID, faceURL = "") },
        )
        engineOrNull = newEngine

        withContext(engineDispatcher) { newEngine.login() }

        connection.onPushMessage = { resp -> pushQueue.send(resp) }
        scope.launch(engineDispatcher) {
            for (resp in pushQueue) {
                runCatching { routePush(newEngine, resp) }
            }
        }
        scope.launch(engineDispatcher) {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected) {
                    newEngine.onConnected()
                    runCatching { newEngine.syncServerData() }
                }
            }
        }
        connection.start()
        _loginStatus.value = LoginStatus.Logged
    }

    /** Go: handleMessage push routing — PushMsg data is sdkws.PushMessages. */
    private suspend fun routePush(engine: OpenIMEngine, resp: GeneralWsResp) {
        when (resp.reqIdentifier) {
            ReqIdentifier.PUSH_MSG ->
                engine.onPushMsg(PushMessages.ADAPTER.decode(resp.data))
            else -> Unit // online status / kv pushes: later modules
        }
    }

    /** App returned to foreground (Go: CmdWakeUpDataSync). */
    suspend fun wakeUp() {
        withContext(engineDispatcher) { engine.onWakeUp() }
    }

    fun logout() {
        scope.cancel()
        conn = null
        engineOrNull = null
        _loginStatus.value = LoginStatus.Logout
    }
}
