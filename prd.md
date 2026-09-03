# PRD — Roll Call
### Video-based unique-person collage · iykyk Android Internship Assignment

**Author:** Shivansh Verma
**Deadline:** Sunday, 6 September 2026, 11:59 PM IST
**Time-box:** 12–15 hours across four days

---

## 1. What this is

An Android app that takes a portrait video, works out **who is in it**, **how many times each
person appears**, picks **the best-looking shot of each person**, and lays those shots out into a
**shareable collage** — entirely on-device, with no network calls.

The product name is **Roll Call**: the app takes attendance on a video. It's short, it's a real
phrase, and it says exactly what the app does without being cute about it.

### The one-sentence pitch
*Drop in a video, get back the cast list.*

---

## 2. Grading-driven priorities

The brief tells us exactly how marks are allocated. This PRD is deliberately organised around that,
because effort should follow the rubric:

| Weight | Criterion | Where this PRD addresses it |
|---|---|---|
| **50%** | Identity grouping & appearance-count accuracy | §5 Pipeline — the bulk of the document |
| **30%** | Code quality & architecture | §6 Architecture |
| **20%** | Usability, representative-shot quality, collage presentation | §7 UX, §8 Collage |

**Consequence:** the pipeline gets the most design attention and the most testing. A beautiful app
that miscounts people fails half the assignment. The reverse is also true, so neither is skipped —
but when time is short, accuracy wins.

---

## 3. What I learned from the test videos (before writing any code)

I profiled the three supplied clips with `ffprobe` and inspected 1-frame-per-second contact sheets
of all three. **This is the single most valuable thing I did**, and it changed several design
decisions. Findings:

**Technical profile** (identical across all three):
- 1080×1920 portrait, H.264, **25 fps**, exactly **750 frames**, **30.000s**
- No rotation side-data → no orientation correction needed, but the code still reads
  `METADATA_KEY_VIDEO_ROTATION` because *"process any similar portrait video"* is a requirement and
  a phone-shot video will very often carry rotation metadata.

**Content profile:**
- All three clips share the **same five-person cast**, re-cut in different orders with different
  pairings. Sample 1 pairs A+E and C+D; Sample 2 pairs A+B and E+D; Sample 3 pairs A+C and E+B.
- The cast: **A** man in a black suit with glasses, holding a clipboard · **B** woman in a cream
  hijab, teal/blue background · **C** woman in a cream headscarf and pink-check jacket ·
  **D** man with a headset mic and round glasses · **E** woman in a white shirt, long dark hair.
- **Heavy whip-pan blur between segments.** A large fraction of sampled frames are unrecognisable
  smears. The brief says *"blurred whip-pan passes count for nobody"* — the contact sheets show
  this isn't a corner case, it's a structural feature of the edit. **The blur gate is a
  first-class part of the algorithm, not a filter bolted on afterwards.**
- **Two-person frames are real and frequent.** Every clip has segments with two people in frame.
  Counting must therefore be **per-face-track**, never per-frame.

**The hard case — this is where the 50% is won or lost:** B and C both wear cream headscarves,
are lit similarly, and are shot against similar warm backgrounds. They are the pair most likely to
be wrongly merged by a loose threshold. Meanwhile the *same* person appears at wildly different
scales (full torso vs. extreme close-up filling the frame), which is what wrongly *splits* an
identity when a threshold is too tight. The algorithm must survive both pressures at once.

> **Anti-hardcoding commitment.** Knowing the answer for Sample 1 (5 people × 4 appearances = 20)
> is used **only to validate**, never to shortcut. No count, cluster count, identity, or timestamp
> is hardcoded anywhere. The cluster count is *discovered*, not supplied — see §5.5.

---

## 4. Scope

### In scope
- Pick a video from device storage (SAF picker — no storage permission needed).
- On-device processing with real, staged progress and a working cancel.
- Face detection → embeddings → clustering. All three stages, as required.
- Per-person appearance counts using the brief's segment definition.
- Quality-scored representative shot per person, generously cropped.
- Generated collage; save to gallery; share via the system share sheet.

