package kr.urbanmood.camera

import kr.urbanmood.contracts.*
import java.util.Locale
import kotlin.math.abs

/** A single frame only. Reject mismatched frames/IDs or an allocation that cannot reconcile. */
internal fun regionScoreCaptions(regions: List<RegionEvidence>, summary: MoodScoreSummary?): Map<String, List<String>> {
    if (summary == null) return emptyMap()
    val unique = regions.groupingBy { it.id }.eachCount().filterValues { it == 1 }.keys
    val captions = mutableMapOf<String, MutableList<String>>()
    for (mood in summary.scores) for (contribution in mood.contributions) {
        val delta = contribution.delta?.takeIf { it.isFinite() } ?: continue
        val refs = contribution.evidence
        if (refs.isEmpty() || refs.any { it.sampleElapsedMs != 0L || it.regionId !in unique || it.allocatedDelta?.isFinite() != true } ||
            refs.map { it.regionId }.distinct().size != refs.size ||
            abs(refs.sumOf { it.allocatedDelta!!.toDouble() } - delta) > .01) continue
        for (ref in refs) {
            val unit = if (contribution.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH) " 관측강도" else "점"
            val amount = if (ref.allocatedDelta == 0f) "0.0$unit · 추가 기여 없음"
                else "${String.format(Locale.KOREA, "%+.1f", ref.allocatedDelta)}$unit"
            val text = "$amount · ${mood.keyword} · ${contribution.label}"
            captions.getOrPut(ref.regionId) { mutableListOf() }.add(text)
        }
    }
    return captions
}
