package com.example.ipcamapp

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Egyetlen kamera elérhetőségének tesztelése a megadott egy belépéssel.
 * NEM próbálgat több jelszót – csak azt ellenőrzi, amit a felhasználó beírt.
 */
object ConnectionTester {

    sealed class Result {
        object Ok : Result()                       // elérhető, a belépés jó (vagy nem kell)
        object AuthFailed : Result()               // elérhető, de a felhasználónév/jelszó hibás
        object Unreachable : Result()              // nem lehet csatlakozni (hálózat/port)
        data class Other(val message: String) : Result()
    }

    fun test(camera: Camera): Result = when (camera.type) {
        StreamType.RTSP -> testRtsp(camera)
        else -> testHttp(camera)
    }

    private fun testHttp(cam: Camera): Result = try {
        val conn = URL(cam.buildUrl(includeCredentials = false)).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        if (cam.username.isNotEmpty()) {
            val token = Base64.encodeToString("${cam.username}:${cam.password}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        val code = conn.responseCode
        conn.disconnect()
        when (code) {
            401, 403 -> Result.AuthFailed
            in 200..399 -> Result.Ok
            else -> Result.Other("HTTP $code")
        }
    } catch (_: IOException) {
        Result.Unreachable
    } catch (e: Exception) {
        Result.Other(e.message ?: e.javaClass.simpleName)
    }

    /**
     * RTSP: a kameránál kézzel küldünk egy DESCRIBE kérést, és a válaszkódot nézzük.
     * 200 -> ok; 401 -> hibás/hiányzó belépés; egyéb -> elérhető, de más hiba.
     */
    private fun testRtsp(cam: Camera): Result = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(cam.host, cam.port), 5000)
            socket.soTimeout = 5000
            val url = cam.buildUrl(includeCredentials = false)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            fun describe(cseq: Int, auth: String?): String {
                val sb = StringBuilder("DESCRIBE $url RTSP/1.0\r\nCSeq: $cseq\r\n")
                if (auth != null) sb.append("Authorization: $auth\r\n")
                sb.append("Accept: application/sdp\r\n\r\n")
                out.write(sb.toString().toByteArray()); out.flush()
                val buf = ByteArray(4096)
                val n = inp.read(buf)
                return if (n > 0) String(buf, 0, n) else ""
            }

            var resp = describe(1, null)
            if (resp.contains("401") && cam.username.isNotEmpty()) {
                // Basic próbálkozás (a Digest-et nem építjük itt ki – az app lejátszója kezeli)
                val basic = "Basic " + Base64.encodeToString(
                    "${cam.username}:${cam.password}".toByteArray(), Base64.NO_WRAP)
                resp = describe(2, basic)
            }
            when {
                resp.startsWith("RTSP/1.0 200") || resp.startsWith("RTSP/1.0 2") -> Result.Ok
                resp.contains(" 401") -> Result.AuthFailed
                resp.startsWith("RTSP/1.0") -> Result.Ok // válaszol RTSP-vel: a szerver él
                resp.isEmpty() -> Result.Unreachable
                else -> Result.Other(resp.lineSequence().firstOrNull()?.trim() ?: "?")
            }
        }
    } catch (_: IOException) {
        Result.Unreachable
    } catch (e: Exception) {
        Result.Other(e.message ?: e.javaClass.simpleName)
    }
}
