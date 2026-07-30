package com.youtv.app.player

import com.youtv.app.domain.model.SourceIdentity
import com.youtv.app.domain.model.StreamSource

internal class PlaybackAttemptLedger {
    private val attemptedFingerprints = linkedSetOf<String>()

    val attemptedCount: Int get() = attemptedFingerprints.size

    fun reset() {
        attemptedFingerprints.clear()
    }

    fun markAttempted(source: StreamSource) {
        attemptedFingerprints += SourceIdentity.fingerprint(source)
    }

    fun hasAttempted(source: StreamSource): Boolean =
        SourceIdentity.fingerprint(source) in attemptedFingerprints

    fun remapRemaining(
        previousSources: List<StreamSource>,
        previousRemainingIndices: List<Int>,
        currentSources: List<StreamSource>,
        rankedCurrentIndices: List<Int>,
    ): List<Int> {
        val currentByFingerprint = currentSources.indices.associateBy {
            SourceIdentity.fingerprint(currentSources[it])
        }
        val queuedFingerprints = previousRemainingIndices.mapNotNull { index ->
            previousSources.getOrNull(index)?.let(SourceIdentity::fingerprint)
        }.filterNot(attemptedFingerprints::contains).distinct()
        val queued = queuedFingerprints.mapNotNull(currentByFingerprint::get)
        val queuedSet = queued.toSet()
        val appended = rankedCurrentIndices.filter { index ->
            index !in queuedSet && !hasAttempted(currentSources[index])
        }
        return queued + appended
    }
}
