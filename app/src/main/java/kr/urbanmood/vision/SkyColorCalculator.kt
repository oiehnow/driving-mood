package kr.urbanmood.vision

import kr.urbanmood.contracts.SkyColorStatistics

/** Computes bounded sky-color statistics from an aligned RGB raster and mask.
 * PixelFrame and mask are borrowed, opaque upright source RGB and exact same-size mask.
 * Return null for invalid input; return measured zero statistics for an empty valid mask.
 * Do not retain/mutate input arrays. No clock, model, IO, mood weight or frame cache.
 */
internal fun interface SkyColorCalculator {
    fun calculate(frame: PixelFrame, skyMask: BooleanArray): SkyColorStatistics?
}
