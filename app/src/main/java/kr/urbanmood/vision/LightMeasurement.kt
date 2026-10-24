package kr.urbanmood.vision

import kr.urbanmood.contracts.LightPixelStats
import kotlin.math.*

/** Input coordinates are normalized in the upright, unmirrored input image. */
internal data class ObjectBox(val left: Float, val top: Float, val right: Float, val bottom: Float,
                              val label: String, val confidence: Float)
internal data class LightRegion(val left: Float, val top: Float, val right: Float, val bottom: Float,
                                val label: String, val excluded: Boolean)
internal data class PixelFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width in 1..640 && height in 1..640 && argb.size == width * height)
    }
}
internal data class MeasuredColors(val argb: Int?, val hue: Float?, val saturation: Float?,
                                   val brightness: Float?, val luminousArea: Float)
/** Reliability of sampled pixel statistics, never semantic recognition accuracy. */
internal data class PixelMeasurementQuality(
    val colorConfidence: Float = 0f,
    val lightConfidence: Float = 0f,
    val validPixelRatio: Float = 0f,
    val acceptedPixelCount: Int = 0,
    val darkPixelRatio: Float = 0f,
    val clippedPixelRatio: Float = 0f,
    val candidateClippedRatio: Float = 0f,
    val excludedBrightRatio: Float = 0f,
    val reasons: List<String> = emptyList(),
)
/** Optional same-frame measurement-grid selections. No RGB or Bitmap is retained.
 * Arrays are newly owned per call, row-major width*height. Consumers must not mutate them.
 * Semantic-eligible pixels are NOT accepted light candidates or proof of a light's type.
 */
internal data class LightPixelEvidence(
    val width: Int,
    val height: Int,
    val acceptedAmbientMask: BooleanArray,
    val semanticEligibleBrightMask: BooleanArray,
)
internal data class LightMeasurement(
    val score: Float?, val colors: MeasuredColors, val vehicleRatio: Float,
    val confidence: Float, val reasons: List<String>, val regions: List<LightRegion>,
    val signature: FloatArray,
    val pixelQuality: PixelMeasurementQuality = PixelMeasurementQuality(),
    // All accepted connected light regions, independent of the capped debug list.
    val candidateRegions: List<LightRegion> = emptyList(),
    val pixelEvidence: LightPixelEvidence? = null,
    val lightFeatures: MeasuredLightFeatures? = null,
)

/** Pixel measurements + conservative geometry heuristics, NOT a storefront classifier.
 * All thresholds are uncalibrated prototype settings. No location, disk, or network access.
 */
internal class LightMeasurer {
    private data class Blob(val pixels: IntArray, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val cx get() = (left + right) / 2f
        val cy get() = (top + bottom) / 2f
    }

