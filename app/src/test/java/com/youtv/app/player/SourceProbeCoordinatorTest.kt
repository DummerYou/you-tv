package com.youtv.app.player

import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.StreamSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceProbeCoordinatorTest {
    @Test
    fun rawHttpProbeCachesReachableCandidateWithoutReadingWholeStream() {
        val hits = AtomicInteger()
        TestHttpServer().use { server ->
            server.respond("/stream") {
                hits.incrementAndGet()
                ByteArray(256 * 1024) { 0x47 }
            }
            val channel = channelFor(server, "/stream")
            val coordinator = SourceProbeCoordinator()
            try {
                coordinator.schedule(channel, 0, listOf(1), delayMillis = 0)
                assertTrue(waitUntil { coordinator.bestSuccessfulCandidate(channel, listOf(1)) == 1 })
                assertEquals(1, hits.get())
            } finally {
                coordinator.release()
            }
        }
    }

    @Test
    fun hlsProbeReadsManifestAndFirstSegment() {
        val segmentHits = AtomicInteger()
        TestHttpServer().use { server ->
            server.respond("/live.m3u8") {
                "#EXTM3U\n#EXTINF:2,\nsegment.ts\n".toByteArray()
            }
            server.respond("/segment.ts") {
                segmentHits.incrementAndGet()
                ByteArray(4096) { 0x47 }
            }
            val channel = channelFor(server, "/live.m3u8")
            val coordinator = SourceProbeCoordinator()
            try {
                coordinator.schedule(channel, 0, listOf(1), delayMillis = 0)
                assertTrue(waitUntil { coordinator.bestSuccessfulCandidate(channel, listOf(1)) == 1 })
                assertEquals(1, segmentHits.get())
            } finally {
                coordinator.release()
            }
        }
    }

    @Test
    fun sameUrlWithDifferentHeadersDoesNotShareProbeCache() {
        val hits = AtomicInteger()
        TestHttpServer().use { server ->
            server.respond("/headers") {
                hits.incrementAndGet()
                ByteArray(4096) { 0x47 }
            }
            val url = "http://127.0.0.1:${server.port}/headers"
            val channel = Channel(
                id = "headers",
                name = "headers",
                sources = listOf(
                    StreamSource("http://127.0.0.1:1/current"),
                    StreamSource(url, headers = mapOf("Token" to "a")),
                    StreamSource(url, headers = mapOf("Token" to "b")),
                ),
            )
            val coordinator = SourceProbeCoordinator()
            try {
                coordinator.schedule(channel, 0, listOf(1), delayMillis = 0)
                assertTrue(waitUntil { coordinator.bestSuccessfulCandidate(channel, listOf(1)) == 1 })
                assertEquals(null, coordinator.bestSuccessfulCandidate(channel, listOf(2)))
                coordinator.schedule(channel, 0, listOf(2), delayMillis = 0)
                assertTrue(waitUntil { coordinator.bestSuccessfulCandidate(channel, listOf(2)) == 2 })
                assertEquals(2, hits.get())
            } finally {
                coordinator.release()
            }
        }
    }

    @Test
    fun candidateLimitRestrictsConcurrentProbes() {
        val firstHits = AtomicInteger()
        val secondHits = AtomicInteger()
        TestHttpServer().use { firstServer ->
            TestHttpServer().use { secondServer ->
                firstServer.respond("/first") {
                    firstHits.incrementAndGet()
                    ByteArray(4096) { 0x47 }
                }
                secondServer.respond("/second") {
                    secondHits.incrementAndGet()
                    ByteArray(4096) { 0x47 }
                }
                val channel = Channel(
                    id = "limited",
                    name = "limited",
                    sources = listOf(
                        StreamSource("http://127.0.0.1:1/current"),
                        StreamSource("http://127.0.0.1:${firstServer.port}/first"),
                        StreamSource("http://localhost:${secondServer.port}/second"),
                    ),
                )
                val coordinator = SourceProbeCoordinator()
                try {
                    coordinator.schedule(
                        channel = channel,
                        currentSourceIndex = 0,
                        orderedCandidates = listOf(1, 2),
                        delayMillis = 0,
                        candidateLimit = 1,
                    )
                    assertTrue(waitUntil {
                        coordinator.bestSuccessfulCandidate(channel, listOf(1, 2)) == 1
                    })
                    assertEquals(1, firstHits.get())
                    assertEquals(0, secondHits.get())
                } finally {
                    coordinator.release()
                }
            }
        }
    }

    private fun channelFor(server: TestHttpServer, path: String): Channel = Channel(
        id = "test",
        name = "test",
        sources = listOf(
            StreamSource("http://127.0.0.1:1/current"),
            StreamSource("http://127.0.0.1:${server.port}$path"),
        ),
    )

    private fun waitUntil(block: () -> Boolean): Boolean {
        repeat(60) {
            if (block()) return true
            Thread.sleep(50)
        }
        return false
    }

    private class TestHttpServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val handlers = ConcurrentHashMap<String, () -> ByteArray>()
        private val executor = Executors.newCachedThreadPool()

        val port: Int get() = server.localPort

        init {
            executor.execute {
                while (!server.isClosed) {
                    runCatching { server.accept() }
                        .onSuccess { socket -> executor.execute { handle(socket) } }
                }
            }
        }

        fun respond(path: String, body: () -> ByteArray) {
            handlers[path] = body
        }

        private fun handle(socket: Socket) {
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val requestLine = reader.readLine().orEmpty()
                var line: String?
                do {
                    line = reader.readLine()
                } while (!line.isNullOrEmpty())
                val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                val provider = handlers[path]
                val body = provider?.invoke() ?: "not found".toByteArray()
                val status = if (provider == null) "404 Not Found" else "200 OK"
                val output = it.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n" +
                            "Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
                )
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }
}
