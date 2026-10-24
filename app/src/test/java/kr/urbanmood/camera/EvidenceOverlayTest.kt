package kr.urbanmood.camera

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class EvidenceOverlayTest {
    private val region = RegionEvidence("frame-local-1", 0f, 0f, 1f, 1f, "사람 후보",
        RegionEvidenceKind.PERSON, RegionEvidenceSource.OBJECT_DETECTOR)
    @Test fun fitCenterPreservesUprightAspectAndCentersLetterboxing() {
        assertEquals(EvidenceBounds(0f, 25f, 200f, 175f), fitEvidence(region, 640, 480, 200, 200))
        assertEquals(EvidenceBounds(25f, 0f, 175f, 200f), fitEvidence(region, 480, 640, 200, 200))
    }
    @Test fun unsupportedBoundsAreNotDrawnAsRealObjects() {
        assertNull(fitEvidence(region.copy(left = Float.NaN), 640, 480, 200, 200))
        assertNull(fitEvidence(region.copy(left = .8f, right = .2f), 640, 480, 200, 200))
        assertNull(fitEvidence(region, 0, 480, 200, 200))
    }

    @Test fun captionsUseActualAllocatedPointsAndRejectOtherFrameOrDuplicatedGroupPoints() {
        val second = region.copy(id = "frame-local-2")
        fun summary(refs: List<RegionScoreReference>) = MoodScoreSummary("test", 1_000, 1, 1,
            listOf(MoodScore("활기 넘침", 10f, listOf(MoodScoreContribution("persons", "사람 검출",
                delta = 10f, reason = "그룹 기여", evidence = refs)))))
        val refs = listOf(RegionScoreReference(0, region.id, 4f), RegionScoreReference(0, second.id, 6f))
        val captions = regionScoreCaptions(listOf(region, second), summary(refs))
        assertTrue(captions[region.id]!!.single().contains("+4.0점"))
        assertTrue(captions[second.id]!!.single().contains("+6.0점"))
        assertTrue(regionScoreCaptions(listOf(region, second), summary(refs.map { it.copy(allocatedDelta = 10f) })).isEmpty())
        assertTrue(regionScoreCaptions(listOf(region, second), summary(refs.map { it.copy(sampleElapsedMs = 1_000) })).isEmpty())
        assertTrue(regionScoreCaptions(listOf(region, second), summary(refs.map { it.copy(regionId = "missing") })).isEmpty())
    }

    @Test fun measuredZeroAllocationIsVisibleWithoutTurningMissingEvidenceIntoZero() {
        val sign = region.copy(id = "sign-1", label = "간판 성분",
            kind = RegionEvidenceKind.SIGNBOARD, source = RegionEvidenceSource.SEMANTIC_COMPONENT)
        fun summary(delta: Float?, allocation: Float?) = MoodScoreSummary("test", 1_000, 1, 1,
            listOf(MoodScore("활기 넘침", delta, listOf(MoodScoreContribution("sign-light", "간판 빛",
                delta = delta, reason = "주변 빛과 완전히 중복되어 추가 기여 없음",
                evidence = listOf(RegionScoreReference(0, sign.id, allocation)))))))
        val caption = regionScoreCaptions(listOf(sign), summary(0f, 0f))[sign.id]!!.single()
        assertTrue(caption.contains("0.0점 · 추가 기여 없음"))
        assertFalse(caption.contains("+0.0"))
        assertTrue(regionScoreCaptions(listOf(sign), summary(null, null)).isEmpty())
        assertTrue(regionScoreCaptions(listOf(sign), summary(0f, null)).isEmpty())
    }
}
