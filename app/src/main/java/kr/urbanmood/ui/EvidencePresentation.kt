package kr.urbanmood.ui

import kr.urbanmood.contracts.*
import kr.urbanmood.integration.AnalysisSnapshot
import kr.urbanmood.integration.CompletedAnalysis
import kr.urbanmood.context.EnvironmentPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation only. Never estimates missing observations or recomputes a mood. */
internal data class EvidenceMetric(val id: String, val label: String, val value: Float?,
    val maximum: Float, val unit: String, val color: Int, val exclusion: String? = null) {
    val fraction: Float? get() = if (exclusion != null) null else value?.let { it / maximum }
    val display: String get() = exclusion ?: value?.let {
        val number = if (it > 0f && it < .1f) "<0.1" else String.format(Locale.KOREA, "%.1f", it)
        "$number$unit"
    } ?: "미관측"
}
internal data class EvidenceSource(val title: String, val subtitle: String, val metrics: List<EvidenceMetric>,
    val notes: List<String> = emptyList())
internal data class EvidenceFact(val label: String, val value: String, val note: String? = null)
internal data class EvidencePresentation(val sources: List<EvidenceSource>, val context: List<EvidenceFact>,
    val frameFacts: List<EvidenceFact>, val moods: List<String>, val musicMoods: List<String>,
    val desiredMoods: List<String>, val trackTitle: String?, val trackTags: List<String>,
    val inputScope: String, val exclusion: String, val desiredKnown: Boolean = true,
    val preferenceNotice: String? = null, val weatherSources: List<EvidenceSource> = emptyList())

internal object EvidencePresenter {
    private const val WARM = 0xffce6848.toInt()
    private const val COOL = 0xff398aaf.toInt()
    private const val TEAL = 0xff00858b.toInt()
    fun presentCompleted(completed: CompletedAnalysis?, selected: UserPreferences): EvidencePresentation {
        val applied = completed?.recommendationPreferences
        val changed = applied != null && (applied.preferredMoods != selected.preferredMoods ||
            applied.preferredGenres != selected.preferredGenres || applied.excludedTrackIds != selected.excludedTrackIds)
        return present(completed?.recommendation, completed?.snapshot, applied?.preferredMoods.orEmpty()).copy(
            desiredKnown = applied != null,
            preferenceNotice = if (changed) "변경한 취향은 아직 적용되지 않았어요. ‘취향 적용’을 누르면 다시 추천해 드려요." else null)
    }

