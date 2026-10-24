package kr.urbanmood.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kr.urbanmood.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One logo travels from the entry action through observation to the result header.
 * Header rectangles use this overlay's coordinate system. No capture or inference is simulated here.
 */
class LogoJourneyView(context: Context) : FrameLayout(context) {
    var onStart: (() -> Unit)? = null
    private enum class Stage { INTRO, READY, STARTING, RECORDING, ANALYZING, FINISHING, HIDDEN }
    private var stage = Stage.HIDDEN
    private val backdrop = View(context).apply { setBackgroundColor(0xfff7f8fa.toInt()) }
    private val ring = JourneyRing(context)
    private val logo = ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xfff7f8fa.toInt())
        }
        clipToOutline = true
        elevation = dp(7).toFloat()
        foreground = RippleDrawable(ColorStateList.valueOf(0x38ffffff), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
        val pressTarget = this
        stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled),
                ObjectAnimator.ofFloat(pressTarget, "elevation", dp(2).toFloat()).setDuration(100))
            addState(intArrayOf(android.R.attr.state_enabled),
                ObjectAnimator.ofFloat(pressTarget, "elevation", dp(7).toFloat()).setDuration(180))
            addState(intArrayOf(), ObjectAnimator.ofFloat(pressTarget, "elevation", 0f).setDuration(180))
        }
        tag = "mood-analysis-action"
        contentDescription = "무드 분석 시작"
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }
    val actionView: View get() = logo
    private val status = TextView(context).apply {
        textSize = 14f; setTextColor(0xff002c5f.toInt()); gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(dp(20), 0, dp(20), 0)
    }
    private val percentage = TextView(context).apply {
        textSize = 11f; setTextColor(0xff657182.toInt()); gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }
    private var movement: ValueAnimator? = null
    private var pendingLayout: (() -> Unit)? = null
    private var generation = 0L
    private var internalHide = false
    private var resumePending = false
    private var captureFraction = 0f
    private class FinishRequest(val bounds: RectF, val callback: () -> Unit)
    private var pendingFinish: FinishRequest? = null
    private var centerX = 0f
    private var centerY = 0f
    private var logoSize = dp(128).toFloat()
    private val easing = PathInterpolator(.22f, .8f, .24f, 1f)

    init {
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true // While present, the overlay owns the underlying screen's touch area.
        addView(backdrop, LayoutParams(-1, -1))
        addView(ring, LayoutParams(dp(200), dp(200)))
        addView(logo, LayoutParams(dp(128), dp(128)))
        addView(status, LayoutParams(-1, dp(28)))
        addView(percentage, LayoutParams(-1, dp(24)))
        ring.visibility = GONE; status.visibility = GONE
        percentage.visibility = GONE
        logo.setOnClickListener {
            if (stage == Stage.READY && logo.isEnabled) {
                val start = onStart ?: return@setOnClickListener
                stage = Stage.STARTING
                logo.isEnabled = false
                start()
            }
        }
        visibility = GONE
    }

    fun showIntro() {
        cancelMovement()
        stage = Stage.INTRO
        visibility = VISIBLE
        backdrop.alpha = 1f; logo.alpha = 1f
        logo.isEnabled = false
        logo.contentDescription = "무드 분석 시작"
        ring.visibility = GONE; status.visibility = GONE
        percentage.visibility = GONE
        sized {
            place(width / 2f, height / 2f, dp(128).toFloat())
            val fromY = centerY
            val toY = thumbY()
            animateMove(900, { t ->
                // The first portion reveals the mark at the centre before it settles near the thumb.
                val travel = ((t - .18f) / .82f).coerceIn(0f, 1f)
                logo.alpha = (t / .18f).coerceIn(0f, 1f)
                place(width / 2f, fromY + (toY - fromY) * travel, dp(128).toFloat())
            }) {
                stage = Stage.READY; logo.isEnabled = true
            }
        }
    }

    fun startCapture(fromHeader: RectF? = null) {
        cancelMovement()
        stage = Stage.STARTING
        visibility = VISIBLE
        logo.isEnabled = false; logo.alpha = 1f
        captureFraction = 0f
        ring.reset()
        ring.alpha = 1f; ring.visibility = VISIBLE; ring.analyzing()
        status.visibility = VISIBLE
        status.alpha = 0f
        percentage.alpha = 0f; percentage.visibility = GONE
        setStatus("카메라 연결 중")
        val header = fromHeader?.let(::RectF)
        sized {
            if (header != null && !header.isEmpty) {
                place(header.centerX(), header.centerY(), min(header.width(), header.height()))
                backdrop.alpha = 0f
            } else if (logoSize <= 0f || centerY == 0f) {
                place(width / 2f, thumbY(), dp(128).toFloat())
                backdrop.alpha = 1f
            }
            val x = centerX; val y = centerY; val size = logoSize; val beforeAlpha = backdrop.alpha
            animateMove(750, { t ->
                place(x + (width / 2f - x) * t, y + (height / 2f - y) * t,
                    size + (dp(128) - size) * t)
                backdrop.alpha = beforeAlpha + (1f - beforeAlpha) * t
                status.alpha = t
                percentage.alpha = t
            }) { status.alpha = 1f }
        }
    }

    fun recording(elapsed: Long, total: Long) {
        if (stage == Stage.HIDDEN || stage == Stage.FINISHING) return
        stage = Stage.RECORDING
        captureFraction = (elapsed.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
        ring.visibility = VISIBLE
        ring.recording(captureFraction)
        setStatus("주변을 담고 있어요")
        setProgress((captureFraction * 100).toInt())
    }

    fun analyzing(processed: Long? = null, total: Long? = null) {
        if (stage == Stage.HIDDEN || stage == Stage.FINISHING) return
        stage = Stage.ANALYZING
        ring.visibility = VISIBLE; ring.analyzing()
        setStatus("무드를 분석하고 있어요")
        setProgress(if (processed != null && total != null && total > 0)
            (processed.coerceIn(0, total) * 100 / total).toInt() else null)
    }

    fun finishTo(headerBounds: RectF, onFinished: () -> Unit) {
        cancelMovement()
        stage = Stage.FINISHING
        logo.isEnabled = false
        val target = RectF(headerBounds)
        val request = FinishRequest(target, onFinished)
        pendingFinish = request
        if (visibility != VISIBLE) {
            completeFinish(request)
            return
        }
        sized {
            val x = centerX; val y = centerY; val size = logoSize
            val targetSize = min(target.width(), target.height()).coerceAtLeast(dp(24).toFloat())
            val beforeAlpha = backdrop.alpha
            animateMove(850, { t ->
                place(x + (target.centerX() - x) * t, y + (target.centerY() - y) * t,
                    size + (targetSize - size) * t)
                backdrop.alpha = beforeAlpha * (1f - t)
                ring.alpha = 1f - t; status.alpha = 1f - t; percentage.alpha = 1f - t
            }) {
                completeFinish(request)
            }
        }
    }

    private fun completeFinish(request: FinishRequest) {
        if (pendingFinish !== request || stage != Stage.FINISHING) return
        // Clear before hiding: visibility callbacks or a new request must never replay completion.
        pendingFinish = null
        resumePending = false
        stage = Stage.HIDDEN
        ring.stop()
        internalHide = true
        visibility = GONE
        internalHide = false
        request.callback()
    }

    fun resetAfterFailure() {
        cancelMovement()
        stage = Stage.READY
        visibility = VISIBLE
        backdrop.alpha = 1f; logo.alpha = 1f; ring.alpha = 1f; status.alpha = 1f
        ring.stop(); ring.visibility = GONE; status.visibility = GONE
        percentage.visibility = GONE
        logo.contentDescription = "무드 분석 다시 시작"
        logo.isEnabled = true
        sized { place(width / 2f, thumbY(), dp(128).toFloat()) }
    }

    private fun setStatus(value: String) {
        status.text = value
        status.visibility = VISIBLE
        logo.contentDescription = value
    }

    private fun setProgress(value: Int?) {
        percentage.text = value?.let { "$it%" }.orEmpty()
        percentage.visibility = if (value == null) GONE else VISIBLE
        logo.contentDescription = "${status.text} ${percentage.text}".trim()
    }

    private fun thumbY() = (height * .76f).coerceIn(dp(72).toFloat(), (height - dp(72)).coerceAtLeast(dp(72)).toFloat())

    private fun place(x: Float, y: Float, size: Float) {
        centerX = x; centerY = y; logoSize = size
        val scale = size / dp(128)
        logo.x = x - dp(64); logo.y = y - dp(64)
        logo.scaleX = scale; logo.scaleY = scale
        ring.x = x - dp(100); ring.y = y - dp(100)
        ring.scaleX = scale; ring.scaleY = scale
        status.x = 0f; status.y = y + size / 2f + dp(22)
        percentage.x = 0f; percentage.y = status.y + dp(30)
    }

    private fun sized(block: () -> Unit) {
        if (width > 0 && height > 0) block() else pendingLayout = block
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (resumePending && canPresent()) {
            pendingLayout = null
            resumeVisible()
            return
        }
        val pending = pendingLayout
        pendingLayout = null
        if (pending != null) pending()
        else if (w > 0 && h > 0 && movement == null) {
            when (stage) {
                Stage.READY -> place(w / 2f, thumbY(), dp(128).toFloat())
                Stage.STARTING, Stage.RECORDING, Stage.ANALYZING -> place(w / 2f, h / 2f, dp(128).toFloat())
                else -> Unit
            }
        }
    }

    private fun animateMove(durationMs: Long, update: (Float) -> Unit, done: () -> Unit) {
        val ticket = generation
        if (!canPresent()) {
            resumePending = true
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            update(1f); done(); return
        }
        movement = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs; interpolator = easing
            addUpdateListener { if (ticket == generation) update(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ticket != generation) return
                    movement = null; done()
                }
            })
            start()
        }
    }

    private fun cancelMovement(clearCompletion: Boolean = true) {
        generation++
        pendingLayout = null
        movement?.removeAllListeners(); movement?.cancel(); movement = null
        if (clearCompletion) { pendingFinish = null; resumePending = false }
    }

    private fun canPresent() = isAttachedToWindow && isShown && windowVisibility == VISIBLE && width > 0 && height > 0

    private fun suspendMotion() {
        if (internalHide) return
        cancelMovement(clearCompletion = false)
        ring.stop()
        resumePending = stage != Stage.HIDDEN
    }

    private fun resumeVisible() {
        if (!resumePending || !canPresent()) return
        resumePending = false
        when (stage) {
            Stage.INTRO, Stage.READY -> {
                val wasIntro = stage == Stage.INTRO
                stage = Stage.READY
                backdrop.alpha = 1f; logo.alpha = 1f
                ring.visibility = GONE; status.visibility = GONE
                percentage.visibility = GONE
                place(width / 2f, thumbY(), dp(128).toFloat())
                if (wasIntro) logo.isEnabled = true
            }
            Stage.STARTING, Stage.RECORDING, Stage.ANALYZING -> {
                backdrop.alpha = 1f; logo.alpha = 1f; ring.alpha = 1f; status.alpha = 1f; percentage.alpha = 1f
                logo.isEnabled = false
                place(width / 2f, height / 2f, dp(128).toFloat())
                ring.visibility = VISIBLE; status.visibility = VISIBLE
                if (stage == Stage.RECORDING) ring.recording(captureFraction, animate = false) else ring.analyzing()
            }
            Stage.FINISHING -> pendingFinish?.let { request ->
                place(request.bounds.centerX(), request.bounds.centerY(),
                    min(request.bounds.width(), request.bounds.height()).coerceAtLeast(dp(24).toFloat()))
                backdrop.alpha = 0f; ring.alpha = 0f; status.alpha = 0f; percentage.alpha = 0f
                completeFinish(request)
            }
            Stage.HIDDEN -> Unit
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) resumeVisible() else suspendMotion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeVisible()
    }

    override fun onDetachedFromWindow() {
        cancelMovement(); ring.stop()
        stage = Stage.HIDDEN
        internalHide = true
        visibility = GONE
        internalHide = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) resumeVisible() else suspendMotion()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Border motion is decorative; the determinate sweep comes only from camera elapsed time. */
