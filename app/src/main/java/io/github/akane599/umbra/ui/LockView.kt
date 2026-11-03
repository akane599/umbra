package io.github.akane599.umbra.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import io.github.akane599.umbra.R
import kotlin.math.hypot
import kotlin.random.Random

/**
 * What the touch lock overlay draws: an optional dim scrim over the running app and a lock
 * badge near the bottom edge. The badge closes once on entry, then settles to a faint idle
 * state (or disappears). A touch anywhere brings it back with the unlock hint; holding the
 * badge fills a ring and opens the lock.
 *
 * Draws nothing between animations: no idle frames while the lock is on.
 */
@SuppressLint("ViewConstructor")
class LockView(
    context: Context,
    private val dimAlpha: Float,
    private val persistentBadge: Boolean,
    private val onUnlock: () -> Unit,
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.pk_lock_accent)

    private val scrimPaint = Paint().apply { color = Color.BLACK }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
        color = Color.argb(46, 255, 255, 255)
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val keyholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        color = Color.argb(56, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = Color.WHITE
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    private val radius = 30f * dp
    private val shackle = Path()
    private val body = RectF()
    private val arc = RectF()
    private val pill = RectF()
    private val caption = context.getString(R.string.lock_hint_hold)

    // Animated values, 0..1
    private var enter = 0f        // scrim + badge arrival
    private var lift = 1f         // shackle: 1 open, 0 closed
    private var pulse = -1f       // landing ring; <0 means not running
    private var badgeAlpha = 0f
    private var captionAlpha = 0f
    private var hold = 0f         // hold-to-unlock progress
    private var exit = 0f         // fade-out on unlock
    private var shiftX = 0f       // small periodic offset against OLED burn-in
    private var shiftY = 0f

    private var holdAnim: ValueAnimator? = null
    private var hudAnim: ValueAnimator? = null
    private var liftAnim: ValueAnimator? = null
    private var holding = false
    private var unlocked = false

    private val idleAlpha get() = if (persistentBadge) 0.42f else 0f
    private val goIdle = Runnable { fadeHud(idleAlpha, 0f, 600) }
    private val drift = object : Runnable {
        override fun run() {
            if (badgeAlpha <= idleAlpha + 0.01f && !holding) {
                shiftX = Random.nextInt(-4, 5) * dp
                shiftY = Random.nextInt(-4, 5) * dp
                invalidate()
            }
            postDelayed(this, DRIFT_MS)
        }
    }

    init {
        contentDescription = caption
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ------------------------------------------------------------------ choreography

    /** One-time entry: scrim fades in, the badge arrives and the shackle drops shut. */
    fun playEnter() {
        val decel = DecelerateInterpolator(2.2f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = decel
            addUpdateListener {
                enter = it.animatedValue as Float
                badgeAlpha = enter
                captionAlpha = enter
                invalidate()
            }
            start()
        }
        ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 260
            duration = 240
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { lift = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    playPulse()
                }
            })
            start()
        }
        postDelayed(goIdle, HINT_MS + 500)
        postDelayed(drift, DRIFT_MS)
    }

    private fun playPulse() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { pulse = -1f }
            })
            start()
        }
    }

    /** Fades badge and caption to the given levels. */
    private fun fadeHud(toBadge: Float, toCaption: Float, ms: Long) {
        hudAnim?.cancel()
        val fromBadge = badgeAlpha
        val fromCaption = captionAlpha
        hudAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                badgeAlpha = fromBadge + (toBadge - fromBadge) * t
                captionAlpha = fromCaption + (toCaption - fromCaption) * t
                invalidate()
            }
            start()
        }
    }

    /** A touch landed: show the badge and the hint, then settle back after a moment. */
    private fun wake() {
        removeCallbacks(goIdle)
        if (badgeAlpha < 0.99f || captionAlpha < 0.99f) fadeHud(1f, 1f, 180)
        postDelayed(goIdle, HINT_MS)
    }

    /**
     * The hold is timed by the clock, never by an animator: with "Remove animations" on, animators
     * finish instantly, which would turn the hold into a single tap.
     */
    private var holdStartedAt = 0L
    private val holdTick = object : Runnable {
        override fun run() {
            if (!holding) return
            hold = ((SystemClock.uptimeMillis() - holdStartedAt) / HOLD_MS.toFloat()).coerceAtMost(1f)
            invalidate()
            if (hold >= 1f) completeUnlock() else postOnAnimation(this)
        }
    }

    private fun startHold() {
        holding = true
        holdAnim?.cancel()
        // Resume from wherever the drain left the ring.
        holdStartedAt = SystemClock.uptimeMillis() - (hold * HOLD_MS).toLong()
        removeCallbacks(holdTick)
        postOnAnimation(holdTick)
    }

    private fun releaseHold() {
        if (!holding) return
        holding = false
        removeCallbacks(holdTick)
        holdAnim?.cancel()
        holdAnim = ValueAnimator.ofFloat(hold, 0f).apply {
            duration = (hold * 260).toLong().coerceAtLeast(1)
            interpolator = DecelerateInterpolator()
            addUpdateListener { hold = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun completeUnlock() {
        if (unlocked) return
        unlocked = true
        holding = false
        removeCallbacks(goIdle)
        removeCallbacks(holdTick)
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
        )
        liftAnim = ValueAnimator.ofFloat(lift, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { lift = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onUnlock() }
            })
            start()
        }
    }

    /** Exit fade, run by the overlay when the session ends for any reason. */
    fun playExit(onEnd: () -> Unit) {
        unlocked = true // no more input; the session is already ending
        stopAll()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { exit = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopAll()
        super.onDetachedFromWindow()
    }

    /** Cancels everything pending, including a late unlock callback from the shackle animation. */
    private fun stopAll() {
        removeCallbacks(goIdle)
        removeCallbacks(drift)
        removeCallbacks(holdTick)
        holdAnim?.cancel()
        hudAnim?.cancel()
        liftAnim?.removeAllListeners()
        liftAnim?.cancel()
    }

    // ------------------------------------------------------------------ touch

    private val touchExploration: Boolean
        get() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (unlocked) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wake()
                // With TalkBack, a double-tap-and-hold lands at the view's center; accept it anywhere.
                if (touchExploration || onBadge(e.x, e.y, radius + 14 * dp)) startHold()
            }
            MotionEvent.ACTION_MOVE ->
                if (holding && !touchExploration && !onBadge(e.x, e.y, radius + 40 * dp)) releaseHold()
            MotionEvent.ACTION_POINTER_DOWN -> releaseHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseHold()
        }
        return true
    }

    private fun onBadge(x: Float, y: Float, r: Float) = hypot(x - badgeX, y - badgeY) <= r

    /**
     * Called once when a keyboard is up underneath. The keyboard window sits above application
     * overlays, so it would stay usable, and with it open the navigation bar brings back its
     * "hide keyboard" and keyboard-switcher buttons even when navigation is disabled.
     */
    var onKeyboardVisible: (() -> Unit)? = null

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime()) &&
            insets.getInsets(WindowInsets.Type.ime()).bottom > 0
        ) {
            onKeyboardVisible?.also { onKeyboardVisible = null }?.invoke()
        }
        return super.onApplyWindowInsets(insets)
    }

    /** Only reached once the window has taken focus to close a keyboard: keep keys away from the app. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        // Volume, media and other system keys: the window's fallback handler does the usual thing.
        if (event.isSystem) return super.dispatchKeyEvent(event)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_unlock, context.getString(R.string.lock_a11y_unlock)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_unlock) {
            completeUnlock()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    // ------------------------------------------------------------------ drawing

    private val badgeX get() = width / 2f + shiftX
    private val badgeY get() = height - BADGE_FROM_BOTTOM_DP * dp + shiftY

    override fun onDraw(canvas: Canvas) {
        val out = 1f - exit
        if (dimAlpha > 0f) {
            scrimPaint.alpha = (255 * dimAlpha * enter * out).toInt()
            canvas.drawPaint(scrimPaint)
        }
        val a = badgeAlpha * out
        if (a <= 0.003f && pulse < 0f) return

        val cx = badgeX
        val cy = badgeY
        val scale = 0.86f + 0.14f * enter

        if (pulse >= 0f) {
            pulsePaint.alpha = (110 * (1f - pulse) * out).toInt()
            canvas.drawCircle(cx, cy, radius + pulse * 22 * dp, pulsePaint)
        }

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        discPaint.alpha = (150 * a).toInt()
        canvas.drawCircle(cx, cy, radius, discPaint)
        rimPaint.alpha = (46 * a).toInt()
        canvas.drawCircle(cx, cy, radius - rimPaint.strokeWidth / 2, rimPaint)
        drawGlyph(canvas, cx, cy, a)

        if (hold > 0f || holding) {
            val r = radius + 6 * dp
            arc.set(cx - r, cy - r, cx + r, cy + r)
            trackPaint.alpha = (56 * a).toInt()
            canvas.drawCircle(cx, cy, r, trackPaint)
            ringPaint.alpha = (255 * a).toInt()
            canvas.drawArc(arc, -90f, 360f * hold, false, ringPaint)
        }
        canvas.restore()

        val ca = captionAlpha * out
        if (ca > 0.003f) {
            val tw = textPaint.measureText(caption)
            val padH = 14 * dp
            val padV = 8 * dp
            val fm = textPaint.fontMetrics
            val th = fm.descent - fm.ascent
            val bottom = cy - radius - 18 * dp
            pill.set(cx - tw / 2 - padH, bottom - th - 2 * padV, cx + tw / 2 + padH, bottom)
            pillPaint.alpha = (150 * ca).toInt()
            val pr = pill.height() / 2
            canvas.drawRoundRect(pill, pr, pr, pillPaint)
            textPaint.alpha = (235 * ca).toInt()
            canvas.drawText(caption, cx - tw / 2, pill.bottom - padV - fm.descent, textPaint)
        }
    }

    /** Padlock: rounded body with a keyhole and a shackle that lifts when open. */
    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, a: Float) {
        val bw = 17 * dp
        val bh = 13 * dp
        val top = cy - bh / 2 + 3.5f * dp
        body.set(cx - bw / 2, top, cx + bw / 2, top + bh)
        glyphFill.alpha = (255 * a).toInt()
        canvas.drawRoundRect(body, 3.2f * dp, 3.2f * dp, glyphFill)
        keyholePaint.alpha = (150 * a).toInt()
        canvas.drawCircle(cx, top + bh * 0.45f, 1.7f * dp, keyholePaint)
        canvas.drawRect(cx - 0.7f * dp, top + bh * 0.45f, cx + 0.7f * dp, top + bh * 0.72f, keyholePaint)

        // Shackle: a U whose legs sink into the body when closed; open = raised and swung slightly.
        val sw = 10 * dp
        val legBottom = top + 1f * dp
        val raise = lift * 5 * dp
        val archTop = top - 8.5f * dp - raise
        shackle.reset()
        shackle.moveTo(cx - sw / 2, legBottom - raise * 0.25f)
        shackle.lineTo(cx - sw / 2, archTop + sw / 2)
        arc.set(cx - sw / 2, archTop, cx + sw / 2, archTop + sw)
        shackle.arcTo(arc, 180f, 180f, false)
        shackle.lineTo(cx + sw / 2, legBottom - raise)
        glyphStroke.alpha = (255 * a).toInt()
        canvas.drawPath(shackle, glyphStroke)
    }

    private companion object {
        const val HOLD_MS = 1100L
        const val HINT_MS = 2400L
        const val DRIFT_MS = 90_000L
        const val BADGE_FROM_BOTTOM_DP = 132
    }
}
