package io.openim.core.network.gob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * gzip via zlib (windowBits 15+16 selects the gzip wrapper), matching
 * Go compress/gzip framing. Behavior is covered by the golden vectors in
 * kmp/core/testdata/gob-golden.txt once iOS tests run in CI.
 */
@OptIn(ExperimentalForeignApi::class)
actual object Gzip {
    private const val GZIP_WINDOW_BITS = 15 + 16
    private const val CHUNK = 16 * 1024

    actual fun compress(data: ByteArray): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        check(
            deflateInit2(
                strm.ptr, /* level (default) = */ -1, Z_DEFLATED,
                GZIP_WINDOW_BITS, /* memLevel = */ 8, Z_DEFAULT_STRATEGY,
            ) == Z_OK
        ) { "deflateInit2 failed" }
        try {
            run(strm.ptr, data, isDeflate = true)
        } finally {
            deflateEnd(strm.ptr)
        }
    }

    actual fun decompress(data: ByteArray): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        check(inflateInit2(strm.ptr, GZIP_WINDOW_BITS) == Z_OK) { "inflateInit2 failed" }
        try {
            run(strm.ptr, data, isDeflate = false)
        } finally {
            inflateEnd(strm.ptr)
        }
    }

    private fun run(strm: CPointer<z_stream>, input: ByteArray, isDeflate: Boolean): ByteArray {
        val out = ArrayList<Byte>(maxOf(input.size, 64))
        val buffer = ByteArray(CHUNK)
        // usePinned cannot take the address of an empty array.
        val src = if (input.isEmpty()) ByteArray(1) else input
        src.usePinned { pinnedIn ->
            buffer.usePinned { pinnedOut ->
                strm.pointed.next_in = pinnedIn.addressOf(0).reinterpret()
                strm.pointed.avail_in = input.size.convert()
                while (true) {
                    strm.pointed.next_out = pinnedOut.addressOf(0).reinterpret()
                    strm.pointed.avail_out = CHUNK.convert()
                    val flush = if (isDeflate) Z_FINISH else Z_NO_FLUSH
                    val rc = if (isDeflate) deflate(strm, flush) else inflate(strm, flush)
                    check(rc == Z_OK || rc == Z_STREAM_END) { "zlib error: $rc" }
                    val produced = CHUNK - strm.pointed.avail_out.toInt()
                    for (i in 0 until produced) out.add(buffer[i])
                    if (rc == Z_STREAM_END) break
                    if (strm.pointed.avail_in.toInt() == 0 && produced == 0) {
                        error(if (isDeflate) "deflate stalled" else "truncated gzip stream")
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
