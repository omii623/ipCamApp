package com.example.ipcamapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

/** A hibás (kezdőkódos sprop-*) SDP javításának tesztjei – valódi kamera SDP-jével. */
class SdpFixTest {

    private val fix: (String) -> String = { sdp ->
        SdpFix.fixSdp(sdp, { Base64.getDecoder().decode(it) }, { Base64.getEncoder().encodeToString(it) })
    }

    // LIVE555 v2011.05.25 alapú kamera (192.168.1.x /mpeg4), H.265 2560x1440
    private val badSdp = "v=0\r\n" +
        "m=video 0 RTP/AVP 96\r\n" +
        "a=rtpmap:96 H265/90000\r\n" +
        "a=fmtp:96 profile-space=0;profile-id=12;tier-flag=0;level-id=0;interop-constraints=60000000B000;" +
        "sprop-vps=AAAAAUABDAH//wFgAAADALAAAAMAAAMAmawJ;" +
        "sprop-sps=AAAAAUIBAQFgAAADALAAAAMAAAMAmaABQCAFoWNruTL83AQEBAI=;" +
        "sprop-pps=AAAAAUQBwPKwOzQ=;config=0000000140010c01\r\n" +
        "a=control:track1\r\n"

    private fun sprop(sdp: String, key: String): ByteArray =
        Base64.getDecoder().decode(Regex("$key=([^;\\s]+)").find(sdp)!!.groupValues[1])

    @Test fun stripsStartCodes() {
        val out = fix(badSdp)
        // a NAL-fejléc típusa: VPS=32, SPS=33, PPS=34
        assertEquals(32, (sprop(out, "sprop-vps")[0].toInt() shr 1) and 0x3F)
        assertEquals(33, (sprop(out, "sprop-sps")[0].toInt() shr 1) and 0x3F)
        assertEquals(34, (sprop(out, "sprop-pps")[0].toInt() shr 1) and 0x3F)
        assertTrue(out.contains("config=0000000140010c01")) // a többi mező marad
    }

    /** Ugyanúgy, mint a Media3 RtspMediaTrack: kezdőkód + sprop-sps, elemzés a kezdőkód után. */
    private fun media3ParseSps(sdp: String): androidx.media3.container.NalUnitUtil.H265SpsData {
        val sps = byteArrayOf(0, 0, 0, 1) + sprop(sdp, "sprop-sps")
        return androidx.media3.container.NalUnitUtil.parseH265SpsNalUnit(sps, 4, sps.size, null)
    }

    @Test fun media3ParsesFixedSps() {
        val sps = media3ParseSps(fix(badSdp))
        assertEquals(2560, sps.width)
        assertEquals(1440, sps.height)
    }

    @Test fun media3FailsOnOriginalSps() {
        // a javítás nélküli SPS-t a Media3 nem tudja értelmezni (ez döntötte le az appot)
        val ok = runCatching { media3ParseSps(badSdp) }.getOrNull()
        assertTrue(ok == null || ok.width != 2560 || ok.height != 1440)
    }

    @Test fun correctSdpUnchanged() {
        val good = fix(badSdp)
        assertEquals(good, fix(good))
        val h264 = "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM48gA==\r\n"
        assertEquals(h264, fix(h264))
    }

    @Test fun h264ParameterSetsFixed() {
        val bad = "a=fmtp:96 sprop-parameter-sets=AAAAAWdCAB+VqBQBbkA=,AAAAAWjOPIA=\r\n"
        assertEquals("a=fmtp:96 sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM48gA==\r\n", fix(bad))
    }

    @Test fun streamFixesSdpBodyAndContentLength() {
        val unauthorized = "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nWWW-Authenticate: Digest realm=\"x\", nonce=\"y\"\r\n\r\n"
        val sdpHead = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${badSdp.length}\r\n\r\n"
        val rtp = byteArrayOf('$'.code.toByte(), 0, 0, 3, 0x0D, 0x0A, 0x0D) // bináris, CRLF-fel is
        val input = (unauthorized + sdpHead + badSdp).toByteArray(Charsets.ISO_8859_1) + rtp

        val out = SdpFixingInputStream(ByteArrayInputStream(input), fix).readBytes()

        val fixedSdp = fix(badSdp)
        val expected = (unauthorized +
            "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${fixedSdp.length}\r\n\r\n" +
            fixedSdp).toByteArray(Charsets.ISO_8859_1) + rtp
        assertArrayEquals(expected, out)
    }
}
