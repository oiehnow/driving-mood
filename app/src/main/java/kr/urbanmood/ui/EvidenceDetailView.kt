package kr.urbanmood.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Saved measurements in ordinary detail rows. The main screen already owns the mood hero. */
internal class EvidenceDetailView(context: Context) : LinearLayout(context) {
    private val ink = 0xff002c5f.toInt()
    private val muted = 0xff637083.toInt()
    private val separator = 0xffdfe4e9.toInt()
    private val stacked get() = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360

    init { orientation = VERTICAL; tag = "evidence_detail" }

    fun render(model: EvidencePresentation, recommendationOnly: Boolean = false) {
        removeAllViews()
        if (!recommendationOnly) section("분석 기록", model.inputScope)
        fact("주변 분위기", model.moods.joinToString(" · ").ifBlank { "분석 정보 부족" },
            valueTag = "evidence_observed_moods")

        if (!recommendationOnly) {
            section("색감")
            model.sources.take(2).forEach { source ->
                val group = column(this, top = 8, bottom = 16)
                label(group, source.title, 16, ink, true)
                label(group, source.subtitle, 12, muted)
                source.metrics.chunked(if (stacked) 1 else 2).forEach { pair ->
                    val row = LinearLayout(context)
                    pair.forEachIndexed { index, item ->
                        val cell = LinearLayout(context).apply { orientation = VERTICAL }
                        row.addView(cell, LayoutParams(0, -2, 1f).apply { if (index < pair.lastIndex) marginEnd = dp(20) })
                        metric(cell, item, showUnit = false)
                    }
                    group.addView(row)
                }
                source.notes.forEach { note ->
                    label(group, note, 12, muted).apply { setPadding(0, dp(8), 0, 0) }
                }
                divider()
            }

            section("밝기와 사람")
            model.sources.drop(2).forEach { source ->
                val group = column(this, bottom = 12)
                label(group, source.subtitle, 12, muted)
                source.metrics.forEach { metric(group, it) }
            }
            if (model.frameFacts.any { it.value != "미관측" }) {
                model.frameFacts.forEach { fact(it.label, it.value) }
                label(this, model.exclusion, 12, muted)
            }

            section("촬영 정보")
            model.context.forEach { fact(it.label, it.value, it.note) }
        }

        section("음악 추천")
        model.preferenceNotice?.let {
            label(this, it, 13, muted).apply { tag = "evidence_preference_notice"; setPadding(0, 0, 0, dp(12)) }
        }
        fact("추천에 반영한 취향", if (!model.desiredKnown) "선택 기록 없음" else
            model.desiredMoods.joinToString(" · ").ifBlank { "선택 안 함" },
            valueTag = "evidence_applied_preferences")
        fact("추천 분위기", model.musicMoods.joinToString(" · ").ifBlank { "추천 정보 없음" },
            valueTag = "evidence_music_moods")
        if (model.trackTitle != null) {
            val song = column(this, top = 18, bottom = 12)
            label(song, "첫 번째 추천곡", 12, muted)
            label(song, model.trackTitle, 20, ink, true)
            label(song, model.trackTags.joinToString(" · ").ifBlank { "곡 태그 없음" }, 13, muted)
        } else label(this, "추천 곡 없음", 13, muted).apply { setPadding(0, dp(16), 0, dp(12)) }
    }

    private fun section(title: String, subtitle: String? = null) {
        label(this, title, 19, ink, true).apply { setPadding(0, dp(24), 0, dp(8)) }
        subtitle?.let { label(this, it, 12, muted) }
    }

    private fun fact(title: String, value: String, note: String? = null, valueTag: String? = null) {
        val group = column(this, top = 14, bottom = 14)
        val row = LinearLayout(context).apply {
            orientation = if (stacked) VERTICAL else HORIZONTAL
            gravity = Gravity.TOP
        }
        val titleView = text(title, 14, muted)
        val valueView = text(value, 15, ink, true).apply {
            tag = valueTag; gravity = if (stacked) Gravity.START else Gravity.END
        }
        if (stacked) {
            row.addView(titleView, LayoutParams(-1, -2))
            row.addView(valueView, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        } else {
            row.addView(titleView, LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
            row.addView(valueView, LayoutParams(0, -2, 1.45f))
        }
        group.addView(row)
        note?.let { label(group, it, 12, muted).apply { setPadding(0, dp(6), 0, 0) } }
        divider()
    }

    private fun metric(parent: LinearLayout, item: EvidenceMetric, showUnit: Boolean = true) {
        val row = LinearLayout(context).apply {
            orientation = if (stacked) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(6))
        }
        val name = text(item.label, 14, muted)
        val display = if (!showUnit && item.value != null) item.display.removeSuffix(item.unit) else item.display
        val amount = text(display, if (item.exclusion == null) 16 else 13, ink, true)
        if (stacked) {
            row.addView(name, LayoutParams(-1, -2)); row.addView(amount, LayoutParams(-1, -2))
        } else {
            row.addView(name, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            row.addView(amount, LayoutParams(-2, -2))
        }
        parent.addView(row)
        parent.addView(Meter(context, item).apply { tag = "evidence_metric_${item.id}" }, LayoutParams(-1, dp(4)))
    }

    private fun column(parent: LinearLayout, top: Int = 0, bottom: Int = 0) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(0, dp(top), 0, dp(bottom))
        parent.addView(this, LayoutParams(-1, -2))
    }
    private fun divider() { addView(View(context).apply { setBackgroundColor(separator) }, LayoutParams(-1, dp(1))) }
    private fun label(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean = false) =
        text(value, size, color, bold).also { parent.addView(it, LayoutParams(-1, -2)) }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private class Meter(context: Context, private val metric: EvidenceMetric) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            contentDescription = "${metric.label} ${metric.display}, 척도 0–${metric.maximum.toInt()}${metric.unit}"
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            paint.color = 0xffe3e8ec.toInt()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            metric.fraction?.let { fraction ->
                paint.color = metric.color
                if (fraction > 0f) canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), radius, radius, paint)
            } ?: run {
                paint.color = 0xffa9b4bf.toInt(); paint.strokeWidth = resources.displayMetrics.density
                var x = -height.toFloat()
                while (x < width) { canvas.drawLine(x, height.toFloat(), x + height, 0f, paint); x += height * 1.5f }
            }
        }
    }
}
