package com.example.ipcamapp

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mentés/visszaállítás: kamerák + saját sablonok + beállítások egyetlen JSON-ben.
 * A jelszavak NYÍLTAN kerülnek a fájlba (a Keystore-kulcs eszközhöz kötött, nem hordozható) –
 * a felhasználó export előtt figyelmeztetést kap. Importkor a mentés újratitkosítja őket.
 *
 * A tiszta (Context nélküli) `build`/`parse*`/`isValid` függvények unit-tesztelhetők.
 */
object Backup {
    private const val FORMAT = "ipcam-backup"
    private const val VERSION = 1

    // ---- tiszta (Context nélküli) rész ----

    fun build(cameras: List<Camera>, presets: List<Preset>, settings: JSONObject): JSONObject =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("cameras", JSONArray().apply { cameras.forEach { put(it.toJson()) } })
            put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
            put("settings", settings)
        }

    /**
     * Érvényesség: csak a formátumot nézzük, a verziószámot NEM.
     * Így kisebb és nagyobb verziószámú fájl is betölthető (a mezők additívak:
     * az ismeretlen mezőket a parse figyelmen kívül hagyja, a hiányzókat alapértékkel tölti).
     */
    fun isValid(root: JSONObject): Boolean = root.optString("format") == FORMAT

    fun parseCameras(root: JSONObject): List<Camera> {
        val arr = root.optJSONArray("cameras") ?: return emptyList()
        // egy hibás/értelmezhetetlen bejegyzés ne döntse el az egész importot
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { Camera.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    fun parsePresets(root: JSONObject): List<Preset> {
        val arr = root.optJSONArray("presets") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { Preset.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    // ---- Context-réteg ----

    /** Az aktuális adatok összegyűjtése exporthoz. */
    fun export(context: Context): JSONObject {
        val cameras = CameraRepository(context).getAll() // visszafejtett jelszavakkal
        val presets = PresetStore.getAll(context)
        val settings = JSONObject().apply {
            put("night_mode", Settings.nightMode(context))
            put("accent", Settings.accentColor(context))
            put("search_ports", Settings.searchPorts(context))
            put("language", AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
        }
        return build(cameras, presets, settings)
    }

    /** A mentés visszaállítása. [replace] = felülírás, egyébként összefésülés. */
    fun import(context: Context, root: JSONObject, replace: Boolean) {
        val cameras = parseCameras(root)
        val presets = parsePresets(root)
        val repo = CameraRepository(context)
        if (replace) {
            repo.replaceAll(cameras)
            PresetStore.replaceAll(context, presets)
        } else {
            repo.addOrUpdateAll(cameras)
            PresetStore.addAll(context, presets)
        }
        root.optJSONObject("settings")?.let { s ->
            if (s.has("night_mode")) Settings.setNightMode(context, s.getInt("night_mode"))
            if (s.has("accent")) Settings.setAccentColor(context, s.getInt("accent"))
            if (s.has("search_ports")) Settings.setSearchPorts(context, s.getString("search_ports"))
            val lang = s.optString("language")
            val locales = if (lang.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                          else LocaleListCompat.forLanguageTags(lang)
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
