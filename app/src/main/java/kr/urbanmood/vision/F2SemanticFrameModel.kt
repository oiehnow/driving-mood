/* Nearest-exact index arithmetic adapted from OpenCV modules/imgproc/src/resize.cpp:
 * https://github.com/opencv/opencv/blob/5.x/modules/imgproc/src/resize.cpp
 * Copyright (C) 2000-2008, 2017, Intel Corporation, all rights reserved.
 * Copyright (C) 2009, Willow Garage Inc., all rights reserved.
 * Copyright (C) 2014-2015, Itseez Inc., all rights reserved.
 * Third party copyrights are property of their respective owners.
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer. Redistributions in binary form must
 * reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * The name of the copyright holders may not be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * This software is provided by the copyright holders and contributors "as is" and
 * any express or implied warranties, including, but not limited to, the implied
 * warranties of merchantability and fitness for a particular purpose are disclaimed.
 * In no event shall the Intel Corporation or contributors be liable for any direct,
 * indirect, incidental, special, exemplary, or consequential damages (including, but
 * not limited to, procurement of substitute goods or services; loss of use, data, or
 * profits; or business interruption) however caused and on any theory of liability,
 * whether in contract, strict liability, or tort (including negligence or otherwise)
 * arising in any way out of the use of this software, even if advised of the
 * possibility of such damage.
 */
package kr.urbanmood.vision

import android.content.Context
import java.security.MessageDigest

/** Model-role composition consumes the original frame, never an already reduced 320 tensor. */
internal interface SourceSemanticFrameModel {
    fun inferSource(frame:PixelFrame):SemanticObservationRaster
}

internal data class SemanticObservationRaster(
    val pixels:PixelFrame,
    val classes:ByteArray,
    val sourceSha256:String,
    val description:String,
    val fallbackReason:String?=null,
    val roleElapsedMs:Map<String,Long> = emptyMap(),
)

/** Fixed F2 positive overrides. No model confidence, ground truth or per-scene tuning. */
internal object F2SemanticFusion {
    const val RULES_SHA256="537009477068d6ffaca5c2bf6eeff18ace3903b9c98df9592e03fcbc12805a10"
    private val protected=setOf(6,11,12,20,80,83,87,93,102,116,127,136)
    private val structure=setOf(1,8,43)
    fun merge(base:ByteArray,sky:ByteArray,detail:ByteArray):ByteArray {
        require(base.isNotEmpty() && sky.size==base.size && detail.size==base.size)
        require(listOf(base,sky,detail).all { a -> a.all { (it.toInt() and 255)<150 } })
        return ByteArray(base.size) { i ->
            val b=base[i].toInt() and 255
            when {
                b in protected -> base[i]
                (sky[i].toInt() and 255)==2 -> 2
                (detail[i].toInt() and 255) in structure -> detail[i]
                else -> base[i]
            }
        }
    }
    /** OpenCV 5.0 INTER_NEAREST_EXACT: sequential IEEE-754 double additions, not x*scale.
     * Version 4.x used different fixed-point ties. The frozen evaluator uses 5.0.0.
     */
    fun align(cropped:ByteArray,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int):ByteArray {
        require(sourceWidth>0 && sourceHeight>0 && width>0 && height>0)
        require(cropped.size==sourceWidth*sourceHeight)
        fun indices(source:Int,target:Int):IntArray {
            val step=source.toDouble()/target;var position=step*.5
            return IntArray(target) { val index=position.toInt().coerceIn(0,source-1);position+=step;index }
        }
        val xs=indices(sourceWidth,width);val ys=indices(sourceHeight,height)
        return ByteArray(width*height) { i ->
            cropped[ys[i/width]*sourceWidth+xs[i%width]]
        }
    }
}

/** Serial, request-worker-owned sessions; optional-role failure explicitly returns baseline.
 * Auxiliary failure disables both optional sessions until close/recreation. No stale masks.
 */
