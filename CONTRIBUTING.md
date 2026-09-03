# Contributing to Roll Call

Android app for the iykyk internship assignment: process a portrait video on-device, detect faces,
group them by identity, count each person's appearances, and build a shareable collage.

**Deadline: Sun 6 Sep 2026, 11:59 PM IST.**

## Where things are

| Path | What |
|---|---|
| `prd.md` | Product spec. The algorithm design lives in §5 — read it before touching the pipeline. |
| `plan.md` | Build order, 6 layers, a verification gate on every step. Work through it in order. |
| `progress.md` | Checkboxes + decisions log. Update after finishing a step. |
| `design_notes.md` | Design system and UI rules (Part I), code style (Part II). |
| `tools/prototype/` | Python prototype for tuning. Dev tool, not shipped, nothing depends on it. |
| `vids/` | The three supplied test clips (gitignored - drop them here yourself). |
| `design_insp/` | Reference images incl. the iykyk landing page. |
| `android-sdk/` | Local SDK (gitignored). `export ANDROID_HOME="$PWD/android-sdk"`. |
| `app/` | The Android app (once Layer 2 starts). |

## Grading — this drives priority

- **50%** identity grouping + appearance-count accuracy
- **30%** code quality + architecture
- **20%** usability, representative-shot quality, collage presentation

When time is short, accuracy wins. A pretty app that miscounts fails half the assignment.

## Hard rules

1. **Never hardcode results.** No `5`, no `20`, no names or timestamps from the sample clips
   anywhere in `app/`. The cluster count is discovered, never supplied. Knowing Sample 1's answer
   is for *validation only*.
2. **Tuned constants live in one place** — `PipelineConfig.kt` — each with its provenance.
3. **`domain/` imports nothing from `android.*`.** That's what keeps clustering, quality scoring and
   segmentation unit-testable on the JVM with no device.
4. **Don't skip a verification gate** in `plan.md`. A failed gate carried forward becomes three bugs.
5. **Everything on-device.** No backend, no network. The app has no `INTERNET` permission.
6. **ML Kit tracking IDs are a hint, never identity.** They're motion-based and break across cuts.
   Identity comes from embeddings + clustering only.
7. Update `progress.md` when a step completes.

## Code style

Full rules in `design_notes.md` Part II. The short version:

- **Comments explain *why*, code explains *what*.** If a comment restates the line under it, delete it.
- No docstrings on trivial functions. No `// ─── Section ───` banners.
- No blanket `try/catch`. Handle failures where they happen and where there's a real recovery.
- Short names in short scopes: `faces`, not `faceDetectionResultList`; `i`, not `itemCounter`.
- No dead scaffolding: no commented-out code, no unused "for later" helpers, no single-implementation
  interfaces.
- Idiomatic Kotlin, not translated Java.

Same principle for UI: no emoji as UI, no blue→purple gradients, no uniform 16dp everything, and
every screen needs designed empty / loading / error / cancelled states.

## Setup

The Android SDK is installed locally in `android-sdk/`:

```bash
export ANDROID_HOME="$PWD/android-sdk"
./android-sdk/platform-tools/adb.exe devices
./gradlew assembleDebug
```

Prototype (needs `opencv-python`, `mediapipe`, `ai-edge-litert`, `numpy` + `ffmpeg` on PATH):

```bash
python tools/prototype/pipeline.py 1     # frame sampling + scene cuts
```

## Facts already established

- Clips are 1080×1920, H.264, 25fps, 750 frames, exactly 30.000s, no rotation metadata.
- All three clips use the **same five-person cast**, re-cut in different orders with different
  pairings. Sample 1 pairs A+E and C+D; Sample 2 pairs A+B and E+D; Sample 3 pairs A+C and E+B.
- Roughly 75–80% of sampled frames are whip-pan blur. The blur gate is central, not incidental.
- The hard case is B vs C — both in cream headscarves, similarly lit. Most likely wrong merge.
- Embedding model: MobileFaceNet, verified `[1,112,112,3]` float32 → `[1,192]` float32, Apache 2.0,
  5.2 MB.
- Scene-cut detector finds ~14–17 cuts per clip at ~1.5s median spacing.

## What makes this approach distinct

1. **Temporal cannot-link constraint** — two faces in the same frame are provably different people,
   so clustering is forbidden from merging them.
2. **Scrubber strip** — per-person timeline showing appearance segments as blocks.
3. **Live face discovery** — thumbnails appear during processing.
