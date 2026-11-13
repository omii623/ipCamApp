package com.example.ipcamapp

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** IP tartomány + portok átvizsgálása nyitott portok után. */
object PortScanner {

    val DEFAULT_PORTS = listOf(554, 8554)
    val RTSP_PORTS = setOf(554, 8554)
    const val MAX_ADDRESSES = 4096

    /** A telefon saját IPv4 címe a Wi-Fi/Ethernet hálózaton, vagy null. */
    fun localIpv4(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
    }

    /** Alapértelmezett tartomány a saját IP alapján, pl. "192.168.0.1-254". */
    fun defaultRange(self: Inet4Address): String {
        val b = self.address
        return "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.1-254"
    }

    /**
     * Érvénytelen tartomány / port. A szöveg fordítható erőforrás ([res] + [args]),
     * a megjelenítéshez: [message] (Context-tel).
     */
    class RangeException(val res: Int, vararg val args: Any) : Exception("range error") {
        fun message(context: android.content.Context): String = context.getString(res, *args)
    }

    /**
     * Tartomány értelmezése. Elfogadott formák (vesszővel több is megadható):
     *   192.168.0.50
     *   192.168.0.10-50
     *   192.168.0.10-192.168.1.20
     *   192.168.0.0/24
     */
    fun parseRange(text: String): List<String> {
        val result = LinkedHashSet<Long>()
        for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val (from, to) = when {
                part.contains('/') -> {
                    val (ip, bitsStr) = part.split('/', limit = 2)
                    val bits = bitsStr.toIntOrNull()?.takeIf { it in 16..32 }
                        ?: throw RangeException(R.string.scan_bad_mask, bitsStr)
                    val base = ipToLong(ip)
                    val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                    val net = base and mask
                    val bcast = net or (mask.inv() and 0xFFFFFFFFL)
                    // hálózati és broadcast cím kihagyása, ha van mit
                    if (bits <= 30) (net + 1) to (bcast - 1) else net to bcast
                }
                part.contains('-') -> {
                    val (a, b) = part.split('-', limit = 2).map { it.trim() }
                    val start = ipToLong(a)
                    val end = if (b.contains('.')) ipToLong(b) else {
                        val last = b.toIntOrNull()?.takeIf { it in 0..255 }
                            ?: throw RangeException(R.string.scan_bad_range_end, b)
                        (start and 0xFFFFFF00L) or last.toLong()
                    }
                    if (end < start) throw RangeException(R.string.scan_range_reversed, part)
                    start to end
                }
                else -> ipToLong(part).let { it to it }
            }
            if (to - from + 1 + result.size > MAX_ADDRESSES) {
                throw RangeException(R.string.scan_range_too_big, MAX_ADDRESSES)
            }
            for (v in from..to) result.add(v)
        }
        if (result.isEmpty()) throw RangeException(R.string.scan_range_empty)
        return result.map(::longToIp)
    }

    /** "554, 8554 8080" -> [554, 8554, 8080] */
    fun parsePorts(text: String): List<Int> {
        val ports = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.map {
            it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw RangeException(R.string.scan_bad_port, it)
        }.distinct()
        if (ports.isEmpty()) throw RangeException(R.string.scan_ports_empty)
        if (ports.size > 10) throw RangeException(R.string.scan_ports_too_many, 10)
        return ports
    }

    private fun ipToLong(ip: String): Long {
        val p = ip.trim().split('.')
        if (p.size != 4) throw RangeException(R.string.scan_bad_ip, ip)
        return p.fold(0L) { acc, s ->
            val n = s.toIntOrNull()?.takeIf { it in 0..255 } ?: throw RangeException(R.string.scan_bad_ip, ip)
            (acc shl 8) or n.toLong()
        }
    }

    private fun longToIp(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /**
     * Blokkoló keresés. [onFound] háttérszálon hívódik (ip, port),
     * [onProgress] a kész címek számát adja (0..ips.size).
     */
    fun scan(
        ips: List<String>,
        ports: List<Int>,
        onFound: (String, Int) -> Unit,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val pool = Executors.newFixedThreadPool(64)
        val done = AtomicInteger()
        for (ip in ips) {
            pool.execute {
                if (!isCancelled()) {
                    for (port in ports) {
                        if (isCancelled()) break
                        if (isOpen(ip, port)) onFound(ip, port)
                    }
                }
                onProgress(done.incrementAndGet())
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
    }

    private fun isOpen(ip: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS); true }
    } catch (e: Exception) {
        android.util.Log.v("PortScanner", "$ip:$port -> ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** Wi-Fi-n az alvó kamerák / lassú hálózatok miatt nem érdemes ennél rövidebbre venni. */
    private const val CONNECT_TIMEOUT_MS = 800
}
