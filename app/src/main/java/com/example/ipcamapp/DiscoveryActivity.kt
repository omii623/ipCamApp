package com.example.ipcamapp

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Kamerák keresése a helyi hálózaton:
 *  1. ONVIF WS-Discovery (név + pontos stream cím lekérdezhető)
 *  2. RTSP port keresés (554 / 8554) a /24-es hálózaton
 * A kiválasztott kamerát előre kitöltve visszaadja a MainActivity-nek.
 */
class DiscoveryActivity : AppCompatActivity() {

    sealed class Found(val host: String) {
        class Onvif(val device: OnvifDevice) : Found(device.host)
        class Port(host: String, val port: Int) : Found(host)
    }

    private val results = mutableListOf<Found>()
    private lateinit var adapter: FoundAdapter
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var rescan: Button
    private lateinit var rangeInput: TextInputEditText
    private lateinit var rangeLayout: TextInputLayout
    private lateinit var portsInput: TextInputEditText
    private lateinit var portsLayout: TextInputLayout
    private val prefs by lazy { getSharedPreferences("discovery", MODE_PRIVATE) }
    @Volatile private var cancelled = false
    private var scanning = false
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovery)
        padForSystemBars()
        setTitle(R.string.discover)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        progress = findViewById(R.id.discoverProgress)
        status = findViewById(R.id.discoverStatus)
        rescan = findViewById(R.id.rescanButton)
        rangeInput = findViewById(R.id.rangeInput)
        rangeLayout = findViewById(R.id.rangeLayout)
        portsInput = findViewById(R.id.portsInput)
        portsLayout = findViewById(R.id.portsLayout)
        adapter = FoundAdapter(results) { onPick(it) }
        findViewById<RecyclerView>(R.id.foundList).apply {
            layoutManager = LinearLayoutManager(this@DiscoveryActivity)
            adapter = this@DiscoveryActivity.adapter
        }

        // Tartomány: utoljára használt, különben a saját hálózat (pl. 192.168.0.1-254)
        val ownRange = PortScanner.localIpv4(this)?.let { PortScanner.defaultRange(it) }
        rangeInput.setText(prefs.getString(KEY_RANGE, null) ?: ownRange ?: "")
        // a keresési portok alapértéke a beállításokból jön
        portsInput.setText(Settings.searchPorts(this))
        rangeLayout.setEndIconOnClickListener {
            // "saját hálózat" gomb: visszaállítja az alapértelmezett tartományt
            ownRange?.let { rangeInput.setText(it) }
        }

        rescan.setOnClickListener { if (scanning) cancelled = true else startScan() }
        tintAccent()
        startScan()
    }

    override fun onDestroy() {
        cancelled = true
        releaseLock()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun startScan() {
        rangeLayout.error = null
        portsLayout.error = null
        val ips = try {
            PortScanner.parseRange(rangeInput.text.toString())
        } catch (e: PortScanner.RangeException) {
            rangeLayout.error = e.message; return
        }
        val ports = try {
            PortScanner.parsePorts(portsInput.text.toString())
        } catch (e: PortScanner.RangeException) {
            portsLayout.error = e.message; return
        }
        prefs.edit().putString(KEY_RANGE, rangeInput.text.toString().trim()).apply()
        // a kereséskor használt portok lesznek az új alapértelmezettek
        Settings.setSearchPorts(this, ports.joinToString(", "))
        currentFocus?.let {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(it.windowToken, 0)
        }

        cancelled = false
        scanning = true
        results.clear()
        adapter.notifyDataSetChanged()
        rescan.setText(R.string.stop)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.setText(R.string.discover_onvif)

        // Sok telefon eldobja a multicast csomagokat, ha nincs lock
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("ipcam-discovery")?.apply {
            setReferenceCounted(false); acquire()
        }

        Thread {
            // 1. ONVIF (multicast – a tartománytól függetlenül a helyi hálózatot nézi)
            try {
                Onvif.discover(3000) { dev -> runOnUiThread { add(Found.Onvif(dev)) } }
            } catch (_: Exception) { /* nincs multicast -> megy tovább a port kereséssel */ }
            releaseLock()

            // 2. Port keresés a megadott tartományban
            if (!cancelled) {
                runOnUiThread {
                    progress.isIndeterminate = false
                    progress.max = ips.size
                    progress.progress = 0
                    status.text = getString(R.string.discover_ports_range,
                        ips.first(), ips.last(), ips.size, ports.joinToString(", "))
                }
                PortScanner.scan(ips, ports,
                    onFound = { ip, port -> runOnUiThread { add(Found.Port(ip, port)) } },
                    onProgress = { n -> runOnUiThread { progress.progress = n } },
                    isCancelled = { cancelled },
                )
            }
            runOnUiThread {
                finishScan(when {
                    cancelled && !isFinishing -> getString(R.string.discover_stopped, results.size)
                    results.isEmpty() -> getString(R.string.discover_none)
                    else -> getString(R.string.discover_done, results.size)
                })
            }
        }.start()
    }

    private fun releaseLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun finishScan(msg: String) {
        scanning = false
        if (isFinishing) return
        progress.visibility = View.GONE
        status.text = msg
        rescan.setText(R.string.rescan)
    }

    private fun add(f: Found) {
        when (f) {
            is Found.Onvif -> {
                // ugyanazt a gépet ne mutassuk kétszer: az ONVIF találat az okosabb
                val idx = results.indexOfFirst { it is Found.Port && it.host == f.host }
                if (idx >= 0) { results.removeAt(idx); adapter.notifyItemRemoved(idx) }
            }
            is Found.Port -> {
                if (results.any { it is Found.Onvif && it.host == f.host }) return
                if (results.any { it is Found.Port && it.host == f.host && it.port == f.port }) return
            }
        }
        results.add(f)
        adapter.notifyItemInserted(results.size - 1)
    }

    private fun onPick(f: Found) {
        when (f) {
            is Found.Port -> {
                // RTSP port -> RTSP; más (pl. 80/8080) -> HTTP MJPEG-et feltételezünk
                val type = if (f.port in PortScanner.RTSP_PORTS) StreamType.RTSP else StreamType.MJPEG
                returnCamera(Camera(
                    name = getString(R.string.camera_default_name, f.host),
                    host = f.host, port = f.port, type = type, path = type.defaultPath,
                ))
            }
            is Found.Onvif -> askCredentials(f.device)
        }
    }

    private fun askCredentials(dev: OnvifDevice, error: String? = null) {
        val v = layoutInflater.inflate(R.layout.dialog_credentials, null)
        v.tintAccentTree(accentColor())
        val user = v.findViewById<TextInputEditText>(R.id.credUser)
        val pass = v.findViewById<TextInputEditText>(R.id.credPass)
        v.findViewById<TextView>(R.id.credError).apply {
            text = error; visibility = if (error == null) View.GONE else View.VISIBLE
        }
        AlertDialog.Builder(this)
            .setTitle(dev.name)
            .setMessage(R.string.onvif_login_hint)
            .setView(v)
            .setPositiveButton(R.string.connect) { _, _ ->
                fetchStreams(dev, user.text.toString().trim(), pass.text.toString())
            }
            .setNeutralButton(R.string.skip_onvif) { _, _ ->
                returnCamera(Camera(name = dev.name, host = dev.host, port = 554,
                    type = StreamType.RTSP, path = StreamType.RTSP.defaultPath))
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { it.tintButtonsAccent(accentColor()) }
    }

    private fun fetchStreams(dev: OnvifDevice, user: String, pass: String) {
        val wait = AlertDialog.Builder(this).setMessage(R.string.onvif_fetching).setCancelable(false).show()
        Thread {
            val result = runCatching { Onvif.getStreams(dev, user, pass) }
            runOnUiThread {
                wait.dismiss()
                result.onSuccess { streams ->
                    when (streams.size) {
                        0 -> askCredentials(dev, getString(R.string.onvif_no_stream))
                        1 -> returnCamera(Onvif.toCamera(dev.name, streams[0].uri, user, pass))
                        else -> chooseStream(dev, streams, user, pass)
                    }
                }.onFailure { e ->
                    val msg = if (e is OnvifAuthException) getString(R.string.onvif_bad_login)
                              else getString(R.string.error_stream, e.message ?: e.javaClass.simpleName)
                    askCredentials(dev, msg)
                }
            }
        }.start()
    }

    /** Több profil esetén (pl. fő stream / alstream) a felhasználó választ. */
    private fun chooseStream(dev: OnvifDevice, streams: List<OnvifStream>, user: String, pass: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_stream)
            .setItems(streams.map { "${it.profileName}\n${it.uri}" }.toTypedArray()) { _, i ->
                returnCamera(Onvif.toCamera(dev.name, streams[i].uri, user, pass))
            }
            .show()
    }

    private fun returnCamera(cam: Camera) {
        cancelled = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CAMERA, cam.toJson().toString()))
        finish()
    }

    companion object {
        const val EXTRA_CAMERA = "camera_json"
        private const val KEY_RANGE = "range"
    }
}

private class FoundAdapter(
    private val items: List<DiscoveryActivity.Found>,
    private val onClick: (DiscoveryActivity.Found) -> Unit,
) : RecyclerView.Adapter<FoundAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.foundName)
        val subtitle: TextView = v.findViewById(R.id.foundInfo)
        val badge: TextView = v.findViewById(R.id.foundBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_found, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.itemView.context
        h.badge.setTextColor(ctx.accentColor())
        when (val f = items[position]) {
            is DiscoveryActivity.Found.Onvif -> {
                h.title.text = f.device.name
                h.subtitle.text = listOf(f.host, f.device.hardware).filter { it.isNotBlank() }.joinToString(" • ")
                h.badge.text = ctx.getString(R.string.badge_onvif)
            }
            is DiscoveryActivity.Found.Port -> {
                h.title.text = f.host
                h.subtitle.text = ctx.getString(R.string.found_port, f.port)
                h.badge.text = ctx.getString(
                    if (f.port in PortScanner.RTSP_PORTS) R.string.badge_rtsp else R.string.badge_http)
            }
        }
        h.itemView.setOnClickListener { onClick(items[h.bindingAdapterPosition]) }
    }
}
