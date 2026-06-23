package com.youtv.app.domain.playlist

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.annotation.Keep
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.ChannelGroup
import com.youtv.app.domain.model.ImportIssue
import com.youtv.app.domain.model.ImportReport
import com.youtv.app.domain.model.StreamSource
import java.net.URI
import java.security.MessageDigest
import java.util.LinkedHashMap

enum class PlaylistFormat { AUTO, TXT, M3U, JSON }

class PlaylistParser(private val gson: Gson = Gson()) {
    fun parse(content: String, format: PlaylistFormat = PlaylistFormat.AUTO): ImportReport {
        val text = content.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return emptyReport("频道列表为空")
        return when (resolveFormat(text, format)) {
            PlaylistFormat.TXT -> parseTxt(text)
            PlaylistFormat.M3U -> parseM3u(text)
            PlaylistFormat.JSON -> parseJson(text)
            PlaylistFormat.AUTO -> error("AUTO must be resolved")
        }
    }

    private fun parseTxt(text: String): ImportReport {
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>()
        val issues = mutableListOf<ImportIssue>()
        var currentGroup = ""
        var updateMetadataGroup = false
        var skipCurrentGroup = false
        var updatedAt: String? = null
        var sourceCount = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.contains("#genre#")) {
                currentGroup = line.substringBefore(',').trim()
                updateMetadataGroup = currentGroup.contains("更新时间")
                skipCurrentGroup = updateMetadataGroup || currentGroup.contains("无结果")
                return@forEachIndexed
            }
            val separator = line.indexOf(',')
            if (separator <= 0 || separator == line.lastIndex) {
                if (!skipCurrentGroup) issues += ImportIssue(index + 1, "缺少频道名称或播放地址")
                return@forEachIndexed
            }
            val title = line.substring(0, separator).trim()
            if (updateMetadataGroup) {
                if (updatedAt == null && title.isNotEmpty()) updatedAt = title
                return@forEachIndexed
            }
            if (skipCurrentGroup) return@forEachIndexed
            val urls = line.substring(separator + 1).split(',').map(String::trim).filter(String::isNotEmpty)
            val valid = urls.filter { isSupportedUri(it) }
            if (title.isEmpty() || valid.isEmpty()) {
                issues += ImportIssue(index + 1, "播放地址无效")
                return@forEachIndexed
            }
            val channel = groups.getOrPut(currentGroup) { LinkedHashMap() }
                .getOrPut(title) { MutableChannel(title, title, currentGroup) }
            valid.forEach { channel.sources += StreamSource(it, order = channel.sources.size) }
            sourceCount += valid.size
        }
        return buildReport(groups, sourceCount, issues, updatedAt = updatedAt)
    }

    private fun parseM3u(text: String): ImportReport {
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>()
        val issues = mutableListOf<ImportIssue>()
        var current: MutableChannel? = null
        var headers = linkedMapOf<String, String>()
        var epgUrl: String? = null
        var sourceCount = 0

        fun flush() {
            val item = current ?: return
            if (item.sources.isNotEmpty()) {
                val target = groups.getOrPut(item.group) { LinkedHashMap() }
                    .getOrPut(item.name) { item.copy(sources = mutableListOf()) }
                item.sources.forEach { target.sources += it.copy(order = target.sources.size) }
                sourceCount += item.sources.size
            }
            current = null
            headers = linkedMapOf()
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U", true) -> epgUrl = attribute(line, "x-tvg-url")
                line.startsWith("#EXTINF", true) -> {
                    flush()
                    val title = line.substringAfterLast(',').trim()
                    current = MutableChannel(
                        name = attribute(line, "tvg-name").orEmpty().ifEmpty { title },
                        title = title,
                        group = attribute(line, "group-title").orEmpty(),
                        logo = attribute(line, "tvg-logo").orEmpty(),
                        number = attribute(line, "tvg-chno")?.toIntOrNull() ?: -1,
                    )
                }
                line.startsWith("#EXTVLCOPT:http-", true) -> {
                    val pair = line.substringAfter("#EXTVLCOPT:http-").split('=', limit = 2)
                    if (pair.size == 2) headers[pair[0]] = pair[1]
                }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    if (current == null || !isSupportedUri(line)) {
                        issues += ImportIssue(index + 1, "播放地址缺少 EXTINF 或格式无效")
                    } else {
                        current?.sources?.add(StreamSource(line, headers.toMap()))
                    }
                }
            }
        }
        flush()
        return buildReport(groups, sourceCount, issues, epgUrl)
    }

    private fun parseJson(text: String): ImportReport {
        val type = object : TypeToken<List<JsonChannel>>() {}.type
        val items = runCatching { gson.fromJson<List<JsonChannel>>(text, type) }
            .getOrElse { return emptyReport("JSON 格式错误：${it.message}") }
        val groups = LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>()
        val issues = mutableListOf<ImportIssue>()
        var sourceCount = 0
        items.forEachIndexed { index, item ->
            val title = item.title.orEmpty().ifEmpty { item.name.orEmpty() }
            val name = item.name.orEmpty().ifEmpty { title }
            val valid = item.uris.orEmpty().filter(::isSupportedUri)
            if (title.isEmpty() || valid.isEmpty()) {
                issues += ImportIssue(index + 1, "频道名称或播放地址无效")
                return@forEachIndexed
            }
            val group = item.group.orEmpty()
            val target = groups.getOrPut(group) { LinkedHashMap() }
                .getOrPut(name) { MutableChannel(name, title, group, item.logo.orEmpty(), item.number ?: -1) }
            valid.forEach { target.sources += StreamSource(it, item.headers.orEmpty(), target.sources.size) }
            sourceCount += valid.size
        }
        return buildReport(groups, sourceCount, issues)
    }

    private fun buildReport(
        source: LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>,
        sourceCount: Int,
        issues: List<ImportIssue>,
        epgUrl: String? = null,
        updatedAt: String? = null,
    ): ImportReport {
        val groups = source.map { (groupName, channels) ->
            ChannelGroup(groupName.ifEmpty { "未知" }, channels.values.map { value ->
                Channel(
                    id = stableId(groupName, value.name),
                    name = value.name,
                    title = value.title,
                    group = groupName,
                    logo = value.logo,
                    number = value.number,
                    sources = value.sources.toList(),
                )
            })
        }
        val channelCount = groups.sumOf { it.channels.size }
        return ImportReport(
            groups, channelCount, (sourceCount - channelCount).coerceAtLeast(0),
            issues.size, issues, epgUrl, updatedAt,
        )
    }

    private fun resolveFormat(text: String, requested: PlaylistFormat): PlaylistFormat = when {
        requested != PlaylistFormat.AUTO -> requested
        text.startsWith('[') -> PlaylistFormat.JSON
        text.startsWith("#EXTM3U", true) -> PlaylistFormat.M3U
        else -> PlaylistFormat.TXT
    }

    private fun isSupportedUri(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https", "rtsp", "rtmp", "file", "content")
    }.getOrDefault(false)

    private fun attribute(line: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}="([^"]*)""", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.trim()

    private fun stableId(group: String, name: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$group\u0000$name".toByteArray())
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun emptyReport(message: String) = ImportReport(emptyList(), 0, 0, 1, listOf(ImportIssue(0, message)))

    private data class MutableChannel(
        val name: String,
        val title: String,
        val group: String,
        val logo: String = "",
        val number: Int = -1,
        val sources: MutableList<StreamSource> = mutableListOf(),
    )

    @Keep
    private data class JsonChannel(
        val group: String? = null,
        val name: String? = null,
        val title: String? = null,
        val logo: String? = null,
        val number: Int? = null,
        val uris: List<String>? = null,
        val headers: Map<String, String>? = null,
    )
}
