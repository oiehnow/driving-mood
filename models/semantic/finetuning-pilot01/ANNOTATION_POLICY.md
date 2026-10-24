# Partial pilot labels — AI-assisted, local only

These are conservative AI visual annotations, **not human-verified dense ground truth**. All24 original RGB samples were viewed before polygon creation; the6 original contact sheets and6 label-overlay contact sheets were inspected. Model prediction masks were not loaded into the annotation workflow. Polygon coordinates and reasons are retained in annotations.json.

Only clearly visible interior surfaces are labeled. Ambiguous borders, occlusion, tiny distant lights, glare identities, vehicle boundary halos, dark silhouettes, video text/watermarks and most of each image remain255. A blank label does not mean absence. The pilot cannot validate full-image boundary quality or complete taillight exclusion. Road reflections belong to road when the road surface is visible; bright red pixels are not automatically vehicle/traffic-light labels. Signboard43 includes a physical road-direction sign as well as advertising panels; it does not imply a store or emission.

Class IDs remain the existing150 ADE20K IDs. 0=wall,1=building,2=sky,5=ceiling,6=road,8=windowpane,11=sidewalk,12=person,20=car,43=signboard,80=bus,83=truck. 255 is the only ignore value. Annotations do not represent all150 classes. RGB is copied losslessly, masks are8-bit single-channel PNG at640x360, and resizing masks requires nearest-neighbor.

Review corrections before freeze:

- Two initial sidewalk polygons (clip04/000150 andclip05/000450) crossed onto roadway. RGB/overlay review moved them to visible pavement above the curb.
- Shrub/tree class uncertainty in clip03/000850 was removed to255.
- Four clip06 road polygons were reduced to leave a wider unlabeled margin around the motorcycle/windscreen silhouette.
- Unclear distant red lights, including the reported182-second scene, were not assigned a vehicle class based on color or model error reports. No claim is made that this pilot labels those16 disputed pixels.

Splits are frozen by complete source-video SHA256: train01/03/05, val02, test04/06. Four samples per video are about15seconds apart (clip01:8seconds). All temporal relatives from a source stay within one split. Frames remain correlated within splits;24 frames do not represent24 independent videos. Rain is only in val, and heldout night clip06 mainly supports road labels. This is a deliberately small feasibility pilot, not a balanced/generalization benchmark.

The heldout labels are fixed before receiving fine-tuned predictions. Do not inspect heldout metrics to select checkpoints, adjust training, or relabel disagreements. Report IoU only over explicitly annotated pixels, with class support and ignore coverage, per-video results and regressions. Absent ground-truth classes must not be reported as perfect. Display model false-positive classes on labeled regions separately. Boundary/whole-frame recall outside labeled regions is unmeasured.

Local source images are user-supplied videos; public redistribution rights have not been established. Keep source videos, extracted RGB and label masks local. Reports may reference local review artifacts; do not automatically publish them.

Official references reviewed before implementation:

- https://github.com/ultralytics/ultralytics/blob/main/docs/en/datasets/semantic/index.md — image/PNG-mask pairing and ignore255. Ultralytics AGPL-3.0/Enterprise terms remain B's model/training responsibility; no code copied.
- https://github.com/python-pillow/Pillow/blob/main/docs/reference/ImageDraw.rst — polygon rasterization using existing Pillow installation, HPND/PIL Software License. No library source or history copied. Existing library notices remain unchanged.

No external dataset has been added. The current test sample directory contained the same6 original videos when inventoried; no new files were found.
