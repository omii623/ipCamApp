package com.example.ipcamapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.Base64
import androidx.appcompat.widget.AppCompatImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Egyszerű MJPEG / JPEG-snapshot megjelenítő.
 * MJPEG módban a folyamból kivágja a JPEG képkockákat (FFD8 ... FFD9),
 * snapshot módban adott időközönként újratölti a képet.
 */
class MjpegView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var onError: ((String) -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null

    // Az aktuális munkaszál; egy régi szál ebből látja, hogy már le kell állnia
    // (egy közös "running" jelző újraindításkor a régi szálat is életben hagyná).
    @Volatile private var worker: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null

    private fun alive() = worker === Thread.currentThread()

    fun start(url: String, user: String, pass: String, snapshotMode: Boolean, intervalMs: Long = 1000) {
        stop()
        worker = Thread {
            val first = FirstFrameSignal()
            while (alive()) {
                var c: HttpURLConnection? = null
                try {
                    c = open(url, user, pass)
                    conn = c
                    c.inputStream.use { input ->
                        if (snapshotMode) {
                            val bytes = input.readBytes()
                            pending.set(false) // állóképnél ne maradjon ki egy frissítés sem
                            decodeFrame(bytes, 0, bytes.size) {
                                if (first.onFrame()) post { onFirstFrame?.invoke() }
                            }
                        } else {
                            readMjpeg(input) {
                                if (first.onFrame()) post { onFirstFrame?.invoke() }
                            }
                        }
                    }
                    if (snapshotMode) Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (!alive()) return@Thread
                    first.onError()
                    post { onError?.invoke(e.message ?: e.javaClass.simpleName) }
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                } finally {
                    c?.disconnect() // hiba esetén se maradjon nyitva a socket
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        val w = worker
        worker = null
        w?.interrupt()
        // a blokkoló olvasást az interrupt nem szakítja meg, a kapcsolat bontása igen
        conn?.let { c -> Thread { runCatching { c.disconnect() } }.start() }
        conn = null
    }

    // Ha a kamera Digest hitelesítést kért, a következő kéréseknél rögtön azzal próbálkozunk.
    @Volatile private var digest: DigestAuth? = null

    private fun connect(url: String, auth: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10000
            auth?.let { setRequestProperty("Authorization", it) }
        }

    private fun open(url: String, user: String, pass: String): HttpURLConnection {
        val basic = if (user.isEmpty()) null
            else "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        var c = connect(url, digest?.header("GET", URL(url).file.ifEmpty { "/" }, user, pass) ?: basic)
        var code = c.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED && user.isNotEmpty()) {
            // Digest kihívás (sok IP-kamera csak ezt fogadja el)
            val challenge = c.getHeaderField("WWW-Authenticate")
            c.disconnect()
            val d = challenge?.let { DigestAuth.parse(it) }
            if (d != null) {
                digest = d
                c = connect(url, d.header("GET", URL(url).file.ifEmpty { "/" }, user, pass))
                code = c.responseCode
            }
        }
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            throw IllegalStateException("HTTP $code")
        }
        return c
    }

    /** Az MJPEG folyam képkockáinak kivágása blokkos olvasással (nem bájtonként). */
    private fun readMjpeg(input: InputStream, onFrame: () -> Unit) {
        var buf = ByteArray(256 * 1024)
        var len = 0      // érvényes bájtok a pufferben
        var start = -1   // az aktuális képkocka eleje (FFD8), -1 ha még nincs
        var scan = 1     // innen folytatjuk a keresést
        while (alive()) {
            if (buf.size - len < READ_CHUNK) {
                if (len + READ_CHUNK > MAX_FRAME) { len = 0; start = -1; scan = 1 } // hibás folyam
                else buf = buf.copyOf(maxOf(buf.size * 2, len + READ_CHUNK))
            }
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) throw IllegalStateException(context.getString(R.string.stream_disconnected))
            len += n
            var i = scan
            while (i < len) {
                if (buf[i - 1] == FF) {
                    val b = buf[i]
                    if (start < 0 && b == SOI) start = i - 1
                    else if (start >= 0 && b == EOI) {
                        decodeFrame(buf, start, i + 1 - start, onFrame)
                        // a feldolgozott rész eldobása
                        len -= i + 1
                        System.arraycopy(buf, i + 1, buf, 0, len)
                        start = -1; i = 0
                    }
                }
                i++
            }
            if (start < 0) {          // képkockán kívüli bájtok: csak az utolsó kell (fél FFD8 miatt)
                if (len > 0) { buf[0] = buf[len - 1]; len = 1 }
            } else if (start > 0) {   // a félkész képkocka a puffer elejére
                len -= start
                System.arraycopy(buf, start, buf, 0, len)
                start = 0
            }
            scan = maxOf(len, 1)
        }
    }

    /**
     * Egy JPEG dekódolása erőforrás-takarékosan:
     * - ha az előző kép még nincs kirajzolva, vagy a nézet nem látszik, kihagyjuk (nincs dekódolás),
     * - [downsample] esetén a nézet méretére kicsinyítve (inSampleSize) és RGB_565-ben (fele memória).
     */
    private fun decodeFrame(data: ByteArray, off: Int, len: Int, onFrame: () -> Unit) {
        if (!alive() || !visible || pending.get()) return
        val opts = BitmapFactory.Options()
        if (downsample) {
            val w = width; val h = height
            if (w > 0 && h > 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, off, len, bounds)
                var s = 1
                while (bounds.outWidth / (s * 2) >= w && bounds.outHeight / (s * 2) >= h) s *= 2
                opts.inSampleSize = s
            }
            opts.inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(data, off, len, opts) ?: return
        if (!alive()) return
        pending.set(true)
        post { pending.set(false); setImageBitmap(bmp) }
        onFrame()
    }

    /** Kicsinyítve dekódoljon-e (osztott nézet: igen; teljes képernyős nagyítható nézet: nem). */
    var downsample = true

    @Volatile private var visible = true
    private val pending = AtomicBoolean(false)

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visible = visibility == VISIBLE
    }

    private companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
        const val READ_CHUNK = 32 * 1024
        const val FF = 0xFF.toByte()
        const val SOI = 0xD8.toByte()
        const val EOI = 0xD9.toByte()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
