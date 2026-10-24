package kr.urbanmood.contracts

/** Runtime provenance, not a claim that an offline frame ran through the Android model. */
enum class SceneObservationRuntime { ANDROID_CAMERA, ANDROID_RECORDED, DESKTOP_SEGMENTATION, TEST_FIXTURE }

enum class ContributionBasis { LEGACY_HEURISTIC, OBSERVED_FEATURE_STRENGTH }
/** RGB observations; none of these names establishes a physical emitting source. */
enum class LightGroupKind { LOCAL_CONTRAST, EXTENDED_BRIGHT_SURFACE, LEGACY_UNCLASSIFIED_CANDIDATE }

/** Actual disjoint pixels, not values reconstructed from an average hue.
 * categoryCounts order: red/yellow/green/cyan/blue/magenta/near-neutral/all-channel-limited-unknown.
 * upperChannelCounts order: 0/1/2/3 RGB channels > .98; both count lists sum to pixelCount.
 * Saturation sums exclude all-channel-limited unknown pixels. Color sums additionally
 * require chromatic s>=.20; red-yellow hue<65 or hue>330, cyan-blue 165<=hue<255.
 * Counts use the measurementVersion's actual RGB classifier, not mood labels.
 */
data class LightPixelStats(
    val pixelCount: Int,
    val categoryCounts: List<Int>,
    val upperChannelCounts: List<Int>,
    val saturationSum: Float,
    val redYellowSaturationSum: Float,
    val cyanBlueSaturationSum: Float,
)

data class LightGroupFeature(val regionId: String, val kind: LightGroupKind, val pixels: LightPixelStats)

/** Quality describes limits; never multiply observed feature strength by this value.
 * Unknown is null, not 1. Invalid quality is distinct from malformed pixel evidence.
 */
data class LightFeatureQuality(
    val heuristicQuality: Float? = null,
    val inspectedAreaRatio: Float? = null,
    val flags: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)

/** Full-frame raster, actual union after the versioned hard exclusions and deduplication.
 * groups=null means unmeasured/incomplete groups; empty means measured zero groups.
 * Complete groups reference unique same-frame regions and partition summary exactly.
 * Arrays/masks/RGB are not retained. Detach all nested lists at snapshot boundaries.
 */
data class LightFeatureObservation(
    val measurementVersion: String,
    val rasterWidth: Int,
    val rasterHeight: Int,
    val summary: LightPixelStats,
    val groups: List<LightGroupFeature>?,
    val quality: LightFeatureQuality,
)

enum class RegionEvidenceKind { PERSON, VEHICLE, AMBIENT_LIGHT, SKY, BUILDING, SIGNBOARD, OTHER }
enum class RegionEvidenceSource { OBJECT_DETECTOR, PIXEL_HEURISTIC, SEMANTIC_COMPONENT }

/** ID is unique only inside one frame; never a tracked/distinct person ID.
 * Bounds use upright full-frame normalized [0,1] coordinates, not preview coordinates.
 */
data class RegionEvidence(
    val id: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String,
    val kind: RegionEvidenceKind,
    val source: RegionEvidenceSource,
    val exclusionReason: String? = null,
)

/** One frame's detector output. Zero detections is not proof of absence.
 * count is NOT a distinct-person count. areaRatio is union box area / full frame.
 * threshold/capacity describe the detector configuration, not accuracy. Null = unknown.
 */
data class PersonObservation(
    val detectionCount: Int,
    val areaRatio: Float? = null,
    val scoreThreshold: Float? = null,
    val detectorCapacity: Int? = null,
    val capacityReached: Boolean? = null,
    val measurementQuality: Float? = null, // [0,1], heuristic quality, never probability
)

/** Non-vehicle light candidates from this frame; neither shops nor business activity. */
data class AmbientLightObservation(
    val candidateCount: Int,
    val areaRatio: Float? = null,
    val vehiclesExcluded: Boolean = false,
    val measurementQuality: Float? = null,
)

