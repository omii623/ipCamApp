package com.example.ipcamapp

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

enum class StreamType(val label: String, val defaultPort: Int, val defaultPath: String) {
    RTSP("RTSP", 554, "/stream1"),
    MJPEG("HTTP MJPEG", 80, "/video.mjpg"),
    SNAPSHOT("HTTP JPEG (képfrissítés)", 80, "/snapshot.jpg"),
    HLS("HLS (m3u8)", 80, "/index.m3u8");
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
        val scheme = if (type == StreamType.RTSP) "rtsp" else "http"
        val p = if (path.startsWith("/")) path else "/$path"
        val auth = if (includeCredentials && username.isNotEmpty()) {
            Uri.encode(username) + ":" + Uri.encode(password) + "@"
        } else ""
        return "$scheme://$auth$host:$port$p"
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
