# Frame observations and region evidence

The vision analyzer reuses its existing EfficientDet-Lite0 inference to expose person detections separately from the vehicle exclusion mask. It also exposes accepted light components from the existing pixel measurement. No additional inference or tracking runs in this change.

## Measurements

- Person detections use the detector's existing score threshold0.25, followed by a person acceptance threshold0.5. Invalid/nonfinite boxes are rejected, bounds are clipped to the input, and overlapping person rectangles contribute their union area once. The model has a25-output ceiling across classes. A frame count is neither unique people nor crowd density. Zero detections does not establish absence.
- Ambient candidate count is the number of accepted connected light regions after vehicle/reflection/geometric exclusions. Area is the accepted pixel area divided by full-frame area. Count is not the number of shops, windows, signs or active businesses. The full candidate list is separate from the capped debug list.
- Semantic measurements remain null in this Android implementation. Person detection scores do not fill semanticConfidence. A desktop semantic mask is a different source and must not be labelled Android detector output.
- A failed or uninitialized frame returns null sceneObservations. No previous observation is retained. Temporal light smoothing changes only the legacy scalar score; these observations and regions belong to the current frame.

Person measurement quality is an uncalibrated operating rule:0 for rejected malformed/out-of-frame detector records,0.35 at detection capacity or extreme darkness/white clipping, otherwise0.65. Darkness and clipping thresholds are respectively >90% and >25% of the sampled frame. These values are not detection scores, accuracy or probabilities. They do not reject every false person detection. Person boxes do not alter the independent color/light measurement quality or vehicle mask.

## Region evidence

Coordinates are normalized to the upright, unmirrored full input. Region IDs contain the current camera timestamp and sequence plus a local index. They are frame-local references and do not represent persistent tracks.

Accepted person and vehicle boxes use OBJECT_DETECTOR. Actual accepted light components use PIXEL_HEURISTIC. Vehicle regions carry an exclusion reason rather than an invented mood penalty. Mood contributions and allocations are calculated by the recommendation module, not this builder. A consumer must not repeat a whole group's capped score on every region.

When using semantic connected components in an offline adapter, use SEMANTIC_COMPONENT and preserve that provenance. The adapter's acceptance gate is not model probability. Do not turn connected person masks into object counts, or road signboard masks into confirmed storefronts.

## Optional pixel selections

The internal `LightMeasurer.measure` accepts `capturePixelEvidence = true` for consumers that need exact intersections with a separate semantic mask. Its default is false: no extra selection arrays are allocated or retained by the normal analyzer path. Enabling it does not change acceptance, colors, quality or scores.

`LightPixelEvidence` contains the measurement grid's width/height and two newly owned, row-major Boolean arrays. `acceptedAmbientMask` selects only actually accepted bright pixels. `semanticEligibleBrightMask` retains measured bright pixels after vehicle, traffic-object, reflection and geometric exclusions and after paired-light, excessive-size and white-glare vetoes. Components rejected only by size/shape/fill remain eligible for an intersection; they are not thereby accepted lights or confirmed signs. Accepted is a subset of eligible. Consumers must not mutate the arrays, and no source RGB/Bitmap is retained.

Align semantic component membership to this exact measurement grid using a documented sampling convention. Never substitute a component's bounding rectangle for its actual pixel mask. For a component S and full grid area N, bright=|S∩eligible|/N, overlap=|S∩eligible∩accepted|/N and unique=|S∩eligible∖accepted|/N. Thus bright=overlap+unique; use only unique for an additional area contribution. A component that vanishes on downsampling has zero measured samples, not an invented minimum area. Semantic source and quality remain independent of these measured intersections.

## Validation and limitations

Procedural tests cover rectangle union, clipping/invalid inputs, successful zero versus failure, detection capacity, person/light independence, candidate-region correspondence, evidence source and frame-local IDs. Such tests are not real-world model accuracy measurements.

Actual offline review found night pedestrians missed at the current0.5 threshold, a dashboard and motorcycle front falsely labelled person, and a daytime pedestrian correctly localized. The extra observations therefore improve traceability, not proven model accuracy. Device latency, memory, thermal behavior and native Android inference still require device testing.

API reuse follows Google's Apache-2.0 [ObjectDetectorHelper example](https://github.com/google-ai-edge/mediapipe-samples/blob/1675dd30f602848d0674de5c88dbc395f0877e59/examples/object_detection/android/app/src/main/java/com/google/mediapipe/examples/objectdetection/ObjectDetectorHelper.kt). Existing model and license notices are retained; no new model or application dependency is introduced.
