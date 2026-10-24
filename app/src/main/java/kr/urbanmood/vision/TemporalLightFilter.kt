package kr.urbanmood.vision

import kotlin.math.abs
import kotlin.math.exp

/** Smooths only the scalar score. Current colors, masks, quality and timestamps stay current.
 * No pixel-coordinate tracking: moving buildings must not be treated as moving vehicles.
 */
internal class TemporalLightFilter {
    private var previous: LightMeasurement? = null
    private var timeNs = -1L
    private var sequence = -1L
    fun reset() { previous=null; timeNs=-1L; sequence=-1L }

    fun apply(current: LightMeasurement, timestampNs: Long, currentSequence: Long): LightMeasurement {
        val old=previous
        val elapsed=timestampNs-timeNs
        val difference=if(old==null) 1f else current.signature.indices.sumOf {
            abs(current.signature[it]-old.signature[it]).toDouble()
        }.toFloat()/current.signature.size
        val reset=old==null || timestampNs<=timeNs || currentSequence<=sequence || elapsed>1_500_000_000L ||
            difference>.10f || current.score==null || old.score==null ||
            abs(current.vehicleRatio-old.vehicleRatio)>.12f || current.confidence < old.confidence*.65f
        val output=if(reset || current.score==0f || old?.score==null) current else {
            // A disappearing signal drops immediately; only upward jitter is smoothed.
            val alpha=if(current.score!! < old.score) 1f else (1-exp(-elapsed/550_000_000.0)).toFloat()
            current.copy(score=old.score+alpha*(current.score-old.score))
        }
        previous=output; timeNs=timestampNs; sequence=currentSequence
        return output
    }
}