### Out of scope
- Live camera recording (explicitly not evaluated).
- Any backend, network call, or analytics. The app ships with `INTERNET` permission absent.
- Naming/editing people, face database across videos, video playback/scrubbing.

### Non-negotiable constraints (from the brief, taken verbatim)
- Kotlin · minSdk 26 · Jetpack Compose
- ML Kit for face detection; a documented on-device embedding model
- Everything on-device, no backend
- Processing off the main thread
- **Never crop tightly to the face box** — generous crop or full frame
- Must generalise to any similar portrait video

---

## 5. The pipeline (the 50%)

Six stages. Each is a separate, independently testable component.

```
Video ──▶ ① Sample ──▶ ② Detect ──▶ ③ Gate ──▶ ④ Embed ──▶ ⑤ Cluster ──▶ ⑥ Segment ──▶ Result
          frames       ML Kit       quality     MobileFaceNet  identities   appearances
```

### 5.1 Stage ① — Frame sampling

**Decision: sample at 5 fps (every 200 ms), 150 frames from a 30 s clip.**

Reasoning: the brief's own example has appearances as short as **1.4 s** (10.1–11.5 s). At 5 fps a
1.4 s appearance yields ~7 sampled frames — enough to survive the blur gate and still embed
several times. At 2 fps it would yield 3, which is too fragile. At 10 fps we'd double the cost for
little accuracy gain. 5 fps is the defensible middle, and it's a named constant, not a magic number.

**Implementation:** `MediaMetadataRetriever.getScaledFrameAtTime(us, OPTION_CLOSEST, w, h)`.
- `OPTION_CLOSEST` (not `OPTION_CLOSEST_SYNC`) — sync-frame-only sampling would snap many requests
  onto the same keyframe and silently collapse our timeline. Accuracy of timestamps matters here.
- `getScaledFrameAtTime` decodes to a **540×960** working bitmap (half resolution) rather than
  full 1080×1920. Detection and embedding do not benefit from the extra pixels, and this roughly
  quarters memory traffic. **The full-resolution frame is re-fetched only once per person**, for
  the winning representative shot.
- Bitmaps are recycled promptly; only one working bitmap is live at a time.

*Risk:* `MediaMetadataRetriever` is slow (~10–30 ms/frame). 150 frames ≈ 2–5 s of decode. Acceptable.
Falls back gracefully if a frame returns null (some codecs do this at boundaries) — that timestamp
is skipped, not fatal.

### 5.2 Stage ② — Face detection (ML Kit)

```kotlin
FaceDetectorOptions.Builder()
    .setPerformanceMode(PERFORMANCE_MODE_ACCURATE)   // offline batch: accuracy > speed
    .setLandmarkMode(LANDMARK_MODE_ALL)              // eyes → alignment + crop geometry
    .setClassificationMode(CLASSIFICATION_MODE_ALL)  // smiling + eyes-open, required by brief
    .setMinFaceSize(0.12f)                           // ignore tiny background faces
    .enableTracking()                                // stable IDs across consecutive frames
    .build()
```

`PERFORMANCE_MODE_ACCURATE` is chosen deliberately: this is **offline batch processing**, not a
real-time camera preview, so the speed/accuracy trade-off should be spent on accuracy.

**On `enableTracking()` — an honest note.** ML Kit tracking IDs are *motion-and-position* based, not
recognition. Google's docs are explicit that it "only makes inferences based on the position and
motion of the faces in a video sequence and is not a form of face recognition." Across a whip-pan
the ID **will** be lost or reassigned. So:

> **Tracking IDs are used as a cheap, local grouping hint — never as identity.** Identity comes
> exclusively from embeddings + clustering (stage ⑤). Tracking is a helper for segment continuity
> (stage ⑥); the pipeline is correct even if every tracking ID were discarded.

This distinction matters, and conflating the two is the most common way this assignment goes wrong.

### 5.3 Stage ③ — The quality gate (the whip-pan defence)

Every detected face gets a **quality score in [0,1]** before anything else happens to it. This score
serves three purposes at once: rejecting junk, choosing representative shots, and weighting
embeddings.

