package kr.urbanmood.ui

import kr.urbanmood.contracts.*
import kr.urbanmood.integration.AnalysisSnapshot
import kr.urbanmood.integration.CompletedAnalysis
import kr.urbanmood.context.EnvironmentPolicy
import kr.urbanmood.recommendation.MoodRecommendationEngine
import org.junit.Assert.*
import org.junit.Test

class EvidencePresentationTest {
    private fun result(vararg contributions: MoodScoreContribution) = RecommendationResult(
        Availability.AVAILABLE, moodKeywords = listOf("차분한"), commonMoodKeywords = listOf("따뜻한"),
        scoreSummary = MoodScoreSummary("test", 30_000, 1, 1,
            listOf(MoodScore("따뜻한", 5f, contributions.toList()))))
    private fun metric(model: EvidencePresentation, id: String) = model.sources.flatMap { it.metrics }.single { it.id == id }

    @Test fun missingZeroAndSmallPositiveRemainDifferent() {
        val model = EvidencePresenter.present(result(
            MoodScoreContribution("feature_warm", "warm", delta = 0f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH),
            MoodScoreContribution("feature_area", "area", observedValue = .00001f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH)), null)
        assertEquals("0.0강도", metric(model, "feature_warm").display)
        assertEquals(0f, metric(model, "feature_warm").fraction)
        assertEquals("미관측", metric(model, "feature_cool").display)
        assertNull(metric(model, "feature_cool").fraction)
        assertEquals("<0.1%", metric(model, "feature_area").display)
        assertTrue(metric(model, "feature_area").fraction!! > 0f)
    }

    @Test fun skyAndLightKeepDifferentUnitsAndNeverSumRepeatedLedgerEntries() {
        val warm = MoodScoreContribution("feature_warm", "warm", delta = 7f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH)
        val model = EvidencePresenter.present(result(warm, warm,
            MoodScoreContribution("sky_color_warm_strength", "sky", delta = 14f, reason = "", basis = ContributionBasis.SKY_COLOR_RULE)), null)
        assertEquals(7f, metric(model, "feature_warm").value)
        assertEquals(.1f, metric(model, "feature_warm").fraction!!, .0001f)
        assertEquals("규칙점", metric(model, "sky_color_warm_strength").unit)
        assertEquals("강도", metric(model, "feature_warm").unit)
        assertEquals(70f, metric(model, "feature_warm").maximum)
        assertEquals(70f, metric(model, "sky_color_warm_strength").maximum)
    }

    @Test fun inconsistentOrWrongBasisOrOutOfRangeDoesNotBecomeMeasuredValue() {
        val valid = MoodScoreContribution("feature_warm", "warm", delta = 7f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH)
        val model = EvidencePresenter.present(result(valid, valid.copy(delta = 9f),
            MoodScoreContribution("feature_cool", "wrong", delta = 5f, reason = "", basis = ContributionBasis.LEGACY_HEURISTIC),
            MoodScoreContribution("feature_area", "bad", observedValue = Float.NaN, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH)), null)
        assertNull(metric(model, "feature_warm").value)
        assertNull(metric(model, "feature_cool").value)
        assertNull(metric(model, "feature_area").value)
    }

    @Test fun percentageUsesFullWindowSupportAndFixedScale() {
        val model = EvidencePresenter.present(result(MoodScoreContribution("interpret_people_light", "support",
            observedValue = .25f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH)), null)
        val support = metric(model, "interpret_people_light")
        assertEquals(25f, support.value)
        assertEquals(100f, support.maximum)
        assertEquals(.25f, support.fraction)
    }

    @Test fun roadRuleExclusionIsNotDisplayedAsZeroObservedDuration() {
        fun support(reason: String) = result(
            MoodScoreContribution("interpret_visual", "vigor", observedValue = 0f, reason = reason,
                basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH),
            MoodScoreContribution("interpret_people_light", "people", observedValue = 0f, reason = "",
                basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH))
        val excluded = EvidencePresenter.present(support("고속도로: 자동 시각적 활력 후보 제한"), null)
        val measured = EvidencePresenter.present(support("조건 충족 구간 없음"), null)
        assertEquals("도로 조건으로 제외", metric(excluded, "interpret_people_light").display)
        assertNull(metric(excluded, "interpret_visual").fraction)
        assertEquals("0.0%", metric(measured, "interpret_people_light").display)
        assertEquals(0f, metric(measured, "interpret_visual").fraction)
    }

