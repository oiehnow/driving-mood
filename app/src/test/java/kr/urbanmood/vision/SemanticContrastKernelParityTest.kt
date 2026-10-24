package kr.urbanmood.vision

import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SemanticContrastKernelParityTest {
    private val reference=ReferenceSemanticContrastObserver()
    private fun oldMorphology(values:FloatArray,w:Int,h:Int,size:Int,max:Boolean):FloatArray {
        val method=reference.javaClass.getDeclaredMethod("morphology",FloatArray::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
        method.isAccessible=true
        return method.invoke(reference,values,w,h,size,max) as FloatArray
    }
    private fun oldGaussian(values:FloatArray,w:Int,h:Int,sigma:Double):FloatArray {
        val method=reference.javaClass.getDeclaredMethod("gaussian",FloatArray::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Double::class.javaPrimitiveType)
        method.isAccessible=true
        return method.invoke(reference,values,w,h,sigma) as FloatArray
    }
    private fun bitwise(a:FloatArray,b:FloatArray) {
        assertEquals(a.size,b.size)
        for(i in a.indices)assertEquals("Float bits at $i",a[i].toRawBits(),b[i].toRawBits())
    }
    @Test fun ellipseDequePreservesEveryFinitePixelIncludingClippedBordersAndSignedZero() {
        val random=Random(20261024)
        for((w,h) in listOf(1 to 1,1 to 17,19 to 1,2 to 3,7 to 9,31 to 23,67 to 39)) {
            val values=FloatArray(w*h){(random.nextInt(511)-255).toFloat()}
            values[0]=-0.0f;if(values.size>1)values[1]=0.0f
            for(size in listOf(1,3,5,9,13,25))for(max in listOf(false,true))
                bitwise(oldMorphology(values,w,h,size,max),SemanticContrastKernels.morphology(values,w,h,size,max))
        }
    }
    @Test fun gaussianPlanPreservesFloatAccumulationBitsAndReflectEdges() {
        val random=Random(20261024)
        for((w,h) in listOf(1 to 1,1 to 17,19 to 1,2 to 3,7 to 9,31 to 23,67 to 39)) {
            val values=FloatArray(w*h){random.nextFloat()*255f}
            for(sigma in listOf(.8,1.0,2.0,3.0,25.0/3.0,9.0,18.0)) {
                val plan=SemanticContrastKernels.GaussianPlan(w,h,sigma)
                bitwise(oldGaussian(values,w,h,sigma),plan.apply(values))
                bitwise(oldGaussian(FloatArray(values.size){values[it]*values[it]},w,h,sigma),plan.apply(FloatArray(values.size){values[it]*values[it]}))
            }
        }
    }
    private fun sameObservations(old:SemanticContrastResult,new:SemanticContrastResult) {
        assertEquals(old.features,new.features);assertEquals(old.semantic,new.semantic)
        assertEquals(old.regions,new.regions);assertEquals(old.vehicleAreaRatio,new.vehicleAreaRatio,0f)
        assertArrayEquals(old.compactMask,new.compactMask);assertArrayEquals(old.broadMask,new.broadMask)
    }
    @Test fun fullObserverStatisticsMasksAndRegionsStayIdenticalAcrossResolutionChanges() {
        val random=Random(20261024);val observer=SemanticContrastObserver()
        for((w,h) in listOf(32 to 19,19 to 32,64 to 37,32 to 19)) {
            val frame=PixelFrame(w,h,IntArray(w*h){0xff000000.toInt() or random.nextInt(0x1000000)})
            val labels=intArrayOf(1,8,43,2,12,20,87,136)
            val classes=ByteArray(w*h){labels[random.nextInt(labels.size)].toByte()}
            val boxes=listOf(ObjectBox(.2f,.4f,.4f,.9f,"car",.8f))
            sameObservations(reference.observe(frame,classes,boxes,false,"test",true),observer.observe(frame,classes,boxes,false,"test",true))
        }
    }
    @Test fun actualDevelopmentRgbMasksStatisticsAndRegionsMatchUnoptimizedObserver() {
        val path=System.getenv("DRIVING_MOOD_F2_PARITY")
        assumeTrue("Private development frames not provided",!path.isNullOrBlank())
        val root=File(path!!);val observer=SemanticContrastObserver()
        val rows=mutableListOf("frame\treferenceMs\toptimizedMs\tpixelMaskDifferences\tstatisticsIdentical")
        root.resolve("frames.tsv").readLines().filter { it.substringBefore('\t').endsWith("000000") }.forEach { line ->
            val v=line.split('\t');val id=v[0];val w=v[1].toInt();val h=v[2].toInt();val folder=root.resolve(id)
            val rgb=folder.resolve("rgb.bin").readBytes()
            val frame=PixelFrame(w,h,IntArray(w*h){i -> 0xff000000.toInt() or ((rgb[i*3].toInt() and 255) shl 16) or ((rgb[i*3+1].toInt() and 255) shl 8) or (rgb[i*3+2].toInt() and 255) })
            val classes=folder.resolve("F2-aligned.bin").readBytes()
            val boxes=folder.resolve("objects.tsv").readLines().filter { it.isNotBlank() }.map { row -> val b=row.split('\t');ObjectBox(b[1].toFloat(),b[2].toFloat(),b[3].toFloat(),b[4].toFloat(),b[0],b[5].toFloat()) }
            var start=System.nanoTime();val old=reference.observe(frame,classes,boxes,false,id,true);val oldMs=(System.nanoTime()-start)/1e6
            start=System.nanoTime();val new=observer.observe(frame,classes,boxes,false,id,true);val newMs=(System.nanoTime()-start)/1e6
            sameObservations(old,new)
            rows.add("$id\t$oldMs\t$newMs\t0\ttrue")
        }
        root.resolve("kotlin-optimized-observer-parity.tsv").writeText(rows.joinToString("\n"))
    }
}
