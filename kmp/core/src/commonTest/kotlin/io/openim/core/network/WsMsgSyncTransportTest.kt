package io.openim.core.network

import kotlinx.coroutines.test.runTest
import openim.sdkws.GetMaxSeqReq
import openim.sdkws.GetMaxSeqResp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** End-to-end envelope check: protobuf in GeneralWsReq.data, errCode mapping. */
class WsMsgSyncTransportTest {

    @Test
    fun encodesRequestAndDecodesResponse() = runTest {
        var captured: GeneralWsReq? = null
        val transport = WsMsgSyncTransport(
            loginUserID = "u1",
            token = { "tok" },
            sendReqWaitResp = { req ->
                captured = req
                GeneralWsResp(
                    reqIdentifier = req.reqIdentifier,
                    errCode = 0,
                    errMsg = "",
                    msgIncr = "1",
                    operationID = req.operationID,
                    data = GetMaxSeqResp.ADAPTER.encode(
                        GetMaxSeqResp(maxSeqs = mapOf("si_a" to 5L))
                    ),
                )
            },
            operationID = { "op-1" },
        )

        val maxSeqs = transport.getMaxSeqs("u1")
        assertEquals(mapOf("si_a" to 5L), maxSeqs)

        val req = captured!!
        assertEquals(ReqIdentifier.GET_NEWEST_SEQ, req.reqIdentifier)
        assertEquals("tok", req.token)
        assertEquals("u1", req.sendID)
        assertEquals("op-1", req.operationID)
        assertEquals("u1", GetMaxSeqReq.ADAPTER.decode(req.data).userID)
    }

    @Test
    fun serverErrorBecomesServerException() = runTest {
        val transport = WsMsgSyncTransport(
            loginUserID = "u1",
            token = { "tok" },
            sendReqWaitResp = { req ->
                GeneralWsResp(req.reqIdentifier, 1004, "token expired", "1", req.operationID, ByteArray(0))
            },
        )
        val e = assertFailsWith<ServerException> { transport.getMaxSeqs("u1") }
        assertEquals(1004, e.errCode)
        assertEquals("token expired", e.errMsg)
    }
}
