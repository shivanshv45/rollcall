# Implementation Plan — Roll Call

**Read [`prd.md`](prd.md) first.** This file is the build order. It is written to be executed
step-by-step, so each step states its **goal**, **exact work**, and a
**verification gate** that must pass before moving on.

> ## Rules for whoever is implementing this
>
> 1. **Do the steps in order.** Later steps assume earlier gates passed.
> 2. **Do not skip a verification gate.** If a gate fails, fix it before continuing. A failing gate
>    that gets carried forward turns into three bugs later.
> 3. **Never hardcode results.** No `5`, no `20`, no person names, no timestamps from the sample
>    videos anywhere in `app/`. Counts are always computed.
> 4. **Tuned constants live in one file** (`PipelineConfig.kt`) with a comment explaining each
>    value's justification. No magic numbers scattered through the code.
> 5. **Update [`progress.md`](progress.md)** after each step completes.
> 6. **`domain/` must never import `android.*`.** That's what keeps it JVM-testable. If you need a
>    Bitmap in domain logic, you've put the logic in the wrong layer.
> 7. **Commit after each layer.** Small, working commits.

---

## Layer 0 — Toolchain (blocking; nothing else works first)

**Goal:** a working command-line Android SDK, and a device that can install an APK.

### Step 0.1 — Install Android command-line SDK
- Download `commandlinetools-win-*.zip` from https://developer.android.com/studio#command-line-tools-only
- Extract to `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\` (the `latest` folder name matters).
- Set `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`, add `platform-tools` and `cmdline-tools\latest\bin`
  to PATH.
- `sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"`
- Accept licences: `sdkmanager --licenses`

**Gate:** `adb version` and `sdkmanager --list_installed` both succeed.

### Step 0.2 — Phone setup
- Settings → About phone → tap **Build number** 7× → Developer options enabled.
- Developer options → enable **USB debugging**. Connect by USB, accept the RSA prompt.

**Gate:** `adb devices` lists the phone as `device` (not `unauthorized`/`offline`).

---

## Layer 1 — Algorithm prototype in Python (the 50%, de-risked)

**Why first:** see PRD §9.5. Proving the algorithm on the desktop takes minutes; proving it on a
device takes hours. All tuned constants come out of this layer.

Everything here lives in `tools/prototype/`. **No app code depends on it.**

### Step 1.1 — Frame extraction + scene-cut detection
Extract frames at 5 fps from each clip via ffmpeg. Compute per-adjacent-pair global difference
(32×32 greyscale MAD + histogram correlation).

**Gate:** scene-cut timestamps printed for each clip look plausible against the contact sheets
(cuts roughly every 1–3 s, matching the fast-cut edit).

### Step 1.2 — Face detection
ML Kit isn't available in Python. Use **MediaPipe** or **OpenCV DNN/YuNet** as the prototype
detector. It only needs to be good enough to produce face boxes + eye landmarks for tuning.

> **Important:** the prototype detector will *not* match ML Kit exactly. The threshold tuned here is
> a strong starting point, then **re-validated on-device** in Step 5.3. Treat prototype numbers as
> "correct within ~±0.05", not gospel.

**Gate:** faces found in a sensible fraction of sharp frames; near-zero in whip-pan frames.

### Step 1.3 — Quality gate
Implement PRD §5.3: Laplacian-variance sharpness on the face region, frontality from pose, eyes,
size, expression. Calibrate the blur floor.

**Gate:** print the 10 sharpest and 10 blurriest accepted faces as a contact sheet — blurriest
accepted must still be recognisable, and every whip-pan smear must be rejected.

### Step 1.4 — Alignment + embeddings
Eye-line similarity alignment, 25% margin crop, 112×112, `[-1,1]` normalisation, run
`mobilefacenet.tflite`, L2-normalise output. Aggregate per tracklet, quality-weighted.

**Gate:** for two crops of the *same* person, cosine distance is small; for two *different* people,
large. Print the matrix. If this doesn't separate, **stop and fix alignment** — everything
downstream depends on it.

### Step 1.5 — ⭐ Threshold sweep (the gating step)
Implement average-linkage agglomerative clustering with cannot-link constraints. Sweep
`MERGE_THRESHOLD` 0.20→1.20 in 0.01 steps across all three clips. Emit a table of
threshold → cluster count per clip, plus intra/inter-cluster separation margin.

