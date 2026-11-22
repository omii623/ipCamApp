package com.example.ipcamapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Az RTSP-kapcsolatteszt válaszfeldolgozása (valódi LIVE555 kamera válaszaival). */
class ConnectionTesterTest {

    private val unauthorized = "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nDate: Sat, Nov 14 2026 17:49:18 GMT\r\n" +
        "WWW-Authenticate: Digest realm=\"LIVE555 Streaming Media\", nonce=\"7a6401ac69d1ee3914ad0f40b72ec884\"\r\n\r\n"

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun statusFromFirstLineOnly() {
        assertEquals(401, ConnectionTester.rtspStatus(unauthorized))
        // a 200-as SDP-törzsben előfordul a "401" számsor – ez nem 401-es válasz
        val ok = "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\na=fmtp:96 config=0000000140010c01 401\r\n"
        assertEquals(200, ConnectionTester.rtspStatus(ok))
        assertNull(ConnectionTester.rtspStatus("HTTP/1.1 401 Unauthorized\r\n\r\n"))
        assertNull(ConnectionTester.rtspStatus(""))
    }

    @Test fun digestChallengeAnsweredCorrectly() {
        val url = "rtsp://192.168.1.183:554/mpeg4"
        val h = ConnectionTester.rtspDigest(unauthorized)!!.header("DESCRIBE", url, "admin", "123456")
        val ha1 = md5("admin:LIVE555 Streaming Media:123456")
        val expected = md5("$ha1:7a6401ac69d1ee3914ad0f40b72ec884:${md5("DESCRIBE:$url")}")
        assertTrue(h, h.startsWith("Digest "))
        assertTrue(h, h.contains("response=\"$expected\""))
        assertTrue(h, h.contains("uri=\"$url\""))
        // a régi LIVE555 az "algorithm=MD5"-öt (ha a kihívás nem kérte) nem tűri: jó jelszóra is 401
        assertTrue(h, !h.contains("algorithm"))
        assertTrue(h, h.endsWith("response=\"$expected\""))
    }

    @Test fun algorithmKeptWhenChallenged() {
        val d = DigestAuth.parse("Digest realm=\"r\", nonce=\"n\", algorithm=SHA-256, qop=\"auth\"")!!
        val h = d.header("GET", "/", "u", "p")
        assertTrue(h, h.endsWith("algorithm=SHA-256"))
        assertTrue(h, h.indexOf("response=") < h.indexOf("qop=auth"))
    }

    @Test fun digestPreferredOverBasic() {
        val both = "RTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\n" +
            "WWW-Authenticate: Digest realm=\"x\", nonce=\"n\"\r\n\r\n"
        assertNotNull(ConnectionTester.rtspDigest(both))
        assertNull(ConnectionTester.rtspDigest("RTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"x\"\r\n\r\n"))
    }
}