    fun measure(frame: PixelFrame, boxes: List<ObjectBox>, detectorAtCapacity: Boolean,
                capturePixelEvidence: Boolean = false): LightMeasurement {
        val w = frame.width; val h = frame.height; val n = w * h
        val value = FloatArray(n); val saturation = FloatArray(n); val hue = FloatArray(n)
        val luma = FloatArray(n)
        var dark = 0; var clipped = 0; var mean = 0f
        val signature = FloatArray(48) // 4x4 coarse RGB; never stores source frames.
        val signatureCounts = IntArray(16)
        for (i in 0 until n) {
            val c = frame.argb[i]
            val r = ((c ushr 16) and 255) / 255f
            val g = ((c ushr 8) and 255) / 255f
            val b = (c and 255) / 255f
            val max = max(r, max(g, b)); val min = min(r, min(g, b)); val delta = max - min
            value[i] = max
            saturation[i] = if (max > 0f) delta / max else 0f
            hue[i] = if (delta < 0.0001f) 0f else {
                val sector = when (max) { r -> (g-b)/delta; g -> (b-r)/delta+2f; else -> (r-g)/delta+4f }
                ((sector * 60f) + 360f) % 360f
            }
            // Relative gamma-encoded camera brightness, not linear luminance or lux.
            luma[i] = .2126f*r + .7152f*g + .0722f*b
            mean += luma[i]
            if (max < .12f) dark++
            if (r > .98f && g > .98f && b > .98f) clipped++
            val cell = min(3, (i / w) * 4 / h) * 4 + min(3, (i % w) * 4 / w)
            signature[cell*3] += r; signature[cell*3+1] += g; signature[cell*3+2] += b
            signatureCounts[cell]++
        }
        mean /= n
        for (cell in 0..15) for (channel in 0..2) {
            signature[cell*3+channel] /= max(1, signatureCounts[cell])
        }
        val vehicleMask = BooleanArray(n)
        val otherMask = BooleanArray(n)
        val regions = mutableListOf<LightRegion>()
        fun rect(mask: BooleanArray, l: Float, t: Float, r: Float, b: Float, label: String) {
            val x0 = floor(l.coerceIn(0f,1f)*w).toInt().coerceIn(0,w)
            val y0 = floor(t.coerceIn(0f,1f)*h).toInt().coerceIn(0,h)
            val x1 = ceil(r.coerceIn(0f,1f)*w).toInt().coerceIn(0,w)
            val y1 = ceil(b.coerceIn(0f,1f)*h).toInt().coerceIn(0,h)
            if (x1 <= x0 || y1 <= y0) return
            for (y in y0 until y1) for (x in x0 until x1) mask[y*w+x] = true
            if (regions.size < 32) regions.add(LightRegion(x0.toFloat()/w,y0.toFloat()/h,
                x1.toFloat()/w,y1.toFloat()/h,label,true))
        }
        val vehicles = setOf("car", "bus", "truck", "motorcycle", "train", "bicycle")
        var vehicleCount = 0
        for (box in boxes) {
            if (!listOf(box.left,box.top,box.right,box.bottom,box.confidence).all { it.isFinite() }) continue
            if (box.confidence < .25f || box.right <= box.left || box.bottom <= box.top) continue
            val bw = box.right-box.left; val bh = box.bottom-box.top
            if (box.label in vehicles) {
                vehicleCount++
                val dx = bw*.14f+.012f; val dy = bh*.12f+.012f
                rect(vehicleMask,box.left-dx,box.top-dy,box.right+dx,box.bottom+dy,
                    "차량으로 추정되는 영역과 주변 불빛")
                // Separate reflection exclusion: not counted as detected vehicle area.
                rect(otherMask,box.left-dx,box.bottom,box.right+dx,1f,"차량 아래 반사된 빛이 있을 수 있는 영역")
            } else if (box.label == "traffic light" || box.label == "stop sign") {
                rect(otherMask,box.left-.01f,box.top-.01f,box.right+.01f,box.bottom+.01f,
                    "신호등 또는 교통 표지로 추정되는 영역")
            }
        }
        val vehicleRatio = vehicleMask.count { it }.toFloat()/n
        // Camera-angle-dependent priors, deliberately NOT called road/sky segmentation.
        for (y in 0 until h) for (x in 0 until w) {
            val ny = (y+.5f)/h; val nx = (x+.5f)/w
            val roadHalfWidth = .10f + max(0f,ny-.56f)*1.1f
            if (ny < .10f || ny > .82f || (ny > .56f && abs(nx-.5f) < roadHalfWidth)) otherMask[y*w+x] = true
        }
        regions.add(LightRegion(0f,.82f,1f,1f,"화면 아래·중앙의 도로로 추정되는 영역 제외",true))

        // Integral image permits local contrast measurement in bounded O(width*height).
        val integral = FloatArray((w+1)*(h+1))
        for (y in 0 until h) {
            var sum = 0f
            for (x in 0 until w) { sum += value[y*w+x]; integral[(y+1)*(w+1)+x+1] = integral[y*(w+1)+x+1]+sum }
        }
        val radius = max(3, min(w,h)/24)
        fun localMean(x: Int, y: Int): Float {
            val l = max(0,x-radius); val r = min(w,x+radius+1)
            val t = max(0,y-radius); val b = min(h,y+radius+1); val stride = w+1
            return (integral[b*stride+r]-integral[t*stride+r]-integral[b*stride+l]+integral[t*stride+l]) / ((r-l)*(b-t))
        }
        val bright = BooleanArray(n)
        var brightTotal = 0; var excludedBright = 0
        for (i in 0 until n) {
            val luminous = value[i] >= .62f && luma[i] >= .14f &&
                (value[i]-localMean(i%w,i/w) >= .10f || value[i] >= .90f)
            if (luminous) {
                brightTotal++
                if (vehicleMask[i] || otherMask[i]) excludedBright++ else bright[i] = true
            }
        }
        val blobs = components(bright,w,h)
        // Paired small white/red lights are uncertain, never positive store evidence.
        val paired = BooleanArray(blobs.size)
        // Bound pair comparisons even for fragmented sensor noise (max 128^2).
        val pairIndices = blobs.indices.filter { blobs[it].pixels.size >= 2 }
            .sortedByDescending { blobs[it].pixels.size }.take(128)
        for (position in pairIndices.indices) {
            val i = pairIndices[position]
            val a = blobs[i]
            if (a.pixels.size > n*.012f || a.cy/h < .28f) continue
            val aSat = a.pixels.sumOf { saturation[it].toDouble() }/a.pixels.size
            val aRed = a.pixels.count { hue[it] < 30f || hue[it] > 330f }.toFloat()/a.pixels.size
            if (aSat > .25 && aRed < .75f) continue
            for (next in position+1 until pairIndices.size) {
                val j = pairIndices[next]
                val b = blobs[j]
                if (b.pixels.size > n*.012f || b.cy/h < .28f) continue
                val dx = abs(a.cx-b.cx)/w
                if (dx !in .025f.. .30f || abs(a.cy-b.cy)/h > .025f) continue
                if (max(a.pixels.size,b.pixels.size).toFloat()/min(a.pixels.size,b.pixels.size) > 2.5f) continue
                val bSat = b.pixels.sumOf { saturation[it].toDouble() }/b.pixels.size
                val bRed = b.pixels.count { hue[it] < 30f || hue[it] > 330f }.toFloat()/b.pixels.size
                if ((aSat < .25 && bSat < .25) || (aRed > .75 && bRed > .75)) { paired[i]=true; paired[j]=true }
            }
        }
        val accepted = mutableListOf<Int>()
        val candidateRegions = mutableListOf<LightRegion>()
        val featureGroups = mutableListOf<LightPixelStats>()
        val featureSummary = LightPixelStatistics()
        val acceptedMask = if(capturePixelEvidence) BooleanArray(n) else null
        // bright already excludes vehicles, traffic objects, reflection columns and geometry.
        // Retain small/skinny/sparse components for measured semantic intersections only.
        val semanticEligibleMask = if(capturePixelEvidence) bright.copyOf() else null
        var rejected = 0; var candidates = 0; var billboard = 0; var glareCount = 0
        blobs.forEachIndexed { index, blob ->
            val area = blob.pixels.size.toFloat()/n
            val fill = blob.pixels.size.toFloat()/(blob.width*blob.height)
            val aspect = blob.width.toFloat()/blob.height
            val tooLarge = area > .10f || blob.width > w*.65f
            // Saturated neutral cores lose both color and source identity. This also rejects
            // some white signs: a conservative uncertainty veto, NOT a streetlamp classifier.
            val glare = area >= .002f && blob.pixels.count { value[it]>.98f && saturation[it]<.15f }.toFloat()/blob.pixels.size > .45f
            if(semanticEligibleMask!=null && (paired[index] || tooLarge || glare)) {
                blob.pixels.forEach { semanticEligibleMask[it]=false }
            }
            val eligible = !paired[index] && !tooLarge && !glare && area >= .0008f && fill >= .35f &&
                blob.width >= max(3f,w*.025f) && blob.height >= max(2f,h*.018f) && aspect in .4f..7f
            if (eligible) {
                accepted.addAll(blob.pixels.toList()); candidates++
                if(acceptedMask!=null) blob.pixels.forEach { acceptedMask[it]=true }
                val group = LightPixelStatistics()
                blob.pixels.forEach { group.add(frame.argb[it], hue[it], saturation[it]) }
                val stats = group.snapshot()
                featureGroups.add(stats)
                featureSummary.merge(stats)
                candidateRegions.add(LightRegion(blob.left.toFloat()/w,blob.top.toFloat()/h,
                    (blob.right+1f)/w,(blob.bottom+1f)/h,"주변 조명 후보",false))
            }
            else { rejected += blob.pixels.size; if (tooLarge) billboard++; if(glare) glareCount++ }
            if (regions.size < 64) regions.add(LightRegion(blob.left.toFloat()/w,blob.top.toFloat()/h,
                (blob.right+1f)/w,(blob.bottom+1f)/h,
                if (eligible) "조명으로 추정되는 영역 · 상점 여부는 알 수 없음" else if (paired[index]) "나란한 불빛 · 차량 불빛일 수 있음"
                else if (glare) "너무 밝은 불빛 · 가로등과 간판 구분 어려움"
                else if (tooLarge) "큰 불빛 · 전광판이거나 빛 번짐일 수 있음" else "작거나 반사된 불빛 · 구분 어려움",
                !eligible))
        }
        val luminousArea = accepted.size.toFloat()/n
        val colors = if (accepted.isEmpty()) MeasuredColors(null,null,null,null,0f) else {
            var sinSum = 0.0; var cosSum = 0.0; var weight = 0.0
            var satSum = 0f; var vSum = 0f
            val bins = IntArray(13) // 12 chromatic hue bins + achromatic.
            val rgbBins = Array(13) { LongArray(3) }
            for (i in accepted) {
                val rgb = frame.argb[i]; val r=(rgb ushr 16) and 255; val g=(rgb ushr 8) and 255; val b=rgb and 255
                satSum+=saturation[i]; vSum+=value[i]
                val bin = if (saturation[i]<.12f) 12 else (hue[i]/30f).toInt().coerceIn(0,11)
                bins[bin]++; rgbBins[bin][0]+=r; rgbBins[bin][1]+=g; rgbBins[bin][2]+=b
                if (saturation[i]>=.12f) {
                    val angle=hue[i]*PI/180.0; val sw=saturation[i].toDouble()
                    sinSum+=sin(angle)*sw; cosSum+=cos(angle)*sw; weight+=sw
                }
            }
            val dominant = bins.indices.maxBy { bins[it] }; val count = bins[dominant]
            val argb = (0xff shl 24) or ((rgbBins[dominant][0]/count).toInt() shl 16) or
                ((rgbBins[dominant][1]/count).toInt() shl 8) or (rgbBins[dominant][2]/count).toInt()
            // Undefined circular mean (e.g. balanced opposing colors) remains null.
            val meanHue = if (weight<.01 || hypot(sinSum,cosSum)/weight < .20) null
                else ((atan2(sinSum,cosSum)*180/PI+360)%360).toFloat()%360f
            MeasuredColors(argb,meanHue,satSum/accepted.size,vSum/accepted.size,luminousArea)
        }
        val clipping = clipped.toFloat()/n
        val exclusion = if (brightTotal==0) 0f else (excludedBright+rejected).toFloat()/brightTotal
        val reasons = mutableListOf("조명의 색감과 밝기를 살펴봅니다. 간판이나 상점인지는 확인할 수 없습니다.",
            "도로로 추정되는 화면 아래·중앙을 제외합니다. 촬영 각도에 따라 실제 조명도 빠질 수 있습니다.")
        if (vehicleCount>0) reasons.add("차량으로 추정되는 $vehicleCount 개 영역과 주변 불빛을 제외했습니다. 놓친 차량 불빛은 남을 수 있습니다.")
        if (vehicleRatio>.35f) reasons.add("차량에 가려 주변 조명을 충분히 볼 수 없습니다.")
        if (paired.any { it }) reasons.add("나란한 흰색·붉은 불빛은 차량 불빛일 수 있어 제외했습니다.")
        if (billboard>0) reasons.add("큰 불빛은 전광판이거나 빛 번짐일 수 있어 제외했습니다. 실제 간판도 빠질 수 있습니다.")
        if (glareCount>0) reasons.add("너무 밝은 불빛은 가로등과 간판을 구분하기 어려워 제외했습니다. 흰색 간판도 빠질 수 있습니다.")
        if (dark.toFloat()/n>.90f || mean<.045f) reasons.add("화면이 어두워 작은 차량이나 주변 조명을 구분하기 어렵습니다.")
        if (clipping>.08f) reasons.add("너무 밝게 찍힌 부분이 많아 원래 색감과 불빛의 경계를 알기 어렵습니다.")
        if (candidates==0) reasons.add("측정할 조명을 충분히 찾지 못했습니다. 주변에 조명이 없다는 뜻은 아닙니다.")
        else reasons.add("조명으로 추정되는 $candidates 개 영역의 색감과 밝기를 측정했습니다. 상점 영업 여부는 알 수 없습니다.")
        if (detectorAtCapacity) reasons.add("차량을 한 번에 살펴볼 수 있는 범위에 도달했습니다. 일부 차량 불빛이 남을 수 있습니다.")
        if (blobs.size>128) reasons.add("작은 불빛이 흩어져 있어 먼 조명인지 영상의 잡음인지 구분하기 어렵습니다.")
        if (brightTotal>0 && exclusion>.70f) reasons.add("대부분의 불빛이 차량·도로 또는 구분하기 어려운 영역에 있어 주변 분위기를 판단하기 어렵습니다.")
        val quality = (1f-clipping*3f).coerceIn(0f,1f) * (1f-vehicleRatio) * (if(blobs.size>128) .5f else 1f) *
            (if (mean<.045f) .4f else 1f) * (if (detectorAtCapacity) .5f else 1f)
        val confidence = (.55f*quality*(if(candidates==0) .25f else 1f)*(1f-exclusion*.6f)).coerceIn(0f,.55f)
        val score = if (clipping>.25f || mean>.8f) null else {
            val areaScore = 1f-exp(-luminousArea/.08f)
            (areaScore*(.65f+.35f*(colors.saturation ?: 0f)) *
                (.5f+.5f*(colors.brightness ?: 0f))*(1f-(vehicleRatio*1.5f).coerceAtMost(.8f)) *
                (if(detectorAtCapacity) .5f else 1f)).coerceIn(0f,1f)
        }
        val validRatio = (0 until n).count { !vehicleMask[it] && !otherMask[it] }.toFloat()/n
        // A clipped individual channel can bias hue even when the pixel is not white.
        val candidateClipped = if(accepted.isEmpty()) 0f else
            accepted.count { value[it]>.98f }.toFloat()/accepted.size
        val darkRatio = dark.toFloat()/n
        val qualityReasons = mutableListOf("측정 품질은 색감과 밝기를 얼마나 참고할 수 있는지 나타냅니다. 조명의 종류를 맞혔다는 뜻은 아닙니다.")
        val enoughPixels = accepted.size>=24 && luminousArea>=.001f && min(w,h)>=32 && validRatio>=.15f
        if(!enoughPixels) qualityReasons.add("남은 조명 영역이 작거나 영상이 작아 색감과 밝기를 충분히 측정하기 어렵습니다.")
        if(candidateClipped>.20f) qualityReasons.add("조명이 너무 밝게 찍혀 색감과 밝기를 제한적으로만 참고합니다.")
        if(detectorAtCapacity) qualityReasons.add("확인하지 못한 차량 불빛이 섞일 수 있어 측정 품질을 낮췄습니다.")
        if(exclusion>.70f) qualityReasons.add("차량이나 반사된 빛의 영향이 남을 수 있어 측정 품질을 낮췄습니다.")
        if(paired.any { it } || glareCount>0) qualityReasons.add("차량 불빛이나 빛 번짐이 의심되어 측정 결과를 제한적으로 참고합니다.")
        // Independent of the old mixed confidence ceiling (.55). Conservative, uncalibrated
        // gates: a well-sampled non-clipped colored patch can be useful while its source is unknown.
        val factors = listOf(
            if(!enoughPixels || clipping>.25f || mean>.8f) 0f else 1f,
            (luminousArea/.005f).coerceIn(0f,1f),
            (validRatio/.35f).coerceIn(0f,1f),
            if(mean<.045f || darkRatio>.90f) .25f else if(mean<.08f) .55f else 1f,
            if(clipping>.08f) .4f else if(candidateClipped>.20f) .55f else 1f,
            (1f-vehicleRatio).coerceIn(0f,1f),
            if(detectorAtCapacity) .35f else 1f,
            if(blobs.size>128) .25f else 1f,
            if(exclusion>.70f) .4f else 1f,
            if(paired.any { it } || glareCount>0) .55f else 1f,
        )
        val malformedBoxes=boxes.any { box ->
            !listOf(box.left,box.top,box.right,box.bottom,box.confidence).all { it.isFinite() } ||
                box.confidence !in 0f..1f || box.right<=box.left || box.bottom<=box.top
        }
        if(malformedBoxes) qualityReasons.add("차량 위치를 제대로 확인하지 못해 측정 결과를 사용할 수 없습니다.")
        val lightQuality=if(malformedBoxes) 0f else .85f*factors.min()
        val mixedHue=colors.hue==null && (colors.saturation ?: 0f)>=.12f
        if(mixedHue) qualityReasons.add("여러 색이 섞여 하나의 대표 색조로 나타내기 어렵습니다.")
        val colorQuality=lightQuality*(if(mixedHue) .5f else 1f)
        qualityReasons.add("조명 측정에 살펴본 범위는 화면의 ${(validRatio*100).toInt()}%입니다. 측정한 조명 중 ${(candidateClipped*100).toInt()}%는 너무 밝게 찍혀 원래 색이 달라졌을 수 있습니다.")
        val pixelQuality=PixelMeasurementQuality(colorQuality,lightQuality,validRatio,accepted.size,
            darkRatio,clipping,candidateClipped,exclusion.coerceIn(0f,1f),qualityReasons)
        return LightMeasurement(score,colors,vehicleRatio,confidence,reasons,regions,signature,pixelQuality,
            candidateRegions.toList(),
            if(acceptedMask!=null && semanticEligibleMask!=null)
                LightPixelEvidence(w,h,acceptedMask,semanticEligibleMask) else null,
            MeasuredLightFeatures(w,h,featureSummary.snapshot(),featureGroups.toList()))
    }

    private fun components(mask: BooleanArray, width: Int, height: Int): List<Blob> {
        val visited = BooleanArray(mask.size); val queue = IntArray(mask.size)
        val result = mutableListOf<Blob>()
        for (seed in mask.indices) {
            if (!mask[seed] || visited[seed]) continue
            var head=0; var tail=1; queue[0]=seed; visited[seed]=true
            var left=seed%width; var right=left; var top=seed/width; var bottom=top
            fun add(i:Int) { if(mask[i] && !visited[i]) { visited[i]=true; queue[tail++]=i } }
            while(head<tail) {
                val i=queue[head++]; val x=i%width; val y=i/width
                left=min(left,x); right=max(right,x); top=min(top,y); bottom=max(bottom,y)
                if(x>0) add(i-1); if(x+1<width) add(i+1)
                if(y>0) add(i-width); if(y+1<height) add(i+width)
            }
            result.add(Blob(queue.copyOf(tail),left,top,right,bottom))
        }
        return result
    }
}
