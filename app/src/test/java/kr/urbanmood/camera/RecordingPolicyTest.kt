package kr.urbanmood.camera

import android.media.MediaFormat
import org.junit.Assert.*
import org.junit.Test

class RecordingPolicyTest {
    @Test fun actualPtsAreNormalizedWithoutInventingSeekTimesOrRepeatingSeconds() {
        val sampler = PresentationSampler(60_000)
        assertEquals(0L, sampler.accept(5_000_123))
        assertNull(sampler.accept(5_000_123))
        assertNull(sampler.accept(4_999_999))
        assertNull(sampler.accept(5_999_999))
        assertEquals(1_000L, sampler.accept(6_000_123))
        assertEquals(59_999L, sampler.accept(64_999_999))
        assertNull(sampler.accept(65_000_123))
    }
    @Test fun missingSecondsStayMissingAndThirtySecondBoundaryIsExcluded() {
        val sampler = PresentationSampler(30_000)
        assertEquals(0L, sampler.accept(0))
        assertEquals(2_400L, sampler.accept(2_400_321))
        assertNull(sampler.accept(2_999_999))
        assertNull(sampler.accept(30_000_000))
    }
    @Test fun expectedRecordingLimitAllowsCodecRoundingButRejectsPartialFiles() {
        assertTrue(validRecordingDuration(30_000, 30_050))
        assertTrue(validRecordingDuration(60_000, 59_980))
        assertFalse(validRecordingDuration(30_000, 15_000))
        assertFalse(validRecordingDuration(60_000, 0))
        assertFalse(validRecordingDuration(50_000, 50_000))
    }
    @Test fun yuvRangeAndColorMatrixAreExplicitAndDoNotTintNeutralPixels() {
        assertEquals(0xff000000.toInt(), yuvToArgb(16, 128, 128, false, MediaFormat.COLOR_STANDARD_BT709))
        assertEquals(0xffffffff.toInt(), yuvToArgb(235, 128, 128, false, MediaFormat.COLOR_STANDARD_BT709))
        assertEquals(0xff808080.toInt(), yuvToArgb(128, 128, 128, true, MediaFormat.COLOR_STANDARD_BT601_NTSC))
        val red = yuvToArgb(63, 102, 240, false, MediaFormat.COLOR_STANDARD_BT709)
        assertTrue((red shr 16 and 255) > 250)
        assertTrue((red shr 8 and 255) < 5)
        assertTrue((red and 255) < 5)
    }
}
