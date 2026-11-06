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

    /**
     * A mentés beállításai, előre beolvasva és ellenőrizve. null mező = a mentésben nincs ilyen,
     * azt a beállítást NEM módosítjuk (pl. régi mentés nyelv nélkül ne állítsa vissza a nyelvet).
     */
    data class ImportedSettings(
        val nightMode: Int? = null,
        val accent: Int? = null,
        val searchPorts: String? = null,
        /** "" = rendszer nyelve, "hu"/"en" = választott nyelv, null = ne változzon */
        val language: String? = null,
    )

    /**
     * A beállítások beolvasása MIELŐTT bármit írnánk: hibás érték (pl. szám helyett szöveg)
     * esetén itt dob kivételt, így az import semmit nem módosít (nem marad félkész állapot).
     */
    fun parseSettings(root: JSONObject): ImportedSettings {
        val s = root.optJSONObject("settings") ?: return ImportedSettings()
        return ImportedSettings(
            nightMode = if (s.has("night_mode")) s.getInt("night_mode") else null,
            accent = if (s.has("accent")) s.getInt("accent") else null,
            searchPorts = if (s.has("search_ports")) s.getString("search_ports") else null,
            language = if (s.has("language")) s.getString("language") else null,
        )
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
        // 1. minden beolvasása és ellenőrzése (hiba esetén itt áll meg, még semmi nem változott)
        val cameras = parseCameras(root)
        val presets = parsePresets(root)
        val settings = parseSettings(root)
        // 2. írás
        val repo = CameraRepository(context)
        if (replace) {
            repo.replaceAll(cameras)
            PresetStore.replaceAll(context, presets)
        } else {
            repo.addOrUpdateAll(cameras)
            PresetStore.addAll(context, presets)
        }
        settings.nightMode?.let { Settings.setNightMode(context, it) }
        settings.accent?.let { Settings.setAccentColor(context, it) }
        settings.searchPorts?.let { Settings.setSearchPorts(context, it) }
        settings.language?.let { lang ->
            AppCompatDelegate.setApplicationLocales(
                if (lang.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(lang))
        }
    }
}
