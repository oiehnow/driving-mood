package kr.urbanmood.vision

import java.io.File
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads real desktop LiteRT boxes, then runs production Kotlin pixel code unchanged.
 * Optional evidence collection; checks numeric invariants, not recognition accuracy.
 */
class OfflinePhotoEvaluationTest {
    @Test fun evaluateLicensedStillsWithProductionPixelCode() {
        val directory=File(requireNotNull(System.getProperty("visionEvaluationDir")))
        val index=File(directory,"index.tsv")
        assumeTrue("Run desktop-model-evaluation.py first",index.isFile)
        val core=LightMeasurer()
        val lines=mutableListOf("sample\tmodelVehicleCount\tvehicleExcludedRatio\tlightScore\twithoutVehicleBoxes\tluminousArea\thue\tsaturation\tbrightness\tconfidence\treasons")
        for(row in index.readLines().filter { it.isNotBlank() }) {
            val parts=row.split('\t'); val name=parts[0]; val width=parts[1].toInt(); val height=parts[2].toInt()
            val pixels=DataInputStream(File(directory,"$name.argb").inputStream()).use { input -> IntArray(width*height) { input.readInt() } }
            val boxes=File(directory,"$name.boxes.tsv").readLines().filter { it.isNotBlank() }.map {
                val p=it.split('\t'); ObjectBox(p[0].toFloat(),p[1].toFloat(),p[2].toFloat(),p[3].toFloat(),p[4],p[5].toFloat())
            }
            val result=core.measure(PixelFrame(width,height,pixels),boxes,parts[3].toBoolean())
            val unmasked=core.measure(PixelFrame(width,height,pixels),emptyList(),parts[3].toBoolean())
            assertTrue(result.vehicleRatio in 0f..1f)
            assertTrue(result.score==null || result.score in 0f..1f)
            assertTrue(result.colors.luminousArea in 0f..1f)
            val vehicleCount=boxes.count { it.label in setOf("car","truck","bus","motorcycle","bicycle","train") }
            lines.add(listOf(name,vehicleCount,result.vehicleRatio,result.score,unmasked.score,result.colors.luminousArea,
                result.colors.hue,result.colors.saturation,result.colors.brightness,result.confidence,
                result.reasons.joinToString(" | ")).joinToString("\t"))
        }
        File(requireNotNull(System.getProperty("visionEvaluationReport"))).writeText(lines.joinToString("\n"))
    }
}
