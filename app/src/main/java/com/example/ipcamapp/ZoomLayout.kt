package com.example.ipcamapp

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout

/**
 * Konténer, ami a gyerek nézeteit nagyítja/mozgatja:
 * - két ujjas csípés: zoom (1x – 5x)
 * - húzás nagyított állapotban: mozgatás
 * - dupla koppintás: 1x <-> 2.5x az ujj helyére
 * - egy koppintás: [onSingleTap] (pl. teljes képernyő ki/be)
 */
class ZoomLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onSingleTap: (() -> Unit)? = null

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var animator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAround(scale * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1f) return false
                tx -= dx; ty -= dy
                apply()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateTo(if (scale > 1.05f) 1f else 2.5f, e.x, e.y)
                return true
            }
        })

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        animator?.cancel()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun reset() {
        animator?.cancel()
        scale = 1f; tx = 0f; ty = 0f
        apply()
    }

    private fun zoomAround(newScale: Float, fx: Float, fy: Float) {
        val s = newScale.coerceIn(MIN, MAX)
        // a fókuszpont alatti tartalom maradjon az ujj alatt
        tx = fx - (fx - tx) * (s / scale)
        ty = fy - (fy - ty) * (s / scale)
        scale = s
        apply()
    }

    private fun animateTo(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(scale, target).apply {
            duration = 220
            addUpdateListener { zoomAround(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    private fun apply() {
        // ne lehessen a képet a keretből kitolni
        tx = tx.coerceIn(width - width * scale, 0f)
        ty = ty.coerceIn(height - height * scale, 0f)
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                pivotX = 0f; pivotY = 0f
                scaleX = scale; scaleY = scale
                translationX = tx; translationY = ty
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    private companion object {
        const val MIN = 1f
        const val MAX = 5f
    }
}