    @Test fun contextDoesNotInventCaptureTimeLocationOrUseStaleWeather() {
        val snapshot = AnalysisSnapshot(1, 12345L, EnvironmentSnapshot(
            road = RoadContext(Availability.MANUAL, RoadType.HIGHWAY),
            weather = WeatherSnapshot(Availability.STALE, condition = "맑음")), VisionResult(12345L, AnalysisStatus.OK))
        val model = EvidencePresenter.present(result(), snapshot, setOf("활기 넘침"))
        assertEquals("미확인", model.context.single { it.label == "촬영 시작" }.value)
        assertEquals("미연결", model.context.single { it.label == "위치" }.value)
        assertEquals("오래된 정보 · 미반영", model.context.single { it.label == "날씨" }.value)
        assertEquals("고속도로 · 직접 설정", model.context.single { it.label == "도로" }.value)
        assertEquals(listOf("따뜻한"), model.moods)
        assertEquals(listOf("차분한"), model.musicMoods)
        assertEquals(listOf("활기 넘침"), model.desiredMoods)
    }

    private val captureEnd = 1_790_000_000_000L
    private fun recordedSnapshot(locationTime: Long = captureEnd, weatherTime: Long = captureEnd,
        end: Long? = captureEnd): AnalysisSnapshot {
        val environment = EnvironmentSnapshot(zoneId = "Asia/Seoul",
            location = LocationSnapshot(Availability.AVAILABLE, 37.5, 127.0, 20f,
                source = SourceInfo("GPS", locationTime)),
            weather = WeatherSnapshot(Availability.AVAILABLE, 18.0, condition = "비",
                source = SourceInfo("기상청", weatherTime, captureEnd, cached = true)))
        val vision = VisionResult(captureEnd, AnalysisStatus.OK, sceneObservations = SceneObservations(
            SceneObservationRuntime.ANDROID_RECORDED, SourceInfo("녹화", captureEnd)))
        return AnalysisSnapshot(1, captureEnd + 600_000, environment, vision,
            observationEndEpochMs = end, captureStartedAtEpochMs = captureEnd - 30_000)
    }

    private fun engineResult(s: AnalysisSnapshot) = MoodRecommendationEngine().recommend(
        RecommendationRequest(s.vision, s.environment, nowEpochMs = s.analyzedAtEpochMs,
            observationEndEpochMs = s.observationEndEpochMs))

    @Test fun availableButTimeExpiredWeatherIsExcludedLikeEngine() {
        val snapshot = recordedSnapshot(weatherTime = captureEnd - EnvironmentPolicy.WEATHER_MAX_AGE_MS - 1)
        val recommendation = engineResult(snapshot)
        assertFalse("비" in recommendation.moodKeywords)
        val weather = EvidencePresenter.present(recommendation, snapshot).context.single { it.label == "날씨" }
        assertEquals("미반영 · 관측 시각 확인 필요", weather.value)
        assertTrue(weather.note!!.contains("기상청"))
        assertTrue(weather.note.contains("이전 조회"))
    }

    @Test fun receivedWeatherSurvivesLocationExpiryDuringRecording() {
        val snapshot = recordedSnapshot(locationTime = captureEnd - EnvironmentPolicy.LOCATION_MAX_AGE_MS - 1)
        val recommendation = engineResult(snapshot)
        assertTrue("비" in recommendation.moodKeywords)
        val model = EvidencePresenter.present(recommendation, snapshot)
        assertEquals("미반영 · 위치 확인 필요", model.context.single { it.label == "위치" }.value)
        assertEquals("비 · 18.0°C", model.context.single { it.label == "날씨" }.value)
    }

    @Test fun recordedEvaluationUsesCaptureEndNotLaterProcessingTime() {
        val snapshot = recordedSnapshot()
        val recommendation = engineResult(snapshot)
        assertTrue("비" in recommendation.moodKeywords)
        val model = EvidencePresenter.present(recommendation, snapshot)
        assertEquals("연결됨", model.context.single { it.label == "위치" }.value)
        assertEquals("비 · 18.0°C", model.context.single { it.label == "날씨" }.value)
        assertNotEquals("미확인", model.context.single { it.label == "촬영 시작" }.value)
    }

