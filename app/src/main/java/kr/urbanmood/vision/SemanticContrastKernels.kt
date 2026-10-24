package kr.urbanmood.vision

import kotlin.math.*

/** Exact finite-RGB kernels. No threshold, radius, border or arithmetic-order change.
 * Independent implementation; OpenCV's row/column decomposition was consulted:
 * https://github.com/opencv/opencv/blob/4.x/modules/imgproc/src/morph.simd.hpp
 */
internal object SemanticContrastKernels {
    fun morphology(input:FloatArray,w:Int,h:Int,size:Int,maximum:Boolean):FloatArray {
        require(input.size==w*h && size>0 && size%2==1)
        val radius=size/2
        val rowRadius=IntArray(size) { y -> Math.rint(radius*sqrt(max(0.0,1.0-(y-radius).toDouble()*(y-radius)/(radius*radius)))).toInt() }
        val output=FloatArray(input.size) { if(maximum)Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY }
        val horizontal=FloatArray(input.size);val deque=IntArray(w)
        // A disk is a union of horizontal intervals. Compute each distinct interval once.
        for(rx in rowRadius.distinct()) {
            for(y in 0 until h) {
                val row=y*w;var head=0;var tail=0;var next=0
                for(x in 0 until w) {
                    val right=min(w-1,x+rx)
                    while(next<=right) {
                        val value=input[row+next]
                        while(tail>head) {
                            val comparison=value.compareTo(input[row+deque[tail-1]])
                            if(if(maximum)comparison>=0 else comparison<=0)tail-- else break
                        }
                        deque[tail++]=next++
                    }
                    val left=max(0,x-rx)
                    while(deque[head]<left)head++
                    horizontal[row+x]=input[row+deque[head]]
                }
            }
            for(dy in -radius..radius) if(rowRadius[dy+radius]==rx) {
                val first=max(0,-dy);val last=min(h,h-dy)
                for(y in first until last) {
                    val row=y*w;val source=(y+dy)*w
                    for(x in 0 until w) {
                        val p=horizontal[source+x]
                        output[row+x]=if(maximum)max(output[row+x],p) else min(output[row+x],p)
                    }
                }
            }
        }
        return output
    }

    class GaussianPlan(private val w:Int,private val h:Int,sigma:Double) {
        private val size=Math.rint(sigma*8+1).toInt() or 1
        private val radius=size/2
        private val weights:FloatArray
        private val xs=IntArray(size*w)
        private val rows=IntArray(size*h)
        init {
            val kernel=DoubleArray(size) { exp(-.5*(it-radius)*(it-radius)/(sigma*sigma)) }
            val sum=kernel.sum();weights=FloatArray(size) { (kernel[it]/sum).toFloat() }
            for(k in 0 until size) {
                for(x in 0 until w)xs[k*w+x]=reflect(x+k-radius,w)
                for(y in 0 until h)rows[k*h+y]=reflect(y+k-radius,h)*w
            }
        }
        fun apply(input:FloatArray):FloatArray {
            require(input.size==w*h)
            val horizontal=FloatArray(input.size);val output=FloatArray(input.size)
            for(y in 0 until h) {
                val row=y*w
                // Each output still accumulates k=0..size-1 in exactly the old float order.
                for(k in weights.indices) {
                    val weight=weights[k];val offsets=k*w
                    for(x in 0 until w)horizontal[row+x]+=weight*input[row+xs[offsets+x]]
                }
            }
            for(y in 0 until h) {
                val row=y*w
                for(k in weights.indices) {
                    val weight=weights[k];val source=rows[k*h+y]
                    for(x in 0 until w)output[row+x]+=weight*horizontal[source+x]
                }
            }
            return output
        }
        private fun reflect(i:Int,size:Int):Int { var p=i;while(p<0 || p>=size)p=if(p<0)-p-1 else 2*size-p-1;return p }
    }
}
