package com.example.ipcamapp

import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class OnvifDevice(
    val host: String,
    val xaddr: String,
    val name: String,
    val hardware: String,
)

data class OnvifStream(val profileName: String, val uri: String)

class OnvifAuthException : IOException("Hibás felhasználónév vagy jelszó")

/** Minimális ONVIF kliens: WS-Discovery keresés + stream URI lekérdezés. */
object Onvif {

    private const val MULTICAST_ADDR = "239.255.255.250"
    private const val PORT = 3702

    /**
     * WS-Discovery Probe a helyi hálózatra. Blokkol [timeoutMs]-ig,
     * minden új eszközt azonnal visszaad az [onFound]-ban (háttérszálon!).
     */
    fun discover(timeoutMs: Int, onFound: (OnvifDevice) -> Unit) {
        val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>""".toByteArray()

        val seen = mutableSetOf<String>()
        MulticastSocket().use { socket ->
            socket.soTimeout = 500
            val group = InetAddress.getByName(MULTICAST_ADDR)
            // UDP elveszhet -> többször küldjük
            repeat(3) { socket.send(DatagramPacket(probe, probe.size, group, PORT)) }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(64 * 1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val xaddrs = tag(xml, "XAddrs")?.split(Regex("\\s+"))?.filter { it.startsWith("http") } ?: continue
                val host = packet.address.hostAddress ?: continue
                // ha több cím van, azt választjuk, amelyikben a válaszoló IP-je szerepel
                val xaddr = xaddrs.firstOrNull { it.contains(host) } ?: xaddrs.firstOrNull() ?: continue
                if (!seen.add(xaddr)) continue
                val scopes = tag(xml, "Scopes").orEmpty()
                onFound(OnvifDevice(
                    host = Uri.parse(xaddr).host ?: host,
                    xaddr = xaddr,
                    name = scope(scopes, "name") ?: "ONVIF kamera",
                    hardware = scope(scopes, "hardware").orEmpty(),
                ))
            }
        }
    }

    /** Lekérdezi a kamera összes profiljának stream URI-ját (fő stream, alstream…). */
    fun getStreams(device: OnvifDevice, user: String, pass: String): List<OnvifStream> {
        val caps = soap(device.xaddr, user, pass,
            """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>""")
        val mediaXaddr = Regex("""Media>\s*<(?:\w+:)?XAddr>([^<]+)<""").find(caps)?.groupValues?.get(1)?.trim()
            ?: device.xaddr

        val profilesXml = soap(mediaXaddr, user, pass,
            """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
        val profiles = Regex("""<(?:\w+:)?Profiles[^>]*token="([^"]+)"[^>]*>(.*?)</(?:\w+:)?Profiles>""",
            RegexOption.DOT_MATCHES_ALL).findAll(profilesXml).map {
            val token = it.groupValues[1]
            val name = Regex("""<(?:\w+:)?Name>([^<]*)<""").find(it.groupValues[2])?.groupValues?.get(1) ?: token
            token to name
        }.toList()

        return profiles.mapNotNull { (token, name) ->
            val resp = soap(mediaXaddr, user, pass, """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
<StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup>
<ProfileToken>${xmlEscape(token)}</ProfileToken></GetStreamUri>""")
            tag(resp, "Uri")?.let { OnvifStream(name, xmlUnescape(it.trim())) }
        }
    }

    private fun soap(url: String, user: String, pass: String, body: String): String {
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
<s:Header>${if (user.isNotEmpty()) securityHeader(user, pass) else ""}</s:Header>
<s:Body>$body</s:Body></s:Envelope>""".toByteArray()

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        conn.outputStream.use { it.write(envelope) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code == 401 || text.contains("NotAuthorized") || text.contains("FailedAuthentication")) {
            throw OnvifAuthException()
        }
        if (code >= 400) throw IOException("ONVIF hiba: HTTP $code")
        return text
    }

    /** WS-Security UsernameToken (PasswordDigest = Base64(SHA1(nonce + created + jelszó))). */
    private fun securityHeader(user: String, pass: String): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val sha = MessageDigest.getInstance("SHA-1").run {
            update(nonce); update(created.toByteArray()); update(pass.toByteArray()); digest()
        }
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken>""" +
            "<Username>${xmlEscape(user)}</Username>" +
            """<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${b64(sha)}</Password>""" +
            """<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${b64(nonce)}</Nonce>""" +
            """<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>""" +
            "</UsernameToken></Security>"
    }

    private fun tag(xml: String, name: String): String? =
        Regex("""<(?:\w+:)?$name(?:\s[^>]*)?>(.*?)</(?:\w+:)?$name>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    private fun scope(scopes: String, key: String): String? =
        scopes.split(Regex("\\s+"))
            .firstOrNull { it.contains("onvif.org/$key/") }
            ?.substringAfter("/$key/")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Stream URI -> előre kitöltött kamera (a szerkesztő ablakhoz). */
    fun toCamera(name: String, uri: String, user: String, pass: String): Camera {
        val u = Uri.parse(uri)
        val scheme = u.scheme?.lowercase()
        val lowerPath = (u.path ?: "").lowercase()
        val type = when {
            scheme == "rtsp" -> StreamType.RTSP
            lowerPath.endsWith(".m3u8") -> StreamType.HLS
            lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.contains("snapshot") -> StreamType.SNAPSHOT
            else -> StreamType.MJPEG
        }
        val port = if (u.port > 0) u.port else type.defaultPort
        val path = (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "")
        return Camera(name = name, host = u.host ?: "", port = port, type = type,
            path = path, username = user, password = pass)
    }
}
