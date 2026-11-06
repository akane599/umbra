package io.github.akane599.umbra

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import io.github.akane599.umbra.ui.LockView

/**
 * Full-screen overlay for touch lock. The display stays on; the overlay swallows every touch
 * that reaches it, optionally dims what's underneath and shows the lock badge ([LockView]).
 *
 * Edge gestures and the notification shade belong to system windows above any overlay; they
 * are only blocked when the privileged helper also sets the status bar disable flags ("full lock").
 */
class LockOverlay(private val context: Context, private val onUnlock: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LockView? = null

    val isShowing get() = view != null

    fun show(dimAlpha: Float, persistentBadge: Boolean): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val v = LockView(context, dimAlpha, persistentBadge, onUnlock)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ALT_FOCUSABLE_IM: without it the system never reports keyboard insets to a non-focusable window.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Umbra touch lock"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            // The scrim and the touch catch reach the edges, status and navigation bar areas included.
            coverWholeDisplay()
        }
        v.onKeyboardVisible = { takeFocus(v, lp) }
        return try {
            wm.addView(v, lp)
            view = v
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
    }
}
