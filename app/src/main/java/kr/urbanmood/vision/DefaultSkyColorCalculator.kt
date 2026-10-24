/*
 * RGB-to-HSV arithmetic adapted from Skia SkRGBToHSV:
 * https://github.com/google/skia/blob/main/src/core/SkColor.cpp
 * Copyright 2006 The Android Open Source Project
 * Copyright (c) 2011 Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package kr.urbanmood.vision

import kr.urbanmood.contracts.SkyColorStatistics

/** Encoded RGB observations only: no mood, physical luminance or sky confidence.
 * Achromatic means exactly R=G=B, including black. Alpha is ignored.
 * All accumulators are local; input arrays are neither retained nor modified.
 */
internal object DefaultSkyColorCalculator : SkyColorCalculator {
    override fun calculate(frame: PixelFrame, skyMask: BooleanArray): SkyColorStatistics? {
        // PixelFrame already enforces these dimensions; retain a defensive guard here.
        if (frame.width !in 1..640 || frame.height !in 1..640) return null
        val size = frame.width * frame.height
        if (frame.argb.size != size || skyMask.size != size) return null
        val hues = IntArray(12)
        val hueSums = DoubleArray(12)
        val lumas = IntArray(4)
        var count = 0
        var achromatic = 0
        var saturationSum = 0.0
        var lumaSum = 0.0
        for (i in 0 until size) {
            if (!skyMask[i]) continue
            count++
            val color = frame.argb[i]
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            val max = maxOf(r, g, b)
            val delta = max - minOf(r, g, b)
            // Integer coefficients exactly express the contract's decimal weights.
            // The numerator fits Int and permits exact .25/.5/.75 bin boundaries.
            val lumaNumerator = 2126 * r + 7152 * g + 722 * b
            lumaSum += lumaNumerator / 2550000.0
            lumas[minOf(3, lumaNumerator * 4 / 2550000)]++
            if (delta == 0) {
                achromatic++
                continue
            }
            val saturation = delta.toDouble() / max
            var hue = when (max) {
                r -> (g - b).toDouble() / delta
                g -> 2.0 + (b - r).toDouble() / delta
                else -> 4.0 + (r - g).toDouble() / delta
            } * 60.0
            if (hue < 0.0) hue += 360.0
            val sector = (hue / 30.0).toInt()
            hues[sector]++
            hueSums[sector] += saturation
            saturationSum += saturation
        }
        return SkyColorStatistics(count, hues.toList(), hueSums.toList(), achromatic,
            saturationSum, lumaSum, lumas.toList())
    }
}
