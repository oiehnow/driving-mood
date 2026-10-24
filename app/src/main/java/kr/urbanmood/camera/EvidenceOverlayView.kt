package kr.urbanmood.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kr.urbanmood.R
import kr.urbanmood.contracts.*
import kotlin.math.min

internal data class EvidenceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** FIT_CENTER letterbox mapping; the analyzer already rotated the input upright. */
internal fun fitEvidence(region: RegionEvidence, imageWidth: Int, imageHeight: Int,
    viewWidth: Int, viewHeight: Int): EvidenceBounds? {
    if (minOf(imageWidth, imageHeight, viewWidth, viewHeight) <= 0 ||
        listOf(region.left, region.top, region.right, region.bottom).any { !it.isFinite() || it !in 0f..1f } ||
        region.left >= region.right || region.top >= region.bottom) return null
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val x = (viewWidth - imageWidth * scale) / 2
    val y = (viewHeight - imageHeight * scale) / 2
    return EvidenceBounds(x + region.left * imageWidth * scale, y + region.top * imageHeight * scale,
        x + region.right * imageWidth * scale, y + region.bottom * imageHeight * scale)
}

/** Shows only this frame's actual observations; never attaches old scores to new boxes. */
class EvidenceOverlayView(context: Context) : View(context) {
    private var regions = emptyList<RegionEvidence>()
    private var imageWidth = 0
    private var imageHeight = 0
    private var captions = emptyMap<String, List<String>>()
    private var scored = false
    private var scoreFailed = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density }
    private val background = Paint().apply { color = Color.argb(205, 16, 27, 35) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = resources.getFont(R.font.pretendard_variable)
    }
    private val expire = Runnable { clear() }

    fun show(value: VisionResult, receipt: FrameReceipt, summary: MoodScoreSummary? = null, calculationFailed: Boolean = false) {
        removeCallbacks(expire)
        regions = value.sceneObservations?.regions.orEmpty().toList()
        imageWidth = receipt.width; imageHeight = receipt.height
        captions = regionScoreCaptions(regions, summary)
        scored = summary != null
        scoreFailed = calculationFailed
        contentDescription = regions.take(12).joinToString(". ") { it.label + (it.exclusionReason?.let { why -> " · $why" } ?: "") }
        invalidate()
        postDelayed(expire, 1_500)
    }

    fun clear() { removeCallbacks(expire); regions = emptyList(); captions = emptyMap(); contentDescription = ""; invalidate() }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || regions.isEmpty()) return
        val pad = 3 * resources.displayMetrics.density
        val headerHeight = label.fontSpacing + 2 * pad
        val maxLabels = ((height - headerHeight) / (label.fontSpacing * 4 + 2 * pad)).toInt().coerceIn(1, 4)
        val shown = regions.filter { fitEvidence(it, imageWidth, imageHeight, width, height) != null }
            .sortedWith(compareByDescending<RegionEvidence> { captions[it.id]?.isNotEmpty() == true }
                .thenByDescending { (it.right - it.left) * (it.bottom - it.top) }).take(maxLabels)
        val header = "현재 프레임 · 주요 영역 ${shown.size}/${regions.size}"
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, background)
        canvas.drawText(ellipsize(header, width - 2 * pad), pad, pad - label.ascent(), label)
        val occupied = mutableListOf<RectF>()
        for (region in shown) {
            val bounds = fitEvidence(region, imageWidth, imageHeight, width, height) ?: continue
            line.color = if (region.exclusionReason != null) Color.rgb(255, 177, 119) else Color.rgb(157, 228, 203)
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, line)
            val origin = when (region.source) {
                RegionEvidenceSource.OBJECT_DETECTOR -> "검출"
                RegionEvidenceSource.PIXEL_HEURISTIC -> "빛 후보"
                RegionEvidenceSource.SEMANTIC_COMPONENT -> "분할 영역"
            }
            val lines = listOf("${region.label} · $origin") + if (region.exclusionReason != null)
                listOf(region.exclusionReason) else captions[region.id]
                    ?: listOf(if (scoreFailed) "현재 프레임 기여 계산 미완료" else if (scored) "개별 점수 미반영 · 근거 부족/전체 화면 근거" else "현재 프레임 기여 계산 중")
            val fitted = lines.map { ellipsize(it, (width - 2 * pad).coerceAtLeast(0f)) }
            val textWidth = fitted.maxOf(label::measureText)
            val boxWidth = textWidth + 2 * pad
            val x = bounds.left.coerceIn(0f, (width - boxWidth).coerceAtLeast(0f))
            val lineHeight = label.fontSpacing
            val boxHeight = (fitted.size - 1) * lineHeight + label.descent() - label.ascent() + 2 * pad
            val maxTop = (height - boxHeight).coerceAtLeast(headerHeight)
            val candidates = listOf((bounds.top - boxHeight).coerceIn(headerHeight, maxTop),
                bounds.bottom.coerceIn(headerHeight, maxTop)) +
                generateSequence(headerHeight) { it + lineHeight + pad }.takeWhile { it <= maxTop }.toList()
            val boxes = candidates.map { RectF(x, it, x + boxWidth, it + boxHeight) }
            val box = boxes.firstOrNull { candidate -> occupied.none { RectF.intersects(candidate, it) } }
                ?: continue // Keep the evidence rectangle; never cover another label with text.
            occupied += box
            canvas.drawLine((bounds.left + bounds.right) / 2, bounds.top, box.centerX(), box.bottom, line)
            canvas.drawRect(box, background)
            val baseline = box.top + pad - label.ascent()
            fitted.forEachIndexed { index, text -> canvas.drawText(text, box.left + pad, baseline + index * lineHeight, label) }
        }
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (label.measureText(text) <= maxWidth) return text
        val suffix = "…"
        val available = maxWidth - label.measureText(suffix)
        if (available <= 0) return ""
        var count = label.breakText(text, true, available, null)
        if (count > 0 && Character.isHighSurrogate(text[count - 1])) count--
        return text.take(count).trimEnd() + suffix
    }
}