/** One real sign-mask component in the same upright frame as RegionEvidence.
 * regionId references a unique SIGNBOARD / SEMANTIC_COMPONENT region, never a track ID.
 * All ratios use the SAME full-frame pixel count and raster, never bounding-box area.
 * brightAreaRatio: sign mask AND common brightness predicate AFTER all hard exclusions
 * (geometry, vehicles, traffic lights, reflections, paired lights, glare, etc.).
 * ambientOverlapAreaRatio: those eligible bright pixels AND the union of ACTUALLY
 * ACCEPTED ambient candidate pixels used by the ambient measurement, not their boxes.
 * uniqueBrightAreaRatio: eligible bright pixels MINUS that accepted ambient union.
 * Ratios must be finite in [0,1], with bright = overlap + unique within float tolerance.
 * Components must be pixel-disjoint; unique means ambient deduplication in ONE frame.
 * These are measurements, not quality/probability, shops, or business activity.
 */
data class SignLightEvidence(
    val regionId: String,
    val brightAreaRatio: Float,
    val ambientOverlapAreaRatio: Float,
    val uniqueBrightAreaRatio: Float,
)

/** Actual semantic mask measurements only. Unsupported Android observations stay null.
 * All area ratios use full frame area. personAreaRatio is NOT a detector/person count.
 * signboardCandidateCount counts mask components, not unique shops/signs over time.
 * skyBrightness is mean normalized luminance of sky-mask pixels, not measured weather.
 */
data class SemanticSceneObservation(
    val personAreaRatio: Float? = null,
    val skyAreaRatio: Float? = null,
    val buildingAreaRatio: Float? = null,
    val signboardAreaRatio: Float? = null,
    val signboardCandidateCount: Int? = null,
    val skyBrightness: Float? = null,
    val measurementQuality: Float? = null,
    // Signboard-mask AND measured bright RGB pixels / full frame area. Not neon,
    // storefront, business activity, or detector confidence. Unsupported stays null.
    val signboardBrightAreaRatio: Float? = null,
    // Complete per-component measurement, independent of overlay/debug display limits.
    // Null = unsupported/unmeasured/incomplete; empty = measured with zero sign components.
    // Retain zero rows for existing dark, fully overlapping, or fully excluded components.
    // All IDs must reference same-frame sign regions; no partial list or fabricated zero.
    // Producers/accumulators must detach this list when freezing observation snapshots.
    // Legacy signboardBrightAreaRatio keeps its original meaning; do not assume equality
    // with this list's sum or add both measurements as independent score contributions.
    val signLightRegions: List<SignLightEvidence>? = null,
    // Same-frame raw RGB sky-mask observations; unused by existing mood rules.
    val skyColor: SkyColorObservation? = null,
)

/** The two clocks have different origins; never compare monotonic time to epoch time.
 * Recorded PTS is actual decoder PTS, while cameraTimestampNs is normalized media time.
 */
data class SkyFrameIdentity(
    val sequence: Long,
    val capturedAtEpochMs: Long,
    val cameraTimestampNs: Long,
    val inputSource: FrameInputSource,
    val mediaPresentationTimeUs: Long? = null,
)

/** Raw sufficient statistics. Exact max(R,G,B)==min(R,G,B) alone is achromatic.
 * Hue sectors are [0,30),...,[330,360); no hue mean and no S/V rejection threshold.
 * Luma bins are [0,.25),[.25,.5),[.5,.75),[.75,1], on encoded RGB weighted sum.
 * Neither luma nor these histogram boundaries measure physical luminance or mood.
 */
data class SkyColorStatistics(
    val skyPixCount: Int,
    val hueSectorCounts: List<Int>,
    val hueSectorSaturationSums: List<Double>,
    val achromaticPixCount: Int,
    val saturationSum: Double,
    val encodedLumaSum: Double,
    val encodedLumaBinCounts: List<Int>,
)

