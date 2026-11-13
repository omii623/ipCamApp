package com.example.ipcamapp

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ (targetSdk 35+) alatt az app a képernyő széléig rajzol (edge-to-edge),
 * és az AppCompat fejléc részben a tartalom fölé kerül.
 * Ez megméri, mennyit takar a fejléc a tartalom tetejéből, és pontosan annyi
 * felső margót ad; alul/oldalt a navigációs sáv méretét.
 */
fun AppCompatActivity.padForSystemBars() {
    val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) ?: return
    val barContainer: View? = findViewById(androidx.appcompat.R.id.action_bar_container)
    var insetBottom = 0
    var insetLeft = 0
    var insetRight = 0

    fun update() {
        var top = 0
        if (barContainer != null && supportActionBar?.isShowing == true) {
            val a = IntArray(2).also { barContainer.getLocationOnScreen(it) }
            val r = IntArray(2).also { (root.parent as View).getLocationOnScreen(it) }
            top = (a[1] + barContainer.height - r[1]).coerceAtLeast(0)
        }
        if (root.paddingTop != top || root.paddingBottom != insetBottom ||
            root.paddingLeft != insetLeft || root.paddingRight != insetRight) {
            root.setPadding(insetLeft, top, insetRight, insetBottom)
        }
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        insetBottom = bars.bottom; insetLeft = bars.left; insetRight = bars.right
        update()
        insets
    }
    // a fejléc mérete/helyzete csak elrendezés után ismert (és forgatáskor változik)
    barContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { update() } }
    root.post { update() }
    ViewCompat.requestApplyInsets(root)
}