internal class F2SemanticFrameModel(
    private val baseline:SemanticFrameModel,
    private val skyFactory:()->SemanticFrameModel,
    private val structureFactory:()->SemanticFrameModel,
    private val nanoTime:()->Long=System::nanoTime,
) : SemanticFrameModel,SourceSemanticFrameModel {
    constructor(context:Context):this(OrtSemanticFrameModel(context.applicationContext),
        { OrtSemanticFrameModel(context.applicationContext,OrtSemanticFrameModel.SKY_640) },
        { OrtSemanticFrameModel(context.applicationContext,OrtSemanticFrameModel.STRUCTURE_C) })
    override val inputSize=320
    override val modelSha256=baseline.modelSha256
    private var sky:SemanticFrameModel?=null
    private var structure:SemanticFrameModel?=null
    private var optionalFailure:String?=null
    private var closed=false

    override fun infer(prepared:SemanticPreparedFrame):ByteArray=
        error("F2 requires the original RGB frame; use inferSource")

    override fun inferSource(frame:PixelFrame):SemanticObservationRaster {
        check(!closed)
        require(frame.width in 1..640 && frame.height in 1..640)
        val times=linkedMapOf<String,Long>()
        fun checkCancelled() { if(Thread.currentThread().isInterrupted)throw InterruptedException("Semantic analysis cancelled") }
        fun predict(model:SemanticFrameModel,role:String):Pair<SemanticPreparedFrame,ByteArray> {
            checkCancelled()
            val start=nanoTime()
            val prepared=SemanticModelInput.prepare(frame,model.inputSize)
            val classes=prepared.layout.cropClassMap(model.infer(prepared))
            times[role]=(nanoTime()-start).coerceAtLeast(0)/1_000_000
            checkCancelled()
            return prepared to classes
        }
        require(baseline.inputSize==320)
        val (basePrepared,baseClasses)=predict(baseline,"baseline320")
        fun fallback(reason:String)=SemanticObservationRaster(basePrepared.pixels,baseClasses,
            baseline.modelSha256,"baseline320 ${baseline.modelSha256}; F2 fallback: $reason",reason,times.toMap())
        optionalFailure?.let { return fallback(it) }
        try {
            if(sky==null)sky=skyFactory()
            if(structure==null)structure=structureFactory()
            val skyModel=checkNotNull(sky);val detailModel=checkNotNull(structure)
            require(skyModel.inputSize==640 && detailModel.inputSize==320)
            val (skyPrepared,skyClasses)=predict(skyModel,"sky640")
            val (detailPrepared,detailClasses)=predict(detailModel,"structureC320")
            fun align(p:SemanticPreparedFrame,c:ByteArray)=F2SemanticFusion.align(c,p.pixels.width,p.pixels.height,frame.width,frame.height)
            val merged=F2SemanticFusion.merge(align(basePrepared,baseClasses),align(skyPrepared,skyClasses),align(detailPrepared,detailClasses))
            val identity="F2|${F2SemanticFusion.RULES_SHA256}|${baseline.modelSha256}|${skyModel.modelSha256}|${detailModel.modelSha256}"
            val digest=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return SemanticObservationRaster(frame,merged,digest,
                "$identity; upright ${frame.width}x${frame.height}; INTER_NEAREST_EXACT; sky=merged ADE2",roleElapsedMs=times.toMap())
        } catch(error:InterruptedException) { throw error }
        catch(error:Exception) { return disableOptional(error.javaClass.simpleName,::fallback) }
        catch(error:LinkageError) { return disableOptional(error.javaClass.simpleName,::fallback) }
    }
    private fun disableOptional(reason:String,fallback:(String)->SemanticObservationRaster):SemanticObservationRaster {
        optionalFailure=reason;releaseOptional();return fallback(reason)
    }
    private fun releaseOptional() {
        val oldSky=sky;val oldStructure=structure;sky=null;structure=null
        for(model in listOf(oldSky,oldStructure))try { model?.close() } catch(_:Exception) {} catch(_:LinkageError) {}
    }
    override fun close() {
        if(!closed) { closed=true;releaseOptional();baseline.close() }
    }
}
