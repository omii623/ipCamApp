package com.example.ipcamapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Az IP-tartomány és port feldolgozás tesztjei (tiszta logika, Android nélkül). */
class PortScannerTest {

    // ---- parseRange ----

    @Test fun singleIp() {
        assertEquals(listOf("192.168.0.50"), PortScanner.parseRange("192.168.0.50"))
    }

    @Test fun shortDashRange() {
        val r = PortScanner.parseRange("192.168.0.10-13")
        assertEquals(listOf("192.168.0.10", "192.168.0.11", "192.168.0.12", "192.168.0.13"), r)
    }

    @Test fun fullDashRange() {
        val r = PortScanner.parseRange("192.168.0.254-192.168.1.1")
        assertEquals(listOf("192.168.0.254", "192.168.0.255", "192.168.1.0", "192.168.1.1"), r)
    }

    @Test fun cidr24SkipsNetworkAndBroadcast() {
        val r = PortScanner.parseRange("192.168.0.0/24")
        assertEquals(254, r.size)
        assertEquals("192.168.0.1", r.first())
        assertEquals("192.168.0.254", r.last())
    }

    @Test fun cidr30HasTwoUsable() {
        val r = PortScanner.parseRange("10.0.0.0/30")
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), r)
    }

    @Test fun multipleParts() {
        val r = PortScanner.parseRange("192.168.0.1, 192.168.0.5-6")
        assertEquals(listOf("192.168.0.1", "192.168.0.5", "192.168.0.6"), r)
    }

    @Test fun duplicatesAreMerged() {
        val r = PortScanner.parseRange("192.168.0.1, 192.168.0.1-2")
        assertEquals(listOf("192.168.0.1", "192.168.0.2"), r)
    }

    @Test fun badIpThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("192.168.0.300") }
    }

    @Test fun reversedRangeThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("192.168.0.10-5") }
    }

    @Test fun emptyThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("   ") }
    }

    @Test fun tooLargeThrows() {
        // /8 jóval több, mint a megengedett maximum
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("10.0.0.0/8") }
    }

    @Test fun badMaskThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parseRange("10.0.0.0/40") }
    }

    // ---- parsePorts ----

    @Test fun portsParsed() {
        assertEquals(listOf(554, 8554), PortScanner.parsePorts("554, 8554"))
    }

    @Test fun portsVariousSeparators() {
        assertEquals(listOf(80, 554, 8080), PortScanner.parsePorts("80 554,8080"))
    }

    @Test fun portsDeduplicated() {
        assertEquals(listOf(80), PortScanner.parsePorts("80, 80"))
    }

    @Test fun portOutOfRangeThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts("70000") }
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts("0") }
    }

    @Test fun nonNumericPortThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts("abc") }
    }

    @Test fun tooManyPortsThrows() {
        assertThrows(PortScanner.RangeException::class.java) {
            PortScanner.parsePorts("1,2,3,4,5,6,7,8,9,10,11")
        }
    }

    @Test fun emptyPortsThrows() {
        assertThrows(PortScanner.RangeException::class.java) { PortScanner.parsePorts(" ") }
    }

    @Test fun rtspPortsConstant() {
        assertTrue(554 in PortScanner.RTSP_PORTS)
        assertTrue(8554 in PortScanner.RTSP_PORTS)
        assertTrue(8080 !in PortScanner.RTSP_PORTS)
    }
}
