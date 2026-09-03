# Progress

Deadline: **Sun 6 Sep 2026, 11:59 PM IST**

## Status: Layer 1 — algorithm prototype

### Done
- [x] Videos profiled — 1080×1920, 25 fps, 750 frames, 30.000 s, no rotation metadata
- [x] Contact sheets reviewed for all 3 clips → same 5-person cast, re-cut per clip
- [x] `prd.md` written, self-reviewed, 3 real problems found and fixed
- [x] `ui_avoidance.md` written (design system + anti-patterns)
- [x] `plan.md` written (6 layers, verification gate on every step)
- [x] MobileFaceNet downloaded + **verified** `[1,112,112,3]` → `[1,192]` float32, Apache 2.0
- [x] LiteRT working on desktop for prototyping

### Layer 0 — Toolchain
- [x] 0.1 Android SDK installed locally in `android-sdk/` (platform-35, build-tools 35)
- [ ] 0.2 Phone in USB-debugging mode, `adb devices` sees it

### Layer 1 — Prototype (the 50%)
- [x] 1.1 Frame extraction + scene-cut detection - 14-17 cuts/clip, ~1.5s median spacing
- [x] 1.2 Face detection - MediaPipe FaceLandmarker; 2-face windows match the brief's stated pairings
- [x] 1.3 Quality gate - BLUR_FLOOR=3.0 measured (bimodal, empty 2-5 band, 24 rejects/clip)
- [x] 1.4 Alignment + embeddings - bimodal: same-person 0.01-0.27, different 0.42+
- [x] 1.5 Threshold sweep - large plateau: core 0.26-0.50 x assign 0.725-0.95
- [x] 1.6 Segmentation - **Sample 1 = 5 people / 20 appearances / 4 each**

### Layer 2 — Skeleton
- [x] 2.1 Gradle scaffold - **BUILD SUCCESSFUL**, 77MB debug APK produced
- [x] 2.2 Theme + design tokens (Color/Type/Theme)
- [x] 2.3 Domain models + `PipelineConfig` with every tuned constant

### Layer 3 — Pipeline (Kotlin)
- [x] 3.1 Clusterer + JVM tests (8 tests: chaining, cannot-link, discovery)
- [x] 3.2 Quality scorer + tests (10 tests: weights, monotonicity, range)
- [x] 3.3 Segmenter + tests (12 tests incl. the brief's 1.4s example)
- [x] 3.4 Frame extractor (half-res, OPTION_CLOSEST, cancellable)
- [x] 3.5 Detector + embedder written (eye-line alignment, MobileFaceNet)
- [ ] 3.6 Repository wired end-to-end
- [ ] 3.7 ⭐ On-device threshold re-validation

### Layer 4 — UI
- [ ] 4.1 Nav + Home
- [ ] 4.2 Processing screen
- [ ] 4.3 Results screen + scrubber strip
- [ ] 4.4 Collage renderer
- [ ] 4.5 Save + share

### Layer 5 — Delivery
- [ ] 5.1 Motion, haptics, states
- [ ] 5.2 QA pass
- [ ] 5.3 README
- [ ] 5.4 Debug APK
- [ ] 5.5 Screen recording (≤60 s, all 3 collages legible)
- [ ] 5.6 Submit

---

## Decisions log
| Decision | Value | Why |
|---|---|---|
| Sample rate | 5 fps (200 ms) | Brief's shortest example appearance is 1.4 s → ~7 samples |
| Working resolution | 540×960 (half) | Detection/embedding don't need full res; ¼ the memory |
| Representative shot | Full 1080×1920 re-decode | Only place full res is paid for |
| Detector mode | `PERFORMANCE_MODE_ACCURATE` | Offline batch — spend the budget on accuracy |
| Embedding model | MobileFaceNet 192-d | <1M params, 5.2 MB, purpose-built for mobile |
| Clustering | Average-linkage agglomerative | k-means needs `k`; DBSCAN drops outliers; single-linkage chains |
| Blur floor | **3.0** | Laplacian variance is bimodal; the 2-5 band is empty on all 3 clips |
| Core threshold | **0.38** | Midpoint of the all-5 plateau (0.26-0.50); people merge at 0.56 |
| Assign threshold | **0.84** | Midpoint of 0.725-0.95; places two-person-shot fragments |
| Min core tracks | **3** | Below this a cluster is a fragment, not a person |
| Max gap | **0.8s** | 0.6-1.0 all give the same answer |
| Min segment | **0.0s** | Any floor deletes real short appearances from shared shots |
| Tracking IDs | Hint only, never identity | ML Kit tracking is motion-based, not recognition |

## Notes / blockers
- Threshold stays `TBD` until the Step 1.5 sweep runs. Nothing downstream is tuned before then.

## Validation against the brief (computed, not hardcoded)
- Brief says A+B share frame at 10.1-11.5s. Detector finds 2 faces at **10.0-11.2s** in sample 1.
- Brief says C+D share frame at 20.2-21.6s. Sample 2 shows 2-face runs at 10.2-11.2s and **20.2-21.2s**.
- Detection recovers ~123-125/150 frames per clip; the ~25 missing are whip-pan blur, as intended.

## Layer 1 result (final)

| clip | people | appearances | per person |
|---|---|---|---|
| sample 1 | 5 | **20** | 4, 4, 4, 4, 4 |
| sample 2 | 5 | 21 | 4, 4, 4, 4, 5 |
| sample 3 | 5 | 19 | 3, 3, 4, 4, 5 |

**Sample 1 matches the brief's worked example exactly** (5 people x 4 appearances = 20),
computed end-to-end with nothing hardcoded. The two-person windows also line up: two people
share 10.0-11.4s, two more share 20.4-21.4s.

Identity grouping - the headline metric - is correct on all three clips.

Samples 2 and 3 are each off by one appearance. Cause is understood and documented: a short
fragment from a two-person shot sits almost equidistant between two identities (0.557 vs 0.566
in sample 3), so the embedding genuinely cannot separate them. Attempted fixes and why they
were rejected:
- **Ambiguity margin** (leave near-ties unassigned): breaks the person count, producing 6-9
  people. A spurious extra person is a worse error than a misattributed appearance.
- **Min/single linkage** for assignment: fixes nothing and breaks sample 1 (20 -> 19).

Left as-is deliberately. Tuning further against clips whose true counts are unpublished would
be over-fitting - exactly what the brief warns against.

## Test status
**30/30 JVM unit tests passing.** Run with `./gradlew testDebugUnitTest`.
The domain layer has no Android imports, so the clustering, segmentation and quality
logic - the 50%-weighted part - is testable without a device or emulator.
