package com.example.ipcamapp

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

/**
 * [label]: technikai név (RTSP, HTTP MJPEG…), nem fordítjuk; ha [labelRes] meg van adva,
 * a megjelenített felirat abból jön ([label] (Context)), így követi a nyelvet.
 */
enum class StreamType(
    val label: String, val defaultPort: Int, val defaultPath: String, val labelRes: Int? = null,
) {
    RTSP("RTSP", 554, "/stream1"),
    MJPEG("HTTP MJPEG", 80, "/video.mjpg"),
    SNAPSHOT("HTTP JPEG", 80, "/snapshot.jpg"),
    HLS("HLS (m3u8)", 80, "/index.m3u8");

    fun label(context: android.content.Context): String = labelRes?.let(context::getString) ?: label
}

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val type: StreamType,
    val path: String,
    val username: String = "",
    val password: String = "",
    /** Csoport neve (pl. "Ház", "Udvar"); üres = nincs csoportban. */
    val group: String = "",
) {
    /** Az URL hitelesítő adatok nélkül (megjelenítéshez). */
    fun displayUrl(): String = buildUrl(includeCredentials = false)

    fun buildUrl(includeCredentials: Boolean): String {
        // HTTPS: ha a címet "https://"-sel írták be, vagy a port 443 (sok nyilvános kamera csak így érhető el)
        val secure = host.startsWith("https://", ignoreCase = true) || port == 443
        val h = host.substringAfter("://").trimEnd('/')
        val scheme = when (type) {
            StreamType.RTSP -> "rtsp"
            else -> if (secure) "https" else "http"
        }
        val p = if (path.startsWith("/")) path else "/$path"
        val auth = if (includeCredentials && username.isNotEmpty()) {
            Uri.encode(username) + ":" + Uri.encode(password) + "@"
        } else ""
        return "$scheme://$auth$h:$port$p"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("type", type.name); put("path", path)
        put("username", username); put("password", password)
        put("group", group)
    }

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.getString("id"),
            name = o.getString("name"),
            host = o.getString("host"),
            port = o.getInt("port"),
            type = runCatching { StreamType.valueOf(o.getString("type")) }.getOrDefault(StreamType.RTSP),
            path = o.optString("path"),
            username = o.optString("username"),
            password = o.optString("password"),
            group = o.optString("group"),
        )
    }
}
