package io.github.akane599.paneloff.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.akane599.paneloff.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The big power button on the main screen. The black center disc is the "panel"; the dial
 * and glow around it show state:
 *  - [Mode.READY]   mint glow
 *  - [Mode.BLOCKED] amber; privileges needed first
 *  - [Mode.BUSY]    a sweep rotating around the dial
 *  - [Mode.ACTIVE]  panel off; dial dimmed, icon dimmed
 *
 * The only scripted motion is the ignition: when the screen first appears or the panel comes
 * back on, the ticks light from the top down both sides in ~700 ms, then the view is static.
 * The only continuous animation is the BUSY sweep. Nothing plays if system animations are
 * off (scale 0). The static state is drawn to a bitmap once; no frames are produced when idle.
 */
class PowerOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { READY, BLOCKED, BUSY, ACTIVE }

    var mode: Mode = Mode.READY
        set(value) {
            if (field == value) return
            val wasOff = field == Mode.ACTIVE || field == Mode.BUSY
            field = value
            staticLayer = null
            updateColors()
            // Panel came back on or setup finished: the dial "ignites" again.
            if (wasOff && (value == Mode.READY || value == Mode.BLOCKED)) pendingIgnite = true
            syncAnimators()
            invalidate()
        }

    private val dp = resources.displayMetrics.density

    private val colAccent = ContextCompat.getColor(context, R.color.pk_accent)
    private val colWarn = ContextCompat.getColor(context, R.color.pk_warn)
    private val colOutline = ContextCompat.getColor(context, R.color.pk_outline)
    private val colCore = ContextCompat.getColor(context, R.color.pk_orb_core)
    private val colDim = ContextCompat.getColor(context, R.color.pk_text_dim)

    private var tint = colAccent

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * dp
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colCore }
    private val coreEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * dp
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glyphRect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    /** Cached dial + disc + icon; redrawn when size or mode changes. */
    private var staticLayer: Bitmap? = null
    /** Full-intensity glow; each frame only varies its alpha (the gradient isn't recomputed). */
    private var glowLayer: Bitmap? = null
    private val glowBlit = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Ignition progress: 0 = dial dark, 1 = fully lit (static state). */
    private var ignite = 1f
    /** Ignite once on first appearance. */
    private var pendingIgnite = true
    /** BUSY sweep angle in degrees. */
    private var sweep = 0f

    private val igniteAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        // ease-out-quart: fast start, soft landing
        interpolator = PathInterpolator(0.25f, 1f, 0.5f, 1f)
        addUpdateListener { ignite = it.animatedValue as Float; invalidate() }
    }
    private val sweepAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        isFocusable = true
        updateColors()
    }

    private fun updateColors() {
        tint = when (mode) {
            Mode.BLOCKED -> colWarn
            Mode.ACTIVE -> colAccent
            else -> colAccent
        }
        ringPaint.color = ColorUtils.blendARGB(colOutline, tint, 0.25f)
        coreEdgePaint.color = ColorUtils.setAlphaComponent(tint, 0x55)
        glyphPaint.color = if (mode == Mode.ACTIVE) colDim else tint
        rebuildShaders()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f
        staticLayer = null
        rebuildShaders()
    }

    private fun rebuildShaders() {
        glowLayer = null
        if (radius <= 0f) return
        glowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(tint, 0x70),
                ColorUtils.setAlphaComponent(tint, 0x22),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0.55f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Very faint glass sheen at the disc's upper left: "off, but still there".
        val coreR = radius * CORE
        sheenPaint.shader = RadialGradient(
            cx - coreR * 0.35f, cy - coreR * 0.45f, coreR * 0.9f,
            intArrayOf(Color.argb(0x1C, 255, 255, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        // 1) glow
        val glowAlpha = when (mode) {
            Mode.READY -> 0.9f
            Mode.BLOCKED -> 0.6f
            Mode.BUSY -> 0.7f
            Mode.ACTIVE -> 0.25f
        } * (0.15f + 0.85f * ignite)
        val glow = glowLayer ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { Canvas(it).drawCircle(cx, cy, radius, glowPaint); glowLayer = it }
        glowBlit.alpha = (glowAlpha * 255).toInt()
        canvas.drawBitmap(glow, 0f, 0f, glowBlit)

        if (mode == Mode.BUSY || ignite < 1f) {
            drawStatic(canvas) // sweep/ignition changes every frame
        } else {
            val layer = staticLayer ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { drawStatic(Canvas(it)); staticLayer = it }
            canvas.drawBitmap(layer, 0f, 0f, null)
        }
    }

    private fun drawStatic(canvas: Canvas) {
        // 2) dial ticks
        val rOuter = radius * 0.9f
        val rInner = radius * 0.84f
        for (i in 0 until TICKS) {
            val deg = i * 360f / TICKS
            val a = Math.toRadians((deg - 90).toDouble())
            val major = i % 5 == 0
            val r0 = if (major) rInner - 3 * dp else rInner
            tickPaint.color = tickColor(deg)
            tickPaint.strokeWidth = (if (major) 2.2f else 1.5f) * dp
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + c * r0, cy + s * r0, cx + c * rOuter, cy + s * rOuter, tickPaint)
        }

        // 3) inner ring
        canvas.drawCircle(cx, cy, radius * 0.76f, ringPaint)

        // 4) panel disc
        val coreR = radius * CORE
        canvas.drawCircle(cx, cy, coreR, corePaint)
        canvas.drawCircle(cx, cy, coreR, sheenPaint)
        canvas.drawCircle(cx, cy, coreR, coreEdgePaint)

        // 5) power icon
        val gr = coreR * 0.34f
        glyphRect.set(cx - gr, cy - gr, cx + gr, cy + gr)
        glyphPaint.strokeWidth = (coreR * 0.07f).coerceIn(3f * dp, 6f * dp)
        canvas.drawArc(glyphRect, -90f + 40f, 280f, false, glyphPaint)
        canvas.drawLine(cx, cy - gr * 1.18f, cx, cy - gr * 0.12f, glyphPaint)
    }

    private fun tickColor(deg: Float): Int = when (mode) {
        Mode.BUSY -> {
            // rotating sweep: bright head, fading tail
            val d = ((sweep - deg) % 360f + 360f) % 360f
            if (d < 90f) ColorUtils.blendARGB(tint, colOutline, d / 90f) else colOutline
        }
        Mode.ACTIVE -> ColorUtils.blendARGB(colOutline, tint, 0.15f)
        else -> {
            // highlight fading from the top down both sides: gives the dial a direction
            val fromTop = min(deg, 360f - deg) / 180f
            val lit = ColorUtils.blendARGB(tint, colOutline, (fromTop * 1.6f).coerceAtMost(1f))
            if (ignite >= 1f) lit else {
                // ignition front moves down from the top; just ahead of it flashes briefly
                val front = ignite * 1.15f
                when {
                    fromTop > front -> colOutline
                    fromTop > front - 0.12f -> tint
                    else -> lit
                }
            }
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.95f).scaleY(0.95f).setDuration(120).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return super.performClick()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimators()
    }

    override fun onDetachedFromWindow() {
        igniteAnim.cancel()
        ignite = 1f
        sweepAnim.cancel()
        staticLayer = null
        glowLayer = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimators()
    }

    private fun syncAnimators() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE
        if (visible && pendingIgnite && (mode == Mode.READY || mode == Mode.BLOCKED)) {
            pendingIgnite = false
            if (ValueAnimator.areAnimatorsEnabled()) {
                ignite = 0f
                igniteAnim.start()
            }
        }
        if (!visible && igniteAnim.isStarted) {
            igniteAnim.cancel()
            ignite = 1f
        }
        val wantSweep = visible && mode == Mode.BUSY
        if (wantSweep && !sweepAnim.isStarted) sweepAnim.start()
        if (!wantSweep && sweepAnim.isStarted) sweepAnim.cancel()
    }

    private companion object {
        const val TICKS = 60
        const val CORE = 0.62f
    }
}
