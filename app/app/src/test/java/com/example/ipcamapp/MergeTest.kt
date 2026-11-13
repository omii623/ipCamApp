package com.example.ipcamapp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Az import-összefésülés és a listaátrendezés tiszta logikájának tesztjei. */
class MergeTest {

    private fun cam(id: String, name: String = id, group: String = "") =
        Camera(id = id, name = name, host = "10.0.0.1", port = 554,
            type = StreamType.RTSP, path = "/s", group = group)

    // ---- kamerák összefésülése id szerint ----

    @Test fun mergeAddsNewCameras() {
        val existing = listOf(cam("1"), cam("2"))
        val result = CameraRepository.mergeById(existing, listOf(cam("3")))
        assertEquals(listOf("1", "2", "3"), result.map { it.id })
    }

    @Test fun mergeUpdatesExistingById() {
        val existing = listOf(cam("1", name = "régi"), cam("2"))
        val result = CameraRepository.mergeById(existing, listOf(cam("1", name = "új")))
        assertEquals(2, result.size)
        assertEquals("új", result.first { it.id == "1" }.name)  // frissült, nem duplikálódott
    }

    @Test fun mergeEmptyIncomingKeepsExisting() {
        val existing = listOf(cam("1"), cam("2"))
        assertEquals(existing, CameraRepository.mergeById(existing, emptyList()))
    }

    // ---- átrendezés (szűrt részhalmaz is) ----

    @Test fun reorderWithinSubsetKeepsOthersInPlace() {
        // teljes lista: A(Ház), B(Kert), C(Ház) — a "Ház" csoportot átrendezzük: C, A
        val all = listOf(cam("A", group = "Ház"), cam("B", group = "Kert"), cam("C", group = "Ház"))
        val reorderedHouse = listOf(cam("C", group = "Ház"), cam("A", group = "Ház"))
        val result = CameraRepository.applyReorder(all, reorderedHouse)
        // a Ház-pozíciókba (0 és 2) kerül C majd A; a Kert (1) marad B
        assertEquals(listOf("C", "B", "A"), result.map { it.id })
    }

    @Test fun reorderFullList() {
        val all = listOf(cam("1"), cam("2"), cam("3"))
        val result = CameraRepository.applyReorder(all, listOf(cam("3"), cam("1"), cam("2")))
        assertEquals(listOf("3", "1", "2"), result.map { it.id })
    }

    // ---- sablonok összefésülése név szerint ----

    @Test fun presetMergeAddsAndDedupesByName() {
        val existing = listOf(
            Preset.user("A", StreamType.RTSP, "/a", ""),
            Preset.user("B", StreamType.RTSP, "/b", ""),
        )
        val incoming = listOf(
            Preset.user("B", StreamType.MJPEG, "/b2", "admin"), // azonos nevű → felülír
            Preset.user("C", StreamType.RTSP, "/c", ""),
        )
        val result = PresetStore.mergeByName(existing, incoming)
        assertEquals(listOf("A", "B", "C"), result.map { it.label })
        val b = result.first { it.label == "B" }
        assertEquals(StreamType.MJPEG, b.type)  // az új B értékei érvényesek
        assertEquals("/b2", b.path)
    }
}
