package com.youtv.app.domain.model

import java.util.Locale

class EpgChannelLookup(guide: EpgGuide) {
    private val programs = guide.programs.entries
        .map { canonicalName(it.key) to it.value }
        .filter { it.first.isNotBlank() }
    private val logos = guide.logos.entries
        .map { canonicalName(it.key) to it.value }
        .filter { it.first.isNotBlank() }
    private val exactPrograms = programs.associate { it.first to it.second }
    private val exactLogos = logos.associate { it.first to it.second }

    fun programsFor(channelName: String): List<Program> =
        find(channelName, exactPrograms, programs).orEmpty()

    fun logoFor(channelName: String): String =
        find(channelName, exactLogos, logos).orEmpty()

    private fun <T> find(
        channelName: String,
        exact: Map<String, T>,
        candidates: List<Pair<String, T>>,
    ): T? {
        val key = canonicalName(channelName)
        exact[key]?.let { return it }
        if (key.length < MIN_FUZZY_KEY_LENGTH || '+' in key) return null
        val matches = candidates.filter { (candidate, _) ->
            '+' !in candidate && candidate.length >= MIN_FUZZY_KEY_LENGTH &&
                (key.contains(candidate) || candidate.contains(key))
        }.map { it.second }.distinct()
        return matches.singleOrNull()
    }

    companion object {
        fun canonicalName(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace('＋', '+')
            .replace(Regex("(?:高清|超清|uhd|fhd|hd)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^\\p{IsHan}a-z0-9+]+"), "")

        private const val MIN_FUZZY_KEY_LENGTH = 3
    }
}
