# Failures

Every bug that cost real time, what it actually was, and how it was found. Kept because the
diagnosis was usually wrong before it was right, and the wrong turns are the useful part.

Ordered by when they were hit.

---

## 1. The blur gate threw away 82% of the footage

**Symptom.** Barely any faces survived the quality filter. Too few samples to cluster.

**Assumption.** `BLUR_FLOOR = 40.0`, carried over from a typical Laplacian-variance figure for
sharp photographs.

**Actual cause.** This is handheld footage that whip-pans constantly. Roughly 75–80% of sampled
frames are motion-smeared, and the *usable* frames are nowhere near photographic sharpness.
A floor set for stills rejects almost everything.

**Fix.** Measured the distribution instead of guessing. It is bimodal — smears land under 2,
usable frames above 5, and the 2–5 band is empty across all three clips. Set `BLUR_FLOOR = 3.0`,
in the empty band, where the exact value cannot matter.

**Lesson.** A constant borrowed from a different kind of input is a guess wearing a number's
clothing. Plot the distribution; the gap tells you the threshold.

---

## 2. Sharpness measured on the wrong pixels

**Symptom.** Frames with a perfectly sharp face were being rejected.

**Cause.** Laplacian variance was computed over the whole frame. These are portrait videos with
shallow depth of field — the background is *supposed* to be soft. A sharp subject against a
blurred background scores low overall.

**Fix.** Measure sharpness on the face region only.

---

## 3. The threshold had no plateau (the algorithm was wrong, not the number)

**Symptom.** The first threshold sweep produced a correct answer only in a 0.02-wide window.
Anything that narrow is a coincidence, not a setting — it would not survive footage it was not
tuned on, which the brief explicitly warns against.

**Cause, in two parts.**
- Clustering per-frame embeddings meant every noisy frame voted independently.
- One threshold was being asked to do two incompatible jobs: recognise a clean face from another
  angle, *and* place a small, side-on fragment from a two-person shot. Those distances do not
  overlap — fragments sit 0.5–0.7 from their own identity while distinct people sit above 0.9.

**Fix.**
- Aggregate frames into tracklets first, averaged with quality weighting. Suppresses per-frame
  noise and cuts the clustering input ~10×.
- Two passes: a strict `coreThreshold` builds confident identities, then a relaxed
  `assignThreshold` folds leftovers in.

**Result.** The plateau went from 0.02 wide to 0.24 × 0.225. Constants now sit at its midpoint.

---

## 4. A minimum-appearance-length floor deleted real appearances

**Symptom.** Sample 1 came out one appearance short of the brief's stated 20.

**Cause.** `MIN_SEGMENT_S = 0.3`, added to suppress spurious one-frame blips. Some genuine
appearances in shared shots are genuinely that short.

**Fix.** `MIN_SEGMENT_S = 0.0`. Any floor above zero costs a real appearance.

---

## 5. Two plausible clustering fixes that made things worse

Both were tried, measured, and reverted. Recorded so they are not re-attempted.

| Idea | Why it seemed right | What actually happened |
|---|---|---|
| Leave near-ties unassigned | Don't guess when the embedding can't separate two candidates | Person count broke: 6–9 people instead of 5. A spurious extra person is a worse error than a misattributed appearance. |
| Min/single linkage for assignment | Nearest-member is the intuitive match | Fixed nothing and broke sample 1 (20 → 19 appearances). |

**What worked instead** — commit `3313e54`. Where a fragment sat 0.557 from one identity and
0.566 from another, the winner was effectively a coin flip. Falling back to the *smaller*
identity fixed sample 3's distribution from 3,3,4,4,5 to 3,4,4,4,4. Verified rather than
assumed: the person count stays 5 across all 30 cells of the threshold plateau, and the result
is identical for any tie margin from 0.04 to 0.20.

---

## 6. Crash at ~50% — and a wrong diagnosis first

**Symptom.** App died mid-run, on-device, roughly halfway through processing.

**First diagnosis — wrong.** Out-of-memory, from a preview-bitmap leak retaining a crop per
detected face (~67MB by the end of a clip). Real, but not the cause.

**Actual cause.** `Bitmap.createBitmap(source, x, y, w, h)` **returns the source object itself**
when the crop covers the whole bitmap — which happens whenever a face fills the frame. The
preview handed to Compose was therefore sometimes the exact frame recycled on the next line.
Drawing it threw *"trying to use a recycled bitmap"*.

**Fix** (`21957e5`, `50fb675`). Composite into a fresh bitmap via
`Canvas.drawBitmap(src, srcRect, dstRect, paint)` in all three affected places — preview crop,
collage portrait crop, and the embedder's no-landmark fallback. Every remaining `createBitmap`
call now uses the `(w, h, config)` form, which always allocates. The leak was fixed too, plus:
previews capped at the 12 actually shown, collage decoding one portrait at a time,
`MlKitFaceDetector` made a singleton, and the 112×112 pixel buffer hoisted out of the per-face path.

**Lesson.** "Crashes while handling big bitmaps" reads as OOM and isn't necessarily. The aliasing
is documented Android behaviour and invisible at the call site.

---

## 7. Crash at exactly 100% — two more wrong diagnoses

**Symptom.** App died the instant processing completed. No visible error.

**Wrong diagnosis #1.** An unguarded `viewModelScope.launch` around the collage render, outside
the pipeline's `.catch{}`. Real gap, guarded — not the cause.

