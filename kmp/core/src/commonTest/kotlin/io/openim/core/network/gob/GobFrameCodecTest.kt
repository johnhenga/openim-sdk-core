package io.openim.core.network.gob

import io.openim.core.network.GeneralWsReq
import io.openim.core.network.GeneralWsResp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Embedded subset of the golden vectors produced by Go's own gob encoder
 * (tools/gobgolden; full set in kmp/core/testdata/gob-golden.txt). Asserts
 * the Kotlin codec is byte-identical to internal/interaction/encoder.go.
 */
class GobFrameCodecTest {

    private val codec = GobFrameCodec(compression = false)

    @Test
    fun encodesReqIdenticallyToGo() {
        val req = GeneralWsReq(
            reqIdentifier = 1001, token = "tok-abc", sendID = "user1",
            operationID = "op-123", msgIncr = "1", data = byteArrayOf(0x01, 0x02, 0xff.toByte()),
        )
        assertEquals(GOLDEN_REQ, codec.encode(req).toHex())
    }

    @Test
    fun encodesZeroValueReqIdenticallyToGo() {
        val req = GeneralWsReq(0, "", "", "", "", ByteArray(0))
        assertEquals(GOLDEN_REQ_ZERO, codec.encode(req).toHex())
    }

    @Test
    fun decodesGoEncodedResp() {
        val resp = codec.decode(GOLDEN_RESP.hexToBytes())
        assertEquals(2002, resp.reqIdentifier)
        assertEquals(1004, resp.errCode)
        assertEquals("token expired", resp.errMsg)
        assertEquals("7", resp.msgIncr)
        assertEquals("op-x", resp.operationID)
        assertContentEquals(ByteArray(0), resp.data)
    }

    @Test
    fun respRoundTrip() {
        val resp = GeneralWsResp(
            reqIdentifier = 2001, errCode = -1, errMsg = "服务器错误",
            msgIncr = "8", operationID = "op-unicode", data = ByteArray(200) { (it % 2 * 16).toByte() },
        )
        val decoded = codec.decode(codec.encodeResp(resp))
        assertEquals(resp.reqIdentifier, decoded.reqIdentifier)
        assertEquals(resp.errCode, decoded.errCode)
        assertEquals(resp.errMsg, decoded.errMsg)
        assertContentEquals(resp.data, decoded.data)
    }

    @Test
    fun compressedFrameRoundTrip() {
        val gzCodec = GobFrameCodec(compression = true)
        val frame = Gzip.compress(
            gzCodec.encodeResp(
                GeneralWsResp(1003, 0, "", "9", "op", byteArrayOf(9))
            )
        )
        assertEquals("9", gzCodec.decode(frame).msgIncr)
    }

    private companion object {
        // tools/gobgolden REQ vector 1
        const val GOLDEN_REQ =
            "657f0301010c47656e6572616c577352657101ff80000106010d5265714964656e7469666965720104000105546f6b656e010c00" +
                "010653656e644944010c00010b4f7065726174696f6e4944010c0001074d7367496e6372010c00010444617461010a00000027ff80" +
                "01fe07d20107746f6b2d6162630105757365723101066f702d31323301013101030102ff00"

        // tools/gobgolden REQ vector 3 (all zero values)
        const val GOLDEN_REQ_ZERO =
            "657f0301010c47656e6572616c577352657101ff80000106010d5265714964656e7469666965720104000105546f6b656e010c00" +
                "010653656e644944010c00010b4f7065726174696f6e4944010c0001074d7367496e6372010c00010444617461010a00000003ff8000"

        // tools/gobgolden RESP vector 2
        const val GOLDEN_RESP =
            "69ff810301010d47656e6572616c57735265737001ff82000106010d5265714964656e7469666965720104000107457272436f6465" +
                "01040001064572724d7367010c0001074d7367496e6372010c00010b4f7065726174696f6e4944010c00010444617461010a000000" +
                "23ff8201fe0fa401fe07d8010d746f6b656e206578706972656401013701046f702d7800"
    }
}