**Gate — the most important in the whole plan:**
- There is a **contiguous plateau** where all three clips report **5**.
- The plateau is **≥0.10 wide** (narrower means the model/alignment isn't separating well enough —
  go back to 1.4).
- Chosen threshold = **plateau midpoint**. Record it, the table, and the margin for the README.

### Step 1.6 — Segmentation + full validation
Apply gap-splitting (`MAX_GAP`) + scene-cut splitting + `MIN_SEGMENT` filtering.

**Gate:** Sample 1 reports **5 people × 4 appearances = 20 total**, and A+B share ~10.1–11.5 s and
C+D share ~20.2–21.6 s. Samples 2 and 3 report 5 people with self-consistent counts.
**Record all final constants** — they are the spec for Layer 3.

---

## Layer 2 — Android project skeleton

### Step 2.1 — Project scaffold
Gradle KTS, version catalog (`libs.versions.toml`), `minSdk 26`, `targetSdk 35`, Kotlin 2.x, Compose
BOM, Hilt, ML Kit face detection, LiteRT, coroutines, Coil. Package `com.shivansh.rollcall`.
`INTERNET` permission deliberately absent.

**Gate:** `./gradlew assembleDebug` succeeds; APK installs and launches to a blank screen.

### Step 2.2 — Theme + design system
`ui/theme/`: Color, Type, Shape, Spacing from `design_notes.md` §7. Dark-first.
**Every value is a named token** — no raw hex or raw dp in screen code, ever.

**Gate:** a scratch screen renders each token; verify at 1.0× and 1.3× font scale.

### Step 2.3 — Domain models + config
`domain/model/`: `FaceSample`, `TrackletEmbedding`, `Person`, `Appearance`, `ProcessingState`,
`PipelineConfig`. Pure Kotlin. `PipelineConfig` carries every constant from Step 1.6, each with a
justifying comment.

**Gate:** `./gradlew test` runs (even with no tests yet); `domain/` imports zero `android.*`.

---

## Layer 3 — The pipeline in Kotlin (port the proven algorithm)

Port from the Layer-1 prototype. Because the algorithm is already proven, this is translation, not
invention — and each piece gets a JVM unit test asserting the prototype's known-good behaviour.

### Step 3.1 — `CosineDistance` + `AgglomerativeClusterer`
Pure Kotlin. Average linkage, cannot-link constraint set, threshold cut, singleton absorption.

**Gate — JVM tests, no device:**
- 3 well-separated synthetic clusters → exactly 3.
- Chaining case (a bridge point between two groups) → does **not** collapse into 1.
- Cannot-link pair → never co-clustered, even at a very loose threshold.
- Identical vectors → distance 0; orthogonal → 1; symmetric.

### Step 3.2 — `FaceQualityScorer`
**Gate:** weights sum to 1.0; blurrier ⇒ strictly lower; more frontal ⇒ strictly higher; clipped ⇒
penalised; score always within [0,1].

### Step 3.3 — `AppearanceSegmenter`
Gap splitting + scene-cut splitting + minimum-duration filter.

**Gate:** hand-built timestamp fixtures — a continuous run ⇒ 1; a run with a 1 s hole ⇒ 2; a 0.2 s
blink ⇒ still 1; a 0.1 s flicker ⇒ 0; a scene cut mid-run ⇒ 2; **and the brief's 1.4 s example ⇒ 1.**

### Step 3.4 — `FrameExtractor`
`MediaMetadataRetriever`, `getScaledFrameAtTime` + `OPTION_CLOSEST`, half-res, reads rotation
metadata, emits `(timestampUs, Bitmap)`, recycles promptly, cancellable.

**Gate:** instrumented test on a sample video — expected frame count, non-null bitmaps, correct
orientation, memory stable across the run.

### Step 3.5 — `MlKitFaceDetector` + `FaceEmbedder`
Detector per PRD §5.2. Embedder: aligner → crop → 112×112 → LiteRT → L2-normalise.

**Gate:** on a sample clip, same-person crops cluster tightly and different-person crops don't —
**the same separation matrix as Step 1.4, now on-device.** Numbers should be close to the prototype.

### Step 3.6 — `ProcessingRepository` — wire the pipeline
Orchestrate all stages, emit `Flow<ProcessingState>`, run on `Dispatchers.Default`, fully cancellable.

