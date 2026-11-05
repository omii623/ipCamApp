package com.example.ipcamapp

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.Base64
import androidx.appcompat.widget.AppCompatImageView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start(url: String, user: String, pass: String, snapshotMode: Boolean, intervalMs: Long = 1000) {
        stop()
        running = true
        worker = Thread {
            var first = true
            while (running) {
                try {
                    val conn = open(url, user, pass)
                    conn.inputStream.use { input ->
                        if (snapshotMode) {
                            val bmp = BitmapFactory.decodeStream(input)
                            if (bmp != null) {
                                post { setImageBitmap(bmp) }
                                if (first) { first = false; post { onFirstFrame?.invoke() } }
                            }
                        } else {
                            readMjpeg(BufferedInputStream(input)) {
                                if (first) { first = false; post { onFirstFrame?.invoke() } }
                            }
                        }
                    }
                    conn.disconnect()
                    if (snapshotMode) Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    if (!running) return@Thread
                    post { onError?.invoke(e.message ?: e.javaClass.simpleName) }
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun open(url: String, user: String, pass: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        if (user.isNotEmpty()) {
            val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
        return conn
    }

    private fun readMjpeg(input: InputStream, onFrame: () -> Unit) {
        val buf = ByteArrayOutputStream(64 * 1024)
        var prev = -1
        var inFrame = false
        while (running) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("A kapcsolat megszakadt")
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true
                    buf.reset(); buf.write(0xFF); buf.write(0xD8)
                }
            } else {
                buf.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    inFrame = false
                    val bytes = buf.toByteArray()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) { post { setImageBitmap(bmp) }; onFrame() }
                }
            }
            prev = b
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
