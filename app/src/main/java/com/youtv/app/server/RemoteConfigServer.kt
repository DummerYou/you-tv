package com.youtv.app.server

import android.content.Context
import com.google.gson.Gson
import com.youtv.app.R
import com.youtv.app.data.PlaylistTextDecoder
import com.youtv.app.data.repository.AppSettings
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

class RemoteConfigServer(
    private val context: Context,
    host: String,
    private val settingsProvider: () -> AppSettings,
    private val onImport: (String) -> Unit,
    private val onImportUrl: (String) -> Unit,
    private val onProxy: (String) -> Unit,
    private val onEpg: (String) -> Unit,
    private val onDefaultChannel: (Int) -> Unit,
    private val onPreference: (String, Boolean) -> Unit,
) : NanoHTTPD(host, PORT) {
    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        if (session.method !in setOf(Method.GET, Method.POST)) return methodNotAllowed()
        if (session.method == Method.POST) {
            val contentType = session.headers["content-type"].orEmpty().substringBefore(';').lowercase()
            if (contentType !in setOf("application/json", "text/plain", "application/x-www-form-urlencoded")) {
                return newFixedLengthResponse(Response.Status.UNSUPPORTED_MEDIA_TYPE, MIME_PLAINTEXT, "unsupported content type")
            }
        }
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (length > MAX_BODY_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "request too large")
        }
        return when (session.uri) {
            "/", "/index.html" -> staticPage()
            "/api/settings", "/api/v1/settings" -> if (session.method == Method.GET) settings() else methodNotAllowed()
            "/api/import-text", "/api/v1/import-text" -> if (session.method == Method.POST) importText(session) else methodNotAllowed()
            "/api/import-uri", "/api/v1/import-uri" -> if (session.method == Method.POST) importUrl(session) else methodNotAllowed()
            "/api/proxy", "/api/v1/proxy" -> if (session.method == Method.POST) updateSetting(session, "proxy") else methodNotAllowed()
            "/api/epg", "/api/v1/epg" -> if (session.method == Method.POST) updateSetting(session, "epg") else methodNotAllowed()
            "/api/default-channel", "/api/v1/default-channel" -> if (session.method == Method.POST) updateDefaultChannel(session) else methodNotAllowed()
            "/api/v1/preference" -> if (session.method == Method.POST) updatePreference(session) else methodNotAllowed()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }.apply { addHeader("Cache-Control", "no-store") }
    }

    private fun settings(): Response {
        val channelText = File(context.filesDir, "playlist-text.txt")
            .takeIf(File::exists)
            ?.let { file -> runCatching { PlaylistTextDecoder.decode(file.readBytes()) }.getOrDefault("") }
            .orEmpty()
        val settings = settingsProvider()
        val body = gson.toJson(mapOf(
            "channelUri" to settings.configUrl,
            "channelText" to channelText,
            "channelDefault" to settings.defaultChannel,
            "proxy" to settings.proxy,
            "epg" to settings.epgUrl,
            "history" to emptyList<Any>(),
            "sourceMode" to settings.sourceMode.name.lowercase(),
            "playlistUpdatedAt" to settings.playlistUpdatedAt,
            "channelReversal" to settings.channelReversal,
            "channelNumber" to settings.channelNumber,
            "showTime" to settings.showTime,
            "displaySeconds" to settings.displaySeconds,
            "repeatInfo" to settings.repeatInfo,
            "defaultFavorite" to settings.defaultFavorite,
            "showAllChannels" to settings.showAllChannels,
            "compactMenu" to settings.compactMenu,
            "softDecode" to settings.softDecode,
            "bootStartup" to settings.bootStartup,
        ))
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun importText(session: IHTTPSession): Response {
        val body = PlaylistTextDecoder.decode(readBodyBytes(session))
        if (body.isBlank()) return badRequest("empty playlist")
        onImport(body)
        return ok()
    }

    private fun importUrl(session: IHTTPSession): Response {
        val request = parseJsonBody(session) ?: return badRequest("invalid json")
        val uri = request["uri"] as? String ?: return badRequest("uri missing")
        val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
        if (scheme !in setOf("http", "https")) return badRequest("unsupported uri")
        onImportUrl(uri)
        return ok()
    }

    private fun updateSetting(session: IHTTPSession, field: String): Response {
        val request = parseJsonBody(session) ?: return badRequest("invalid json")
        when (field) {
            "proxy" -> (request["proxy"] as? String)?.let(onProxy) ?: return badRequest("proxy missing")
            "epg" -> (request["epg"] as? String)?.let(onEpg) ?: return badRequest("epg missing")
        }
        return ok()
    }

    private fun updateDefaultChannel(session: IHTTPSession): Response {
        val request = parseJsonBody(session) ?: return badRequest("invalid json")
        val channel = (request["channel"] as? Number)?.toInt() ?: return badRequest("channel missing")
        if (channel < 0) return badRequest("invalid channel")
        onDefaultChannel(channel)
        return ok()
    }

    private fun updatePreference(session: IHTTPSession): Response {
        val request = parseJsonBody(session) ?: return badRequest("invalid json")
        val key = request["key"] as? String ?: return badRequest("key missing")
        val value = request["value"] as? Boolean ?: return badRequest("value missing")
        if (key !in ALLOWED_PREFERENCES) return badRequest("unsupported preference")
        onPreference(key, value)
        return ok()
    }

    private fun parseJsonBody(session: IHTTPSession): Map<String, Any?>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(readUtf8Body(session), Map::class.java) as Map<String, Any?>
    }.getOrNull()

    private fun readUtf8Body(session: IHTTPSession): String =
        readBodyBytes(session).toString(StandardCharsets.UTF_8)

    private fun readBodyBytes(session: IHTTPSession): ByteArray {
        val length = session.headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY_BYTES.toInt()) ?: 0
        if (length <= 0) return ByteArray(0)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(bytes, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == bytes.size) bytes else bytes.copyOf(offset)
    }

    private fun staticPage(): Response {
        val html = context.resources.openRawResource(R.raw.index).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun ok() = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
    private fun badRequest(message: String) = newFixedLengthResponse(
        Response.Status.BAD_REQUEST, "application/json", gson.toJson(mapOf("success" to false, "error" to message)),
    )
    private fun methodNotAllowed() = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "method not allowed")

    companion object {
        const val PORT = 34567
        const val SESSION_MILLIS = 10 * 60 * 1000L
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private val ALLOWED_PREFERENCES = setOf(
            "channelReversal", "channelNumber", "showTime", "displaySeconds", "repeatInfo",
            "defaultFavorite", "showAllChannels", "compactMenu", "softDecode", "bootStartup",
        )
    }
}
