package com.example.ipcamapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A saját gyártói sablonok JSON oda-vissza alakításának tesztjei. */
class PresetTest {

    @Test fun userPresetRoundTrip() {
        val p = Preset.user("Saját", StreamType.MJPEG, "/video", "admin")
        val restored = Preset.fromJson(p.toJson())
        assertEquals("Saját", restored.label)
        assertEquals(StreamType.MJPEG, restored.type)
        assertEquals("/video", restored.path)
        assertEquals("admin", restored.defaultUser)
    }

    @Test fun userPresetIsNotBuiltin() {
        assertFalse(Preset.user("X", StreamType.RTSP, "/s", "").builtin)
    }

    @Test fun fromJsonUnknownTypeFallsBackToRtsp() {
        val json = Preset.user("X", StreamType.RTSP, "/s", "").toJson().put("type", "???")
        assertEquals(StreamType.RTSP, Preset.fromJson(json).type)
    }
}
