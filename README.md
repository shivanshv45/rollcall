# Roll Call

An Android app that watches a video, works out who appears in it, counts how many
times each person shows up, and builds a collage from their best shot.

Everything runs on the phone. No backend, no network calls, no API keys.

## What it does

Pick a video. The app samples it at 5 fps, finds faces, groups the ones belonging
to the same person, then reports each person with their appearance count and a
timeline showing when they were on screen.

It does not know how many people to expect. That number is worked out from the
footage.

## How the grouping works

The hard part is deciding that two faces, 20 seconds apart, in different lighting,
at different angles, are the same person.

```
video
  |
  v
[ sample at 5 fps, half resolution ]        FrameExtractor
  |
  v
[ detect faces ]                            MlKitFaceDetector
  |   every face in the frame, not just the biggest
  |   drops whip-pan blur, measured on the face not the whole frame
  v
[ align + embed ]                           FaceAligner -> FaceEmbedder
  |   level the eyes, scale to a fixed template, 112x112
  |   MobileFaceNet -> 192 numbers per face
  v
[ group into tracklets ]                    TrackletBuilder
  |   consecutive frames of one face become a single averaged embedding
  |   weighted by quality, so clean frames count for more
  v
[ cluster into people ]                     AgglomerativeClusterer
  |   average linkage, no k needed
  |   two faces in one frame can never be the same person
  v
[ split into appearances ]                  AppearanceSegmenter
  |   a gap over 0.8s or a scene cut starts a new appearance
  v
[ pick each person's shot ]                 PortraitPicker
  |   a solo frame where there is one, else cropped out of a shared frame
  |   re-detects at output size to check nobody else is in the crop
  v
[ frame the collage ]                       CollageRenderer
  |   portraits cut once, then repainted in any of six styles
  v
results + collage
```

Four things worth calling out:

**Tracklets before clustering.** Averaging a run of frames into one embedding kills
per-frame noise and cuts the clustering input by about 10x.

**Two faces in one frame are different people.** That is free ground truth, and the
clusterer treats it as a hard constraint the embeddings cannot override.

**Three clustering passes.** A strict pass builds confident identities. A looser
second pass folds leftovers into them, which are usually small or side-on faces
from two-person shots. A third merges the leftovers among themselves, for someone
who only ever appears in fragments and so has no strong cluster to join. One
threshold cannot do all of that, because those distances do not overlap.

**Thresholds were measured, not picked.** Every constant in `PipelineConfig` came
from a sweep over the sample clips, and each sits mid-plateau rather than at an
edge so it holds on footage it was not tuned against.

## Tech

| | |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM, Hilt for DI, coroutines and Flow |
| Face detection | ML Kit Face Detection 16.1.7 |
| Face embedding | MobileFaceNet, 192-d, via LiteRT 1.0.1 |
| Clustering | Average-linkage agglomerative, written from scratch |
| Tests | JUnit 4 + Robolectric, 147 tests |
| Min SDK | 26 |

### The embedding model

`app/src/main/assets/mobilefacenet.tflite`, 5.0 MB, Apache 2.0.

Input `[1,112,112,3]` float32, normalised to `(px - 127.5) / 128`.
Output `[1,192]` float32, L2-normalised before use.
Both shapes were read off the model file, not taken from documentation.

Faces are compared by cosine distance. Same person lands under 0.38, different
people above 0.9.

## Run it locally

You need JDK 17 and the Android SDK. No Android Studio required, though it works
fine if you have it.

```bash
git clone <this repo>
cd forIYKYK
```

Point the build at your SDK:

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

On Windows, escape the backslashes: `sdk.dir=C\:\\Users\\you\\android-sdk`

Then:

```bash
./gradlew testDebugUnitTest      # 147 tests, no device needed
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
```

Install on a phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK across and tap it, allowing installs from unknown sources.

### Testing without a phone

The domain layer has no Android imports, so the clustering, segmentation and
quality logic all run on the JVM. That is the part carrying the accuracy, and it
is the fast loop while working on it:

```bash
./gradlew testDebugUnitTest --tests '*AgglomerativeClusterer*'
```

`PortParityTest` is the interesting one. It feeds real distance matrices exported
from the Python prototype through the Kotlin clusterer and checks it produces the
same identities, which catches the port drifting from the version whose thresholds
were actually measured.

### The prototype

`tools/prototype/pipeline.py` is the Python version the algorithm was tuned in.
Threshold sweeps that take seconds there take minutes on a device, so all the
tuning happened first and the Kotlin port came after. It needs `opencv-python`,
`mediapipe` and `numpy`, plus clips in `vids/`.

## Notes

Faces are never cropped tight to the detected box. Tiles are re-decoded from the
video at output size and cropped wide enough for head and shoulders, sitting
slightly high so they do not read like mugshots.

The finished collage can be framed in one of six styles, chosen from a strip of
live previews under it. A frame is only ever paint around the tiles, so the
portraits are cut once and every style after that is a repaint rather than
another pass over the video. The cost is holding a portrait per person in memory
while that screen is open; they are recycled when it closes. Adding a style is
one entry in `CollageBorder.ALL` and nothing else.

Processing runs entirely off the main thread on `Dispatchers.Default`, and
cancelling the screen cancels the work.

If the app ever crashes, it writes the stack trace, device details, memory figures
and a trail of pipeline milestones to a file, then shows them on next launch with
a share button. That was built to debug a phone with no cable attached and it
earned its keep twice.

## Licence

MobileFaceNet is Apache 2.0.
