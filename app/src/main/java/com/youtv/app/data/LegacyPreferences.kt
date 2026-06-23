package com.youtv.app.data

import android.content.Context

class LegacyPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        context.getString(com.youtv.app.R.string.app_name),
        Context.MODE_PRIVATE,
    )

    val position: Int = preferences.getInt("position", 0)
    val favoriteIndexes: Set<Int> = preferences.getStringSet("like", emptySet())
        .orEmpty().mapNotNull(String::toIntOrNull).toSet()
}
