package io.github.akane599.umbra

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Region
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.RequiresApi
import io.github.akane599.umbra.ui.LockView

/**
 * Full-screen overlay for touch lock. The display stays on; the overlay swallows every touch
 * that reaches it, optionally dims what's underneath and shows the lock badge ([LockView]).
 *
 * When injected touches are let through ([onInjectedTouch], [TouchRelay]), the window stays a little see-through for
 * the whole session: Android drops a touch that goes through another app's window more opaque than
 * InputManager.maximumObscuringOpacityForTouch (0.8), injected ones included, and it goes by the window's alpha, not
 * by what the window draws. The view draws brighter to make up for it ([LockView] gain), so the badge and the dim look
 * as usual, except a dim past that limit, which stops at it. Setting it once means nothing on screen changes when a
 * touch is replayed: only the holes ([openHoles]) come and go. The system's dim layer (FLAG_DIM_BEHIND) is no way
 * around the limit: it counts too, and on One UI a deep one dropped the replayed touches.
 *
 * Edge gestures and the notification shade belong to system windows above any overlay; they
 * are only blocked when the privileged helper also sets the status bar disable flags ("full lock").
 */
class LockOverlay(private val context: Context, private val onUnlock: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LockView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Gets the touches adb, scrcpy and the like inject, which never reach the badge (see [TouchRelay]). Set before [show]. */
    var onInjectedTouch: ((MotionEvent) -> Unit)? = null

    val isShowing get() = view != null

    fun show(dimAlpha: Float, persistentBadge: Boolean): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val alpha = if (Build.VERSION.SDK_INT >= 34 && onInjectedTouch != null) passThroughAlpha() else 1f
        val v = LockView(context, dimAlpha, persistentBadge, gain = 1f / alpha, onUnlock)
        v.onInjectedTouch = onInjectedTouch
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ALT_FOCUSABLE_IM: without it the system never reports keyboard insets to a non-focusable window.
            // NOT_TOUCH_MODAL: no effect on a window this size until [takeFocus] drops NOT_FOCUSABLE. Then it keeps the
            // window from turning touch-modal, which the system answers by making all of the display touchable,
            // holes ([openHoles]) included.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Umbra touch lock"
            this.alpha = alpha
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            // The scrim and the touch catch reach the edges, status and navigation bar areas included.
            coverWholeDisplay()
        }
        v.onKeyboardVisible = { takeFocus(v, lp) }
        return try {
            wm.addView(v, lp)
            view = v
            params = lp
            v.post { v.playEnter() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "failed to add overlay", t)
            false
        }
    }

    /**
     * A keyboard is open under the lock. Take window focus, which closes it, and stay focused for
     * the rest of the session so it can't come back. Only focus moves: the app underneath keeps
     * running and is not paused.
     */
    private fun takeFocus(v: LockView, lp: WindowManager.LayoutParams) {
        if (view !== v) return
        // Both flags off: the window takes key focus and counts as a keyboard user, so the
        // "always hidden" soft input mode below closes the keyboard.
        lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM).inv()
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        try {
            wm.updateViewLayout(v, lp)
            Log.i(TAG, "took focus to close the keyboard")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to take focus", t)
        }
    }

    @RequiresApi(34)
    private fun passThroughAlpha(): Float {
        val max = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        // A little under: the compositor keeps the window's alpha at half-float precision.
        return (max - 0.02f).coerceIn(0.05f, 1f)
    }

    /** Opens a hole of a few pixels at each of [points] for [TouchRelay] to replay a touch through. */
    @RequiresApi(34)
    fun openHoles(points: List<TouchRelay.Pointer>) {
        val v = view ?: return
        val region = Region(0, 0, v.width, v.height)
        for (p in points) {
            val cx = p.x.toInt()
            val cy = p.y.toInt()
            region.op(cx - HOLE_PX, cy - HOLE_PX, cx + HOLE_PX + 1, cy + HOLE_PX + 1, Region.Op.DIFFERENCE)
        }
        v.rootSurfaceControl?.setTouchableRegion(region)
    }

    @RequiresApi(34)
    fun closeHoles() {
        view?.rootSurfaceControl?.setTouchableRegion(null)
    }

    /** Fades out, then removes the window. */
    fun dismiss() {
        val v = view ?: return
        view = null
        v.playExit { remove(v) }
        // Safety net if the animation never finishes (window detached, animations off mid-flight).
        v.postDelayed({ remove(v) }, 600)
    }

    fun hideNow() {
        val v = view ?: return
        view = null
        remove(v)
    }

    private fun remove(v: LockView) {
        if (!v.isAttachedToWindow) return
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "LockOverlay"
        const val HOLE_PX = 2
    }
}
