package com.example.ipcamapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1: a jelszó nem veszhet el csendben, ha a titkosítás / visszafejtés nem sikerül. */
class PasswordStorageTest {
    private val cam = Camera(id = "c1", name = "Kapu", host = "10.0.0.2", port = 554,
        type = StreamType.RTSP, path = "/s", username = "admin", password = "titok")

    // „titkosítás”: egyszerű megfordítás, a hibát null jelzi
    private val encOk: (String) -> String? = { "ENC:" + it.reversed() }
    private val decOk: (String) -> String? = { it.removePrefix("ENC:").reversed() }
    private val fail: (String) -> String? = { null }

    @Test fun roundTripEncrypted() {
        val stored = CameraRepository.encodeEntry(cam, null, encOk)
        assertTrue(stored.getBoolean("pw_enc"))
        assertFalse(stored.getString("password").contains("titok"))
        val (back, locked) = CameraRepository.decodeEntry(stored, decOk)
        assertEquals("titok", back.password)
        assertNull(locked)
    }

    @Test fun undecryptablePasswordIsKeptNotWiped() {
        val stored = CameraRepository.encodeEntry(cam, null, encOk)
        // másik telefon: nem fejthető vissza
        val (back, locked) = CameraRepository.decodeEntry(stored, fail)
        assertEquals("", back.password)
        assertEquals(stored.getString("password"), locked)
        // visszamentéskor az eredeti titkosított adat marad meg, NEM üres
        val again = CameraRepository.encodeEntry(back, locked, encOk)
        assertEquals(stored.getString("password"), again.getString("password"))
        assertTrue(again.getBoolean("pw_enc"))
    }

    @Test fun newPasswordReplacesLockedOne() {
        val stored = CameraRepository.encodeEntry(cam, null, encOk)
        val (back, locked) = CameraRepository.decodeEntry(stored, fail)
        val fixed = CameraRepository.encodeEntry(back.copy(password = "uj"), locked, encOk)
        assertEquals("uj", CameraRepository.decodeEntry(fixed, decOk).first.password)
    }

    @Test fun encryptionFailureStoresPlainInsteadOfLosing() {
        val stored = CameraRepository.encodeEntry(cam, null, fail)
        assertFalse(stored.getBoolean("pw_enc"))
        assertEquals("titok", CameraRepository.decodeEntry(stored, fail).first.password)
    }

    @Test fun legacyPlainEntryStillReadable() {
        val legacy = JSONObject(cam.toJson().toString()) // pw_enc nélkül, nyílt jelszóval
        assertEquals("titok", CameraRepository.decodeEntry(legacy, fail).first.password)
    }

    @Test fun emptyPasswordNeedsNoCrypto() {
        val noPw = cam.copy(password = "")
        val stored = CameraRepository.encodeEntry(noPw, null, fail)
        val (back, locked) = CameraRepository.decodeEntry(stored, fail)
        assertEquals("", back.password)
        assertNull(locked)
    }
}
