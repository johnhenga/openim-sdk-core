package io.openim.core.network

import io.openim.core.conversation.SendMsgAck
import io.openim.core.conversation.SendTransport
import openim.msg.SendMsgResp
import openim.sdkws.MsgData

/**
 * [SendTransport] over the websocket envelope: MsgData protobuf in
 * GeneralWsReq.data with ReqIdentifier SendMsg (1003), ack decoded from
 * msg.SendMsgResp — the counterpart of Go's sendMsg in
 * internal/conversation_msg/api.go.
 */
class WsSendTransport(
    private val loginUserID: String,
    private val token: () -> String,
    private val sendReqWaitResp: suspend (GeneralWsReq) -> GeneralWsResp,
    private val operationID: () -> String = WsMsgSyncTransport.Companion::generateOperationID,
) : SendTransport {

    override suspend fun sendMsg(msg: MsgData): SendMsgAck {
        val resp = sendReqWaitResp(
            GeneralWsReq(
                reqIdentifier = ReqIdentifier.SEND_MSG,
                token = token(),
                sendID = loginUserID,
                operationID = operationID(),
                msgIncr = "", // assigned by the connection manager
                data = MsgData.ADAPTER.encode(msg),
            )
        )
        if (resp.errCode != 0) throw ServerException(resp.errCode, resp.errMsg)
        val ack = SendMsgResp.ADAPTER.decode(resp.data)
        return SendMsgAck(
            serverMsgID = ack.serverMsgID,
            clientMsgID = ack.clientMsgID,
            sendTime = ack.sendTime,
            modify = ack.modify,
        )
    }
}
