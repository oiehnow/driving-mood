package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

/** Pure pre/postprocessing checks. Native execution is a separate device test. */
class OrtObjectDetectorTest {
    private val landscape = SemanticLetterbox(640, 640, 360, 0, 140)
    private fun rows(vararg boxes: FloatArray): FloatArray = FloatArray(300 * 6).also { out ->
        boxes.forEachIndexed { i, box -> box.copyInto(out, i * 6) }
    }

    @Test fun removesLetterboxAndClipsToOriginalImage() {
        val batch = OrtObjectDetector.decode(rows(floatArrayOf(-10f, 130f, 700f, 510f, .7f, 0f)), 640, 360, landscape)
        assertEquals(ObjectBox(0f, 0f, 1f, 1f, "person", .7f), batch.boxes.single())
        assertFalse(batch.atCapacity)
    }

    @Test fun handlesPortraitAndOddPaddingUsingOriginalScale() {
        val portrait = SemanticLetterbox(640, 360, 640, 140, 0)
        val b = OrtObjectDetector.decode(rows(floatArrayOf(140f, 0f, 500f, 640f, .6f, 2f)), 180, 320, portrait).boxes.single()
        assertEquals(ObjectBox(0f, 0f, 1f, 1f, "car", .6f), b)
        val odd = SemanticLetterbox(640, 640, 417, 0, 111)
        val y = (417f - 111f) / (640f / 513f) / 334f
        val oddBox = OrtObjectDetector.decode(rows(floatArrayOf(0f, 111f, 640f, 417f, .7f, 0f)), 513, 334, odd).boxes.single()
        assertEquals(y, oddBox.bottom, 1e-6f)
    }

    @Test fun thresholdIsInclusiveAndPaddingOnlyBoxIsRejected() {
        val batch = OrtObjectDetector.decode(rows(
            floatArrayOf(10f, 150f, 20f, 160f, .25f, 0f),
            floatArrayOf(10f, 150f, 20f, 160f, .24999f, 0f),
            floatArrayOf(10f, 10f, 20f, 20f, .9f, 2f)), 640, 360, landscape)
        assertEquals(1, batch.boxes.size)
        assertEquals(.25f, batch.boxes.single().confidence, 0f)
    }

    @Test fun capUsesHighestScoresAndSignalsTruncationAndExactCeiling() {
        val boxes = (0 until 26).map { floatArrayOf(10f, 150f, 20f, 160f, .3f + it * .01f, (it % 3).toFloat()) }
        val batch = OrtObjectDetector.decode(rows(*boxes.toTypedArray()), 640, 360, landscape)
        assertEquals(25, batch.boxes.size)
        assertEquals(.55f, batch.boxes.first().confidence, 1e-6f)
        assertTrue(batch.atCapacity)
        assertTrue(OrtObjectDetector.decode(rows(*boxes.take(25).toTypedArray()), 640, 360, landscape).atCapacity)
        assertFalse(OrtObjectDetector.decode(rows(*boxes.take(24).toTypedArray()), 640, 360, landscape).atCapacity)
    }

    @Test fun preprocessingUsesRgbChannelsAndNeutralPadding() {
        val input = SemanticModelInput.prepare(PixelFrame(2, 1, intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt())), 640)
        val offset = 160 * 640
        assertEquals(114f / 255f, input.tensor[0], 0f)
        assertEquals(1f, input.tensor[offset], 0f)
        assertEquals(0f, input.tensor[640 * 640 + offset], 0f)
        assertEquals(0f, input.tensor[2 * 640 * 640 + offset], 0f)
        assertEquals(1f, input.tensor[2 * 640 * 640 + offset + 639], 0f)
    }

    @Test fun malformedOutputCannotBecomeAQuietEmptyResult() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrtObjectDetector.decode(rows(floatArrayOf(bad, 150f, 20f, 160f, .7f, 0f)), 640, 360, landscape)
            }
        }
        for (badClass in listOf(-1f, 80f, 1.5f)) {
            assertThrows(IllegalArgumentException::class.java) {
                OrtObjectDetector.decode(rows(floatArrayOf(10f, 150f, 20f, 160f, .7f, badClass)), 640, 360, landscape)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrtObjectDetector.decode(FloatArray(6), 640, 360, landscape)
        }
    }
}
