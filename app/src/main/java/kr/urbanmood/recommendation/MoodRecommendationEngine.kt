package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import kr.urbanmood.context.EnvironmentPolicy
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.random.Random

/** Explainable heuristics, not a trained predictor of business activity or user taste. */
class MoodRecommendationEngine(private val random: Random = Random.Default) : RecommendationEngine {
    override fun recommend(request: RecommendationRequest): RecommendationResult {
        val env = request.environment
        val words = linkedSetOf<String>()
        val reasons = mutableListOf("확인된 시간·날씨·조명 정보를 참고합니다. 추천 신뢰도는 정확도 확률이 아닙니다.")
        var confidence = 0.25f
        var manual = false
        val zone = runCatching { ZoneId.of(env.zoneId) }.getOrElse {
            reasons += "시간대 설정을 확인할 수 없어 한국 시간을 사용합니다."; ZoneId.of("Asia/Seoul")
        }
        val observations = request.sceneWindow?.samples?.mapNotNull { it.vision.sceneObservations }
            ?: listOfNotNull(request.vision?.sceneObservations)
        val offline = observations.any { it.runtime == SceneObservationRuntime.DESKTOP_SEGMENTATION }
        val observationTime = if (!request.timeContextAvailable) null
            else if (offline) observations.mapNotNull { it.source.observedAtEpochMs?.takeIf { t -> t > 0 } }.maxOrNull()
            else request.nowEpochMs.takeIf { it > 0 }
        val hour = observationTime?.let { Instant.ofEpochMilli(it).atZone(zone).hour }
        if (hour != null) {
            words += if (hour < 6 || hour >= 19) "야간" else if (hour < 11) "아침" else if (hour < 17) "낮" else "저녁"
            reasons += "${if (offline) "제공된 촬영" else "분석 당시 기기"} 시각 ${hour}시 (${zone.id})를 기준으로 골랐습니다."
        } else reasons += "촬영 시각이 제공되지 않아 시간대 후보를 만들지 않았습니다. 현재 시각으로 대체하지 않습니다."

        val locationFresh = observationTime != null && EnvironmentPolicy.usableLocation(env.location, observationTime)
        if (locationFresh) confidence += 0.05f else reasons += "최근 위치를 확인하지 못해 위치 정보 반영을 제한합니다."
        val roadUsable = env.road.availability == Availability.MANUAL || (locationFresh &&
            env.road.availability == Availability.AVAILABLE && EnvironmentPolicy.fresh(env.road.source.observedAtEpochMs,
                observationTime!!, EnvironmentPolicy.LOCATION_MAX_AGE_MS))
        if (env.road.availability == Availability.MANUAL) { manual = true; reasons += "도로는 수동 입력값입니다. 자동으로 확인한 도로 정보가 아닙니다." }
        if (roadUsable) when (env.road.type) {
            RoadType.HIGHWAY -> { words += "고속도로"; words += "일정한 리듬"; reasons += "고속도로 정보를 참고해 일정한 리듬의 음악을 골랐습니다." }
            RoadType.RESIDENTIAL -> { words += "차분한"; reasons += "주거지 정보를 참고했습니다." }
            RoadType.URBAN -> { words += "도심"; reasons += "도심 정보를 참고했습니다." }
            else -> Unit
        } else reasons += "도로 종류 미확인 — 도로 정보는 추천에 반영하지 않았습니다."

        val weather = env.weather
        val weatherUsable = weather.availability == Availability.MANUAL || (locationFresh &&
            weather.availability == Availability.AVAILABLE && EnvironmentPolicy.fresh(weather.source.observedAtEpochMs,
                observationTime!!, EnvironmentPolicy.WEATHER_MAX_AGE_MS))
        if (weatherUsable) {
            if (weather.availability == Availability.MANUAL) { manual = true; reasons += "날씨는 수동 입력값입니다. 실제 관측값이 아닙니다." }
            else { confidence += 0.15f; reasons += "${weather.source.name}${if (weather.source.cached) " (이전 조회 정보)" else ""} · 관측 ${Instant.ofEpochMilli(weather.source.observedAtEpochMs!!).atZone(zone)}" }
            when (weather.condition) {
                "비", "비/눈", "소나기", "빗방울", "빗방울눈날림" -> { words += "비"; words += "차분한" }
                "눈", "눈날림" -> { words += "눈"; words += "잔잔한" }
                "강수 없음" -> reasons += "관측된 비나 눈이 없습니다. 하늘이 맑다는 뜻은 아닙니다."
                else -> reasons += "비·눈 정보를 확인하지 못해 추천에 반영하지 않았습니다."
            }
            weather.temperatureC?.takeIf { it.isFinite() }?.let { if (it < 5) words += "포근한" else if (it > 28) words += "청량한" }
        } else reasons += "최근 날씨를 확인하지 못해 날씨 정보는 추천에 반영하지 않았습니다."

        val window = request.sceneWindow ?: SceneWindow(1_000,receivedFrameCount=if (request.vision==null) 0 else 1,
            samples=listOfNotNull(request.vision?.takeIf { offline || (observationTime != null &&
                EnvironmentPolicy.fresh(it.frameTimeEpochMs,observationTime,VisionMeasurementPolicy.MAX_AGE_MS)) }
                ?.let { SceneSample(0,it) }))
        val scorer = SceneMoodScorer()
        val scores = scorer.score(window,env,observationTime)
        words += scorer.keywords(scores)
        // Retain detailed single-frame provenance for older callers; accumulated scoring uses the whole window.
        if (request.sceneWindow==null && !offline) reasons += SceneInterpreter.interpret(request.vision,env.road,roadUsable,request.nowEpochMs,zone).reasons
        reasons += scores.notes
        scores.scores.forEach { mood ->
            reasons += "${mood.keyword} 근거 점수: ${mood.score?.toString() ?: "미확인"}"
            mood.contributions.forEach { c -> reasons += "${c.label}: ${c.delta?.let { "${if (it>=0) "+" else ""}$it" } ?: "미반영"} · ${c.reason}${c.source?.let { " · 출처: ${it.name}" } ?: ""}" }
        }
        confidence += scores.scores.count { it.score != null } * .025f
        if (manual) { words += "수동 입력"; confidence = minOf(confidence, 0.35f) }

        // The shared vocabulary is the only accepted selection surface. Never retain previous requests.
        val desired = MoodVocabulary.options.filter { it in request.preferences.preferredMoods }.toSet()
        if (request.preferences.preferredMoods.any { it !in MoodVocabulary.options })
            reasons += "지원하지 않는 분위기 선택은 반영하지 않았습니다."
        if (desired.isNotEmpty()) {
            val environmentMoods = words.filter { it in MoodVocabulary.options }
            if (environmentMoods.isNotEmpty())
                reasons += "환경 참고 분위기: ${environmentMoods.joinToString(", ")}. 음악 선택에는 사용자가 원하는 분위기를 우선합니다."
            words.removeAll(MoodVocabulary.options.toSet())
            words += desired
            reasons += "사용자가 원하는 분위기: ${desired.joinToString(", ")}. 직접 선택한 음악 목표이며, 관측한 환경이나 운전자의 실제 감정을 판정한 결과가 아닙니다."
        }

        val allowed = request.catalog.distinctBy { it.id }.filter { it.id !in request.preferences.excludedTrackIds }
        val preferred = request.preferences.preferredGenres.map { it.lowercase() }.toSet()
        val candidates = CandidateOrder.order(allowed, { it.id }, request.recentTrackIds, random) { track ->
            MoodTagWeights.weight(track.tags, preferred, words - desired, desired)
        }
        if (allowed.isEmpty()) reasons += "선택한 음악이 없습니다. 음악 파일을 선택하세요."
        else if (allowed.size == 1) reasons += "선택한 음악이 1곡이라 반복해서 재생될 수 있습니다."
        if (allowed.isNotEmpty() && allowed.all { it.tags.isEmpty() }) reasons += "곡의 분위기 정보가 없어 최근 곡의 반복을 줄여 무작위로 고릅니다."
        val query = (words.filterNot { it == "수동 입력" } + request.preferences.preferredGenres.take(3) + "음악").joinToString(" ").take(200)
        return RecommendationResult(if (manual) Availability.MANUAL else Availability.AVAILABLE,
            words.toList(), query, candidates, reasons, confidence.coerceIn(0f, 1f),scores)
    }
}

/** Tag preference changes random order, never excludes unmatched playable tracks. */
internal object MoodTagWeights {
    fun weight(tags: Set<String>, genres: Set<String>, environmentWords: Set<String>, desired: Set<String>): Double {
        val normalized = tags.map { it.lowercase() }.toSet()
        return 1.0 + normalized.count { it in genres } * 3 +
            normalized.count { it in environmentWords } * 2 +
            normalized.count { it in desired && it in MoodVocabulary.options } * 6
    }
}

/** Keep the full playable catalog in the upcoming queue; recent tracks are ordered last.
 * This also allows natural completion to choose another song when camera updates stop.
 */
internal object CandidateOrder {
    fun <T> order(items: List<T>, id: (T) -> String, recent: List<String>, random: Random, weight: (T) -> Double): List<T> {
        return items.distinctBy(id).map { item ->
            Triple(item, recent.lastIndexOf(id(item)).let { if (it < 0) -1 else it },
                -ln(random.nextDouble().coerceAtLeast(1e-12)) / weight(item).coerceAtLeast(0.001))
        }.sortedWith(compareBy<Triple<T, Int, Double>> { it.second }.thenBy { it.third }).map { it.first }
    }
}