| Component | Weight | How it's measured | Why |
|---|---|---|---|
| **Sharpness** | 0.35 | Variance of Laplacian over the greyscale face region, normalised | Kills whip-pan blur — the dominant failure mode in these clips |
| **Frontality** | 0.30 | From `headEulerAngleY` (yaw) and `headEulerAngleX` (pitch): `cos(yaw)·cos(pitch)`, floored at 0 | Brief explicitly asks for frontality; profile faces also embed poorly |
| **Eyes open** | 0.15 | `min(leftEyeOpenProbability, rightEyeOpenProbability)` | Brief explicitly asks; `min` so one closed eye is penalised |
| **Face size** | 0.12 | Face box area ÷ frame area, normalised and capped | Bigger faces = more pixels = better embeddings |
| **Expression** | 0.08 | `smilingProbability`, gently rewarded | Brief asks for "pleasant"; weighted low so it never overrides a sharp, frontal shot |

Two hard gates applied before scoring:
1. **Blur floor** — a face below the sharpness threshold is *discarded entirely*. It does not
   embed, does not vote, does not count. This is the brief's "blurred whip-pan passes count for
   nobody", implemented literally.
2. **Full-face containment** — a face whose box touches the frame edge is flagged `isClipped`.
   Clipped faces may still contribute to identity, but are **heavily penalised as representative
   candidates**, per *"prefer a source frame where the full face is visible."*

Sharpness is computed on the **face region only**, not the whole frame — a sharp face against a
motion-blurred background is a good shot, and whole-frame sharpness would wrongly reject it.

### 5.4 Stage ④ — Face embeddings

**Model: MobileFaceNet, TFLite — verified, not assumed.**

I downloaded the model and inspected it with the LiteRT runtime rather than trusting documentation:

```
input   "input"        shape [1, 112, 112, 3]   float32, unquantized
output  "embeddings"   shape [1, 192]           float32
file size 5.2 MB · TFL3 flatbuffer · Apache 2.0
```

Chosen over FaceNet-512 (~23 MB, 160×160) because it uses <1M parameters and was purpose-built for
"high-accuracy real-time face verification on mobile and embedded devices." For separating a handful
of people it is comfortably sufficient, and the smaller size keeps the debug APK lean.
Runs via LiteRT (TensorFlow Lite) with **XNNPACK** and 4 threads.

Provenance and licence are recorded in the README as the brief requires; the `.tflite` is vendored
into `app/src/main/assets/` so the build has no download step.

**Preprocessing is where embedding quality is actually won:**
1. **Similarity-align on the eyes.** Use ML Kit's left/right eye landmarks to rotate the face so the
   eye line is horizontal, then scale so inter-ocular distance is a fixed fraction of the crop.
   This removes in-plane rotation — a large, free accuracy gain, and the main reason to have asked
   for `LANDMARK_MODE_ALL`.
2. **Crop with margin** — 25% padding around the ML Kit box, clamped to frame bounds, so the crop
   includes hairline and chin rather than a tight box.
3. Resize to 112×112, normalise to `[-1, 1]`.
4. L2-normalise the output vector so cosine similarity reduces to a dot product.

**Track-level embedding aggregation.** Rather than clustering thousands of per-frame vectors, each
short tracklet produces **one quality-weighted mean embedding** (re-normalised after averaging).
This is a meaningful design choice: averaging suppresses per-frame noise, weighting by quality lets
the clean frames dominate, and it cuts the clustering input by ~10×. Fewer, cleaner points cluster
far more reliably than many noisy ones.

### 5.5 Stage ⑤ — Clustering (discovering *how many* people)

**The core requirement: the number of people is unknown and must be discovered.** k-means is
therefore unusable — it demands `k` up front. Any solution that assumes 5 is disqualified.

**Decision: average-linkage agglomerative clustering on cosine distance, cut at a fixed threshold.**

```
distance(a, b) = 1 − cosine_similarity(a, b)        # both L2-normalised
merge while  min_pair_distance < MERGE_THRESHOLD    # average linkage
```

