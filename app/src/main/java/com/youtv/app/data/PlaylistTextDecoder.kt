package com.youtv.app.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object PlaylistTextDecoder {
    private val gb18030: Charset by lazy { Charset.forName("GB18030") }

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoded = if (hasUtf8Bom(bytes)) {
            bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        } else {
            decodeUtf8Strict(bytes) ?: gb18030.decode(ByteBuffer.wrap(bytes)).toString()
        }
        return decoded.removePrefix("\uFEFF")
    }

    private fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
