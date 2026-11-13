package com.example.ipcamapp

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** A kiválasztott téma (világos/sötét/rendszer) betöltése indításkor. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings.nightMode(this))
        // A nyelvet az AppCompat automatikusan visszatölti (autoStoreLocales a manifestben).
    }
}

/** A beállítások tárolása és alkalmazása. */
object Settings {
    private const val PREFS = "settings"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_PORTS = "search_ports"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Alapértelmezés: sötét (a korábbi megjelenés megtartásához). */
    fun nightMode(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT, AppCompatDelegate.MODE_NIGHT_YES)

    /** Téma mentése és azonnali alkalmazása (az aktivitások újraindulnak). */
    fun setNightMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_NIGHT, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Egyéni kiemelőszín ARGB-ben; 0 = nincs beállítva (a téma alap színe). */
    fun accentColor(context: Context): Int = prefs(context).getInt(KEY_ACCENT, 0)

    fun setAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT, color).apply()
    }

    /** A kamerakereső alapértelmezett portjai (a keresőoldal ezt tölti ki). */
    fun searchPorts(context: Context): String =
        prefs(context).getString(KEY_PORTS, null) ?: "554, 8554"

    fun setSearchPorts(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PORTS, value).apply()
    }
}