private class JourneyRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var rotating = false
    private var phase = 0f
    private var fraction = 0f
    private var ticker: ValueAnimator? = null
    private var progress: ValueAnimator? = null
    private var active = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun recording(value: Float, animate: Boolean = true) {
        rotating = false; active = true
        progress?.cancel()
        if (animate && isShown && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            progress = ValueAnimator.ofFloat(fraction, value).apply {
                duration = 400; interpolator = LinearInterpolator()
                addUpdateListener { fraction = it.animatedValue as Float; invalidate() }; start()
            }
        } else { fraction = value; invalidate() }
        runTicker()
    }

    fun analyzing() { rotating = true; active = true; progress?.cancel(); runTicker(); invalidate() }

    fun reset() { stop(); fraction = 0f; phase = 0f; rotating = false; invalidate() }

    private fun runTicker() {
        if (ticker != null || !active || !isShown || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return
        ticker = ValueAnimator.ofFloat(phase, phase + 360f).apply {
            duration = 4200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
        }
    }

    fun stop() { active = false; ticker?.cancel(); ticker = null; progress?.cancel(); progress = null }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * .35f
        val cx = width / 2f; val cy = height / 2f
        val density = resources.displayMetrics.density
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = 0x22002c5f
        canvas.drawOval(oval, paint)
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = 2f * density; paint.color = 0x80007e89.toInt()
        val start = if (rotating) phase - 90f else -90f
        val sweep = if (rotating) 75f else fraction * 360f
        canvas.drawArc(oval, start, sweep, false, paint)
        val angle = Math.toRadians((start + sweep).toDouble())
        val x = cx + cos(angle).toFloat() * radius; val y = cy + sin(angle).toFloat() * radius
        val pulse = .5f + .5f * sin(Math.toRadians(phase.toDouble()) * 3).toFloat()
        paint.style = Paint.Style.FILL; paint.color = 0x183aabb1
        canvas.drawCircle(x, y, (6f + 3f * pulse) * density, paint)
        paint.color = 0x993aabb1.toInt()
        canvas.drawCircle(x, y, (2.3f + .5f * pulse) * density, paint)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); runTicker() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) runTicker() else { ticker?.cancel(); ticker = null; progress?.cancel(); progress = null }
    }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
