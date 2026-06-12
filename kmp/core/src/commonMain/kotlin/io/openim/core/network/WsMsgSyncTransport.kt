package io.openim.core.network

import io.openim.core.sync.MsgSyncTransport
import openim.msg.GetConversationsHasReadAndMaxSeqReq
import openim.msg.GetConversationsHasReadAndMaxSeqResp
import openim.msg.GetLastMessageReq
import openim.msg.GetLastMessageResp
import openim.sdkws.GetMaxSeqReq
import openim.sdkws.GetMaxSeqResp
import openim.sdkws.MsgData
import openim.sdkws.PullMessageBySeqsReq
import openim.sdkws.PullMessageBySeqsResp
import kotlin.random.Random

/** Server-reported error on a websocket request (Go: GeneralWsResp.ErrCode). */
class ServerException(val errCode: Int, val errMsg: String) :
    Exception("server error $errCode: $errMsg")

/**
 * [MsgSyncTransport] over the websocket envelope: protobuf-encodes requests
 * into GeneralWsReq.data with the matching [ReqIdentifier], sends them via
 * [sendReqWaitResp] (normally LongConnManager::sendReqWaitResp), and decodes
 * the protobuf response — the Kotlin counterpart of Go's
 * LongConnMgr.SendReqWaitResp call sites in msg_sync.go.
 */
class WsMsgSyncTransport(
    private val loginUserID: String,
    private val token: () -> String,
    private val sendReqWaitResp: suspend (GeneralWsReq) -> GeneralWsResp,
    private val operationID: () -> String = ::generateOperationID,
) : MsgSyncTransport {

    private suspend fun request(reqIdentifier: Int, data: ByteArray): ByteArray {
        val resp = sendReqWaitResp(
            GeneralWsReq(
                reqIdentifier = reqIdentifier,
                token = token(),
                sendID = loginUserID,
                operationID = operationID(),
                msgIncr = "", // assigned by the connection manager
                data = data,
            )
        )
        if (resp.errCode != 0) throw ServerException(resp.errCode, resp.errMsg)
        return resp.data
    }

    override suspend fun getMaxSeqs(userID: String): Map<String, Long> {
        val data = request(
            ReqIdentifier.GET_NEWEST_SEQ,
            GetMaxSeqReq.ADAPTER.encode(GetMaxSeqReq(userID = userID)),
        )
        return GetMaxSeqResp.ADAPTER.decode(data).maxSeqs
    }

    override suspend fun pullMessageBySeqs(req: PullMessageBySeqsReq): PullMessageBySeqsResp {
        val data = request(
            ReqIdentifier.PULL_MSG_BY_RANGE,
            PullMessageBySeqsReq.ADAPTER.encode(req),
        )
        return PullMessageBySeqsResp.ADAPTER.decode(data)
    }

    override suspend fun getLastMessages(
        userID: String,
        conversationIDs: List<String>,
    ): Map<String, MsgData> {
        val data = request(
            ReqIdentifier.PULL_CONV_LAST_MESSAGE,
            GetLastMessageReq.ADAPTER.encode(
                GetLastMessageReq(userID = userID, conversationIDs = conversationIDs)
            ),
        )
        return GetLastMessageResp.ADAPTER.decode(data).msgs
    }

    override suspend fun getConversationsMaxSeq(
        userID: String,
        conversationIDs: List<String>,
    ): Map<String, Long> {
        val data = request(
            ReqIdentifier.GET_CONV_MAX_READ_SEQ,
            GetConversationsHasReadAndMaxSeqReq.ADAPTER.encode(
                GetConversationsHasReadAndMaxSeqReq(
                    userID = userID,
                    conversationIDs = conversationIDs,
                )
            ),
        )
        return GetConversationsHasReadAndMaxSeqResp.ADAPTER.decode(data)
            .seqs.mapValues { it.value.maxSeq }
    }

    companion object {
        /** Go: utils.OperationIDGenerator — timestamp + random suffix. */
        fun generateOperationID(): String =
            "${nowMillis()}${Random.nextLong(0, Long.MAX_VALUE)}"
    }
}
