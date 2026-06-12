package io.openim.core.network.gob

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual object Gzip {
    actual fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    actual fun decompress(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
