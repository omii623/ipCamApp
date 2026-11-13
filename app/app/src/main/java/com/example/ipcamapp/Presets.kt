package com.example.ipcamapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gyártói sablon: a stream típusa, a megszokott útvonal és a gyári felhasználónév.
 * A JELSZÓT a felhasználónak kell beírnia – itt nincs jelszó.
 *
 * A felirat fix gyártónév ([label]), kivéve a generikus „Egyéni” sablont, ami
 * szövegerőforrásból ([labelRes]) jön, és így követi a nyelvet.
 * A felhasználó saját sablonokkal bővítheti a listát ([builtin] == false).
 */
data class Preset(
    val labelRes: Int?,
    val label: String?,
    val type: StreamType,
    val path: String,
    val defaultUser: String,
    val builtin: Boolean = true,
) {
    fun label(context: Context): String = labelRes?.let { context.getString(it) } ?: (label ?: "")

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", label)
        put("type", type.name)
        put("path", path)
        put("user", defaultUser)
    }

    companion object {
        fun user(name: String, type: StreamType, path: String, user: String) =
            Preset(null, name, type, path, user, builtin = false)

        fun fromJson(o: JSONObject) = user(
            o.getString("name"),
            runCatching { StreamType.valueOf(o.getString("type")) }.getOrDefault(StreamType.RTSP),
            o.optString("path"),
            o.optString("user"),
        )
    }
}

object Presets {
    // Fix (nem fordított) gyártói sablonok; az első a fordítható „Egyéni”.
    private val BUILTIN = listOf(
        Preset(R.string.preset_generic, null, StreamType.RTSP, "/stream1", ""),
        Preset(null, "Hikvision", StreamType.RTSP, "/Streaming/Channels/101", "admin"),
        Preset(null, "Hikvision (sub)", StreamType.RTSP, "/Streaming/Channels/102", "admin"),
        Preset(null, "Dahua", StreamType.RTSP, "/cam/realmonitor?channel=1&subtype=0", "admin"),
        Preset(null, "Dahua (sub)", StreamType.RTSP, "/cam/realmonitor?channel=1&subtype=1", "admin"),
        Preset(null, "Reolink", StreamType.RTSP, "/h264Preview_01_main", "admin"),
        Preset(null, "Reolink (sub)", StreamType.RTSP, "/h264Preview_01_sub", "admin"),
        Preset(null, "TP-Link Tapo", StreamType.RTSP, "/stream1", "admin"),
        Preset(null, "TP-Link Tapo (sub)", StreamType.RTSP, "/stream2", "admin"),
        Preset(null, "Axis", StreamType.RTSP, "/axis-media/media.amp", "root"),
        Preset(null, "Foscam", StreamType.RTSP, "/videoMain", "admin"),
        Preset(null, "Amcrest", StreamType.RTSP, "/cam/realmonitor?channel=1&subtype=0", "admin"),
        Preset(null, "Ubiquiti UniFi", StreamType.RTSP, "/s0", ""),
        Preset(null, "ONVIF", StreamType.RTSP, "/onvif1", "admin"),
        Preset(null, "ESP32-CAM / MJPEG", StreamType.MJPEG, "/stream", ""),
        Preset(null, "Android IP Webcam", StreamType.MJPEG, "/video", ""),
    )

    /** A beépített + a felhasználó által hozzáadott sablonok. */
    fun all(context: Context): List<Preset> = BUILTIN + PresetStore.getAll(context)

    fun labels(context: Context): List<String> = all(context).map { it.label(context) }
}

/** A felhasználó saját gyártói sablonjainak tárolása. */
object PresetStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Preset> {
        val raw = prefs(context).getString("list", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Preset.fromJson(arr.getJSONObject(it)) }
    }

    fun add(context: Context, preset: Preset) {
        val list = getAll(context).toMutableList()
        // azonos nevű felhasználói sablon felülírása
        list.removeAll { it.label == preset.label }
        list.add(preset)
        persist(context, list)
    }

    fun remove(context: Context, name: String) =
        persist(context, getAll(context).filterNot { it.label == name })

    /** Import: a saját sablonok felülírása. */
    fun replaceAll(context: Context, presets: List<Preset>) = persist(context, presets)

    /** Import: összefésülés név szerint (azonos nevű felülíródik). */
    fun addAll(context: Context, presets: List<Preset>) =
        persist(context, mergeByName(getAll(context), presets))

    /** Összefésülés név szerint: az azonos nevű régi elem kiesik, az új a végére kerül.
     *  Tiszta függvény (teszteléshez). */
    fun mergeByName(existing: List<Preset>, incoming: List<Preset>): List<Preset> {
        val list = existing.toMutableList()
        incoming.forEach { p ->
            list.removeAll { it.label == p.label }
            list.add(p)
        }
        return list
    }

    private fun persist(context: Context, list: List<Preset>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs(context).edit().putString("list", arr.toString()).apply()
    }
}