    /** desired is the captured input of result; live selections enter through presentCompleted only. */
    fun present(result: RecommendationResult?, snapshot: AnalysisSnapshot?, desired: Set<String> = emptySet()): EvidencePresentation {
        val contributions = result?.scoreSummary?.scores.orEmpty().flatMap { it.contributions }
        // The same support/metadata may occur in several mood rows. Do not add duplicates.
        fun value(id: String, basis: ContributionBasis, max: Float, delta: Boolean = false): Float? {
            val matches = contributions.filter { it.id == id && it.basis == basis }
            val values = matches.map { if (delta) it.delta else it.observedValue }.distinct()
            return values.singleOrNull()?.takeIf { it.isFinite() && it in 0f..max }
        }
        fun metric(id: String, label: String, basis: ContributionBasis, max: Float, unit: String,
                   color: Int, ratio: Boolean = false, delta: Boolean = false): EvidenceMetric {
            val raw = value(id, basis, if (ratio) 1f else max, delta)
            return EvidenceMetric(id, label, raw?.let { if (ratio) it * 100f else it }, max, unit, color)
        }
        val sky = ContributionBasis.SKY_COLOR_RULE
        val light = ContributionBasis.OBSERVED_FEATURE_STRENGTH
        val highwayExcluded = contributions.any { it.id == "interpret_visual" && it.basis == light &&
            it.reason.contains("고속도로: 자동 시각적 활력 후보 제한") }
        fun supportNotes(basis: ContributionBasis, entries: List<Pair<String, String>>) = entries.mapNotNull { (id, label) ->
            value(id, basis, 1f)?.takeIf { it > 0f }?.let { support ->
                val percent = EvidenceMetric(id, label, support * 100f, 100f, "%", TEAL).display
                "$label · 촬영 시간의 ${percent}"
            }
        }
        val sources = listOf(
            EvidenceSource("하늘색", "색감 점수 · 70점 만점", listOf(
                metric("sky_color_warm_strength", "따뜻한 색", sky, 70f, "규칙점", WARM, delta = true),
                metric("sky_color_cool_strength", "차가운 색", sky, 70f, "규칙점", COOL, delta = true)),
                supportNotes(sky, listOf("sky_color_warm_support" to "따뜻한 하늘",
                    "sky_color_cool_support" to "차가운 하늘", "sky_color_mixed_support" to "여러 색의 하늘"))),
            EvidenceSource("주변 조명", "색감 강도 · 최대 70", listOf(
                metric("feature_warm", "따뜻한 색", light, 70f, "강도", WARM, delta = true),
                metric("feature_cool", "차가운 색", light, 70f, "강도", COOL, delta = true)),
                supportNotes(light, listOf("interpret_warm" to "따뜻한 주변색",
                    "interpret_cool" to "차가운 주변색", "interpret_mixed" to "따뜻하고 차가운 주변색"))),
            EvidenceSource("빛이 차지한 화면", "화면에서 밝은 부분이 차지한 비율", listOf(
                metric("feature_area", "밝은 부분", light, 100f, "%", TEAL, ratio = true))),
            EvidenceSource("장면이 이어진 정도", "전체 촬영 구간에서 해당 장면이 나타난 비율", listOf(
                metric("interpret_visual", "불빛이 많은 장면", light, 100f, "%", TEAL, ratio = true)
                    .copy(exclusion = if (highwayExcluded) "도로 조건으로 제외" else null),
                metric("interpret_people_light", "사람이 보이는 밝은 장면", light, 100f, "%", TEAL, ratio = true)
                    .copy(exclusion = if (highwayExcluded) "도로 조건으로 제외" else null)))
        )
        val env = snapshot?.environment
        fun state(a: Availability?) = when (a) {
            Availability.AVAILABLE -> "연결됨"; Availability.MANUAL -> "직접 설정"
            Availability.STALE -> "오래된 정보"; Availability.PERMISSION_DENIED -> "권한 없음"
            Availability.ERROR -> "연결 오류"; else -> "미연결"
        }
        fun timestamp(epoch: Long?, pattern: String = "HH:mm"): String? = epoch?.takeIf { it > 0 }?.let {
            val zone = runCatching { ZoneId.of(env?.zoneId ?: "Asia/Seoul") }.getOrDefault(ZoneId.of("Asia/Seoul"))
            runCatching { DateTimeFormatter.ofPattern(pattern).withZone(zone).format(Instant.ofEpochMilli(it)) }.getOrNull()
        }
        val time = timestamp(snapshot?.captureStartedAtEpochMs)
        // Match the engine's default time-context path: recorded end precedes desktop provenance,
        // otherwise use the saved evaluation time. Never re-evaluate against today's wall clock.
        val observedAt = observationTime(snapshot)
        val locationUsable = env != null && observedAt != null && EnvironmentPolicy.usableLocation(env.location, observedAt)
        val roadUsable = env?.road?.availability == Availability.MANUAL || (locationUsable &&
            env?.road?.availability == Availability.AVAILABLE && EnvironmentPolicy.fresh(env.road.source.observedAtEpochMs,
                observedAt!!, EnvironmentPolicy.LOCATION_MAX_AGE_MS))
        val assessedWeather = EnvironmentPolicy.assessWeather(env?.weather, observedAt, snapshot?.analyzedAtEpochMs)
        val weatherUsable = assessedWeather.usable
        fun excluded(a: Availability?, reason: String) = if (a == Availability.AVAILABLE) "미반영 · $reason"
            else if (a == Availability.STALE) "오래된 정보 · 미반영" else state(a)
        fun provenance(source: SourceInfo?) = source?.let { s -> listOfNotNull(s.name.takeIf { it.isNotBlank() },
            timestamp(s.observedAtEpochMs, "MM/dd HH:mm")?.let { "관측 $it" },
            timestamp(s.fetchedAtEpochMs, "MM/dd HH:mm")?.let { "조회 $it" },
            if (s.cached) "이전 조회" else null).joinToString(" · ").ifBlank { null } }
        val road = if (roadUsable) when (env?.road?.type) {
            RoadType.HIGHWAY -> "고속도로"; RoadType.URBAN -> "도심"; RoadType.RESIDENTIAL -> "주거지역"
            RoadType.OTHER -> "기타 도로"; else -> "종류 미확인"
        } else excluded(env?.road?.availability, if (!locationUsable) "위치 확인 필요" else "관측 시각 확인 필요")
        val weather = if (weatherUsable) listOfNotNull(assessedWeather.condition,
            assessedWeather.temperatureC?.let { String.format(Locale.KOREA, "%.1f°C", it) },
            assessedWeather.precipitationMm?.let { String.format(Locale.KOREA, "지난1시간 %.1fmm", it) })
            .joinToString(" · ").ifBlank { "상태 미확인" }
            else assessedWeather.exclusion ?: "미연결"
        val weatherLimits = mapOf("weather_rain" to 50f, "weather_snow" to 30f, "weather_precipitation" to 25f,
            "weather_dry" to 10f, "weather_mild" to 25f, "weather_cold" to 30f, "weather_hot" to 30f)
        val weatherSources = result?.scoreSummary?.scores.orEmpty().filter { row -> row.contributions.isNotEmpty() &&
            row.contributions.all { it.basis == ContributionBasis.WEATHER_RULE } }.map { row ->
            val entries = row.contributions.distinctBy { it.id }.filter { it.id in weatherLimits }
            EvidenceSource(row.keyword, "날씨 음악 규칙점수 · 하늘·조명과 별도", entries.map { c ->
                metric(c.id, c.label, ContributionBasis.WEATHER_RULE, weatherLimits.getValue(c.id), "규칙점", TEAL, delta = true)
            }, entries.map { it.reason })
        }
        val weatherNotes = listOfNotNull(
            provenance(env?.weather?.source),
            if (weatherUsable && env?.weather?.availability == Availability.MANUAL) "수동 날씨 입력입니다. 실제 관측값이 아닙니다."
            else if (weatherUsable) "요청 당시 주변 5km 격자의 대표 관측입니다. 강수 없음은 맑은 하늘·낮은 습도를 뜻하지 않습니다." else null,
            if (weatherUsable && listOf(assessedWeather.condition, assessedWeather.temperatureC, assessedWeather.precipitationMm).any { it == null })
                "일부 관측 항목 미확인 · 확인된 항목만 반영" else null).joinToString(" · ").ifBlank { null }
        val roadNotes = env?.road?.let { roadContext -> listOfNotNull(
            roadContext.placeName?.takeIf { roadUsable }, provenance(roadContext.source), roadContext.source.detail)
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } }
        val observation = snapshot?.vision?.sceneObservations
        val facts = listOf(EvidenceFact("사람 검출", observation?.persons?.detectionCount?.takeIf { it >= 0 }?.let { "${it}개" } ?: "미관측"),
            EvidenceFact("주변 빛 후보", observation?.ambientLights?.candidateCount?.takeIf { it >= 0 }?.let { "${it}개" } ?: "미관측"))
        val track = result?.candidates?.firstOrNull()
        return EvidencePresentation(sources, listOf(EvidenceFact("촬영 시작", time ?: "미확인"),
            EvidenceFact("위치", if (locationUsable) "연결됨" else excluded(env?.location?.availability, "위치 확인 필요")), EvidenceFact("도로", road +
                if (env?.road?.availability == Availability.MANUAL) " · 직접 설정" else
                    if (roadUsable && env?.road?.availability == Availability.AVAILABLE) " · 주변 지도 도로 추정" else "", roadNotes),
            EvidenceFact("날씨", weather + if (env?.weather?.availability == Availability.MANUAL) " · 직접 설정" else "",
                weatherNotes)),
            facts, result?.commonMoodKeywords.orEmpty(), result?.moodKeywords.orEmpty(), desired.toList(),
            track?.title, track?.tags.orEmpty().sorted(),
            result?.scoreSummary?.windowDurationMs?.takeIf { it > 0 }?.let { "${it / 1000}초 촬영 결과" } ?: "분석 결과",
            if (snapshot?.vision?.vehicleMaskApplied == true) "마지막 장면 · 차량광 제외 처리 적용" else "마지막 장면 · 차량광 제외 여부 미확인",
            weatherSources = weatherSources)
    }

    private fun observationTime(snapshot: AnalysisSnapshot?): Long? {
        snapshot ?: return null
        val observations = snapshot.sceneWindow?.samples?.mapNotNull { it.vision.sceneObservations }
            ?: listOfNotNull(snapshot.vision.sceneObservations)
        return when {
            observations.any { it.runtime == SceneObservationRuntime.ANDROID_RECORDED } ->
                snapshot.observationEndEpochMs?.takeIf { it > 0 && it <= snapshot.analyzedAtEpochMs }
            observations.any { it.runtime == SceneObservationRuntime.DESKTOP_SEGMENTATION } ->
                observations.mapNotNull { it.source.observedAtEpochMs?.takeIf { t -> t > 0 } }.maxOrNull()
            else -> snapshot.analyzedAtEpochMs.takeIf { it > 0 }
        }
    }
}
