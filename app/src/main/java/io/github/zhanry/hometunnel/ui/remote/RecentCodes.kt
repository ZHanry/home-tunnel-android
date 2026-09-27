package io.github.zhanry.hometunnel.ui.remote

import android.content.Context

internal object RecentCodes {
    private const val FILE = "home_tunnel_ui"

    fun read(context: Context, accountKey: String): List<String> {
        if (accountKey.isBlank()) return emptyList()
        val raw = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(recentPreferenceKey(accountKey), "")
            .orEmpty()
        return rememberRecent(raw.split(','), "")
    }

    fun push(context: Context, accountKey: String, code: String) {
        if (accountKey.isBlank() || nineDigitCode(code) == null) return
        val next = rememberRecent(read(context, accountKey), code)
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(recentPreferenceKey(accountKey), next.joinToString(","))
            .apply()
    }
}