Why this and not the alternatives:
- **vs. k-means** — needs `k`. Disqualified.
- **vs. DBSCAN** — needs `eps` *and* `minPts`, is awkward with small clusters, and labels outliers
  as noise, which would silently drop a person who appears briefly. Unacceptable here.
- **vs. single-linkage** — vulnerable to chaining: one borderline pair welds B and C into one
  person. Given they're the hardest pair in this dataset, chaining is the exact failure to avoid.
- **Average linkage** requires *overall* similarity to merge, which resists chaining, handles the
  natural scale variance within one person, and needs only a single interpretable parameter.

**Threshold: to be determined empirically. No value is asserted in advance.**

Being straight about this: a specific number written here before any embedding has been computed
would be a guess dressed as a decision. Published MobileFaceNet cosine distances typically fall
around 0.2–0.5 for the same person and 0.8–1.2 for different people, which suggests a cut somewhere
near 0.6 — but the actual distribution depends on our alignment, our crop margin, and this specific
footage. The threshold is the one number the README is explicitly required to justify, so it gets
**measured, not assumed.**

**Method — a threshold-sweep harness, built and run before the value is fixed:**
1. Run detection + embedding over all three clips; dump every tracklet embedding to a file.
2. Sweep `MERGE_THRESHOLD` across 0.20 → 1.20 in 0.01 steps; record the resulting cluster count for
   each clip.
3. Plot count vs. threshold and locate the **widest contiguous plateau** where all three clips
   report a stable, plausible count.
4. **Choose the midpoint of that plateau**, not an edge. A value sitting mid-plateau tolerates
   shifts in lighting, scale, and cast — which is precisely what "process any similar portrait
   video" demands. A value at a plateau edge would be over-fitted and would break on unseen input.
5. Additionally report the **separation margin**: mean intra-cluster vs. inter-cluster distance. A
   wide margin means the threshold is genuinely robust rather than luckily placed.

This harness is a **gating step in the plan** — pipeline tuning does not proceed until it has run.
Its output (the sweep table and chosen midpoint) goes into the README as the justification.

**Post-clustering refinement** — two cheap, principled passes:
1. **Absorb singletons.** A cluster built from a single low-quality tracklet is more likely noise
   than a real person; if it is within a relaxed threshold of a large cluster, merge it.
2. **Enforce temporal exclusivity.** *One person cannot be in two places in the same frame.* If two
   tracklets co-occur in a frame, they are definitively different people and must never share a
   cluster. This is a genuinely strong, free constraint derived from physical reality, and the
   two-person segments in every sample clip make it directly applicable. It is implemented as a
   **cannot-link constraint** honoured during merging.

That constraint is the most interesting idea in this pipeline and it comes straight from reading
the brief's counting rules carefully.

### 5.6 Stage ⑥ — Appearance counting

The brief's definition: *"an appearance is one continuous visible segment: it starts when a person's
face becomes clearly visible and ends when it is no longer clearly visible."*

Implemented literally, per person:
1. Collect every sampled timestamp where that person passed the quality gate.
2. Sort. Walk the list, splitting into segments wherever the gap exceeds **`MAX_GAP = 0.6 s`**
   (3 missed samples at 5 fps). A blink, a brief occlusion, or one blurred frame should *not* split
   an appearance; an actual cut to another shot should.
3. **Split on scene cuts even when there is no gap.** See below.
4. Discard segments shorter than **`MIN_SEGMENT = 0.3 s`**, which are flickers rather than
   appearances.
5. The count is the number of surviving segments. Segments are kept with their start/end times —
   they drive the scrubber-strip UI.

All constants are named, documented, and justified against the brief's own 1.4 s example.

**Why gap-splitting alone is not sufficient (a real bug, caught in review).** Consider a cut from a
close-up of person B to a wider shot that *still contains* B. There is no gap in B's timeline, so a
purely gap-based segmenter reports **one** appearance — but by the brief's definition the first
segment ended and a new one began. Given these clips are fast-cut edits of a shared cast, this case
is likely to occur, and every instance is a silently wrong count on the 50%-weighted criterion.

