package com.youtv.app.data.repository

import android.content.Context
import com.youtv.app.domain.epg.EpgParser
import com.youtv.app.domain.model.EpgGuide
import com.youtv.app.domain.model.Program
import com.youtv.app.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

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
                    val bytes = response.body?.bytes() ?: return@use null
                    val parsed = EpgParser().parse(bytes.inputStream())
                    cache.writeBytes(bytes)
                    parsed
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
        val name = channelName.lowercase()
        return _guide.value.programs.entries.firstOrNull { (key, _) ->
            name.contains(key.lowercase(), ignoreCase = true)
        }?.value.orEmpty()
    }
}