**Gate:** end-to-end on all three clips via an instrumented test. **Sample 1 must report 5 people /
20 appearances.** If it doesn't, compare against the prototype stage-by-stage to find the divergence.

### Step 3.7 — ⭐ On-device threshold re-validation
Re-run the Step 1.5 sweep using **real ML Kit + on-device embeddings**. Confirm the plateau; adjust
`PipelineConfig` to the on-device plateau midpoint if it has shifted.

**Gate:** final threshold locked and recorded for the README with its evidence.

---

## Layer 4 — UI

Build against `design_notes.md`. Every screen needs **empty, loading, error, and cancelled** states.

### Step 4.1 — Navigation + Home
Nav graph, video picker via `ActivityResultContracts.OpenDocument` (SAF — no storage permission).

**Gate:** picking a video navigates onward with a valid URI; cancelling the picker doesn't crash;
designed empty state present.

### Step 4.2 — Processing screen
Determinate progress, named stage, live counters, face thumbnails appearing as found, working Cancel.

**Gate:** UI stays responsive throughout (scroll/animate during processing); Cancel genuinely stops
work; rotating the device doesn't restart processing or crash.

### Step 4.3 — Results screen
Header count, per-person rows, identity chips (letter **and** colour), and the **scrubber strip**.

**Gate:** counts match the pipeline exactly; readable at 1.3× font scale; correct with 1 person and
with 10+ people.

### Step 4.4 — Collage renderer
`CollageRenderer`: 1080×1920, adaptive layouts per PRD §8, full-res representative re-decode,
generous crops, Canvas composition off the main thread.

**Gate:** render for 1/2/3/4/5/6/9 people — no overlap, no clipped faces, no stretched aspect
ratios, text legible at every count.

### Step 4.5 — Collage screen, save, share
Preview, MediaStore save, `FileProvider` + `ACTION_SEND` share.

**Gate:** saved image appears in the gallery app; share sheet opens and successfully sends to at
least two apps; verify on API 26–28 path too (`WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`).

---

## Layer 5 — Polish, verification, delivery

### Step 5.1 — Motion, haptics, states
Spring transitions, haptic tick on completion/primary actions, audit every screen for the four states.

### Step 5.2 — Full QA pass
| Check | Expected |
|---|---|
| All three clips end-to-end | No crash; plausible counts; Sample 1 = 5/20 |
| A non-portrait / landscape video | Handled, no crash |
| A video with no faces | Designed empty state, not a crash |
| A corrupt / non-video file | Clear error message |
| Cancel mid-processing | Stops promptly, returns Home |
| Rotate during each screen | State preserved |
| Background/foreground mid-processing | Survives or resumes cleanly |
| 1.3× font scale | No clipped text |
| Low-memory device | No OOM |

### Step 5.3 — README
Build/setup steps · **embedding model + provenance + licence** · **chosen threshold and the sweep
evidence** · architecture overview · the cannot-link and scene-cut ideas · known limitations.
*(The brief explicitly asks for the model and the threshold — do not bury them.)*

### Step 5.4 — Debug APK
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`, verified installing on a
clean device.

### Step 5.5 — Screen recording (≤60 s)
`adb shell screenrecord --time-limit 60 /sdcard/demo.mp4`, or the phone's built-in recorder.
Budget: ~18 s per video × 3. For each: pick video → show processing → **hold on the counts** →
**hold on the collage long enough to read**. No narration or editing needed.

**Gate:** all three collages clearly legible on playback. This is an explicit submission
requirement — if a collage isn't readable, re-record.

### Step 5.6 — Final submission check
Repo pushed · README complete · APK attached · recording attached · deadline **Sun 6 Sep 2026,
11:59 PM IST**.

---

## Time budget (12–15 h)

| Layer | Est. | Notes |
|---|---|---|
| 0 Toolchain | 0.5–1 h | One-time |
| 1 Prototype | 3–4 h | **The 50%. Worth every minute.** |
| 2 Skeleton | 1–1.5 h | |
| 3 Pipeline | 3–4 h | Mostly translation from proven code |
| 4 UI | 3–4 h | |
| 5 Polish + delivery | 1.5–2 h | |

**If time runs short, cut in this order:** collage layout variety (ship 5-person + generic grid) →
live face thumbnails → recent-runs list. **Never cut:** pipeline accuracy, the four UI states, save
and share, or the README's threshold justification.
