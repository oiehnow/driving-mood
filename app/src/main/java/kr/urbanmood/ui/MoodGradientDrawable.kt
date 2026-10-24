package kr.urbanmood.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** A moving mood color field. Only this drawable redraws; text, layout and analysis remain unchanged. */
internal class MoodGradientDrawable(private val cornerRadius: Float, private val orbitDurationMs: Long = 12_000) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val matrix = Matrix()
    private val area = RectF()
    private val evaluator = ArgbEvaluator()
    private var field: Shader? = null
    private var glow: Shader? = null
    private var phase = 0f
    private var drift: ValueAnimator? = null
    private var transition: ValueAnimator? = null
    internal var palette = intArrayOf(0xffa7cfe9.toInt(), 0xffb9ddcf.toInt(), 0xffedc4ab.toInt())
        private set

    fun setPalette(colors: IntArray, animate: Boolean) {
        if (colors.contentEquals(palette)) return
        transition?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !isRunning) {
            palette = colors.copyOf(); rebuild(); invalidateSelf(); return
        }
        val before = palette.copyOf()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            addUpdateListener { value ->
                palette = IntArray(3) { i -> evaluator.evaluate(value.animatedFraction, before[i], colors[i]) as Int }
                rebuild(); invalidateSelf()
            }
            start()
        }
    }

    override fun onBoundsChange(bounds: Rect) { rebuild() }
    private fun rebuild() {
        if (bounds.isEmpty) return
        area.set(bounds)
        val r = area
        clip.reset(); clip.addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
        field = LinearGradient(r.left, r.top, r.right, r.bottom, palette, null, Shader.TileMode.CLAMP)
        // A small highlight adds depth without washing out the opaque mood colors.
        glow = RadialGradient(0f, 0f, r.width() * .58f, intArrayOf(0x28ffffff, 0x00ffffff), null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (field == null) rebuild()
        val r = area
        canvas.save(); canvas.clipPath(clip)
        // A full smooth orbit makes motion visible over a few seconds, without moving the content.
        matrix.reset(); matrix.setRotate(phase * 180f / Math.PI.toFloat(), r.centerX(), r.centerY())
        matrix.postTranslate(cos(phase) * r.width() * .16f, sin(phase) * r.height() * .14f)
        field?.setLocalMatrix(matrix)
        paint.shader = field
        canvas.drawRect(r, paint)
        matrix.reset(); matrix.setTranslate(r.centerX() + cos(phase) * r.width() * .38f,
            r.centerY() + sin(phase) * r.height() * .36f)
        glow?.setLocalMatrix(matrix); glowPaint.shader = glow
        canvas.drawRect(r, glowPaint)
        canvas.restore()
    }
    override fun start() {
        if (isRunning || !ValueAnimator.areAnimatorsEnabled()) return
        drift = ValueAnimator.ofFloat(phase, phase + (Math.PI * 2).toFloat()).apply {
            duration = orbitDurationMs; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }
    override fun stop() { drift?.cancel(); drift = null; transition?.end(); transition = null }
    override fun isRunning() = drift?.isRunning == true
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; glowPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; glowPaint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
