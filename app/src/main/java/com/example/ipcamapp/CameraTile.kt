package com.example.ipcamapp

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/** Egy csempe az osztott nézetben: saját lejátszóval, ami önállóan indítható/leállítható. */
class CameraTile(context: Context, parent: ViewGroup, val camera: Camera) {

    val view: View = LayoutInflater.from(context).inflate(R.layout.item_tile, parent, false)
    private val playerView: PlayerView = view.findViewById(R.id.tilePlayer)
    private val mjpegView: MjpegView = view.findViewById(R.id.tileMjpeg)
    private val progress: ProgressBar = view.findViewById(R.id.tileProgress)
    private val status: TextView = view.findViewById(R.id.tileStatus)
    private var player: ExoPlayer? = null

    init {
        view.findViewById<TextView>(R.id.tileName).text = camera.name
        progress.indeterminateTintList = android.content.res.ColorStateList.valueOf(context.accentColor())
    }

    fun start() {
        stop()
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        when (camera.type) {
            StreamType.RTSP, StreamType.HLS -> startExo()
            StreamType.MJPEG, StreamType.SNAPSHOT -> {
                playerView.visibility = View.GONE
                mjpegView.visibility = View.VISIBLE
                mjpegView.onFirstFrame = { progress.visibility = View.GONE; status.visibility = View.GONE }
                mjpegView.onError = ::showError
                mjpegView.start(
                    camera.buildUrl(includeCredentials = false), camera.username, camera.password,
                    snapshotMode = camera.type == StreamType.SNAPSHOT,
                )
            }
        }
    }

    fun stop() {
        player?.release()
        player = null
        playerView.player = null
        mjpegView.stop()
    }

    @OptIn(UnstableApi::class)
    private fun startExo() {
        playerView.visibility = View.VISIBLE
        mjpegView.visibility = View.GONE
        val ctx = view.context
        // több kamera egyszerre: hang nélkül (dekódolás sincs), legfeljebb a csempe felbontásában
        val lp = view.layoutParams
        val exo = ctx.liveExoPlayer(
            maxWidth = lp?.width?.takeIf { it > 0 } ?: 0,
            maxHeight = lp?.height?.takeIf { it > 0 } ?: 0,
            audio = false,
        )
        val source = if (camera.type == StreamType.RTSP) {
            RtspMediaSource.Factory()
                .setTimeoutMs(8000)
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(camera.buildUrl(includeCredentials = true))))
        } else {
            val http = DefaultHttpDataSource.Factory()
            if (camera.username.isNotEmpty()) {
                val token = Base64.encodeToString(
                    "${camera.username}:${camera.password}".toByteArray(), Base64.NO_WRAP)
                http.setDefaultRequestProperties(mapOf("Authorization" to "Basic $token"))
            }
            HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(camera.buildUrl(includeCredentials = false)))
        }
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                showError(error.cause?.message ?: error.errorCodeName)
                // automatikus újrapróbálás pár másodperc múlva
                view.postDelayed({ if (player === exo) start() }, STREAM_RETRY_MS)
            }
        })
        exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()
        playerView.player = exo
        player = exo
    }

    private fun showError(msg: String) {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = view.context.getString(R.string.error_stream, msg)
    }
}