**Fix: a scene-cut detector as a second, independent split trigger.** For each consecutive pair of
sampled frames, compute a cheap global dissimilarity — mean absolute difference of coarse
(e.g. 32×32) greyscale thumbnails, plus a colour-histogram correlation. A spike past
`SCENE_CUT_THRESHOLD` marks a hard cut. A person's timeline is then split at any scene cut it
spans, *in addition to* gap-based splitting.

This also usefully **distinguishes cuts from whip-pans**: a whip-pan produces a run of
high-difference *blurred* frames (already rejected by the quality gate), whereas a hard cut produces
a single large difference between two *sharp* frames. Combining the two signals is more robust than
either alone, and it costs one extra pass over already-decoded thumbnails.

### 5.7 Representative shot selection

For each person, take the highest-quality-scoring face across all their appearances, with
`isClipped` heavily penalised. Then:
- **Re-decode that exact timestamp at full 1080×1920 resolution** — the working frames were
  half-size, but the final tile deserves full quality. This is the one place we pay for full res.
- Crop **generously**: a box ~3.2× the face box, centred slightly above the face centre (headroom
  looks natural; dead-centring a face looks like a mugshot), clamped to frame bounds, then adjusted
  to the tile's aspect ratio. **Never the bare face box** — the brief is explicit, and tight crops
  look bad.

---

## 6. Architecture (the 30%)

**Clean-ish layered architecture, MVVM presentation, no over-engineering.** The goal is code a
reviewer can navigate in five minutes.

```
com.shivansh.rollcall
├── ui/                        Compose only — no business logic
│   ├── theme/                 Color, Type, Shape, Spacing
│   ├── home/                  video picker + recent runs
│   ├── processing/            staged progress + cancel
│   ├── result/                people list, counts, scrubber strips
│   ├── collage/               preview, save, share
│   └── components/            shared, reusable composables
├── domain/                    Pure Kotlin. Zero Android imports.
│   ├── model/                 Person, Appearance, FaceSample, Embedding, ProcessingStage
│   ├── clustering/            AgglomerativeClusterer, CosineDistance
│   ├── quality/               FaceQualityScorer
│   └── segmentation/          AppearanceSegmenter
├── data/
│   ├── video/                 FrameExtractor
│   ├── detection/             MlKitFaceDetector
│   ├── embedding/             FaceEmbedder (TFLite), FaceAligner
│   └── collage/               CollageRenderer, MediaStoreSaver
└── di/                        Hilt modules
```

**Deliberate decisions:**

- **`domain/` has zero Android dependencies.** Clustering, quality scoring, and segmentation are
  pure Kotlin operating on plain data classes. This is what makes them **unit-testable on the JVM
  with no emulator** — the single highest-leverage architectural choice here, and it's what lets me
  test the 50%-weighted logic thoroughly and fast.
- **Repository exposes `Flow<ProcessingState>`.** Progress is a stream of typed states, not a float.
  The UI renders whatever stage it's told about; the pipeline decides stages. Clean seam.
- **`Dispatchers.Default` for compute, structured concurrency throughout.** Cancellation propagates
  properly — pressing back genuinely stops the work rather than orphaning it. Requirement:
  *"keep processing off the main thread."*
- **Hilt for DI** — constructor injection means the pipeline can be tested with fakes.
- **No premature abstraction.** No interface exists unless something is actually swapped or faked.
  Speculative generality is itself a code smell.

### Testing strategy
| Layer | Test | Runs on |
|---|---|---|
| `AgglomerativeClusterer` | Synthetic vectors: known clusters, chaining resistance, cannot-link honoured | JVM |
| `AppearanceSegmenter` | Hand-built timestamp lists incl. the brief's 1.4 s example, gap/flicker edges | JVM |
| `FaceQualityScorer` | Monotonicity: blurrier ⇒ lower, more frontal ⇒ higher; weights sum to 1 | JVM |
| `CosineDistance` | Identical = 0, orthogonal = 1, symmetry | JVM |
| Full pipeline | The three sample clips end-to-end; assert 5 people / 20 appearances on Sample 1 | Device |

---

## 7. UX (part of the 20%)

