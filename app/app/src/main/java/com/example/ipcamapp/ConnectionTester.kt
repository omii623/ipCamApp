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
            if (rtspStatus(resp) == 401 && cam.username.isNotEmpty()) {
                // Digest, ha a kamera azt kéri (a legtöbb csak ezt fogadja el), különben Basic
                val auth = rtspDigest(resp)?.header("DESCRIBE", url, cam.username, cam.password)
                    ?: ("Basic " + Base64.encodeToString(
                        "${cam.username}:${cam.password}".toByteArray(), Base64.NO_WRAP))
                resp = describe(2, auth)
            }
            when (rtspStatus(resp)) {
                401 -> Result.AuthFailed
                null -> if (resp.isEmpty()) Result.Unreachable
                    else Result.Other(resp.lineSequence().firstOrNull()?.trim() ?: "?")
                else -> Result.Ok // válaszol RTSP-vel (200, vagy pl. 404 rossz útvonalnál): a szerver él
            }
        }
    } catch (_: IOException) {
        Result.Unreachable
    } catch (e: Exception) {
        Result.Other(e.message ?: e.javaClass.simpleName)
    }

    /** Az RTSP-válasz státuszkódja az első sorból (nem a törzsben keresve); null, ha nem RTSP. */
    internal fun rtspStatus(resp: String): Int? =
        Regex("""^RTSP/\d\.\d (\d{3})""").find(resp)?.groupValues?.get(1)?.toIntOrNull()

    /** A 401-es válasz Digest-kihívása (ha több WWW-Authenticate fejléc van, a Digest-et választja). */
    internal fun rtspDigest(resp: String): DigestAuth? =
        Regex("""(?im)^WWW-Authenticate:[ \t]*(.+?)\r?$""").findAll(resp)
            .firstNotNullOfOrNull { DigestAuth.parse(it.groupValues[1]) }
}
