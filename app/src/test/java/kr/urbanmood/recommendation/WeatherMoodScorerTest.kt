package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class WeatherMoodScorerTest {
    private val now = 1_790_000_000_000L
    private val source = SourceInfo("기상청", now - 3_600_000, now - 60_000)
    private fun weather(condition: String? = "비", temperature: Double? = 18.0, rain: Double? = 2.0) =
        WeatherSnapshot(Availability.AVAILABLE, temperature, rain, condition, source)
    private fun score(weather: WeatherSnapshot, at: Long? = now) = SceneMoodScorer().score(
        SceneWindow(30_000), EnvironmentSnapshot(weather = weather), at)
    private fun contributions(weather: WeatherSnapshot, at: Long? = now) =
        score(weather, at).scores.flatMap { it.contributions }.filter { it.id.startsWith("weather_") }

    @Test fun rainSnowDryAndTemperatureHaveAuditableIndependentPoints() {
        assertEquals(30f, contributions(weather()).single { it.id == "weather_rain" }.delta!!, .001f)
        assertEquals(30f, contributions(weather("눈")).single { it.id == "weather_snow" }.delta!!, .001f)
        val dry = contributions(weather("강수 없음", 18.0, 0.0))
        assertEquals(10f, dry.single { it.id == "weather_dry" }.delta!!, .001f)
        assertEquals(25f, dry.single { it.id == "weather_mild" }.delta!!, .001f)
        assertEquals(30f, contributions(weather(temperature = -10.0)).single { it.id == "weather_cold" }.delta!!, .001f)
        assertEquals(30f, contributions(weather(temperature = 35.0)).single { it.id == "weather_hot" }.delta!!, .001f)
        assertTrue(contributions(weather()).all { it.basis.name == "WEATHER_RULE" && it.source == source })
    }

    @Test fun absentInvalidOrUnverifiableWeatherNeverCreatesPoints() {
        val valid = weather()
        val invalid = listOf(WeatherSnapshot(), valid.copy(availability = Availability.STALE),
            valid.copy(source = source.copy(name = "")), valid.copy(source = source.copy(fetchedAtEpochMs = null)),
            valid.copy(source = source.copy(observedAtEpochMs = now - 5_400_001)),
            valid.copy(source = source.copy(fetchedAtEpochMs = now + 1)),
            valid.copy(source = source.copy(fetchedAtEpochMs = source.observedAtEpochMs!! - 1)),
            weather("맑음", Double.NaN, -1.0))
        invalid.forEach { assertTrue("Invalid weather: $it", contributions(it).isEmpty()) }
        assertTrue(contributions(valid, null).isEmpty())
        assertTrue(contributions(valid.copy(source = source.copy(observedAtEpochMs = now + 1))).isEmpty())
    }

    @Test fun partialMeasurementsRemainPartialAndManualIsExplicit() {
        assertEquals(25f, contributions(weather(temperature = null, rain = null)).single { it.id == "weather_rain" }.delta!!, .001f)
        assertFalse(contributions(weather("강수 없음", null, 0.0)).any { it.id == "weather_mild" })
        val manual = contributions(WeatherSnapshot(Availability.MANUAL, condition = "눈"), null)
        assertTrue(manual.isNotEmpty())
        assertTrue(manual.all { it.reason.contains("수동") })
        assertFalse(contributions(weather(null, null, 2.0)).any { it.id == "weather_rain" })
    }

    @Test fun rainChangesCommonMoodScoresWithoutVisualEvidence() {
        val summary = score(weather())
        assertTrue("차분한" in SceneMoodScorer().keywords(summary))
        val rain = summary.scores.single { row -> row.contributions.any { it.id == "weather_rain" } }
        assertEquals(30f, rain.score!!, .001f)
        assertTrue(rain.contributions.all { it.basis.name == "WEATHER_RULE" })
    }

    @Test fun responseAfterCaptureEndUsesSavedReceiptDeadline() {
        val late = weather().copy(source = source.copy(fetchedAtEpochMs = now + 5_000))
        assertTrue(contributions(late).isEmpty())
        val summary = SceneMoodScorer().score(SceneWindow(30_000), EnvironmentSnapshot(weather = late), now, now + 10_000)
        assertEquals(30f, summary.scores.flatMap { it.contributions }.single { it.id == "weather_rain" }.delta!!, .001f)
        val future = late.copy(source = late.source.copy(fetchedAtEpochMs = now + 10_001))
        assertTrue(SceneMoodScorer().score(SceneWindow(30_000), EnvironmentSnapshot(weather = future), now, now + 10_000)
            .scores.none { row -> row.contributions.any { it.basis == ContributionBasis.WEATHER_RULE } })
    }
}
