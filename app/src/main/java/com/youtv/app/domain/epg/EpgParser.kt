package com.youtv.app.domain.epg

import android.util.Xml
import com.youtv.app.domain.model.EpgGuide
import com.youtv.app.domain.model.Program
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

class EpgParser {
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())

    fun parse(inputStream: InputStream): EpgGuide = inputStream.use { stream ->
        val programs = linkedMapOf<String, MutableList<Program>>()
        val logos = linkedMapOf<String, String>()
        val channelNames = linkedMapOf<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }
        val now = System.currentTimeMillis() / 1000
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> parseChannel(parser)?.let { channel ->
                        channelNames[channel.id] = channel.name
                        programs.getOrPut(channel.name) { mutableListOf() }
                        if (channel.logo.isNotBlank()) logos[channel.name] = channel.logo
                    }
                    "programme" -> {
                        val rawChannel = parser.getAttributeValue(null, "channel").orEmpty()
                        val channelName = channelNames[rawChannel] ?: rawChannel
                        val program = parseProgramme(parser, now)
                        if (channelName.isNotBlank() && program != null) {
                            programs.getOrPut(channelName) { mutableListOf() }.add(program)
                        }
                    }
                }
            }
            parser.next()
        }
        EpgGuide(
            programs = programs.toSortedMap(compareByDescending { it }).mapValues { it.value.toList() },
            logos = logos.toMap(),
        )
    }

    private fun parseChannel(parser: XmlPullParser): EpgChannel? {
        val id = parser.getAttributeValue(null, "id").orEmpty()
        val depth = parser.depth
        var name = ""
        var logo = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "channel") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "display-name" -> if (name.isBlank()) name = parser.nextText().trim()
                "icon" -> logo = parser.getAttributeValue(null, "src").orEmpty().ifBlank { logo }
            }
        }
        val channelName = name.ifBlank { id }
        return channelName.takeIf(String::isNotBlank)?.let { EpgChannel(id.ifBlank { it }, it, logo) }
    }

    private fun parseProgramme(parser: XmlPullParser, now: Long): Program? {
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        val depth = parser.depth
        var title = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "programme") break
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title" && title.isBlank()) {
                title = parser.nextText().trim()
            }
        }
        return title.takeIf { it.isNotBlank() && stop > now }?.let { Program(it, start, stop) }
    }

    private fun parseTime(value: String?): Int =
        value?.let { dateFormat.parse(it)?.time?.div(1000)?.toInt() } ?: 0

    private data class EpgChannel(val id: String, val name: String, val logo: String)
}
