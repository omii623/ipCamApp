package com.example.ipcamapp

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Élő kamerakép lejátszására hangolt ExoPlayer.
 * Az alapbeállítás filmekhez készült (akár 50 mp előre pufferelés); élő képnél ez csak
 * memóriát, hálózatot és késleltetést jelent, ezért kis puffert használunk.
 *
 * @param maxWidth ha megadva (osztott nézet csempéje), ennél nagyobb felbontású sávot nem választ
 * @param audio false esetén a hangsáv dekódolása teljesen kimarad
 */
/** Lejátszási hiba után ennyi idő múlva próbálkozunk újra (csempe és teljes képernyő is). */
const val STREAM_RETRY_MS = 5000L

@OptIn(UnstableApi::class)
fun Context.liveExoPlayer(maxWidth: Int = 0, maxHeight: Int = 0, audio: Boolean = true): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(1_500, 5_000, 500, 1_000)
        .build()
    val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().apply {
        if (maxWidth > 0 && maxHeight > 0) setMaxVideoSize(maxWidth, maxHeight)
        if (!audio) setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
    }.build()
    return exo
}
