package com.example.ipcamapp

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

class ViewerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var camera: Camera
    private lateinit var playerView: PlayerView
    private lateinit var mjpegView: MjpegView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var urlText: TextView
    private lateinit var zoomLayout: ZoomLayout
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val cam = intent.getStringExtra(EXTRA_ID)?.let { CameraRepository(this).get(it) }
        if (cam == null) { finish(); return }
        camera = cam
        title = cam.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        playerView = findViewById(R.id.playerView)
        mjpegView = findViewById(R.id.mjpegView)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        urlText.text = cam.displayUrl()
        zoomLayout = findViewById(R.id.zoomLayout)
        zoomLayout.onSingleTap = { setFullscreen(!fullscreen) }

        // edge-to-edge: az alsó sáv ne csússzon a navigációs sáv alá
        ViewCompat.setOnApplyWindowInsetsListener(urlText) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bars.bottom + 8.dp)
            insets
        }

        // vissza gomb álló helyzetű teljes képernyőben: csak abból lép ki
        onBackPressedDispatcher.addCallback(this, backCallback)

        // fekvő helyzetben automatikusan teljes képernyő
        setFullscreen(savedInstanceState?.getBoolean(KEY_FS) ?: isLandscape())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FS, fullscreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::zoomLayout.isInitialized) return
        setFullscreen(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        menu.findItem(R.id.action_fullscreen)?.icon?.setTint(accentColor())
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_fullscreen) { setFullscreen(true); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullscreen(false)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        backCallback.isEnabled = on && !isLandscape()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            supportActionBar?.hide()
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            urlText.visibility = View.GONE
        } else {
            supportActionBar?.show()
            controller.show(WindowInsetsCompat.Type.systemBars())
            urlText.visibility = View.VISIBLE
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onStart() {
        super.onStart()
        if (!::camera.isInitialized) return
        when (camera.type) {
            StreamType.RTSP, StreamType.HLS -> startExo()
            StreamType.MJPEG -> startMjpeg(snapshot = false)
            StreamType.SNAPSHOT -> startMjpeg(snapshot = true)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
        if (::mjpegView.isInitialized) mjpegView.stop()
    }

    @OptIn(UnstableApi::class)
    private fun startExo() {
        playerView.visibility = View.VISIBLE
        mjpegView.visibility = View.GONE
        showLoading()

        val exo = ExoPlayer.Builder(this).build()
        val source = if (camera.type == StreamType.RTSP) {
            RtspMediaSource.Factory()
                .setTimeoutMs(8000)
                // Sok kamera csak TCP-n működik megbízhatóan (NAT / mobilnet esetén is)
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(camera.buildUrl(includeCredentials = true))))
        } else {
            val http = DefaultHttpDataSource.Factory()
            if (camera.username.isNotEmpty()) {
                val token = android.util.Base64.encodeToString(
                    "${camera.username}:${camera.password}".toByteArray(), android.util.Base64.NO_WRAP)
                http.setDefaultRequestProperties(mapOf("Authorization" to "Basic $token"))
            }
            HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(camera.buildUrl(includeCredentials = false)))
        }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) hideLoading()
            }
            override fun onPlayerError(error: PlaybackException) {
                showError(error.cause?.message ?: error.errorCodeName)
            }
        })
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()
        playerView.player = exo
        player = exo
    }

    private fun startMjpeg(snapshot: Boolean) {
        playerView.visibility = View.GONE
        mjpegView.visibility = View.VISIBLE
        showLoading()
        mjpegView.onFirstFrame = { hideLoading() }
        mjpegView.onError = { showError(it) }
        mjpegView.start(
            camera.buildUrl(includeCredentials = false),
            camera.username, camera.password, snapshotMode = snapshot,
        )
    }

    private fun showLoading() {
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setText(R.string.connecting)
    }

    private fun hideLoading() {
        progress.visibility = View.GONE
        status.visibility = View.GONE
    }

    private fun showError(msg: String) {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.error_stream, msg)
    }

    companion object {
        const val EXTRA_ID = "camera_id"
        private const val KEY_FS = "fullscreen"
    }
}
