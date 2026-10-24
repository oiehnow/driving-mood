package kr.urbanmood.vision

import kr.urbanmood.contracts.*
import kotlin.math.*


/** Fixed semantic contrast observation rules. Actual RGB observations only.
 * Independent Kotlin numerical kernels have a separate version, not an assumed OpenCV match.
 * No hard-coded inspection ROI, mood labels, quality=1, or source-type classification.
 */
internal class ReferenceSemanticContrastObserver {
    fun observe(frame: PixelFrame, classes: ByteArray, boxes: List<ObjectBox>, atCapacity: Boolean,
                frameKey: String, captureMasks: Boolean = false): SemanticContrastResult {
        val w=frame.width; val h=frame.height; val n=w*h; val edge=max(w,h)
        require(classes.size==n && classes.all { (it.toInt() and 255)<150 })
        val support=BooleanArray(n);val forbidden=BooleanArray(n);val semanticVeto=BooleanArray(n)
        val vehicle=BooleanArray(n)
        val classCounts=IntArray(150)
        var skyLuma=0.0
        for (i in 0 until n) {
            val c=classes[i].toInt() and 255;classCounts[c]++
            support[i]=c==1 || c==8 || c==43
            semanticVeto[i]=c==20 || c==80 || c==83 || c==102 || c==116 || c==127 || c==136 || c==87
            forbidden[i]=c==6 || c==11
            if(c==2) { val rgb=frame.argb[i];skyLuma+=(.2126*((rgb ushr 16) and 255)+.7152*((rgb ushr 8) and 255)+.0722*(rgb and 255))/255 }
        }
        val dilated=dilate(semanticVeto,w,h,max(1,Math.rint(edge/320.0).toInt()))
        for(i in 0 until n) forbidden[i]=forbidden[i] || dilated[i]
        var malformed=false
        fun rect(mask:BooleanArray,l:Float,t:Float,r:Float,b:Float) {
            val x0=floor(l.coerceIn(0f,1f)*w).toInt();val x1=ceil(r.coerceIn(0f,1f)*w).toInt()
            val y0=floor(t.coerceIn(0f,1f)*h).toInt();val y1=ceil(b.coerceIn(0f,1f)*h).toInt()
            for(y in y0 until y1) for(x in x0 until x1) mask[y*w+x]=true
        }
        for(b in boxes) {
            if(!listOf(b.left,b.top,b.right,b.bottom,b.confidence).all { it.isFinite() } || b.right<=b.left || b.bottom<=b.top || b.confidence !in 0f..1f) { malformed=true;continue }
            if(b.confidence<.25f) continue
            if(b.label in VEHICLES) {
                val dx=(b.right-b.left)*.14f+.012f;val dy=(b.bottom-b.top)*.12f+.012f
                rect(vehicle,b.left-dx,b.top-dy,b.right+dx,b.bottom+dy)
                rect(forbidden,b.left-dx,b.bottom,b.right+dx,1f)
            } else if(b.label=="traffic light" || b.label=="stop sign") rect(forbidden,b.left-.01f,b.top-.01f,b.right+.01f,b.bottom+.01f)
        }
        for(i in 0 until n) forbidden[i]=forbidden[i] || vehicle[i]
        val eligible=BooleanArray(n) { support[it] && !forbidden[it] }
        val eligibleCount=eligible.count { it }
        val referenceHistogram=IntArray(256)
        for(i in 0 until n) if(eligible[i]) {
            val c=frame.argb[i];referenceHistogram[max((c ushr 16) and 255,max((c ushr 8) and 255,c and 255))]++
        }
        val reference=quantile(referenceHistogram,eligibleCount,.95)
        val residualHistogram=IntArray(256)
        val channels=Array(3) { ch -> FloatArray(n) { ((frame.argb[it] ushr (16-ch*8)) and 255).toFloat() } }
        val neighbors=IntArray(9)
        for(channel in channels) for(y in 0 until h) for(x in 0 until w) {
            var k=0
            for(dy in -1..1) for(dx in -1..1) neighbors[k++]=channel[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)].toInt()
            neighbors.sort();residualHistogram[abs(channel[y*w+x].toInt()-neighbors[4])]++
        }
        val noise=.1*quantile(residualHistogram,3*n,.50)+.9*quantile(residualHistogram,3*n,.60)
        val valueFloor=max(24.0,.35*reference)
        val contrastFloor=max(12.0,max(.08*reference,3*noise))
        val broadFloor=max(8.0,max(.05*reference,2*noise))
        val compact=BooleanArray(n);val broad=BooleanArray(n)
        val sizes=intArrayOf(9,25).map { max(3,Math.rint(it*edge/640.0).toInt()/2*2+1) }
        for(channel in channels) {
            for(size in sizes) {
                val opened=morphology(morphology(channel,w,h,size,false),w,h,size,true)
                val mean=gaussian(channel,w,h,max(1.0,size/3.0))
                val square=FloatArray(n) { channel[it]*channel[it] }
                val meanSquare=gaussian(square,w,h,max(1.0,size/3.0))
                for(i in 0 until n) if(eligible[i] && !compact[i]) {
                    val delta=channel[i]-opened[i]
                    val deviation=sqrt(max(0f,meanSquare[i]-mean[i]*mean[i]))
                    if(delta>=contrastFloor && delta/(opened[i]+16)>=.60 && channel[i]>=valueFloor && channel[i]-mean[i]>=.8*deviation) compact[i]=true
                }
            }
            val smooth=gaussian(channel,w,h,max(.8,2*edge/640.0))
            val sigma=max(3.0,18*edge/640.0)
            val surround=gaussian(channel,w,h,sigma)
            val surroundSquare=gaussian(FloatArray(n) { smooth[it]*smooth[it] },w,h,sigma)
            for(i in 0 until n) if(eligible[i]) {
                val delta=smooth[i]-surround[i]
                val deviation=sqrt(max(0f,surroundSquare[i]-surround[i]*surround[i]))
                if(delta>=broadFloor && delta/(surround[i]+16)>=.25 && channel[i]>=valueFloor && delta>=.6*deviation) broad[i]=true
            }
        }
        for(i in 0 until n) broad[i]=broad[i] && !compact[i]
        val groups=mutableListOf<LightGroupFeature>();val regions=mutableListOf<RegionEvidence>()
        val summary=LightPixelStatistics()
        fun collect(mask:BooleanArray,kind:LightGroupKind,radius:Int) {
            val joined=dilate(mask,w,h,radius)
            for(i in 0 until n) joined[i]=joined[i] && eligible[i]
            val queue=IntArray(n);var localId=0
            for(seed in 0 until n) if(joined[seed]) {
                localId++;var head=0;var tail=1;queue[0]=seed;joined[seed]=false
                val stats=LightPixelStatistics();var left=w;var top=h;var right=0;var bottom=0
                while(head<tail) {
                    val i=queue[head++];val x=i%w;val y=i/w
                    if(mask[i]) {
                        val color=frame.argb[i];val r=((color ushr 16) and 255)/255f;val g=((color ushr 8) and 255)/255f;val b=(color and 255)/255f
                        val v=max(r,max(g,b));val delta=v-min(r,min(g,b));val sat=if(v>0) delta/v else 0f
                        val sector=when(v) { r -> if(delta<.0001f) 0f else (g-b)/delta; g -> (b-r)/delta+2f; else -> (r-g)/delta+4f }
                        val hue=if(delta<.0001f) 0f else (sector*60f+360f)%360f
                        stats.add(color,hue,sat)
                        left=min(left,x);top=min(top,y);right=max(right,x+1);bottom=max(bottom,y+1)
                    }
                    fun visit(j:Int) { if(joined[j]) {joined[j]=false;queue[tail++]=j} }
                    if(x>0)visit(i-1);if(x+1<w)visit(i+1);if(y>0)visit(i-w);if(y+1<h)visit(i+w)
                }
                val pixels=stats.snapshot()
                if(pixels.pixelCount>0) {
                    val id="$frameKey:semantic-light:${kind.name}:$localId"
                    groups.add(LightGroupFeature(id,kind,pixels));summary.merge(pixels)
                    regions.add(RegionEvidence(id,left.toFloat()/w,top.toFloat()/h,right.toFloat()/w,bottom.toFloat()/h,
                        if(kind==LightGroupKind.LOCAL_CONTRAST) "작은 밝은 부분" else "넓은 밝은 면",RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC))
                }
            }
        }
        collect(compact,LightGroupKind.LOCAL_CONTRAST,max(1,Math.rint(2*edge/640.0).toInt()))
        collect(broad,LightGroupKind.EXTENDED_BRIGHT_SURFACE,max(1,Math.rint(3*edge/640.0).toInt()))
        val stats=summary.snapshot()
        val flags=mutableListOf("SOURCE_TYPE_UNCONFIRMED","SEMANTIC_MASK_QUALITY_UNEVALUATED",
            "VEHICLE_TRAFFIC_REFLECTION_MISSES_REMAIN","DAYLIGHT_AND_EMISSION_NOT_SEPARATED")
        if(atCapacity) flags.add("DETECTOR_AT_CAPACITY")
        if(malformed) flags.add("INVALID_DETECTOR_RECORDS")
        if(stats.pixelCount==0) flags.add("NO_OBSERVED_PIXELS")
        if(stats.upperChannelCounts[1]+stats.upperChannelCounts[2]>0) flags.add("SOME_CHANNELS_NEAR_UPPER_LIMIT")
        if(stats.upperChannelCounts[3]>0) flags.add("ALL_CHANNELS_NEAR_UPPER_LIMIT")
        return SemanticContrastResult(LightFeatureObservation(VERSION,w,h,stats,groups.toList(),
            LightFeatureQuality(null,eligibleCount.toFloat()/n,flags.toList(),listOf("촬영된 밝은 부분의 색과 면적입니다. 상점·자발광 여부는 미확인입니다."))),regions.toList(),
            SemanticSceneObservation(personAreaRatio=classCounts[12].toFloat()/n,skyAreaRatio=classCounts[2].toFloat()/n,
                buildingAreaRatio=classCounts[1].toFloat()/n,signboardAreaRatio=classCounts[43].toFloat()/n,
                skyBrightness=if(classCounts[2]>0) (skyLuma/classCounts[2]).toFloat() else null,measurementQuality=null),
            vehicle.count { it }.toFloat()/n,if(captureMasks) compact else null,if(captureMasks) broad else null)
    }

    private fun quantile(hist:IntArray,count:Int,q:Double):Double {
        if(count==0)return 0.0
        val index=(count-1)*q;val low=floor(index).toInt();val high=ceil(index).toInt()
        var total=0;var a=0;var b=0;var found=false
        for(i in hist.indices) { total+=hist[i];if(!found && total>low){a=i;found=true};if(total>high){b=i;break} }
        return a+(b-a)*(index-low)
    }
    private fun reflect(i:Int,size:Int):Int { var p=i;while(p<0 || p>=size) p=if(p<0)-p-1 else 2*size-p-1;return p }
    private fun gaussian(input:FloatArray,w:Int,h:Int,sigma:Double):FloatArray {
        val size=Math.rint(sigma*8+1).toInt() or 1;val radius=size/2
        val kernel=DoubleArray(size) { exp(-.5*(it-radius)*(it-radius)/(sigma*sigma)) }
        val sum=kernel.sum();val weights=FloatArray(size) { (kernel[it]/sum).toFloat() }
        val horizontal=FloatArray(input.size);val output=FloatArray(input.size)
        for(y in 0 until h) for(x in 0 until w) {var v=0f;for(k in weights.indices)v+=weights[k]*input[y*w+reflect(x+k-radius,w)];horizontal[y*w+x]=v}
        for(y in 0 until h) for(x in 0 until w) {var v=0f;for(k in weights.indices)v+=weights[k]*horizontal[reflect(y+k-radius,h)*w+x];output[y*w+x]=v}
        return output
    }
    private fun morphology(input:FloatArray,w:Int,h:Int,size:Int,maximum:Boolean):FloatArray {
        val radius=size/2
        val rowRadius=IntArray(size) { y -> Math.rint(radius*sqrt(max(0.0,1.0-(y-radius).toDouble()*(y-radius)/(radius*radius)))).toInt() }
        val output=FloatArray(input.size)
        for(y in 0 until h)for(x in 0 until w) {
            var v=if(maximum) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
            for(dy in -radius..radius) {
                val yy=y+dy;if(yy !in 0 until h)continue
                for(dx in -rowRadius[dy+radius]..rowRadius[dy+radius]) {
                    val xx=x+dx;if(xx !in 0 until w)continue
                    val p=input[yy*w+xx];v=if(maximum)max(v,p) else min(v,p)
                }
            }
            output[y*w+x]=v
        }
        return output
    }
    private fun dilate(input:BooleanArray,w:Int,h:Int,radius:Int):BooleanArray {
        val horizontal=BooleanArray(input.size);val result=BooleanArray(input.size)
        for(y in 0 until h)for(x in 0 until w)for(xx in max(0,x-radius)..min(w-1,x+radius))if(input[y*w+xx]){horizontal[y*w+x]=true;break}
        for(y in 0 until h)for(x in 0 until w)for(yy in max(0,y-radius)..min(h-1,y+radius))if(horizontal[yy*w+x]){result[y*w+x]=true;break}
        return result
    }
    companion object {
        const val VERSION="semantic-contrast-mobile-v1"
        private val VEHICLES=setOf("car","bus","truck","motorcycle","train","bicycle")
    }
}


