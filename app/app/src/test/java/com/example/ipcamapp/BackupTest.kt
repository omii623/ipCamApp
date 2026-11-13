package com.example.ipcamapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A mentés (Backup) és a QR-tartalom oda-vissza alakításának tesztjei. */
class BackupTest {

    private fun cam(name: String, port: Int) = Camera(
        name = name, host = "192.168.0.$port", port = port, type = StreamType.RTSP,
        path = "/stream1", username = "admin", password = "pw-$port", group = "Ház",
    )

    @Test fun backupRoundTripCameras() {
        val cameras = listOf(cam("A", 11), cam("B", 12))
        val root = Backup.build(cameras, emptyList(), JSONObject())
        val restored = Backup.parseCameras(root)
        assertEquals(2, restored.size)
        assertEquals("A", restored[0].name)
        assertEquals("pw-11", restored[0].password)    // a jelszó nyíltan megmarad
        assertEquals("Ház", restored[0].group)
        assertEquals(12, restored[1].port)
    }

    @Test fun backupRoundTripPresets() {
        val presets = listOf(
            Preset.user("Saját1", StreamType.MJPEG, "/video", "admin"),
            Preset.user("Saját2", StreamType.RTSP, "/s", ""),
        )
        val root = Backup.build(emptyList(), presets, JSONObject())
        val restored = Backup.parsePresets(root)
        assertEquals(listOf("Saját1", "Saját2"), restored.map { it.label })
        assertEquals(StreamType.MJPEG, restored[0].type)
    }

    @Test fun isValidAcceptsOwnFormat() {
        val root = Backup.build(emptyList(), emptyList(), JSONObject())
        assertTrue(Backup.isValid(root))
    }

    @Test fun isValidRejectsForeignJson() {
        assertFalse(Backup.isValid(JSONObject().put("hello", "world")))
    }

    @Test fun emptyArraysParseToEmptyLists() {
        val root = JSONObject().put("format", "ipcam-backup")
        assertTrue(Backup.parseCameras(root).isEmpty())
        assertTrue(Backup.parsePresets(root).isEmpty())
    }

    // ---- QR (egy kamera) ----

    @Test fun qrRoundTripKeepsFieldsButNewId() {
        val original = cam("Bejárat", 20)
        val text = Qr.buildCamera(original)
        val restored = Qr.parseCamera(text)
        assertNotNull(restored)
        restored!!
        assertEquals("Bejárat", restored.name)
        assertEquals("pw-20", restored.password)
        assertEquals(original.host, restored.host)
        // új id, hogy a fogadó eszközön ne írjon felül meglévőt
        assertFalse(original.id == restored.id)
    }

    @Test fun qrRejectsInvalidText() {
        assertNull(Qr.parseCamera("not a qr"))
        assertNull(Qr.parseCamera(JSONObject().put("format", "valami-mas").toString()))
    }
}
