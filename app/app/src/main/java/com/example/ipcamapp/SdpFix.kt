package com.example.ipcamapp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Hibás SDP javítása egyes (régi LIVE555-alapú) kameráknál: a `sprop-vps/sps/pps`
 * (H.265) és `sprop-parameter-sets` (H.264) értékekben a base64-kódolt NAL-egység elé
 * Annex B kezdőkódot (`00 00 00 01`) is tesznek. A Media3 RTSP ezt nyers NAL-ként
 * értelmezi, az SPS-elemzés kivételt dob, és – mivel ez nem a lejátszó saját
 * üzenetkezelőjében történik – az egész app összeomlik. Itt a kezdőkódot levágjuk.
 */
object SdpFix {
    private val SPROP = Regex("""(sprop-(?:vps|sps|pps|parameter-sets)=)([^;\s]+)""")

    /** A javított SDP; ha nincs mit javítani, változatlanul adja vissza. */
    fun fixSdp(
        sdp: String,
        decode: (String) -> ByteArray,
        encode: (ByteArray) -> String,
    ): String = SPROP.replace(sdp) { m ->
        m.groupValues[1] + m.groupValues[2].split(',').joinToString(",") { fixSet(it, decode, encode) }
    }

    private fun fixSet(b64: String, decode: (String) -> ByteArray, encode: (ByteArray) -> String): String {
        val raw = try { decode(b64) } catch (_: IllegalArgumentException) { return b64 }
        val skip = when {
            raw.size > 4 && raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 0.toByte() && raw[3] == 1.toByte() -> 4
            raw.size > 3 && raw[0] == 0.toByte() && raw[1] == 0.toByte() && raw[2] == 1.toByte() -> 3
            else -> return b64
        }
        return encode(raw.copyOfRange(skip, raw.size))
    }

    /** Androidos base64-gyel működő javító (az RTSP-kapcsolathoz). */
    val androidFix: (String) -> String = { sdp ->
        fixSdp(
            sdp,
            { android.util.Base64.decode(it, android.util.Base64.DEFAULT) },
            { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) },
        )
    }
}

/**
 * RTSP-válaszok olvasása a kameráról úgy, hogy az első SDP-törzset (DESCRIBE-válasz)
 * a [fix] javítja, és a `Content-Length` fejlécet hozzáigazítja. Az SDP után, vagy
 * az első interleaved (`$`) RTP-csomagnál, változtatás nélkül továbbengedi az adatot.
 */
class SdpFixingInputStream(
    private val src: InputStream,
    private val fix: (String) -> String,
) : InputStream() {
    private var passthrough = false
    private var pending = ByteArray(0)
    private var pos = 0

    override fun read(): Int {
        if (!fillIfNeeded()) return if (passthrough) src.read() else -1
        return pending[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fillIfNeeded()) return if (passthrough) src.read(b, off, len) else -1
        val n = minOf(len, pending.size - pos)
        System.arraycopy(pending, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int =
        if (pos < pending.size) pending.size - pos else if (passthrough) src.available() else 0

    override fun close() = src.close()

    /** true, ha a [pending] pufferben van olvasható adat. */
    private fun fillIfNeeded(): Boolean {
        if (pos < pending.size) return true
        if (passthrough) return false
        val first = src.read()
        if (first < 0) return false
        if (first == '$'.code) { // RTP over TCP: innen már nincs SDP
            passthrough = true
            setPending(byteArrayOf(first.toByte()))
            return true
        }
        // RTSP-üzenet: fejléc az üres sorig, majd Content-Length hosszú törzs
        val head = ByteArrayOutputStream().apply { write(first) }
        while (!endsWithBlankLine(head)) {
            if (head.size() > MAX_HEADER) { passthrough = true; setPending(head.toByteArray()); return true }
            val c = src.read()
            if (c < 0) { setPending(head.toByteArray()); return true }
            head.write(c)
        }
        val headText = String(head.toByteArray(), Charsets.ISO_8859_1)
        val length = CONTENT_LENGTH.find(headText)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = src.read(body, got, length - got)
            if (n < 0) throw EOFException("RTSP body truncated")
            got += n
        }
        val isSdp = headText.contains("application/sdp", ignoreCase = true)
        if (!isSdp) {
            setPending(head.toByteArray() + body)
            return true
        }
        passthrough = true // egy kapcsolaton egy SDP van
        val fixed = fix(String(body, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val newHead = CONTENT_LENGTH.replace(headText) { m -> m.groupValues[1] + fixed.size }
        setPending(newHead.toByteArray(Charsets.ISO_8859_1) + fixed)
        return true
    }

    private fun setPending(bytes: ByteArray) { pending = bytes; pos = 0 }

    private fun endsWithBlankLine(b: ByteArrayOutputStream): Boolean {
        val n = b.size()
        if (n < 4) return false
        val a = b.toByteArray()
        return a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() &&
            a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()
    }

    private companion object {
        const val MAX_HEADER = 64 * 1024
        val CONTENT_LENGTH = Regex("""(?im)^(content-length:[ \t]*)(\d+)""")
    }
}

/** Az RTSP-kapcsolat socketje az [SdpFixingInputStream]-mel (`RtspMediaSource.Factory.setSocketFactory`). */
class SdpFixingSocketFactory(private val fix: (String) -> String = SdpFix.androidFix) : SocketFactory() {
    private inner class FixSocket : Socket() {
        private var wrapped: InputStream? = null
        @Synchronized override fun getInputStream(): InputStream =
            wrapped ?: SdpFixingInputStream(super.getInputStream(), fix).also { wrapped = it }
    }

    override fun createSocket(): Socket = FixSocket()
    override fun createSocket(host: String, port: Int): Socket =
        FixSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        FixSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket =
        FixSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        FixSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}
