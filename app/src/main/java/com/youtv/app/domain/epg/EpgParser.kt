package com.youtv.app.domain.epg

import android.util.Xml
import com.youtv.app.domain.model.Program
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

class EpgParser {
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())

    fun parse(inputStream: InputStream): Map<String, List<Program>> {
        val result = linkedMapOf<String, MutableList<Program>>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }
        val now = System.currentTimeMillis() / 1000
        var channel = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        parser.nextTag()
                        channel = parser.nextText()
                        result.getOrPut(channel) { mutableListOf() }
                    }
                    "programme" -> {
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        parser.nextTag()
                        val title = parser.nextText()
                        if (stop > now) result.getOrPut(channel) { mutableListOf() }
                            .add(Program(title, start, stop))
                    }
                }
            }
            parser.next()
        }
        inputStream.close()
        return result.toSortedMap(compareByDescending { it })
    }

    private fun parseTime(value: String?): Int =
        value?.let { dateFormat.parse(it)?.time?.div(1000)?.toInt() } ?: 0
}
