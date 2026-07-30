package com.youtv.app.ui

internal fun resolveInitialChannelId(
    channelIds: List<String>,
    defaultChannel: Int,
    lastChannelId: String,
    legacyPosition: Int,
): String? {
    if (channelIds.isEmpty()) return null
    if (defaultChannel > 0) return channelIds.getOrNull(defaultChannel - 1) ?: channelIds.first()
    if (lastChannelId.isNotBlank()) return lastChannelId.takeIf(channelIds::contains) ?: channelIds.first()
    return channelIds.getOrNull(legacyPosition) ?: channelIds.first()
}
