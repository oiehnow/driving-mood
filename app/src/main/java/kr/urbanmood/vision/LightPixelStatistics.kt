package kr.urbanmood.vision

import kr.urbanmood.contracts.LightPixelStats

/** Recorded RGB statistics, not corrected source color or a confidence estimate. */
internal class LightPixelStatistics {
    private val categories = IntArray(8)
    private val upperChannels = IntArray(4)
    private var count = 0
    private var saturationSum = 0.0
    private var redYellowSum = 0.0
    private var cyanBlueSum = 0.0

    fun add(argb: Int, hue: Float, saturation: Float) {
        // For 8-bit RGB, exactly the existing channel > .98 test, without per-pixel lists.
        var upper = 0
        if (((argb ushr 16) and 255) >= 250) upper++
        if (((argb ushr 8) and 255) >= 250) upper++
        if ((argb and 255) >= 250) upper++
        count++
        upperChannels[upper]++
        if (upper == 3) {
            categories[7]++
            return
        }
        val chromatic = saturation >= .20f
        val category = if (!chromatic) 6 else (((hue + 30f) % 360f) / 60f).toInt().coerceIn(0, 5)
        categories[category]++
        saturationSum += saturation.toDouble()
        if (chromatic && (hue < 65f || hue > 330f)) redYellowSum += saturation.toDouble()
        if (chromatic && hue >= 165f && hue < 255f) cyanBlueSum += saturation.toDouble()
    }

    /** Merging disjoint measured groups, never their bounding-box areas. */
    fun merge(stats: LightPixelStats) {
        count += stats.pixelCount
        for (i in categories.indices) categories[i] += stats.categoryCounts[i]
        for (i in upperChannels.indices) upperChannels[i] += stats.upperChannelCounts[i]
        saturationSum += stats.saturationSum.toDouble()
        redYellowSum += stats.redYellowSaturationSum.toDouble()
        cyanBlueSum += stats.cyanBlueSaturationSum.toDouble()
    }

    fun snapshot() = LightPixelStats(count, categories.toList(), upperChannels.toList(),
        saturationSum.toFloat(), redYellowSum.toFloat(), cyanBlueSum.toFloat())
}

/** Numeric summaries only. Groups match candidateRegions in order; no pixel arrays survive. */
internal data class MeasuredLightFeatures(
    val width: Int,
    val height: Int,
    val summary: LightPixelStats,
    val groups: List<LightPixelStats>,
) {
    companion object { const val VERSION = "accepted-light-rgb-v1" }
}
