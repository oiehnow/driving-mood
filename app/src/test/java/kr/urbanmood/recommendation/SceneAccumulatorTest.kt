package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class SceneAccumulatorTest {
    private fun frame(t: Long, people: Int = 1) = VisionResult(1_000_000+t, AnalysisStatus.OK,
        method=AnalysisMethod.MODEL, sceneObservations=SceneObservations(SceneObservationRuntime.TEST_FIXTURE,
            SourceInfo("fixture"), persons=PersonObservation(people,measurementQuality=1f)))
    @Test fun boundsDuplicatesReverseAndFirstRepresentative() {
        val accumulator = SceneAccumulator()
        assertFalse(accumulator.add(-1,frame(0)))
        assertTrue(accumulator.add(0,frame(0)))
        assertFalse(accumulator.add(0,frame(0)))
        assertTrue(accumulator.add(250,frame(250,5)))
        assertFalse(accumulator.add(200,frame(200)))
        assertTrue(accumulator.add(1_000,frame(1_000)))
        assertFalse(accumulator.add(50_000,frame(50_000)))
        val result = accumulator.snapshot()
        assertEquals(3,result.receivedFrameCount)
        assertEquals(2,result.samples.size)
        assertEquals(1,result.samples.first().vision.sceneObservations!!.persons!!.detectionCount)
    }
    @Test fun fullWindowBoundsAndClearDetachState() {
        val accumulator = SceneAccumulator()
        repeat(200) { accumulator.add(it*250L,frame(it*250L)) }
        val snapshot = accumulator.snapshot()
        assertEquals(50,snapshot.samples.size)
        assertEquals(200,snapshot.receivedFrameCount)
        accumulator.clear()
        assertEquals(0,accumulator.snapshot().samples.size)
        assertEquals(50,snapshot.samples.size)
        assertTrue(accumulator.add(0,frame(0)))
    }
    @Test fun shortSourceNeverExtendsToFiftySeconds() {
        val accumulator = SceneAccumulator(25_640)
        assertTrue(accumulator.add(25_639,frame(25_639)))
        assertFalse(accumulator.add(25_640,frame(25_640)))
        assertEquals(25_640,accumulator.snapshot().durationMs)
    }
    @Test(expected=IllegalArgumentException::class) fun tooLongWindowRejected() { SceneAccumulator(50_001) }
    @Test(expected=IllegalArgumentException::class) fun excessiveBucketCountRejected() { SceneAccumulator(50_000,1) }
    @Test fun repeatedOrOldFrameCannotMasqueradeAsNewBucket() {
        val accumulator = SceneAccumulator()
        assertTrue(accumulator.add(0,frame(0)))
        assertFalse(accumulator.add(1_000,frame(0)))
        assertFalse(accumulator.add(2_000,frame(-5_000)))
        assertTrue(accumulator.add(2_001,frame(2_001)))
    }
    @Test fun mutableSignMeasurementInputAndSnapshotCannotMutateStoredWindow() {
        val rows = mutableListOf(SignLightEvidence("a",.01f,0f,.01f),SignLightEvidence("b",.02f,0f,.02f))
        val f = frame(0).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            semantic=SemanticSceneObservation(signLightRegions=rows))) }
        val accumulator = SceneAccumulator()
        accumulator.add(0,f)
        rows.clear()
        val first = accumulator.snapshot().samples.single().vision.sceneObservations!!.semantic!!.signLightRegions!!
        assertEquals(2,first.size)
        @Suppress("UNCHECKED_CAST")
        (first as MutableList<SignLightEvidence>).clear()
        assertEquals(2,accumulator.snapshot().samples.single().vision.sceneObservations!!.semantic!!.signLightRegions!!.size)
    }
}
