package com.example.ipcamapp

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/** Téma-attribútum (pl. colorPrimary) feloldása aktuális színné. */
fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/** A tényleges kiemelőszín: a mentett egyéni szín, vagy 0 esetén a téma alap színe. */
fun Context.accentColor(): Int {
    val c = Settings.accentColor(this)
    return if (c != 0) c else themeColor(androidx.appcompat.R.attr.colorPrimary)
}

/** Olvasható szövegszín a kiemelőszínen (világos színen sötét, sötéten fehér). */
fun Int.onAccentText(): Int =
    if (ColorUtils.calculateLuminance(this) > 0.5) 0xFF111318.toInt() else Color.WHITE

/** Az aktivitás tartalmának átszínezése az aktuális kiemelőszínnel. */
fun Activity.tintAccent() {
    findViewById<View>(android.R.id.content)?.tintAccentTree(accentColor())
}

/** Egy megjelenített AlertDialog gombjainak (OK/Mégse/…) átszínezése a kiemelőszínnel. */
fun androidx.appcompat.app.AlertDialog.tintButtonsAccent(color: Int) {
    intArrayOf(
        androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE,
        androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
        androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL,
    ).forEach { getButton(it)?.setTextColor(color) }
}

/** Egy nézetfa accent-hordozó elemeinek átszínezése (dialógus nézetekhez is használható). */
fun View.tintAccentTree(color: Int) {
    val csl = ColorStateList.valueOf(color)
    when (this) {
        is FloatingActionButton -> {
            backgroundTintList = csl
            imageTintList = ColorStateList.valueOf(color.onAccentText())
        }
        is Chip -> { /* a listaelemek adaptere kezeli */ }
        is MaterialButton -> {
            if (strokeWidth > 0) {           // körvonalas gomb: körvonal + szöveg
                strokeColor = csl
                setTextColor(color)
            } else {                          // kitöltött gomb: háttér
                backgroundTintList = csl
                setTextColor(color.onAccentText())
            }
        }
        is ProgressBar -> {
            indeterminateTintList = csl
            progressTintList = csl
        }
        is TextInputLayout -> {
            boxStrokeColor = color
            hintTextColor = csl
            setEndIconTintList(csl)
        }
        is TextView -> if (tag == "accent") setTextColor(color)
    }
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).tintAccentTree(color)
}
