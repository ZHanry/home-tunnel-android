package io.github.zhanry.hometunnel.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal object ThemePreferences {
    private const val FILE = "home_tunnel_ui"
    private const val KEY = "theme_choice"

    fun current(context: Context): ThemeChoice =
        ThemeChoice.fromStored(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))

    fun applyStored(context: Context) {
        AppCompatDelegate.setDefaultNightMode(current(context).nightMode())
    }

    fun save(context: Context, choice: ThemeChoice) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, choice.name)
            .apply()
        val mode = choice.nightMode()
        check(
            mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM ||
                mode == AppCompatDelegate.MODE_NIGHT_NO ||
                mode == AppCompatDelegate.MODE_NIGHT_YES,
        )
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
