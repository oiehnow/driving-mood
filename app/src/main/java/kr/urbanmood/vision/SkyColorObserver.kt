package kr.urbanmood.vision

import kr.urbanmood.contracts.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Borrowed unpadded ADE20K raster. No cache or cross-frame reuse. */
internal data class SemanticSkyMask(val width:Int,val height:Int,val classes:ByteArray,
    val frame:SkyFrameIdentity,val modelSha256:String)

/** Aligns same-frame semantic masks with RGB and validates observation provenance. */
internal class SkyColorObserver(private val calculator:SkyColorCalculator?) {
    fun observe(rgb:PixelFrame, frame:SkyFrameIdentity, semantic:SemanticSkyMask?):SkyColorObservation? {
        val compute=calculator ?: return null
        val mask=semantic ?: return null
        if(mask.frame!=frame || !validFrame(frame) || !mask.modelSha256.matches(Regex("[a-f0-9]{64}"))) return null
        if(mask.width !in 1..640 || mask.height !in 1..640 || mask.classes.size!=mask.width*mask.height ||
            mask.classes.any { (it.toInt() and 255)>=150 }) return null
        val n=rgb.width*rgb.height
        val selected=BooleanArray(n)
        val rows=IntArray(3)
        var count=0
        for(y in 0 until rgb.height) for(x in 0 until rgb.width) {
            val mx=((2*x+1)*mask.width/(2*rgb.width)).coerceAtMost(mask.width-1)
            val my=((2*y+1)*mask.height/(2*rgb.height)).coerceAtMost(mask.height-1)
            if((mask.classes[my*mask.width+mx].toInt() and 255)==2) {
                selected[y*rgb.width+x]=true;count++;rows[(3*y/rgb.height).coerceAtMost(2)]++
            }
        }
        val maskHash=hash("sky-mask-v1\u0000",rgb.width,rgb.height,ByteArray(n) { if(selected[it]) 1 else 0 })
        val rgbBytes=ByteArray(n*3) { i -> ((rgb.argb[i/3] ushr (16-(i%3)*8)) and 255).toByte() }
        val rgbHash=hash("sky-rgb-v1\u0000",rgb.width,rgb.height,rgbBytes)
        val stats=try { compute.calculate(rgb,selected) } catch(_:Exception) { null } ?: return null
        if(!valid(stats,count)) return null
        val detached=stats.copy(hueSectorCounts=stats.hueSectorCounts.toList(),
            hueSectorSaturationSums=stats.hueSectorSaturationSums.toList(),encodedLumaBinCounts=stats.encodedLumaBinCounts.toList())
        return SkyColorObservation(VERSION,rgb.width,rgb.height,n,detached,
            if(count==0)null else stats.saturationSum/count,
            if(count==0)null else stats.encodedLumaSum/count,rows.toList(),maskHash,rgbHash,frame,mask.modelSha256,
            semanticRasterWidth=mask.width,semanticRasterHeight=mask.height)
    }
    private fun validFrame(f:SkyFrameIdentity)=f.sequence>=0 && f.capturedAtEpochMs>=0 && f.cameraTimestampNs>=0 &&
        if(f.inputSource==FrameInputSource.ANDROID_RECORDED) f.mediaPresentationTimeUs?.let { it>=0 }==true else f.mediaPresentationTimeUs==null
    private fun valid(s:SkyColorStatistics,n:Int):Boolean {
        if(s.skyPixCount!=n || s.achromaticPixCount !in 0..n || s.hueSectorCounts.size!=12 ||
            s.hueSectorSaturationSums.size!=12 || s.encodedLumaBinCounts.size!=4) return false
        if(s.hueSectorCounts.any { it !in 0..n } || s.encodedLumaBinCounts.any { it !in 0..n }) return false
        if(s.hueSectorCounts.sumOf { it.toLong() }+s.achromaticPixCount!=n.toLong() ||
            s.encodedLumaBinCounts.sumOf { it.toLong() }!=n.toLong()) return false
        if(!s.saturationSum.isFinite() || s.saturationSum !in 0.0..n.toDouble() ||
            !s.encodedLumaSum.isFinite() || s.encodedLumaSum !in 0.0..n.toDouble()) return false
        for(i in 0..11) if(!s.hueSectorSaturationSums[i].isFinite() ||
            s.hueSectorSaturationSums[i] !in 0.0..s.hueSectorCounts[i].toDouble()) return false
        return abs(s.hueSectorSaturationSums.sum()-s.saturationSum)<=1e-8*maxOf(1,n)
    }
    private fun hash(tag:String,width:Int,height:Int,bytes:ByteArray):String {
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update(tag.toByteArray(Charsets.US_ASCII))
        digest.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(width).putInt(height).array())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object { const val VERSION="sky-rgb-v1" }
}
