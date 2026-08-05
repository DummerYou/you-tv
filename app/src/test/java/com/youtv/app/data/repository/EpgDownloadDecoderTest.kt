package com.youtv.app.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpgDownloadDecoderTest {
    @Test
    fun copiesPlainXml() {
        val xml = "<tv><channel id=\"cctv1\"/></tv>".toByteArray()

        assertArrayEquals(xml, decode(xml, 1_024L))
    }

    @Test
    fun decompressesGzipXml() {
        val xml = "<tv><channel id=\"cctv1\"/></tv>".toByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(xml) }
        }.toByteArray()

        assertArrayEquals(xml, decode(compressed, 1_024L))
    }

    @Test
    fun limitsDecompressedSize() {
        val xml = ByteArray(128) { 'x'.code.toByte() }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(xml) }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            decode(compressed, 64L)
        }
    }

    private fun decode(input: ByteArray, maxBytes: Long): ByteArray =
        ByteArrayOutputStream().also { output ->
            EpgDownloadDecoder.copyXml(ByteArrayInputStream(input), output, maxBytes)
        }.toByteArray()
}
