package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import kotlin.math.abs

/** Fixed prototype interpretation of supplied sky statistics, not an emotion/model probability.
 * No IO, RGB remeasurement, quality invention, or addition to existing light/color scores.
 */
internal class SkyColorMoodScorer {
    internal data class FrameRule(val warm: Double, val cool: Double, val mixed: Double,
        val eligible: Boolean, val warmMatch: Boolean, val coolMatch: Boolean, val mixedMatch: Boolean)

    /** Per-frame diagnostic only. A match is NOT a temporal keyword. */
    fun inspect(o: SkyColorObservation): FrameRule? {
        if (!valid(o)) return null
        val s = o.statistics
        val warm = WARM.sumOf { s.hueSectorSaturationSums[it] }
        val cool = COOL.sumOf { s.hueSectorSaturationSums[it] }
        val denominator = s.saturationSum
        val eligible = s.skyPixCount.toDouble() / o.framePixelCount >= .02 &&
            (o.meanEncodedLuma ?: 0.0) >= .10 && (o.meanSaturation ?: 0.0) >= .05
        val w = if (denominator > 0) warm / denominator else 0.0
        val c = if (denominator > 0) cool / denominator else 0.0
        return FrameRule(70 * warm / o.framePixelCount, 70 * cool / o.framePixelCount,
            70 * (warm + cool) / o.framePixelCount, eligible,
            eligible && w >= .60, eligible && c >= .60,
            eligible && w >= .20 && c >= .20 && w + c >= .60)
    }

    fun score(window: SceneWindow, samples: List<SceneSample>): List<MoodScore> {
        if (window.durationMs !in 1..60_000 || window.bucketDurationMs <= 0) return emptyList()
        val expected = (window.durationMs - 1) / window.bucketDurationMs + 1
        if (expected > 60) return emptyList()
        val buckets = mutableSetOf<Long>()
        var previous: SkyFrameIdentity? = null
        var firstRecorded: Pair<Long, Long>? = null
        val rows = mutableListOf<FrameRule>()
        for (sample in samples) {
            if (sample.elapsedMs !in 0 until window.durationMs) continue
            val o = sample.vision.sceneObservations?.semantic?.skyColor ?: continue
            val f = o.frame
            if (f.capturedAtEpochMs != sample.vision.frameTimeEpochMs) continue
            val row = inspect(o) ?: continue
            val last = previous
            if (last != null && (f.inputSource != last.inputSource || f.sequence <= last.sequence ||
                    f.cameraTimestampNs <= last.cameraTimestampNs ||
                    (f.mediaPresentationTimeUs != null && f.mediaPresentationTimeUs <= last.mediaPresentationTimeUs!!))) continue
            if (f.inputSource == FrameInputSource.ANDROID_RECORDED) {
                val pts = f.mediaPresentationTimeUs!!
                val origin = firstRecorded
                // SceneSample elapsed for recordings is decoder PTS-relative, never processing time.
                if (origin != null && abs((pts - origin.first) / 1000.0 - (sample.elapsedMs - origin.second)) > 2.0) continue
                if (origin == null) firstRecorded = pts to sample.elapsedMs
            }
            if (!buckets.add(sample.elapsedMs / window.bucketDurationMs)) continue
            previous = f
            rows += row
        }
        if (rows.isEmpty()) return emptyList() // Unavailable must preserve the existing path exactly.
        fun mood(word: String, key: String, raw: (FrameRule) -> Double, match: (FrameRule) -> Boolean): MoodScore {
            val strength = rows.map(raw).average().toFloat()
            val support = rows.count(match).toFloat() / expected
            return MoodScore(word, strength, listOf(
                MoodScoreContribution("sky_color_${key}_strength", "하늘색 $word 원시 강도",
                    observedValue = strength, unit = "하늘색 규칙점수", weight = 70f, delta = strength,
                    reason = "70×색상군 채도합/전체 RGB 픽셀, 유효 ${rows.size}개 구간 평균. 어둡거나 작은 하늘도 원시값 보존; 기존 색·조명 점수와 합산하지 않음. 품질 미평가",
                    basis = ContributionBasis.SKY_COLOR_RULE),
                MoodScoreContribution("sky_color_${key}_support", "하늘색 $word 지속 근거",
                    observedValue = support, unit = "전체 창 충족 비율",
                    reason = "하늘 면적≥2%, 평균 encoded luma≥0.10, 평균 채도≥0.05 및 색상군 조건 충족 ${rows.count(match)}/$expected 구간. 결측·부적격도 분모에 포함; 50% 이상일 때만 자동 키워드",
                    basis = ContributionBasis.SKY_COLOR_RULE)))
        }
        return listOf(mood("따뜻한", "warm", { it.warm }, { it.warmMatch }),
            mood("차가운", "cool", { it.cool }, { it.coolMatch }),
            mood("다채로운", "mixed", { it.mixed }, { it.mixedMatch }))
    }

