package kr.urbanmood.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import kr.urbanmood.contracts.RecommendationResult
import kr.urbanmood.integration.dashboardMetrics

/** A visual summary of the current request; all numbers come from the completed result. */
class MoodDashboardView(context: Context) : LinearLayout(context) {
    private val ink = Color.rgb(0, 44, 95)
    private val hero = FrameLayout(context)
    private val heading = label("무드 분석", 20, true)
    private val eyebrow = label("지금, 이 길의 분위기", 12)
    private val caption = label("로고를 눌러 지금 이 길의 분위기를 알아보세요", 12)
    private val logo = ImageView(context).apply {
        setImageDrawable(LogoJourneyView.createLogoDrawable(context))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val actionView: View get() = hero
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(ink); visibility = View.GONE
        contentDescription = "무드 분석 진행 중"
    }
    private val values = mutableListOf<Triple<TextView, TextView, TextView>>()
    private lateinit var metricStrip: LinearLayout
    private val moodBackground = MoodGradientDrawable(dp(30).toFloat())
    private val motion = AnalysisMotionView(context)
    private var welcome = false

    init {
        orientation = VERTICAL
        hero.background = moodBackground
        hero.tag = "mood-analysis-action"
        hero.isFocusable = true
        hero.contentDescription = "무드 분석 시작"
        hero.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        hero.foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x18002c5f), null, surface(Color.WHITE, 30))
        addView(hero, LayoutParams(-1, dp(274)))
        hero.addView(motion, FrameLayout.LayoutParams(dp(246), dp(246), Gravity.CENTER))
        val center = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(10))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        center.addView(eyebrow)
        center.addView(logo, LayoutParams(dp(88), dp(88)).apply { topMargin = dp(16) })
        center.addView(heading, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        center.addView(caption, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        hero.addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        hero.addView(spinner, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        val metrics = LinearLayout(context).apply {
            orientation = HORIZONTAL; background = surface(Color.WHITE, 22)
            setPadding(dp(8), dp(19), dp(8), dp(19)); elevation = dp(2).toFloat()
        }
        metricStrip = metrics
        addView(metrics, LayoutParams(-1, -2).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = -dp(18); bottomMargin = dp(4) })
        repeat(3) { i ->
            val cell = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            val name = label("", 12); val value = label("—", 25, true); val unit = label("관측 대기", 10)
            cell.addView(name); cell.addView(value, LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(3) }); cell.addView(unit)
            metrics.addView(cell, LayoutParams(0, -2, 1f)); values += Triple(name, value, unit)
            if (i < 2) metrics.addView(View(context).apply { setBackgroundColor(0xffe8edf2.toInt()) }, LayoutParams(dp(1), dp(46)).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        showMetrics(null)
    }

    fun showWelcome() {
        welcome = true
        logo.visibility = View.VISIBLE
        eyebrow.visibility = View.GONE; heading.visibility = View.GONE; caption.visibility = View.GONE
        motion.visibility = View.GONE; metricStrip.visibility = View.GONE
        logo.layoutParams = (logo.layoutParams as LayoutParams).apply { width = dp(128); height = dp(128); topMargin = 0 }
        motion.layoutParams = FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER)
        hero.layoutParams = LayoutParams(-1, -1)
    }

    fun leaveWelcome() {
        if (!welcome) return
        welcome = false
        eyebrow.visibility = View.VISIBLE; heading.visibility = View.VISIBLE; caption.visibility = View.VISIBLE
        motion.visibility = View.VISIBLE
        hero.layoutParams = LayoutParams(-1, dp(274))
    }

    fun working(title: String, detail: String, recordingFraction: Float? = null) {
        resetReveal()
        hero.contentDescription = "$title. $detail"
        if (welcome) {
            logo.visibility = View.VISIBLE
            motion.visibility = View.VISIBLE
        } else {
            logo.visibility = View.GONE
            compact(true)
        }
        eyebrow.text = "주변 분위기 분석"; heading.text = title; caption.text = detail
        spinner.visibility = View.GONE
        if (recordingFraction == null) motion.show(AnalysisMotionView.Stage.ANALYZING)
        else motion.show(AnalysisMotionView.Stage.RECORDING, recordingFraction)
        showMetrics(null)
    }

    fun recording(elapsedMs: Long, durationMs: Long) {
        working("주변을 담고 있어요", "${(elapsedMs.coerceIn(0, durationMs.coerceAtLeast(1)) * 100 / durationMs.coerceAtLeast(1))}%",
            elapsedMs.toFloat() / durationMs.coerceAtLeast(1))
    }

    fun analyzing(processed: Long, total: Long) {
        working("무드를 분석하고 있어요", "${(processed.coerceAtMost(total) * 100 / total.coerceAtLeast(1))}%")
    }

    fun complete(result: RecommendationResult, animate: Boolean = true) {
        leaveWelcome()
        resetReveal()
        logo.visibility = View.GONE
        motion.show(AnalysisMotionView.Stage.IDLE)
        compact(false)
        spinner.visibility = View.GONE
        val words = result.commonMoodKeywords.filter { it != "수동 입력" }
        eyebrow.text = "지금 주변의 무드"
        heading.text = words.take(2).joinToString("\n").ifEmpty { "분석 정보가 부족해요" }
        caption.text = if (result.availability == kr.urbanmood.contracts.Availability.MANUAL) "직접 설정한 환경을 반영했어요" else if (words.isEmpty()) "다시 촬영해 주세요" else ""
        caption.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
        hero.contentDescription = heading.text
        showMetrics(result)
        val colors = when {
            "다채로운" in words -> intArrayOf(0xffb7a7e1.toInt(), 0xffefa9b4.toInt(), 0xff8dcce4.toInt())
            "따뜻한" in words -> intArrayOf(0xffefa783.toInt(), 0xfff2cf89.toInt(), 0xffe7a8b2.toInt())
            "활기 넘침" in words -> intArrayOf(0xffee9e99.toInt(), 0xfff0c187.toInt(), 0xffc4abe4.toInt())
            "차가운" in words || "청량한" in words -> intArrayOf(0xff74cbea.toInt(), 0xff91dfcc.toInt(), 0xffa9b9f2.toInt())
            "차분한" in words -> intArrayOf(0xff9cccb5.toInt(), 0xffd0dba6.toInt(), 0xffa5c8df.toInt())
            else -> intArrayOf(0xffa7cfe9.toInt(), 0xffb9ddcf.toInt(), 0xffedc4ab.toInt())
        }
        moodBackground.setPalette(colors, animate)
        updateMotion()
        if (animate && isShown && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            heading.alpha = 0f; heading.translationY = dp(8).toFloat()
            heading.animate().alpha(1f).translationY(0f).setDuration(650).start()
            metricStrip.alpha = 0f; metricStrip.translationY = dp(10).toFloat()
            metricStrip.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(650).start()
        }
    }

    fun interrupted() {
        resetReveal()
        logo.visibility = View.VISIBLE
        hero.contentDescription = "무드 분석 다시 시작"
        motion.show(AnalysisMotionView.Stage.IDLE)
        if (welcome) { showWelcome(); return }
        compact(false)
        spinner.visibility = View.GONE
        eyebrow.text = "분석 중단"; heading.text = "분석이 중단됐어요"; caption.text = "로고를 눌러 다시 시작하세요"
        heading.textSize = 20f
        showMetrics(null)
    }

    private fun resetReveal() {
        listOf(heading, metricStrip).forEach { it.animate().cancel(); it.alpha = 1f; it.translationY = 0f }
    }

    private fun showMetrics(result: RecommendationResult?) {
        dashboardMetrics(result?.scoreSummary, result?.commonMoodKeywords.orEmpty()).forEachIndexed { index, metric ->
            values[index].let { (name, value, unit) -> name.text = metric.keyword; value.text = metric.value; unit.text = metric.unit }
        }
    }
    private fun compact(busy: Boolean) {
        val height = dp(if (busy) 190 else 274)
        heading.textSize = if (busy) 23f else 27f
        if (hero.layoutParams.height != height) {
            hero.layoutParams = hero.layoutParams.apply { this.height = height }
            hero.getChildAt(0).layoutParams = (hero.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(if (busy) 198 else 246); this.height = width
            }
        }
        metricStrip.visibility = if (busy) View.GONE else View.VISIBLE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { moodBackground.stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateMotion() }
    private fun updateMotion() {
        if (isAttachedToWindow && isShown && windowVisibility == View.VISIBLE) moodBackground.start() else moodBackground.stop()
    }
    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(ink); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun surface(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