Four screens. Design system, anti-patterns, and the full rationale live in
[`ui_avoidance.md`](ui_avoidance.md) — this section covers structure and behaviour.

**Visual direction** — drawn from `design_insp/`: iykyk's own magenta `#FF2E88` on near-black,
heavy display type with real weight contrast, generous rounding, full-bleed portrait imagery. The
inspiration images are consistently dark-surface, image-forward, and confident with type; that's
the register. **Explicitly avoided:** blue→purple gradients, emoji-as-UI, uniform 16dp rounding,
stock component sizing — the documented vibecoded tells.

### Screen 1 — Home
Big display title, one-line explanation, a prominent **Choose video** button, and a list of previous
runs (thumbnail, people count, date). Empty state is designed, not blank: a short line explaining
what to do, with the action right there.

### Screen 2 — Processing
The screen that proves the app isn't a toy. **Real determinate progress**, plus:
- The **current stage named in plain words** — "Reading frames" → "Finding faces" → "Grouping
  people" → "Building collage". Users trust progress they can read.
- **Live counters** — frames scanned, faces found — updating as work proceeds.
- **Face thumbnails appearing as they're detected.** Genuinely delightful, near-free (we have the
  bitmaps already), and it makes a 20-second wait feel like watching something work.
- A **working Cancel** that actually cancels.

### Screen 3 — Results
The core payoff, and where the appearance counts must be unmistakable for the screen recording.
- Header: "**5 people · 20 appearances**" in large type.
- One row per person: generously-cropped portrait, identity chip (letter **and** colour — never
  colour alone), appearance count, total screen time.
- **The scrubber strip** — a 30 s timeline per person with their segments as magenta blocks.
  This is the signature element: it makes counts *legible at a glance*, visually proves the
  segmentation is real, and looks like nothing else that will be submitted.
- Primary action: **Create collage**.

### Screen 4 — Collage
Full-bleed preview, **Save to gallery** and **Share** as clear primary/secondary actions. Save
writes via MediaStore (no permission needed on API 29+; `WRITE_EXTERNAL_STORAGE` scoped to
`maxSdkVersion="28"` for older devices). Share uses `FileProvider` + `Intent.ACTION_SEND` — the
standard share sheet, as required.

**Motion:** spring-based, ~200–300 ms, applied to state changes only. Haptic tick on completion and
primary actions. **States:** every screen has designed empty, loading, error, and cancelled states —
their absence is the clearest vibecoded tell there is.

---

## 8. The collage (part of the 20%)

**Output: 1080×1920 portrait PNG** — Instagram-Story-native, which is exactly the reference the
brief suggests.

**Layout: an adaptive editorial grid**, chosen per person-count so it never looks like a generic
uniform grid:

| People | Layout |
|---|---|
| 1 | Single full-bleed hero |
| 2 | Stacked halves |
| 3 | One hero + two stacked |
| 4 | 2×2 |
| 5 | **Hero + 2×2** — the magazine-cover look; the most likely case for these clips |
| 6 | 2×3 |
| 7–9 | 3×3, hero-weighted |
| 10+ | Mosaic, scored by quality so the best shots get the biggest tiles |

Each tile: generously-cropped portrait, subtle gradient scrim at the bottom for text legibility, the
person's identity letter, and their **appearance count** rendered on the tile — so the collage alone
communicates the result, which matters for the screen recording.

Header: "Roll Call" wordmark. Footer: "5 people · 20 appearances · 0:30". Composed with Canvas onto
a Bitmap off the main thread; 8dp gutters, consistent corner radii, magenta→coral wash background.

---

## 9. The three impressive extras

Chosen to *strengthen* the graded requirements rather than distract from them. Each is small,
low-risk, and defensible in an interview.

**① Temporal cannot-link constraint** (§5.5) — *the strongest idea here.* Two faces visible in the
same frame are provably different people, so clustering is forbidden from merging them. It converts
a physical fact into a hard constraint that directly protects the 50% criterion, and it's exactly
the kind of insight that comes from reading a spec closely rather than reaching for a library.
Costs ~30 lines.