    @Test fun weatherScoresUseTheirOwnUnitsAndShowObservationAndReceipt() {
        val snapshot = recordedSnapshot(locationTime = captureEnd - 150_000).let { s ->
            s.copy(environment = s.environment.copy(weather = s.environment.weather.copy(precipitationMm = 2.0,
                source = s.environment.weather.source.copy(fetchedAtEpochMs = captureEnd + 5_000))))
        }
        val result = engineResult(snapshot)
        val model = EvidencePresenter.present(result, snapshot)
        val rain = model.weatherSources.flatMap { it.metrics }.single { it.id == "weather_rain" }
        assertEquals(30f, rain.value!!, .001f)
        assertEquals("규칙점", rain.unit)
        assertTrue(model.context.single { it.label == "날씨" }.value.contains("지난1시간 2.0mm"))
        assertTrue(model.context.single { it.label == "날씨" }.note!!.contains("조회"))
        assertTrue(model.context.single { it.label == "날씨" }.note!!.contains("관측"))
        assertTrue(model.weatherSources.flatMap { it.notes }.any { it.contains("감정") })
    }

    @Test fun unavailableWeatherHasActionableSafeReasons() {
        val cases = listOf(
            WeatherSnapshot(source = SourceInfo("날씨 미연결", detail = "기상청 인증키가 설정되지 않았습니다.")) to "기상청 키 미설정",
            WeatherSnapshot(Availability.PERMISSION_DENIED) to "위치 권한 없음 · 날씨 미조회",
            WeatherSnapshot(Availability.ERROR, source = SourceInfo("기상청 조회 실패", detail = "기상청 통신 시간 초과")) to "날씨 조회 시간 초과")
        cases.forEach { (weather, expected) ->
            val snapshot = recordedSnapshot().let { it.copy(environment = it.environment.copy(weather = weather)) }
            val model = EvidencePresenter.present(engineResult(snapshot), snapshot)
            assertEquals(expected, model.context.single { it.label == "날씨" }.value)
            assertTrue(model.weatherSources.isEmpty())
        }
    }

    @Test fun unknownRecordedEndDoesNotInventTimeAndManualWeatherStaysExplicit() {
        val snapshot = recordedSnapshot(end = null)
        assertFalse("비" in engineResult(snapshot).moodKeywords)
        assertTrue(EvidencePresenter.present(result(), snapshot).context.single { it.label == "날씨" }.value.startsWith("미반영"))
        val manual = snapshot.copy(environment = snapshot.environment.copy(weather = WeatherSnapshot(Availability.MANUAL, condition = "비")))
        assertTrue("비" in engineResult(manual).moodKeywords)
        assertEquals("비 · 직접 설정", EvidencePresenter.present(result(), manual).context.single { it.label == "날씨" }.value)
        assertTrue(EvidencePresenter.present(result(), manual).context.single { it.label == "날씨" }.note!!.contains("실제 관측값이 아닙니다"))
    }

    @Test fun supportNotesUseOnlyValidProvidedRatiosNotStrengthOrSelectedWords() {
        val contribution = MoodScoreContribution("sky_color_warm_support", "warm", observedValue = .25f,
            reason = "", basis = ContributionBasis.SKY_COLOR_RULE)
        val model = EvidencePresenter.present(result(contribution, contribution,
            contribution.copy(id = "sky_color_cool_support", observedValue = Float.NaN),
            contribution.copy(id = "sky_color_mixed_support", observedValue = 2f)), null)
        assertEquals(listOf("따뜻한 하늘 · 촬영 시간의 25.0%"), model.sources.first().notes)
        assertTrue(model.sources[1].notes.isEmpty())
        assertTrue(EvidencePresenter.present(result(contribution.copy(observedValue = null)), null).sources.first().notes.isEmpty())
    }

    @Test fun currentSelectionNeverBecomesAnOldRecommendationsAppliedInput() {
        val inputs = UserPreferences(preferredMoods = setOf("차분한"))
        val completed = CompletedAnalysis(recordedSnapshot(), result(), inputs)
        val model = EvidencePresenter.presentCompleted(completed, UserPreferences(preferredMoods = setOf("활기 넘침")))
        assertEquals(listOf("차분한"), model.desiredMoods)
        assertEquals(listOf("차분한"), model.musicMoods)
        assertNotNull(model.preferenceNotice)
        assertTrue(model.desiredKnown)
        assertFalse(EvidencePresenter.presentCompleted(completed.copy(recommendationPreferences = null), inputs).desiredKnown)
    }
}
