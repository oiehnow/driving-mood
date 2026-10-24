package kr.urbanmood.camera

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** Invalidates late callbacks from a stopped/restarted camera session. */
internal class SessionTokens {
    private val generation = AtomicLong()
    fun advance(): Long = generation.incrementAndGet()
    fun isCurrent(token: Long): Boolean = generation.get() == token
}

internal object CapturePolicy {
    const val SAMPLE_INTERVAL_NS = 250_000_000L
    const val MAX_EDGE = 640
    fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, MAX_EDGE.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
