package com.example.ipcamapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** #6 és #7: a mentés beállításainak beolvasása visszaállítás előtt. */
class BackupSettingsTest {
    private fun backup(settings: String?) = JSONObject(
        """{"format":"ipcam-backup","version":1,"cameras":[]""" +
            (if (settings != null) ""","settings":$settings""" else "") + "}")

    // #6: régi mentés nyelv nélkül -> a nyelvet nem szabad módosítani
    @Test fun missingLanguageLeavesLanguageUntouched() {
        val s = Backup.parseSettings(backup("""{"night_mode":2,"accent":0}"""))
        assertNull(s.language)
        assertEquals(2, s.nightMode)
    }

    @Test fun emptyLanguageMeansSystemLanguage() {
        assertEquals("", Backup.parseSettings(backup("""{"language":""}""")).language)
    }

    @Test fun explicitLanguageIsKept() {
        assertEquals("hu", Backup.parseSettings(backup("""{"language":"hu"}""")).language)
    }

    @Test fun noSettingsBlockChangesNothing() {
        assertEquals(Backup.ImportedSettings(), Backup.parseSettings(backup(null)))
    }

    // #7: hibás érték -> már a beolvasáskor kivétel, mielőtt bármit írnánk
    @Test fun invalidValueFailsBeforeAnyWrite() {
        assertThrows(Exception::class.java) {
            Backup.parseSettings(backup("""{"night_mode":"sötét"}"""))
        }
    }

    // a saját exportunk adatai teljes egészében visszaolvashatók
    @Test fun exportedSettingsRoundTrip() {
        val settings = JSONObject().put("night_mode", 1).put("accent", -16711936)
            .put("search_ports", "554, 80").put("language", "en")
        val root = Backup.build(emptyList(), emptyList(), settings)
        assertEquals(Backup.ImportedSettings(1, -16711936, "554, 80", "en"), Backup.parseSettings(root))
    }
}
