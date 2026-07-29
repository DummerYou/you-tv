package com.youtv.app.player

import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.StreamSource
import com.youtv.app.requests.HttpClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SourceProbeCoordinator {
    private data class ProbeResult(
        val success: Boolean,
        val latencyMs: Long,
        val throughputBps: Long,
        val expiresAt: Long,
    )

    private data class FetchResult(
        val bytes: ByteArray,
        val finalUrl: HttpUrl,
        val contentType: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, ProbeResult>()
    private val hostCooldown = ConcurrentHashMap<String, Long>()
    private var activeJob: Job? = null

    fun schedule(
        channel: Channel,
        currentSourceIndex: Int,
        orderedCandidates: List<Int>,
        delayMillis: Long,
    ) {
        cancelActive()
        activeJob = scope.launch {
            delay(delayMillis)
            val candidates = selectCandidates(channel, currentSourceIndex, orderedCandidates)
            coroutineScope {
                candidates.map { index ->
                    async { probeAndCache(channel.sources[index]) }
                }.awaitAll()
            }
        }
    }

    fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
    }

    fun release() {
        cancelActive()
        scope.cancel()
        cache.clear()
        hostCooldown.clear()
    }

    fun bestSuccessfulCandidate(channel: Channel, candidates: List<Int>): Int? {
        val now = nowMillis()
        return candidates.mapNotNull { index ->
            val result = cache[channel.sources[index].url]
                ?.takeIf { it.success && it.expiresAt > now }
                ?: return@mapNotNull null
            Triple(index, result.latencyMs, result.throughputBps)
        }.sortedWith(compareBy<Triple<Int, Long, Long>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first
    }

    fun isHostCoolingDown(source: StreamSource): Boolean {
        val host = source.url.toHttpUrlOrNull()?.host ?: return false
        return (hostCooldown[host] ?: 0L) > nowMillis()
    }

    private fun selectCandidates(
        channel: Channel,
        currentSourceIndex: Int,
        orderedCandidates: List<Int>,
    ): List<Int> {
        val now = nowMillis()
        val selectedHosts = mutableSetOf<String>()
        return buildList {
            orderedCandidates.forEach { index ->
                if (size >= MAX_CONCURRENT_PROBES || index == currentSourceIndex) return@forEach
                val source = channel.sources.getOrNull(index) ?: return@forEach
                val url = source.url.toHttpUrlOrNull() ?: return@forEach
                if (url.scheme !in HTTP_SCHEMES || !selectedHosts.add(url.host)) return@forEach
                if ((hostCooldown[url.host] ?: 0L) > now) return@forEach
                if (cache[source.url]?.expiresAt?.let { it > now } == true) return@forEach
                add(index)
            }
        }
    }

    private suspend fun probeAndCache(source: StreamSource) {
        val startedAt = nowMillis()
        val url = source.url.toHttpUrlOrNull() ?: return
        var transferredBytes = 0L
        try {
            withTimeout(PROBE_TIMEOUT_MILLIS) {
                if (url.encodedPath.lowercase().endsWith(".m3u8")) {
                    var manifest = fetch(url, source.headers, MANIFEST_LIMIT_BYTES)
                    transferredBytes += manifest.bytes.size
                    var text = manifest.bytes.toString(Charsets.UTF_8)
                    if (!text.contains("#EXTM3U", ignoreCase = true)) {
                        throw ProbeContentException()
                    }
                    if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val variantUrl = resolveFirstMediaUri(manifest.finalUrl, text)
                            ?: throw ProbeContentException()
                        manifest = fetch(variantUrl, source.headers, MANIFEST_LIMIT_BYTES)
                        transferredBytes += manifest.bytes.size
                        text = manifest.bytes.toString(Charsets.UTF_8)
                    }
                    val segmentUrl = resolveFirstMediaUri(manifest.finalUrl, text)
                        ?: throw ProbeContentException()
                    val segment = fetch(segmentUrl, source.headers, STREAM_LIMIT_BYTES)
                    transferredBytes += segment.bytes.size
                    if (!isUsefulPayload(segment)) throw ProbeContentException()
                } else {
                    val data = fetch(url, source.headers, STREAM_LIMIT_BYTES)
                    transferredBytes += data.bytes.size
                    if (!isUsefulPayload(data)) throw ProbeContentException()
                }
            }
            val elapsed = (nowMillis() - startedAt).coerceAtLeast(1L)
            cache[source.url] = ProbeResult(
                success = true,
                latencyMs = elapsed,
                throughputBps = transferredBytes * 8_000L / elapsed,
                expiresAt = nowMillis() + RESULT_TTL_MILLIS,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            cache[source.url] = ProbeResult(
                success = false,
                latencyMs = nowMillis() - startedAt,
                throughputBps = 0L,
                expiresAt = nowMillis() + RESULT_TTL_MILLIS,
            )
            val connectTimeout = error is SocketTimeoutException &&
                error.message.orEmpty().contains("connect", ignoreCase = true)
            if (error is ConnectException || error is UnknownHostException ||
                error is NoRouteToHostException || connectTimeout
            ) {
                hostCooldown[url.host] = nowMillis() + HOST_COOLDOWN_MILLIS
            }
        }
    }

    private suspend fun fetch(
        url: HttpUrl,
        headers: Map<String, String>,
        limitBytes: Int,
    ): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${limitBytes - 1}")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = HttpClient.getProbeClientWithProxy().newCall(request).awaitResponse()
        return response.use {
            if (!it.isSuccessful) throw ProbeContentException()
            val body = it.body ?: throw ProbeContentException()
            val bytes = body.byteStream().use { input ->
                val output = ByteArray(limitBytes)
                var total = 0
                while (total < limitBytes) {
                    val read = input.read(output, total, limitBytes - total)
                    if (read < 0) break
                    total += read
                }
                output.copyOf(total)
            }
            FetchResult(
                bytes = bytes,
                finalUrl = it.request.url,
                contentType = body.contentType()?.toString().orEmpty(),
            )
        }
    }

    private fun resolveFirstMediaUri(baseUrl: HttpUrl, manifest: String): HttpUrl? =
        manifest.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?.let(baseUrl::resolve)

    private fun isUsefulPayload(result: FetchResult): Boolean {
        if (result.bytes.size < MIN_USEFUL_BYTES) return false
        if (result.contentType.contains("text/html", ignoreCase = true)) return false
        val prefix = result.bytes.take(32).toByteArray().toString(Charsets.UTF_8).trimStart()
        return !prefix.startsWith("<html", ignoreCase = true) &&
            !prefix.startsWith("<!doctype html", ignoreCase = true)
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private class ProbeContentException : IOException()

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
        const val MAX_CONCURRENT_PROBES = 2
        const val PROBE_TIMEOUT_MILLIS = 2_500L
        const val RESULT_TTL_MILLIS = 120_000L
        const val HOST_COOLDOWN_MILLIS = 60_000L
        const val MANIFEST_LIMIT_BYTES = 64 * 1024
        const val STREAM_LIMIT_BYTES = 128 * 1024
        const val MIN_USEFUL_BYTES = 1024
    }
}