**Wrong diagnosis #2.** OOM from `getFrameAtTime()` decoding at the video's native resolution
(~8MB per 1080p frame, ~33MB at 4K), once per person. Reasoned that a large native allocation
gets the app *killed by the OS*, which runs no handler and writes no report — consistent with
"app vanishes silently". Plausible, and still wrong.

**How it was actually found.** Built `CrashReporter`: an `UncaughtExceptionHandler` writing the
stack trace, device, memory figures and a breadcrumb trail to a file, shown on next launch with
a Share button. No cable needed. The report ended the guessing in one line:

```
ClassCastException: ProcessingState$Done cannot be cast to ProcessingState$Working
    at RollCallApp.kt:72
Memory:  used 24MB / max 512MB
```

**24MB of 512MB.** Memory was never involved, in either of the two rounds of fixes aimed at it.

**Actual cause.** `AnimatedContent` keeps rendering the *outgoing* screen for the duration of the
crossfade. When processing finished, state flipped to `Done` and routing flipped to `Result` —
but the Processing branch was still being drawn to fade out, and it did
`state as ProcessingState.Working` against a state that was now `Done`. Deterministic, on every
successful run, at exactly 100%.

**Fix** (`2ae3eab`). Screens carry their own payload — `Screen.Processing(state)` rather than a
cast against live state — so a fading screen renders the data it was built with. `Result` and
`Error` had the same latent flaw; all three casts are gone. `ScreenRoutingTest` pins it.

**Lesson.** Two rounds of confident, well-argued fixes aimed at nothing. The instrumentation
should have come first — it took less time to build than either wrong fix took to reason out.

---

## 8. One person grouped as three (the 50% bug)

**Symptom.** First successful end-to-end run reported **3 people, 11 appearances**. The collage
made it obvious: tiles B, C and D were visibly the same man — same glasses, same suit, same
background.

**What it was not.** Thresholds. They were measured correctly against the prototype and are
unchanged by the fix.

**Actual cause — the one real port divergence.** The prototype and the app disagree about what
"left eye" means:

| | `LEFT_EYE` is… | so the eye vector… |
|---|---|---|
| Prototype (MediaPipe, points 33/263) | the eye on the **image's** left | points right, angle ≈ 0° |
| App (ML Kit) | the **subject's** left — image's *right* | points backwards, angle ≈ 180° |

The port assumed they matched, so `atan2` returned ~180° and **every face was rotated upside
down before embedding**. Compounding it: MediaPipe gives outer eye *corners*, ML Kit gives pupil
*centres*, which span ~70% as far on the same face — so with the span constant left at the
prototype's 0.42, every crop was also zoomed ~1.4× too tight. MobileFaceNet never saw an upright,
correctly-framed face.

**Fix** (`8f4ea6c`). Extracted `FaceAligner`, which sorts the two eyes by image x so the naming
convention cannot matter, and set the pupil span to 0.31 to match the geometry the thresholds
were measured against. `FaceAlignerTest` maps eye points through the real transform and asserts
where they land — in image order, in ML Kit's order, and on a tilted face. The ML Kit-order case
is precisely what the old code failed.

**Lesson.** The most dangerous bug in a port is a shared vocabulary with different meanings on
each side. It compiles, it runs, it produces confident numbers, and nothing looks wrong until an
output is inspected by eye. The collage was the diagnostic instrument that a number never was.

---

## Recurring patterns

**Reaching for a cause instead of evidence.** Bugs 6 and 7 were each misdiagnosed — twice, in
7's case — with plausible mechanisms that fit the symptom. Every wrong diagnosis was
memory-shaped, because "crash while handling large bitmaps" pattern-matches to OOM. The
instrumentation that settled it took less effort than the guessing did.

**A number that was never measured.** Bugs 1, 4 and 8 were all constants that looked reasonable
and had never been checked against this input. The fix each time was to measure the distribution.

**Verifying the fix, not just the theory.** In bug 6, the OOM theory *and* the aliasing bug were
both real; only one was the cause. In bug 8, the alignment fix is proven on the JVM but the
on-device count still needs confirming — stated rather than assumed.

**Tests that cannot fail are not tests.** A first attempt at a regression test for person-id
numbering passed against the buggy code, because the sort happened to place the problem case
last. The test was rewritten to construct the case that actually breaks.

---

## Test coverage that came out of this

88 JVM tests, `./gradlew testDebugUnitTest`. The ones that exist because something broke:

| Suite | Tests | Guards |
|---|---|---|
| `TrackletBuilderTest` | 13 | Tracklet closing by identity, not list equality; NaN embeddings |
| `AppearanceSegmenterTest` | 12 | Gap and scene-cut splitting; the min-segment regression |
| `AgglomerativeClustererTest` | 10 | Two-pass clustering, cannot-link constraints, tie-breaking |
| `FaceQualityTest` | 10 | Face-region sharpness, clipping penalties |
| `PortraitCropperTest` | 9 | Bitmap aliasing, edge-clamped crops, never cropping tight to the box |
| `FaceAlignerTest` | 8 | Eye ordering independent of naming; pupil geometry |
| `ScreenRoutingTest` | 7 | The outgoing screen keeps its own payload |
| `CollageLayoutTest` | 6 | Layouts for 0–12 people: no overlaps or spills |
| `BitmapSafetyTest` | 5 | No `createBitmap` aliasing on any path |
| `PortParityTest` | 4 | Kotlin clusterer reproduces the Python result on real exported matrices |
| `PersonIdentityTest` | 4 | Person ids stay contiguous over the displayed list |
