package kr.urbanmood.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/** Decorative motion; only recordingFraction represents measured progress. */
internal class AnalysisMotionView(context: Context) : View(context) {
    enum class Stage { IDLE, RECORDING, ANALYZING }
    var stage = Stage.IDLE
        private set
    var recordingFraction = 0f
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null
    private var drawnFraction = 0f
    private val colors = intArrayOf(0xff7cbac8.toInt(), 0xffe8ad94.toInt(), 0xff9ebbb0.toInt())

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(value: Stage, fraction: Float = 0f) {
        val previous = stage
        stage = value
        recordingFraction = fraction.coerceIn(0f, 1f)
        if (value == Stage.RECORDING && previous == value && isShown && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofFloat(drawnFraction, recordingFraction).apply {
                duration = 500; interpolator = LinearInterpolator()
                addUpdateListener { drawnFraction = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            progressAnimator?.cancel(); progressAnimator = null; drawnFraction = recordingFraction
        }
        updateMotion(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * .44f
        paint.shader = null; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = 0x65ffffff
        canvas.drawCircle(cx, cy, radius, paint)
        if (stage == Stage.RECORDING) {
            paint.strokeWidth = 2 * resources.displayMetrics.density
            paint.strokeCap = Paint.Cap.ROUND; paint.color = 0x65007e89
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, -90f, 360f * drawnFraction, false, paint)
            // A breathing light follows the measured progress tip, behind the text layer.
            val tip = (drawnFraction * Math.PI * 2 - Math.PI / 2).toFloat()
            val x = cx + cos(tip) * radius; val y = cy + sin(tip) * radius
            val pulse = .5f + .5f * sin(phase * 3)
            val haloRadius = (8f + pulse * 4f) * resources.displayMetrics.density
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(x, y, haloRadius,
                intArrayOf(0x650b9ba5, 0x000b9ba5), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, haloRadius, paint)
            paint.shader = null; paint.color = 0xa03aabb1.toInt()
            canvas.drawCircle(x, y, (2.4f + .6f * pulse) * resources.displayMetrics.density, paint)
            paint.color = 0xc0ffffff.toInt()
            canvas.drawCircle(x, y, resources.displayMetrics.density, paint)
        } else if (stage == Stage.ANALYZING) {
            paint.style = Paint.Style.FILL
            repeat(3) { i ->
                val angle = phase + i * (Math.PI * 2 / 3).toFloat()
                val orbit = radius * (.65f + .12f * sin(phase * 2))
                val x = cx + cos(angle) * orbit; val y = cy + sin(angle) * orbit
                val size = radius * .52f
                paint.shader = RadialGradient(x, y, size,
                    intArrayOf((colors[i] and 0xffffff) or 0x70000000, colors[i] and 0xffffff), null, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, size, paint)
            }
            paint.shader = null; paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * resources.displayMetrics.density; paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xaaffffff.toInt()
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, phase * 180f / Math.PI.toFloat(), 52f, false, paint)
        }
        paint.shader = null
    }

    private fun updateMotion() {
        val run = stage != Stage.IDLE && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (run && animator == null) {
            animator = ValueAnimator.ofFloat(phase, phase + (Math.PI * 2).toFloat()).apply {
                duration = 6500; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!run) {
            animator?.cancel(); animator = null
            progressAnimator?.cancel(); progressAnimator = null; drawnFraction = recordingFraction
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateMotion() }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null; progressAnimator?.cancel(); progressAnimator = null
        super.onDetachedFromWindow()
    }
}
