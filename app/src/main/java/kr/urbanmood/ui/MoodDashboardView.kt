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
    private val heading = label("아직 만나지 않은\n오늘의 무드", 27, true)
    private val eyebrow = label("지금, 이 길의 분위기", 12)
    private val caption = label("짧게 담고, 분위기를 발견하세요", 12)
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(ink); visibility = View.GONE
        contentDescription = "무드 분석 진행 중"
    }
    private val values = mutableListOf<Triple<TextView, TextView, TextView>>()
    private lateinit var metricStrip: LinearLayout
    private val moodBackground = MoodGradientDrawable(dp(30).toFloat())

    init {
        orientation = VERTICAL
        hero.background = moodBackground
        addView(hero, LayoutParams(-1, dp(274)))
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(1), 0x99ffffff.toInt())
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hero.addView(ring, FrameLayout.LayoutParams(dp(222), dp(222), Gravity.CENTER))
        val center = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), dp(10)) }
        center.addView(eyebrow)
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

    fun working(title: String, detail: String) {
        compact(true)
        eyebrow.text = "주변을 알아가는 중"; heading.text = title; caption.text = detail
        spinner.visibility = View.VISIBLE
        showMetrics(null)
    }

    fun complete(result: RecommendationResult, animate: Boolean = true) {
        compact(false)
        spinner.visibility = View.GONE
        val words = result.commonMoodKeywords.filter { it != "수동 입력" }
        eyebrow.text = "지금 주변의 무드"
        heading.text = words.take(2).joinToString("\n").ifEmpty { "조금 더 살펴볼까요?" }
        caption.text = if (result.availability == kr.urbanmood.contracts.Availability.MANUAL) "직접 설정한 환경 반영" else if (words.isEmpty()) "분위기를 정할 단서가 부족해요" else "이 분위기를 음악과 조명으로"
        showMetrics(result)
        val colors = when {
            "다채로운" in words -> intArrayOf(0xffddd8ef.toInt(), 0xfff2d8d6.toInt(), 0xffd5e6f0.toInt())
            "따뜻한" in words -> intArrayOf(0xfff2c9b5.toInt(), 0xfff4dbbf.toInt(), 0xfff0d1c8.toInt())
            "활기 넘침" in words -> intArrayOf(0xfff0c6bd.toInt(), 0xfff4d9bf.toInt(), 0xffecd5e1.toInt())
            "차가운" in words || "청량한" in words -> intArrayOf(0xffc6dfed.toInt(), 0xffd2eae4.toInt(), 0xffd9e0f0.toInt())
            "차분한" in words -> intArrayOf(0xffd2e4d8.toInt(), 0xffe1eadb.toInt(), 0xffd5e4e9.toInt())
            else -> intArrayOf(0xffd7e8f2.toInt(), 0xffe2eee8.toInt(), 0xfff4e4dc.toInt())
        }
        moodBackground.setPalette(colors, animate)
    }

    fun interrupted() {
        compact(false)
        spinner.visibility = View.GONE
        eyebrow.text = "다시 시작할 수 있어요"; heading.text = "잠시 멈췄어요"; caption.text = "무드 분석을 눌러 다시 담아보세요"
        showMetrics(null)
    }

    private fun showMetrics(result: RecommendationResult?) {
        dashboardMetrics(result?.scoreSummary, result?.commonMoodKeywords.orEmpty()).forEachIndexed { index, metric ->
            values[index].let { (name, value, unit) -> name.text = metric.keyword; value.text = metric.value; unit.text = metric.unit }
        }
    }
    private fun compact(busy: Boolean) {
        val height = dp(if (busy) 190 else 274)
        if (hero.layoutParams.height != height) {
            hero.layoutParams = hero.layoutParams.apply { this.height = height }
            hero.getChildAt(0).layoutParams = (hero.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply {
                width = dp(if (busy) 164 else 222); this.height = width
            }
            heading.textSize = if (busy) 23f else 27f
        }
        metricStrip.visibility = if (busy) View.GONE else View.VISIBLE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { moodBackground.stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateMotion() }
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
