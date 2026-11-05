package com.example.ipcamapp

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject
import java.util.UUID

/** Egy kamera megosztása QR-kóddal két telefon között. */
object Qr {
    private const val FORMAT = "ipcam-camera"
    private const val VERSION = 1

    /** QR-bitkép a megadott szövegből. */
    fun encode(text: String, size: Int = 800): Bitmap =
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, size, size)

    /** Egy kamera QR-tartalma (nyílt jelszóval – a fájlos exporttal konzisztens). */
    fun buildCamera(camera: Camera): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("camera", camera.toJson())
    }.toString()

    /**
     * QR-tartalom visszaalakítása kamerává; érvénytelen esetén null.
     * Új id-t ad, hogy a fogadó telefonon ne írjon felül meglévő kamerát.
     */
    fun parseCamera(text: String): Camera? = try {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) null
        else Camera.fromJson(root.getJSONObject("camera")).copy(id = UUID.randomUUID().toString())
    } catch (_: Exception) {
        null
    }
}
