package io.github.akane599.paneloff

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Transparent full-screen touch shield shown while the panel is off. It draws nothing,
 * since the panel is dark anyway; it only keeps pocket/accidental touches from reaching
 * the app underneath and turns the panel back on with a double tap.
 */
class TouchShield(private val context: Context, private val onDoubleTap: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap()
                return true
            }
        })
        val v = View(context).apply {
            setBackgroundColor(0)
            setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Umbra touch shield"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            wm.addView(v, lp)
            view = v
            true
        } catch (t: Throwable) {
            Log.w("TouchShield", "failed to add overlay", t)
            false
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
    }
}