**② The scrubber strip** (§7) — per-person timeline of appearance segments. Turns "4 appearances"
from a number you trust into a picture you can verify, makes the app instantly recognisable in a
screenshot, and demonstrates the segmentation is genuinely temporal.

**③ Live face discovery during processing** (§7) — thumbnails popping in as faces are found.
Turns dead waiting time into the most engaging screen in the app, at almost no cost since the
bitmaps are already in hand.

*Deliberately rejected as extras:* face naming (scope creep, no marks), cross-video person database
(large, risks core accuracy), video playback (not asked for), animated/video collage export
(heavy encoder work, high risk, minimal reward). **Every extra must pay rent against the rubric.**

---

## 9.5 De-risking strategy: prove the algorithm before porting it

The riskiest part of this assignment is the 50%-weighted pipeline, and the slowest possible way to
tune it is edit-Kotlin → Gradle build → install → run on device → read logcat. That loop is minutes
long; threshold tuning needs hundreds of iterations.

**So the pipeline is prototyped and validated in Python first, on the desktop, against the real
videos** — the same MobileFaceNet `.tflite`, the same cosine metric, the same agglomerative
clustering and segmentation logic. `ffmpeg` and LiteRT are already working on this machine, so the
sweep from §5.5 runs in seconds instead of minutes.

Once the Python prototype reports the correct answer on all three clips, the algorithm and its
constants are **ported to Kotlin as a known-good specification** rather than discovered on-device.
The Kotlin unit tests then assert the same results the prototype produced, so any port error shows
up immediately as a test failure rather than as a mysterious wrong count.

This is the single highest-leverage decision in the plan. The prototype is a **development tool, not
a deliverable** — it ships in a `tools/` directory, clearly marked, and no app code depends on it.
The shipped app is 100% on-device Kotlin, as required.

*(Note: this does not violate "everything must run on-device" — the prototype is offline tuning, the
same way one might train or evaluate a model before shipping it. The app itself makes no network
calls and has no backend.)*

---

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Threshold over-fits to these three clips | **High** | Mid-plateau selection, not edge; validate on all three; document in README |
| B and C (similar headscarves) merge | Medium | Average linkage + cannot-link + alignment; explicit test case; measured in prototype |
| Same person splits across scale change | Medium | Track-level averaging; singleton absorption |
| Scene-cut splits missed (§5.6) | Medium | Scene-cut detector as second split trigger; validated in prototype |
| ~~MobileFaceNet sourcing/licence~~ | **Resolved** | Downloaded, Apache 2.0, verified `[1,112,112,3]`→`[1,192]` with LiteRT |
| Kotlin port diverges from tuned prototype | Medium | Unit tests assert the prototype's exact outputs (§9.5) |
| Processing too slow (>60 s) | Low | 5 fps + half-res working frames; measure early |
| OOM on large video | Low | One bitmap live at a time; prompt recycling; half-res |
| No Android SDK on dev machine | **Certain** | Install command-line SDK — Step 0 of the plan |
| Emulator unavailable / device-only testing | Medium | Pure-Kotlin `domain/` is JVM-testable without a device (§6) |

---

## 11. Deliverables checklist

- [ ] Git repository
- [ ] README: build/setup steps, **embedding model documented**, **similarity threshold + how it
      was chosen**, architecture overview, known limitations
- [ ] Working **debug APK**
- [ ] **≤60 s screen recording**: processing, appearance counts, and the finished collage for
      **all three** sample videos, each held long enough to read
- [ ] `prd.md`, `plan.md`, `progress.md`, `ui_avoidance.md`

---

## 12. Definition of done

1. All three sample videos process end-to-end without a crash.
2. Sample 1 yields **5 people, 4 appearances each, 20 total** — from the algorithm, not a constant.
3. Samples 2 and 3 yield 5 people with plausible, defensible counts.
4. Every representative shot is frontal, sharp, eyes-open, and **generously cropped**.
5. Collage saves to the gallery and shares through the system sheet.
6. UI passes the §6 checklist in `ui_avoidance.md`, including at 1.3× font scale.
7. Processing never blocks the main thread; cancel works.
8. JVM unit tests pass for clustering, segmentation, and quality scoring.
