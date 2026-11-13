package com.example.ipcamapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A kamera-modell tesztjei: URL-építés és JSON oda-vissza alakítás. */
class CameraTest {

    private fun cam(
        type: StreamType = StreamType.RTSP,
        port: Int = 554,
        path: String = "/stream1",
        user: String = "",
        pass: String = "",
        group: String = "",
    ) = Camera(name = "Teszt", host = "192.168.0.50", port = port, type = type,
        path = path, username = user, password = pass, group = group)

    @Test fun rtspUrlScheme() {
        assertEquals("rtsp://192.168.0.50:554/stream1", cam().displayUrl())
    }

    @Test fun httpUrlScheme() {
        val c = cam(type = StreamType.MJPEG, port = 8080, path = "/video.mjpg")
        assertEquals("http://192.168.0.50:8080/video.mjpg", c.displayUrl())
    }

    @Test fun pathGetsLeadingSlash() {
        val c = cam(path = "stream1")
        assertEquals("rtsp://192.168.0.50:554/stream1", c.displayUrl())
    }

    @Test fun displayUrlHidesCredentials() {
        val c = cam(user = "admin", pass = "secret")
        // a megjelenített URL nem tartalmazhat jelszót
        assertFalse(c.displayUrl().contains("secret"))
        assertFalse(c.displayUrl().contains("admin"))
    }

    @Test fun jsonRoundTripPreservesAllFields() {
        val c = cam(type = StreamType.HLS, port = 8081, path = "/index.m3u8",
            user = "root", pass = "pw123", group = "Ház")
        val restored = Camera.fromJson(c.toJson())
        assertEquals(c.id, restored.id)
        assertEquals(c.name, restored.name)
        assertEquals(c.host, restored.host)
        assertEquals(c.port, restored.port)
        assertEquals(c.type, restored.type)
        assertEquals(c.path, restored.path)
        assertEquals(c.username, restored.username)
        assertEquals(c.password, restored.password)
        assertEquals(c.group, restored.group)
    }

    @Test fun fromJsonFillsDefaultsForMissingOptionalFields() {
        val o = JSONObject()
            .put("id", "abc")
            .put("name", "X")
            .put("host", "10.0.0.1")
            .put("port", 554)
            .put("type", "RTSP")
        val c = Camera.fromJson(o)
        assertEquals("", c.path)
        assertEquals("", c.username)
        assertEquals("", c.password)
        assertEquals("", c.group)
    }

    @Test fun fromJsonUnknownTypeFallsBackToRtsp() {
        val o = JSONObject()
            .put("id", "abc").put("name", "X").put("host", "10.0.0.1")
            .put("port", 554).put("type", "NOPE")
        assertEquals(StreamType.RTSP, Camera.fromJson(o).type)
    }

    @Test fun streamTypeDefaults() {
        assertEquals(554, StreamType.RTSP.defaultPort)
        assertEquals(80, StreamType.MJPEG.defaultPort)
        assertEquals("/stream1", StreamType.RTSP.defaultPath)
    }
}
