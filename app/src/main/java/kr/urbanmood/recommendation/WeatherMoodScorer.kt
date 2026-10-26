package kr.urbanmood.recommendation

import kr.urbanmood.context.EnvironmentPolicy
import kr.urbanmood.contracts.*
import kotlin.math.abs

/** Product music-selection rules, not a psychological model or driver-emotion measurement.
 * Each returned row is exclusively WEATHER_RULE. Never add it to pixel strength or sky units.
 */
internal object WeatherMoodScorer {
    const val VERSION_SUFFIX = "+weather-v1"

    fun score(weather: WeatherSnapshot?, observationTime: Long?, receivedBy: Long?): List<MoodScore> {
        val assessment = EnvironmentPolicy.assessWeather(weather, observationTime, receivedBy)
        if (!assessment.usable || weather == null) return emptyList()
        val rows = linkedMapOf<String, MutableList<MoodScoreContribution>>()
        fun add(word: String, id: String, label: String, measured: Float?, unit: String, maximum: Float, points: Float, rule: String) {
            rows.getOrPut(word) { mutableListOf() } += MoodScoreContribution(id, label, measured, unit, maximum, points,
                source = weather.source, reason = "${if (weather.availability == Availability.MANUAL) "수동 날씨" else "수신 관측"} · $rule · 음악 선택 규칙이며 운전자 감정·정확도 확률 아님",
                basis = ContributionBasis.WEATHER_RULE)
        }
        val condition = assessment.condition
        val rain = assessment.precipitationMm
        if (condition in setOf("비", "비/눈", "소나기", "빗방울", "빗방울눈날림"))
            add("차분한", "weather_rain", "비 → 차분한", rain?.toFloat(), "mm/지난1시간", 50f,
                25f + (rain?.div(10)?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f) * 25f,
                "강수형태 $condition 25점 + 지난1시간 강수량/10mm(최대1)×25점; 강수량 미측정이면 기본25점만")
        if (condition in setOf("눈", "비/눈", "눈날림", "빗방울눈날림"))
            add("잔잔한", "weather_snow", "눈 → 잔잔한", null, "강수형태", 30f, 30f,
                "강수형태 $condition 30점; 적설량·도로 결빙을 추정하지 않음")
        if (condition == null && rain != null)
            add("차분한", "weather_precipitation", "강수량 → 차분한", rain.toFloat(), "mm/지난1시간", 25f,
                (rain / 10).coerceIn(0.0, 1.0).toFloat() * 25f, "강수량/10mm(최대1)×25점; 강수형태 미확인으로 비·눈 키워드를 만들지 않음")
        if (condition == "강수 없음") {
            add("청량한", "weather_dry", "강수 없음 → 청량한", 0f, "강수형태 코드", 10f, 10f,
                "강수 없음 10점; 맑은 하늘·낮은 습도를 뜻하지 않음")
            assessment.temperatureC?.let { temperature ->
                add("청량한", "weather_mild", "온화한 기온 → 청량한", temperature.toFloat(), "°C", 25f,
                    (1f - abs(temperature.toFloat() - 18f) / 12f).coerceIn(0f, 1f) * 25f,
                    "강수 없음일 때 18°C 중심 ±12°C의 삼각 규칙×25점; 체감 쾌적성 측정 아님")
            }
        }
        assessment.temperatureC?.let { temperature ->
            add("포근한", "weather_cold", "낮은 기온 → 포근한", temperature.toFloat(), "°C", 30f,
                ((5f - temperature.toFloat()) / 15f).coerceIn(0f, 1f) * 30f,
                "5°C 아래부터 -10°C까지 선형 증가, 최대30점; 포근한 음악 선택 선호 규칙")
            add("청량한", "weather_hot", "높은 기온 → 청량한", temperature.toFloat(), "°C", 30f,
                ((temperature.toFloat() - 28f) / 7f).coerceIn(0f, 1f) * 30f,
                "28°C 위부터 35°C까지 선형 증가, 최대30점; 청량한 음악 선택 선호 규칙")
        }
        return rows.map { (word, entries) -> MoodScore(word, entries.sumOf { it.delta!!.toDouble() }.toFloat(), entries) }
    }

    fun keywords(summary: MoodScoreSummary): List<String> = summary.scores.filter { row ->
        row.contributions.isNotEmpty() && row.contributions.all { it.basis == ContributionBasis.WEATHER_RULE } &&
            (row.score ?: 0f) >= 10f
    }.map { it.keyword }.distinct()
}
