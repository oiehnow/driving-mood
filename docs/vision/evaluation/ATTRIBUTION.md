# Evaluation still images — rights and limits

These are public, licensed photographs for offline development evaluation. They are **not captured phone video**, not a held-out benchmark, and not bundled in app assets. Original JPEG bytes are unchanged. `sources.json` records exact URLs and SHA-256 hashes; `*-source.json` preserves the Commons attribution/license metadata retrieved for each image. No endorsement is implied.

| Local image | Creator | Title/source | Image license |
| --- | --- | --- | --- |
| retail-wet.jpg | SomeDriftwood | [Mathew Street in the rain at night](https://commons.wikimedia.org/wiki/File:Mathew_Street_in_the_rain_at_night_-_between_the_Cavern_Walks_and_Rubber_Soul,_Liverpool_(2011-08-12_21.21.56_by_SomeDriftwood).jpg) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| highway-night.jpg | Brandon Lim Lik Wei | [NSHighway to JB night](https://commons.wikimedia.org/wiki/File:NSHighway_to_JB_night.jpg) | [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/) |
| rain-street.jpg | Harri Hedman | [Raining street in the night](https://commons.wikimedia.org/wiki/File:Raining_street_in_the_night_-_panoramio.jpg) | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| dark-lamp.jpg | Pavel Soro London | [Persistent Night Rain In The Light Of Street Lamp](https://commons.wikimedia.org/wiki/File:Persistent_Night_Rain_In_The_Light_Of_Street_Lamp_(220669549).jpeg) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| highway-dense.jpg | Encik Tekateki | [Eastern Dispersal Link Highway (JOHOR)](https://commons.wikimedia.org/wiki/File:Eastern_Dispersal_Link_Highway_(JOHOR).jpg) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |

When copying or showing these photographs, retain the creator/title/source/license links above. The resized RGB fixtures generated for evaluation are modified versions; retain their corresponding licenses and mark resizing. In particular, any distributed adaptation of highway-dense.jpg is provided under CC BY-SA 4.0. The generated pixel fixtures stay under the ignored verification/build directory; no image alterations or removals of photographer marks have been made to the saved originals.

The Commons file-page unstructured attribution text is subject to its page license (CC BY-SA); structured metadata is CC0. Original file licenses are those listed above and are not replaced by the metadata license. This attribution file and saved Commons descriptive metadata may be reused under CC BY-SA 4.0 with the linked original page attribution.

Evaluation changes: EXIF orientation is normalized in memory; the desktop model receives a bilinear 320×320 RGB resize; production Kotlin pixel code receives an aspect-preserved, longest-edge-320 copy. Detection boxes are numeric data. No score threshold was fitted to these photographs; this small development check does not measure accuracy or prove highway false-positive prevention.
