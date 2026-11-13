package com.example.ipcamapp

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
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
    tintScrollbars(color)
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

/**
 * Az oldalsó görgetősáv (scrollbar) fogantyújának átszínezése. Alapból a téma
 * colorControlNormal-ját (fix @color/accent) használja, így az egyéni kiemelőszínt
 * nem követné. A thumb drawable csak API 29+ érhető el; régebbi rendszeren marad a téma színe.
 */
internal fun View.tintScrollbars(color: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    if (isVerticalScrollBarEnabled) {
        verticalScrollbarThumbDrawable?.mutate()?.let { it.setTint(color); verticalScrollbarThumbDrawable = it }
    }
    if (isHorizontalScrollBarEnabled) {
        horizontalScrollbarThumbDrawable?.mutate()?.let { it.setTint(color); horizontalScrollbarThumbDrawable = it }
    }
}

/**
 * Legördülő menükhöz (gyártó, típus, csoport) való adapter: a felugró lista görgetősávja
 * is a kiemelőszínt kövesse. A felugró lista a sorok szülője, így a sorok létrehozásakor érjük el.
 */
class AccentArrayAdapter(context: Context, items: List<String>) :
    android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
    private val accent = context.accentColor()
    private var tinted: ViewGroup? = null

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        if (tinted !== parent) { parent.tintScrollbars(accent); tinted = parent }
        return super.getView(position, convertView, parent)
    }
}
