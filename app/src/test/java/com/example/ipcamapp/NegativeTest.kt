package com.example.ipcamapp

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Negatív tesztek: rossz, hiányos vagy rosszul formázott adatok kezelése. */
class NegativeTest {

    // ---- Backup.isValid ----

    @Test fun isValidRejectsEmptyObject() {
        assertFalse(Backup.isValid(JSONObject()))
    }

    @Test fun isValidRejectsWrongFormatValue() {
        assertFalse(Backup.isValid(JSONObject().put("format", "valami-mas")))
        assertFalse(Backup.isValid(JSONObject().put("format", "IPCAM-BACKUP"))) // kis/nagybetű számít
    }

    // ---- Backup.parse* rossz szerkezettel ----

    @Test fun parseCamerasWhenFieldIsNotArray() {
        val root = JSONObject().put("format", "ipcam-backup").put("cameras", "nem tömb")
        assertTrue(Backup.parseCameras(root).isEmpty())
    }

    @Test fun parseCamerasWhenFieldMissing() {
        assertTrue(Backup.parseCameras(JSONObject()).isEmpty())
    }

    @Test fun parseCamerasSkipsNonObjectElements() {
        val arr = JSONArray().put("string").put(5).put(JSONObject.NULL)
        val root = JSONObject().put("cameras", arr)
        assertTrue(Backup.parseCameras(root).isEmpty())
    }

    @Test fun parseCamerasKeepsGoodSkipsBad() {
        val good = JSONObject()
            .put("id", "ok").put("name", "X").put("host", "10.0.0.1")
            .put("port", 554).put("type", "RTSP")
        val arr = JSONArray().put(good).put("szemét").put(JSONObject().put("id", "hiányos"))
        val root = JSONObject().put("cameras", arr)
        val cams = Backup.parseCameras(root)
        assertEquals(1, cams.size)
        assertEquals("ok", cams[0].id)
    }

    @Test fun parsePresetsWhenFieldIsNotArray() {
        val root = JSONObject().put("presets", 123)
        assertTrue(Backup.parsePresets(root).isEmpty())
    }

    // ---- Qr.parseCamera rossz tartalommal ----

    @Test fun qrRejectsEmptyAndGarbage() {
        assertNull(Qr.parseCamera(""))
        assertNull(Qr.parseCamera("   "))
        assertNull(Qr.parseCamera("{ nem valid json"))
        assertNull(Qr.parseCamera("12345"))
    }

    @Test fun qrRejectsWrongFormat() {
        assertNull(Qr.parseCamera(JSONObject().put("format", "más").toString()))
    }

    @Test fun qrRejectsMissingCameraObject() {
        assertNull(Qr.parseCamera(JSONObject().put("format", "ipcam-camera").toString()))
    }

    @Test fun qrRejectsCameraMissingRequiredField() {
        val cameraWithoutHost = JSONObject().put("id", "x").put("name", "Y").put("port", 554).put("type", "RTSP")
        val text = JSONObject().put("format", "ipcam-camera").put("camera", cameraWithoutHost).toString()
        assertNull(Qr.parseCamera(text))
    }

    // ---- Camera.fromJson kötelező mezők hiányában dob ----

    @Test fun cameraFromJsonThrowsWhenRequiredMissing() {
        val missingName = JSONObject().put("id", "x").put("host", "h").put("port", 554).put("type", "RTSP")
        assertThrows(JSONException::class.java) { Camera.fromJson(missingName) }
    }

    @Test fun cameraFromJsonThrowsWhenPortNotNumeric() {
        val badPort = JSONObject()
            .put("id", "x").put("name", "N").put("host", "h").put("port", "nem szám").put("type", "RTSP")
        assertThrows(JSONException::class.java) { Camera.fromJson(badPort) }
    }

    // ---- PortScanner rossz tartományok ----

    @Test fun rangeRejectsTooFewOctets() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("192.168.0") }
    }

    @Test fun rangeRejectsTooManyOctets() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("192.168.0.1.2") }
    }

    @Test fun rangeRejectsNegativeInDashEnd() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("192.168.0.-5") }
    }

    @Test fun rangeRejectsGarbage() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("teljesen rossz") }
    }

    @Test fun rangeRejectsMaskTooSmall() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("10.0.0.0/0") }
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("10.0.0.0/15") }
    }

    @Test fun rangeRejectsNonNumericMask() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("10.0.0.0/ab") }
    }

    // ---- PortScanner rossz portok ----

    @Test fun portsRejectNegative() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts("-1") }
    }

    @Test fun portsRejectOnlySeparators() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts(",,, ;") }
    }

    @Test fun portsRejectMixedValidAndInvalid() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts("554, abc") }
    }
}
