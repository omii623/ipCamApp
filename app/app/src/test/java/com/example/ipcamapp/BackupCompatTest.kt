package com.example.ipcamapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mentésfájl verzió-kompatibilitásának tesztjei: az appnak kisebb és nagyobb
 * verziószámú fájllal is működnie kell (a mezők additívak).
 */
class BackupCompatTest {

    private fun camJson(
        id: String = "cam-1",
        extra: Map<String, Any> = emptyMap(),
        omit: Set<String> = emptySet(),
    ) = JSONObject().apply {
        if ("id" !in omit) put("id", id)
        if ("name" !in omit) put("name", "Teszt")
        if ("host" !in omit) put("host", "10.0.0.1")
        if ("port" !in omit) put("port", 554)
        if ("type" !in omit) put("type", "RTSP")
        extra.forEach { (k, v) -> put(k, v) }
    }

    private fun backup(version: Int?, cameras: List<JSONObject>, extraTop: Map<String, Any> = emptyMap()) =
        JSONObject().apply {
            put("format", "ipcam-backup")
            if (version != null) put("version", version)
            put("cameras", JSONArray().apply { cameras.forEach { put(it) } })
            extraTop.forEach { (k, v) -> put(k, v) }
        }

    @Test fun newerVersionWithUnknownFieldsStillImports() {
        // "jövőbeli" fájl: nagyobb verzió, ismeretlen kamera-mező és ismeretlen felső szekció
        val root = backup(
            version = 99,
            cameras = listOf(camJson(extra = mapOf("newFeature" to "x", "ptz" to true))),
            extraTop = mapOf("futureSection" to JSONArray()),
        )
        assertTrue(Backup.isValid(root))
        val cams = Backup.parseCameras(root)
        assertEquals(1, cams.size)
        assertEquals("Teszt", cams[0].name)   // az ismert mezők beolvasódnak, az ismeretlenek kiesnek
    }

    @Test fun olderVersionWithMissingOptionalFieldsUsesDefaults() {
        // "régi" fájl: alacsonyabb verzió, hiányzó opcionális mezők (path/user/pass/group)
        val root = backup(version = 1, cameras = listOf(camJson()))
        val cams = Backup.parseCameras(root)
        assertEquals(1, cams.size)
        assertEquals("", cams[0].path)
        assertEquals("", cams[0].group)
    }

    @Test fun missingVersionIsStillValid() {
        val root = backup(version = null, cameras = listOf(camJson()))
        assertTrue(Backup.isValid(root))
        assertEquals(1, Backup.parseCameras(root).size)
    }

    @Test fun malformedEntryIsSkippedNotFatal() {
        // az egyik kamera-bejegyzésből hiányzik a kötelező "host" → kimarad, a jó marad
        val root = backup(
            version = 1,
            cameras = listOf(camJson(id = "ok"), camJson(id = "bad", omit = setOf("host"))),
        )
        val cams = Backup.parseCameras(root)
        assertEquals(1, cams.size)
        assertEquals("ok", cams[0].id)
        assertEquals("10.0.0.1", cams[0].host)
    }

    @Test fun unknownStreamTypeFallsBackToRtsp() {
        val root = backup(version = 2, cameras = listOf(camJson(extra = mapOf("type" to "FUTURE_CODEC"))))
        val cams = Backup.parseCameras(root)
        assertEquals(StreamType.RTSP, cams[0].type)
    }

    @Test fun emptyCamerasArrayParsesToEmpty() {
        val root = backup(version = 5, cameras = emptyList())
        assertTrue(Backup.parseCameras(root).isEmpty())
    }
}
