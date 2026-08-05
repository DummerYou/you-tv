package com.youtv.app.data.repository

import android.content.Context
import com.youtv.app.domain.epg.EpgParser
import com.youtv.app.domain.model.EpgGuide
import com.youtv.app.domain.model.EpgChannelLookup
import com.youtv.app.domain.model.Program
import com.youtv.app.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

class EpgRepository(context: Context) {
    private val cache = File(context.filesDir, "epg.xml")
    private val _guide = MutableStateFlow(EpgGuide())
    val guide: StateFlow<EpgGuide> = _guide.asStateFlow()

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        if (!cache.exists() || cache.length() == 0L) return@withContext
        runCatching { EpgParser().parse(cache.inputStream()) }
            .onSuccess { _guide.value = it }
    }

    suspend fun refresh(urls: String): Boolean = withContext(Dispatchers.IO) {
        for (url in urls.split(',').map(String::trim).filter(String::isNotEmpty)) {
            val result = runCatching {
                HttpClient.getClientWithProxy().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    val temporary = File(cache.parentFile, "${cache.name}.download")
                    try {
                        temporary.outputStream().use { output ->
                            EpgDownloadDecoder.copyXml(
                                input = body.byteStream(),
                                output = output,
                                maxBytes = MAX_EPG_BYTES,
                            )
                        }
                        val parsed = temporary.inputStream().use { EpgParser().parse(it) }
                        replaceCache(temporary)
                        parsed
                    } catch (error: Exception) {
                        temporary.delete()
                        throw error
                    }
                }
            }.getOrNull()
            if (result != null) {
                _guide.value = result
                return@withContext true
            }
        }
        false
    }

    fun programsFor(channelName: String): List<Program> {
        return EpgChannelLookup(_guide.value).programsFor(channelName)
    }

    private companion object {
        const val MAX_EPG_BYTES = 64L * 1024 * 1024
    }

    private fun replaceCache(temporary: File) {
        val source = temporary.toPath()
        val target = cache.toPath()
        runCatching {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}

internal object EpgDownloadDecoder {
    fun copyXml(input: InputStream, output: OutputStream, maxBytes: Long) {
        require(maxBytes > 0L)
        decodedInput(input).use { decoded ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val read = decoded.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "EPG 文件超过大小限制" }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun decodedInput(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == GZIP_MAGIC_FIRST && second == GZIP_MAGIC_SECOND) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }
    }

    private const val GZIP_MAGIC_FIRST = 0x1f
    private const val GZIP_MAGIC_SECOND = 0x8b
}
