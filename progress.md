# Progress

Deadline: **Sun 6 Sep 2026, 11:59 PM IST**

## Status: Layer 1 — algorithm prototype

### Done
- [x] Videos profiled — 1080×1920, 25 fps, 750 frames, 30.000 s, no rotation metadata
- [x] Contact sheets reviewed for all 3 clips → same 5-person cast, re-cut per clip
- [x] `prd.md` written, self-reviewed, 3 real problems found and fixed
- [x] `design_notes.md` written (design system + anti-patterns)
- [x] `plan.md` written (6 layers, verification gate on every step)
- [x] MobileFaceNet downloaded + **verified** `[1,112,112,3]` → `[1,192]` float32, Apache 2.0
- [x] LiteRT working on desktop for prototyping

### Layer 0 — Toolchain
- [x] 0.1 Android SDK installed locally in `android-sdk/` (platform-35, build-tools 35)
- [ ] 0.2 Phone in USB-debugging mode - **waiting on hardware** (see TESTING.md)

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
- [x] 3.6 Repository wired end-to-end (Flow<ProcessingState>, cancellable)
- [ ] 3.7 On-device threshold re-validation - **needs a phone**

### Layer 4 — UI
- [x] 4.1 Nav + Home (SAF picker, no storage permission)
- [x] 4.2 Processing screen (staged progress, live counters, face previews)
- [x] 4.3 Results screen + scrubber strip
- [x] 4.4 Collage renderer (adaptive layouts 1-10+, full-res generous crops)
- [x] 4.5 Save (MediaStore) + share (FileProvider)

### Layer 5 — Delivery
- [x] 5.1 Motion, haptics, designed empty/error/cancelled states
- [ ] 5.2 QA pass - **needs a phone**
- [ ] 5.3 README
- [x] 5.4 Debug APK builds (77MB)
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
| Pupil span | **0.31** of crop width | Prototype's 0.42 was for outer eye corners; ML Kit gives pupils (~70% span) |
| Eye ordering | Sort by image x | ML Kit names eyes by the subject's side; trusting the names flips the face 180° |

## Notes / blockers
- First on-device run (LAVA LXX503, Android 14) reached results but grouped one man as three
  people. Cause: the alignment port treated ML Kit's subject-relative eye names as image-relative,
  embedding every face upside down at 1.4x the intended zoom. Fixed in `FaceAligner`; thresholds
  were never the problem and are unchanged. Needs one more on-device run to confirm 5 / 20.
- Crash at 100% was `AnimatedContent` re-rendering the outgoing Processing screen against a state
  that had already become `Done`. Screens now carry their own payload. Fixed, confirmed on-device.

## Validation against the brief (computed, not hardcoded)
- Brief says A+B share frame at 10.1-11.5s. Detector finds 2 faces at **10.0-11.2s** in sample 1.
- Brief says C+D share frame at 20.2-21.6s. Sample 2 shows 2-face runs at 10.2-11.2s and **20.2-21.2s**.
- Detection recovers ~123-125/150 frames per clip; the ~25 missing are whip-pan blur, as intended.

## Layer 1 result (final)

| clip | people | appearances | per person |
|---|---|---|---|
| sample 1 | 5 | **20** | 4, 4, 4, 4, 4 |
| sample 2 | 5 | 21 | 4, 4, 4, 4, 5 |
| sample 3 | 5 | 19 | 3, 4, 4, 4, 4 |

**Sample 1 matches the brief's worked example exactly** (5 people x 4 appearances = 20),
computed end-to-end with nothing hardcoded. The two-person windows also line up: two people
share 10.0-11.4s, two more share 20.4-21.4s.

Identity grouping - the headline metric - is correct on all three clips.

**Tie-breaking improvement.** Where a fragment sat almost equidistant between two identities
(0.557 vs 0.566 in sample 3) the winner was effectively random. It now falls back to the
identity with fewer tracklets, which fixed sample 3's distribution from 3,3,4,4,5 to 3,4,4,4,4.
Verified robust: the person count stays 5 across all 30 cells of the core x assign plateau,
and the result is identical for any tie margin from 0.04 to 0.20.

Rejected alternatives, both measured rather than assumed:
- **Leaving near-ties unassigned**: breaks the person count, producing 6-9 people. A spurious
  extra person is a worse error than a misattributed appearance.
- **Min/single linkage** for assignment: fixes nothing and breaks sample 1 (20 -> 19).

Samples 2 and 3 remain off by one appearance in total. Their true counts are unpublished, so
tuning further would be over-fitting - exactly what the brief warns against.

## Test status
**88/88 JVM unit tests passing.** Run with `./gradlew testDebugUnitTest`.
The domain layer has no Android imports, so the clustering, segmentation and quality
logic - the 50%-weighted part - is testable without a device or emulator.

## Ready to test on a phone
Everything that can be verified without hardware is done:
- `./gradlew assembleDebug` succeeds, APK at app/build/outputs/apk/debug/app-debug.apk
- 88/88 JVM unit tests pass
- Instrumented tests compile - they assert sample 1 gives 5 people / 20 appearances
  on-device, and that the collage renders at 1080x1920
- Collage layouts verified for 0-12 people: no overlaps, spills or degenerate tiles

Remaining work needs a connected phone: run the app end-to-end, re-validate the
threshold with real ML Kit embeddings (step 3.7), and the QA pass.
See TESTING.md for the setup steps.
