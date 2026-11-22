package com.example.ipcamapp

import java.security.MessageDigest
import java.security.SecureRandom

/** HTTP Digest hitelesítés (RFC 7616, MD5 / SHA-256, qop=auth vagy qop nélkül). */
class DigestAuth(
    private val realm: String,
    private val nonce: String,
    private val opaque: String?,
    private val qopAuth: Boolean,
    /** A kihívásban kapott algoritmus; null, ha nem adták meg (ilyenkor MD5). */
    private val algorithm: String?,
) {
    private var nc = 0

    /**
     * Az Authorization fejléc. A `response` közvetlenül az idézőjeles mezők után jön, az
     * idézőjel nélküli mezők (algorithm, qop, nc) a végén, és az `algorithm` csak akkor,
     * ha a kihívás is tartalmazta: a régi LIVE555 RTSP-szerverek (sok olcsó kamera)
     * különben elrontják a feldolgozást, és jó jelszóra is 401-et adnak.
     */
    @Synchronized
    fun header(method: String, uri: String, user: String, pass: String): String {
        val alg = if (algorithm?.startsWith("SHA-256", true) == true) "SHA-256" else "MD5"
        val ha1 = hash(alg, "$user:$realm:$pass")
        val ha2 = hash(alg, "$method:$uri")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qopAuth) {
            val ncStr = "%08x".format(++nc)
            val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.toHex()
            val response = hash(alg, "$ha1:$nonce:$ncStr:$cnonce:auth:$ha2")
            sb.append(", response=\"$response\", cnonce=\"$cnonce\"")
            opaque?.let { sb.append(", opaque=\"$it\"") }
            sb.append(", qop=auth, nc=$ncStr")
        } else {
            sb.append(", response=\"${hash(alg, "$ha1:$nonce:$ha2")}\"")
            opaque?.let { sb.append(", opaque=\"$it\"") }
        }
        algorithm?.let { sb.append(", algorithm=$it") }
        return sb.toString()
    }

    companion object {
        /** A WWW-Authenticate fejléc feldolgozása; null, ha nem Digest kihívás. */
        fun parse(header: String): DigestAuth? {
            if (!header.trimStart().startsWith("Digest", ignoreCase = true)) return null
            val params = Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(header)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]?.split(',')?.map { it.trim() }.orEmpty()
            return DigestAuth(realm, nonce, params["opaque"], "auth" in qop, params["algorithm"])
        }

        private fun hash(alg: String, s: String) =
            MessageDigest.getInstance(alg).digest(s.toByteArray()).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