/** Nullable observation means unavailable/invalid/unbound, never fabricated zero.
 * Valid no-sky has zero counts/sums and null means. Means divide by skyPixCount,
 * including achromatic pixels with S=0. Area denominator is framePixelCount.
 * Row bands use min(2,3*y/height) in the upright ORIGINAL RGB raster, not a horizon.
 * SHA256 input: ASCII version tag + little-endian width,height + row-major bytes:
 * sky-mask-v1\0 + 0/1 mask; sky-rgb-v1\0 + R,G,B (alpha ignored).
 * No arrays/masks/Bitmap retained; detach nested lists at snapshot boundaries.
 */
data class SkyColorObservation(
    val measurementVersion: String,
    val rasterWidth: Int,
    val rasterHeight: Int,
    val framePixelCount: Int,
    val statistics: SkyColorStatistics,
    val meanSaturation: Double?,
    val meanEncodedLuma: Double?,
    val rowBandSkyPixCounts: List<Int>,
    val skyMaskSha256: String,
    val sourceRgbSha256: String,
    val frame: SkyFrameIdentity,
    val semanticModelSha256: String,
    // RGB comes from CameraFrame after the camera/decoder's <=640 upright resize,
    // before semantic letterboxing. It is NOT the sensor/full-resolution video raster.
    val rgbRasterSource: String = "ANALYZER_INPUT_UPRIGHT_MAX640",
    val semanticRasterWidth: Int? = null,
    val semanticRasterHeight: Int? = null,
)

data class SceneObservations(
    val runtime: SceneObservationRuntime,
    val source: SourceInfo,
    val persons: PersonObservation? = null,
    val ambientLights: AmbientLightObservation? = null,
    val semantic: SemanticSceneObservation? = null,
    val notes: List<String> = emptyList(),
    val regions: List<RegionEvidence> = emptyList(),
    val lightFeatures: LightFeatureObservation? = null,
)

/** Window-relative milliseconds: live receipt time OR recorded media PTS minus first PTS.
 * Recorded samples use actual decoded PTS, never processing time or seek target time.
 */
data class SceneSample(val elapsedMs: Long, val vision: VisionResult)

/** Bounded representative frames, at most one per bucket. No Bitmap/position history.
 * Only 0 <= elapsedMs < durationMs participates. Lists must be detached snapshots.
 * receivedFrameCount is frame count, never summed people. Empty samples = unobserved.
 */
data class SceneWindow(
    val durationMs: Long,
    val bucketDurationMs: Long = 1_000,
    val receivedFrameCount: Int = 0,
    val samples: List<SceneSample> = emptyList(),
)

/** Allocation of ONE group's delta across actual regions. Sum of allocatedDelta must
 * equal that contribution's delta, never repeat the full capped score on each box.
 * A null allocation is a reference/exclusion only, not an invented penalty.
 */
data class RegionScoreReference(
    val sampleElapsedMs: Long,
    val regionId: String,
    val allocatedDelta: Float? = null,
)

/** An auditable heuristic contribution, not a probability or learned coefficient.
 * delta=null means no additive contribution; reason must explain this. A feature
 * metric with weight=0 may still carry a measured observedValue/unit without points.
 */
data class MoodScoreContribution(
    val id: String,
    val label: String,
    val observedValue: Float? = null,
    val unit: String? = null,
    val weight: Float = 0f,
    val delta: Float? = null,
    val measurementQuality: Float? = null,
    val source: SourceInfo? = null,
    val reason: String,
    val evidence: List<RegionScoreReference> = emptyList(),
    val basis: ContributionBasis = ContributionBasis.LEGACY_HEURISTIC,
)

data class MoodScore(
    val keyword: String,
    val score: Float? = null, // points or observed strength according to contributions' basis; null = insufficient evidence
    val contributions: List<MoodScoreContribution> = emptyList(),
)

data class MoodScoreSummary(
    val ruleVersion: String,
    val windowDurationMs: Long,
    val receivedFrameCount: Int,
    val sampledBucketCount: Int,
    val scores: List<MoodScore> = emptyList(),
    val notes: List<String> = emptyList(),
)