    private fun valid(o: SkyColorObservation): Boolean {
        val s = o.statistics
        val n = s.skyPixCount
        val f = o.frame
        if (o.measurementVersion != "sky-rgb-v1" || o.rgbRasterSource != "ANALYZER_INPUT_UPRIGHT_MAX640" ||
            o.rasterWidth !in 1..640 || o.rasterHeight !in 1..640 ||
            o.framePixelCount != o.rasterWidth * o.rasterHeight || n !in 0..o.framePixelCount ||
            f.sequence < 0 || f.capturedAtEpochMs < 0 || f.cameraTimestampNs < 0 ||
            (if (f.inputSource == FrameInputSource.ANDROID_RECORDED) f.mediaPresentationTimeUs?.let { it >= 0 } != true
                else f.mediaPresentationTimeUs != null)) return false
        if (listOf(o.skyMaskSha256, o.sourceRgbSha256, o.semanticModelSha256).any { !HASH.matches(it) }) return false
        if ((o.semanticRasterWidth == null) != (o.semanticRasterHeight == null) ||
            o.semanticRasterWidth?.let { it !in 1..640 } == true || o.semanticRasterHeight?.let { it !in 1..640 } == true) return false
        if (s.hueSectorCounts.size != 12 || s.hueSectorSaturationSums.size != 12 ||
            s.encodedLumaBinCounts.size != 4 || o.rowBandSkyPixCounts.size != 3 || s.achromaticPixCount !in 0..n) return false
        if ((s.hueSectorCounts + s.encodedLumaBinCounts + o.rowBandSkyPixCounts).any { it !in 0..n } ||
            s.hueSectorCounts.sumOf { it.toLong() } + s.achromaticPixCount != n.toLong() ||
            s.encodedLumaBinCounts.sumOf { it.toLong() } != n.toLong() ||
            o.rowBandSkyPixCounts.sumOf { it.toLong() } != n.toLong()) return false
        if (!s.saturationSum.isFinite() || s.saturationSum !in 0.0..n.toDouble() ||
            !s.encodedLumaSum.isFinite() || s.encodedLumaSum !in 0.0..n.toDouble()) return false
        if (s.hueSectorSaturationSums.indices.any { i -> !s.hueSectorSaturationSums[i].isFinite() ||
                s.hueSectorSaturationSums[i] !in 0.0..s.hueSectorCounts[i].toDouble() }) return false
        if (abs(s.hueSectorSaturationSums.sum() - s.saturationSum) > 1e-8 * maxOf(1, n)) return false
        if (n == 0) return o.meanSaturation == null && o.meanEncodedLuma == null
        return o.meanSaturation?.let { it.isFinite() && it in 0.0..1.0 && abs(it - s.saturationSum / n) <= 1e-8 } == true &&
            o.meanEncodedLuma?.let { it.isFinite() && it in 0.0..1.0 && abs(it - s.encodedLumaSum / n) <= 1e-8 } == true
    }

    companion object {
        const val VERSION_SUFFIX = "+sky-color-v1"
        // Explicit 30-degree histogram policy: warm [0,60) or [330,360); cool [150,270).
        // Other hues remain in the share denominator. Mixed raw strength is the disjoint union.
        private val WARM = listOf(0, 1, 11)
        private val COOL = listOf(5, 6, 7, 8)
        private val HASH = Regex("[a-f0-9]{64}")
        fun keywords(summary: MoodScoreSummary): List<String> = listOf("따뜻한", "차가운", "다채로운").filter { word ->
            summary.scores.any { it.keyword == word && it.contributions.any { c ->
                c.basis == ContributionBasis.SKY_COLOR_RULE && c.id.endsWith("_support") && (c.observedValue ?: 0f) >= .5f
            } }
        }
    }
}
