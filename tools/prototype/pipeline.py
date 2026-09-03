"""Desktop prototype of the Roll Call face pipeline.

Not shipped. This exists so threshold tuning takes seconds instead of a
Gradle build + install + logcat cycle. Once the numbers here are right they
get ported to Kotlin as PipelineConfig.
"""
from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

SAMPLE_FPS = 5.0
WORK_W, WORK_H = 540, 960

# Laplacian variance is bimodal on this footage: whip-pan frames land under 2,
# everything usable sits above 5, and the 2-5 band is empty across all three
# clips (24 rejects each). 3.0 sits in that gap, so the exact value doesn't matter.
BLUR_FLOOR = 3.0

MIN_FACE_RATIO = 0.10
EDGE_MARGIN_PX = 4

QUALITY_WEIGHTS = dict(sharpness=0.35, frontality=0.30, eyes=0.15, size=0.12, expression=0.08)

EMBED_SIZE = 112
CROP_MARGIN = 0.25

SCENE_CUT_THRESHOLD = 0.45

# Both thresholds are plateau midpoints from the stage-7 sweep: 5 people on all
# three clips holds across core 0.26-0.50 and assign 0.725-0.95. Different people
# only start merging at core 0.56, so 0.38 keeps a wide margin from that cliff.
CORE_THRESHOLD = 0.38
ASSIGN_THRESHOLD = 0.84
MIN_CORE_TRACKS = 3

# Below this gap the two candidates are tied and the smaller identity wins.
# Measured: identical results anywhere from 0.04 to 0.20.
TIE_MARGIN = 0.08

# 0.6 through 1.0 all give the same answer; 0.8 sits mid-range.
MAX_GAP_S = 0.8

# No minimum. Appearances in two-person shots are legitimately short (a single
# 0.2s sighting), and any floor above 0 deletes them - that alone cost sample 1
# an appearance. The blur gate already removes genuine flicker.
MIN_SEGMENT_S = 0.0

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VIDEOS = sorted(ROOT.glob("vids/*.mp4"))


@dataclass
class Face:
    t: float
    box: tuple[int, int, int, int]
    sharpness: float
    frontality: float
    eyes: float
    size: float
    expression: float
    clipped: bool
    embedding: np.ndarray | None = None

    @property
    def quality(self) -> float:
        w = QUALITY_WEIGHTS
        score = (w["sharpness"] * self.sharpness + w["frontality"] * self.frontality
                 + w["eyes"] * self.eyes + w["size"] * self.size
                 + w["expression"] * self.expression)
        return float(np.clip(score, 0.0, 1.0))


@dataclass
class Frame:
    t: float
    image: np.ndarray
    faces: list[Face] = field(default_factory=list)


def decode(video: Path, fps: float = SAMPLE_FPS) -> list[Frame]:
    cmd = ["ffmpeg", "-v", "error", "-i", str(video),
           "-vf", f"fps={fps},scale={WORK_W}:{WORK_H}",
           "-f", "rawvideo", "-pix_fmt", "bgr24", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    stride = WORK_W * WORK_H * 3
    return [
        Frame(t=i / fps,
              image=np.frombuffer(raw[i * stride:(i + 1) * stride], np.uint8)
                      .reshape(WORK_H, WORK_W, 3))
        for i in range(len(raw) // stride)
    ]


def sharpness(gray: np.ndarray) -> float:
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def scene_cuts(frames: list[Frame]) -> list[float]:
    """Timestamps of hard cuts.

    A cut and a whip-pan both produce a large frame-to-frame difference. They
    separate downstream: whip-pan frames fail the blur gate, cuts don't.
    """
    thumbs, hists = [], []
    for f in frames:
        gray = cv2.cvtColor(f.image, cv2.COLOR_BGR2GRAY)
        thumbs.append(cv2.resize(gray, (32, 32)).astype(np.float32) / 255.0)
        hsv = cv2.cvtColor(f.image, cv2.COLOR_BGR2HSV)
        h = cv2.calcHist([hsv], [0], None, [50], [0, 180])
        hists.append(cv2.normalize(h, h).flatten())

    cuts = []
    for i in range(1, len(frames)):
        mad = float(np.mean(np.abs(thumbs[i] - thumbs[i - 1])))
        corr = max(float(cv2.compareHist(hists[i - 1], hists[i], cv2.HISTCMP_CORREL)), 0.0)
        if 0.6 * (mad / 0.35) + 0.4 * (1 - corr) > SCENE_CUT_THRESHOLD:
            cuts.append(frames[i].t)
    return cuts


def landmarker(max_faces: int = 5):
    return vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(HERE / "models/face_landmarker.task")),
        running_mode=vision.RunningMode.IMAGE,
        num_faces=max_faces,
        output_face_blendshapes=True,
        output_facial_transformation_matrixes=True))


def head_pose(matrix) -> tuple[float, float, float]:
    """Yaw, pitch, roll in degrees from MediaPipe's 4x4 transform.

    Stands in for ML Kit's headEulerAngleY/X/Z, which the Kotlin port reads directly.
    """
    r = np.array(matrix)[:3, :3]
    sy = float(np.sqrt(r[0, 0] ** 2 + r[1, 0] ** 2))
    if sy > 1e-6:
        pitch = np.arctan2(r[2, 1], r[2, 2])
        yaw = np.arctan2(-r[2, 0], sy)
        roll = np.arctan2(r[1, 0], r[0, 0])
    else:
        pitch, yaw, roll = np.arctan2(-r[1, 2], r[1, 1]), np.arctan2(-r[2, 0], sy), 0.0
    return tuple(float(np.degrees(a)) for a in (yaw, pitch, roll))


def detect(frame: Frame, lm) -> list[Face]:
    rgb = cv2.cvtColor(frame.image, cv2.COLOR_BGR2RGB)
    res = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
    gray = cv2.cvtColor(frame.image, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    faces = []

    for pts, shapes, matrix in zip(res.face_landmarks, res.face_blendshapes,
                                   res.facial_transformation_matrixes):
        xs = [p.x * w for p in pts]
        ys = [p.y * h for p in pts]
        x0, x1 = int(min(xs)), int(max(xs))
        y0, y1 = int(min(ys)), int(max(ys))
        bw, bh = x1 - x0, y1 - y0
        if bw < MIN_FACE_RATIO * w:
            continue

        patch = gray[max(y0, 0):min(y1, h), max(x0, 0):min(x1, w)]
        if patch.size == 0:
            continue
        sharp = sharpness(patch)
        if sharp < BLUR_FLOOR:
            continue

        bs = {b.category_name: b.score for b in shapes}
        yaw, pitch, _ = head_pose(matrix)
        blink = max(bs.get("eyeBlinkLeft", 0.0), bs.get("eyeBlinkRight", 0.0))
        smile = (bs.get("mouthSmileLeft", 0.0) + bs.get("mouthSmileRight", 0.0)) / 2

        faces.append(Face(
            t=frame.t,
            box=(x0, y0, bw, bh),
            sharpness=min(sharp / 60.0, 1.0),
            frontality=max(0.0, np.cos(np.radians(yaw)) * np.cos(np.radians(pitch))),
            eyes=1.0 - blink,
            size=min((bw * bh) / (w * h) / 0.15, 1.0),
            expression=min(smile * 2, 1.0),
            clipped=(x0 <= EDGE_MARGIN_PX or y0 <= EDGE_MARGIN_PX
                     or x1 >= w - EDGE_MARGIN_PX or y1 >= h - EDGE_MARGIN_PX),
        ))
    return faces


def stage2():
    lm = landmarker()
    for video in VIDEOS:
        frames = decode(video)
        per_frame = [detect(f, lm) for f in frames]
        counts = [len(f) for f in per_frame]
        allf = [f for fs in per_frame for f in fs]
        multi = sum(1 for c in counts if c > 1)
        print(f"\n{video.name}")
        print(f"  frames with >=1 face: {sum(1 for c in counts if c)}/{len(frames)}")
        print(f"  frames with 2+ faces: {multi}")
        print(f"  total faces kept: {len(allf)}")
        if allf:
            q = np.array([f.quality for f in allf])
            print(f"  quality  min {q.min():.2f}  median {np.median(q):.2f}  max {q.max():.2f}")
            print(f"  clipped: {sum(f.clipped for f in allf)}/{len(allf)}")
            two = [i / SAMPLE_FPS for i, c in enumerate(counts) if c > 1]
            print(f"  2-face timestamps: {', '.join(f'{t:.1f}' for t in two)}")


def stage1():
    for video in VIDEOS:
        frames = decode(video)
        cuts = scene_cuts(frames)
        whole = [sharpness(cv2.cvtColor(f.image, cv2.COLOR_BGR2GRAY)) for f in frames]
        gaps = np.diff(cuts) if len(cuts) > 1 else []

        print(f"\n{video.name}")
        print(f"  {len(frames)} frames @ {SAMPLE_FPS}fps")
        print(f"  whole-frame sharpness  min {min(whole):.0f}  "
              f"median {np.median(whole):.0f}  max {max(whole):.0f}")
        print(f"  cuts ({len(cuts)}): {', '.join(f'{c:.1f}' for c in cuts)}")
        if len(gaps):
            print(f"  inter-cut gap  median {np.median(gaps):.1f}s  "
                  f"min {min(gaps):.1f}s  max {max(gaps):.1f}s")




LEFT_EYE, RIGHT_EYE = 33, 263  # MediaPipe outer eye corners


def align_crop(image: np.ndarray, pts, size: int = EMBED_SIZE) -> np.ndarray:
    """Rotate so the eye line is level, then crop a fixed geometry around the face.

    Removing in-plane rotation is the cheapest accuracy win available here, and
    it's why we ask the detector for landmarks at all.
    """
    h, w = image.shape[:2]
    lx, ly = pts[LEFT_EYE].x * w, pts[LEFT_EYE].y * h
    rx, ry = pts[RIGHT_EYE].x * w, pts[RIGHT_EYE].y * h
    cx, cy = (lx + rx) / 2, (ly + ry) / 2
    angle = np.degrees(np.arctan2(ry - ly, rx - lx))
    eye_dist = float(np.hypot(rx - lx, ry - ly))
    if eye_dist < 1:
        return None

    # Place the eyes on a fixed baseline so every crop has the same scale.
    scale = (size * 0.42) / eye_dist
    m = cv2.getRotationMatrix2D((cx, cy), angle, scale)
    m[0, 2] += size / 2 - cx
    m[1, 2] += size * 0.42 - cy
    return cv2.warpAffine(image, m, (size, size), flags=cv2.INTER_LINEAR)


class Embedder:
    def __init__(self):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=str(HERE / "models/mobilefacenet.tflite"))
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]
        self.out = self.it.get_output_details()[0]

    def __call__(self, bgr_crop: np.ndarray) -> np.ndarray:
        rgb = cv2.cvtColor(bgr_crop, cv2.COLOR_BGR2RGB).astype(np.float32)
        x = ((rgb - 127.5) / 128.0)[None]
        self.it.set_tensor(self.inp["index"], x)
        self.it.invoke()
        v = self.it.get_tensor(self.out["index"])[0]
        return v / (np.linalg.norm(v) + 1e-9)


def collect(video: Path, lm, embed: Embedder) -> list[Face]:
    faces = []
    for frame in decode(video):
        rgb = cv2.cvtColor(frame.image, cv2.COLOR_BGR2RGB)
        res = lm.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
        gray = cv2.cvtColor(frame.image, cv2.COLOR_BGR2GRAY)
        h, w = gray.shape

        for pts, shapes, matrix in zip(res.face_landmarks, res.face_blendshapes,
                                       res.facial_transformation_matrixes):
            xs = [p.x * w for p in pts]
            ys = [p.y * h for p in pts]
            x0, x1, y0, y1 = int(min(xs)), int(max(xs)), int(min(ys)), int(max(ys))
            bw, bh = x1 - x0, y1 - y0
            if bw < MIN_FACE_RATIO * w:
                continue
            patch = gray[max(y0, 0):min(y1, h), max(x0, 0):min(x1, w)]
            if patch.size == 0 or sharpness(patch) < BLUR_FLOOR:
                continue
            crop = align_crop(frame.image, pts)
            if crop is None:
                continue

            bs = {b.category_name: b.score for b in shapes}
            yaw, pitch, _ = head_pose(matrix)
            face = Face(
                t=frame.t, box=(x0, y0, bw, bh),
                sharpness=min(sharpness(patch) / 60.0, 1.0),
                frontality=max(0.0, np.cos(np.radians(yaw)) * np.cos(np.radians(pitch))),
                eyes=1.0 - max(bs.get("eyeBlinkLeft", 0.0), bs.get("eyeBlinkRight", 0.0)),
                size=min((bw * bh) / (w * h) / 0.15, 1.0),
                expression=min((bs.get("mouthSmileLeft", 0.0)
                                + bs.get("mouthSmileRight", 0.0)) / 2 * 2, 1.0),
                clipped=(x0 <= EDGE_MARGIN_PX or y0 <= EDGE_MARGIN_PX
                         or x1 >= w - EDGE_MARGIN_PX or y1 >= h - EDGE_MARGIN_PX),
            )
            face.embedding = embed(crop)
            faces.append(face)
    return faces


def stage4():
    lm, embed = landmarker(), Embedder()
    for video in VIDEOS:
        faces = collect(video, lm, embed)
        E = np.stack([f.embedding for f in faces])
        d = 1 - E @ E.T
        near = d[np.triu_indices_from(d, 1)]
        print(f"\n{video.name}: {len(faces)} embedded")
        print(f"  pairwise cosine distance  min {near.min():.3f}  "
              f"p10 {np.percentile(near, 10):.3f}  median {np.median(near):.3f}  "
              f"p90 {np.percentile(near, 90):.3f}  max {near.max():.3f}")
        hist, edges = np.histogram(near, bins=12, range=(0, 1.2))
        for c, lo in zip(hist, edges):
            print(f"   {lo:.1f}-{lo+0.1:.1f} {c:5d} {'#' * int(60 * c / hist.max())}")




def cannot_link(faces: list[Face]) -> np.ndarray:
    """Pairs seen in the same frame: provably different people, never merged."""
    n = len(faces)
    block = np.zeros((n, n), bool)
    by_time: dict[float, list[int]] = {}
    for i, f in enumerate(faces):
        by_time.setdefault(f.t, []).append(i)
    for group in by_time.values():
        for a in group:
            for b in group:
                if a != b:
                    block[a, b] = True
    return block


def cluster(D: np.ndarray, block: np.ndarray, threshold: float) -> np.ndarray:
    """Average linkage over a precomputed distance matrix.

    Group distances are maintained incrementally as a weighted running mean, so
    a merge costs O(k) rather than a rescan of every member pair.
    """
    k = D.shape[0]
    active = list(range(k))
    size = np.ones(k)
    M = D.copy()
    np.fill_diagonal(M, np.inf)
    M[block] = np.inf
    owner = np.arange(k)

    while len(active) > 1:
        sub = M[np.ix_(active, active)]
        i, j = np.unravel_index(np.argmin(sub), sub.shape)
        if sub[i, j] >= threshold:
            break
        a, b = active[i], active[j]

        na, nb = size[a], size[b]
        merged = (na * M[a, active] + nb * M[b, active]) / (na + nb)
        blocked = np.isinf(M[a, active]) | np.isinf(M[b, active])
        merged[blocked] = np.inf
        M[a, active] = merged
        M[active, a] = merged
        M[a, a] = np.inf
        size[a] = na + nb
        active.remove(b)
        owner[owner == b] = a

    for new, root in enumerate(sorted(set(owner.tolist()))):
        owner[owner == root] = -new - 1
    return -owner - 1


def stage5():
    lm, embed = landmarker(), Embedder()
    prepared = []
    for v in VIDEOS:
        faces = collect(v, lm, embed)
        E = np.stack([f.embedding for f in faces])
        prepared.append((v.name, 1 - E @ E.T, cannot_link(faces)))

    print(f"{'thresh':>7}" + "".join(f"{n.split('_')[-1][:-4]:>9}" for n, _, _ in prepared))
    plateau = []
    for t in np.arange(0.20, 1.21, 0.02):
        counts = [len(set(cluster(D, b, t).tolist())) for _, D, b in prepared]
        if all(c == 5 for c in counts):
            plateau.append(t)
        print(f"{t:7.2f}" + "".join(f"{c:9d}" for c in counts)
              + ("   <-- 5 everywhere" if all(c == 5 for c in counts) else ""))

    if plateau:
        lo, hi = min(plateau), max(plateau)
        print(f"\nplateau {lo:.2f}-{hi:.2f} (width {hi-lo:.2f}), midpoint {(lo+hi)/2:.3f}")
    else:
        print("\nno threshold gives 5 on all three clips")




@dataclass
class Tracklet:
    """One continuous run of a face between cuts, reduced to a single embedding."""
    faces: list[Face]

    @property
    def t0(self) -> float:
        return self.faces[0].t

    @property
    def t1(self) -> float:
        return self.faces[-1].t

    @property
    def embedding(self) -> np.ndarray:
        w = np.array([f.quality for f in self.faces])
        v = (np.stack([f.embedding for f in self.faces]) * w[:, None]).sum(0) / w.sum()
        return v / (np.linalg.norm(v) + 1e-9)

    @property
    def best(self) -> Face:
        return max(self.faces, key=lambda f: f.quality - (0.25 if f.clipped else 0))


def build_tracklets(faces: list[Face], cuts: list[float]) -> list[Tracklet]:
    """Chain faces across adjacent frames by position and appearance.

    Stands in for ML Kit's trackingId, which the Kotlin port uses directly. Both
    are only local hints: identity still comes from clustering the results.
    """
    step = 1 / SAMPLE_FPS
    by_time: dict[float, list[Face]] = {}
    for f in faces:
        by_time.setdefault(round(f.t, 3), []).append(f)

    open_tracks: list[list[Face]] = []
    done: list[list[Face]] = []
    for t in sorted(by_time):
        is_cut = any(abs(t - c) < 1e-6 for c in cuts)
        carried, fresh = [], list(by_time[t])
        if not is_cut:
            for track in open_tracks:
                prev = track[-1]
                if t - prev.t > step * 1.5:
                    continue
                match = min(fresh, key=lambda f: box_gap(prev.box, f.box), default=None)
                if match and box_gap(prev.box, match.box) < 0.5 \
                        and float(1 - prev.embedding @ match.embedding) < 0.45:
                    track.append(match)
                    fresh.remove(match)
                    carried.append(track)
        done += [tr for tr in open_tracks if tr not in carried]
        open_tracks = carried + [[f] for f in fresh]
    done += open_tracks
    return [Tracklet(t) for t in done]


def box_gap(a, b) -> float:
    """Centre distance normalised by mean box width."""
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ca, cb = (ax + aw / 2, ay + ah / 2), (bx + bw / 2, by + bh / 2)
    return float(np.hypot(ca[0] - cb[0], ca[1] - cb[1]) / max((aw + bw) / 2, 1))


def cannot_link_tracks(tracks: list[Tracklet]) -> np.ndarray:
    n = len(tracks)
    block = np.zeros((n, n), bool)
    times = [{round(f.t, 3) for f in tr.faces} for tr in tracks]
    for i in range(n):
        for j in range(i + 1, n):
            if times[i] & times[j]:
                block[i, j] = block[j, i] = True
    return block


def prepare(video: Path, lm, embed) -> tuple[list[Tracklet], np.ndarray, np.ndarray]:
    frames = decode(video)
    cuts = scene_cuts(frames)
    faces = collect(video, lm, embed)
    tracks = [t for t in build_tracklets(faces, cuts) if t.faces]
    E = np.stack([t.embedding for t in tracks])
    return tracks, 1 - E @ E.T, cannot_link_tracks(tracks)


def stage6():
    lm, embed = landmarker(), Embedder()
    prepared = [(v.name, *prepare(v, lm, embed)) for v in VIDEOS]
    for name, tracks, _, _ in prepared:
        print(f"{name}: {len(tracks)} tracklets")

    print(f"\n{'thresh':>7}" + "".join(f"{n.split('_')[-1][:-4]:>9}" for n, *_ in prepared))
    plateau = []
    for t in np.arange(0.20, 1.21, 0.02):
        counts = [len(set(cluster(D, b, t).tolist())) for _, _, D, b in prepared]
        if all(c == 5 for c in counts):
            plateau.append(t)
        print(f"{t:7.2f}" + "".join(f"{c:9d}" for c in counts)
              + ("   <-- 5 everywhere" if all(c == 5 for c in counts) else ""))
    if plateau:
        lo, hi = min(plateau), max(plateau)
        print(f"\nplateau {lo:.2f}-{hi:.2f} (width {hi-lo:.2f}), midpoint {(lo+hi)/2:.3f}")




def cluster_two_pass(D, block, core_t, assign_t, min_core=MIN_CORE_TRACKS):
    """Strict pass builds confident identities; relaxed pass places the leftovers.

    Fragments from two-person shots embed poorly - small or side-on faces sit
    0.5-0.7 from their own identity while distinct people sit above 0.9, which no
    single threshold spans. The relaxed pass closes that gap, and where the top
    two candidates are effectively tied it prefers the smaller identity: guessing
    on a 0.01 margin tends to leave one person over-counted and another short.
    """
    labels = cluster(D, block, core_t).copy()
    sizes = {l: int((labels == l).sum()) for l in set(labels.tolist())}
    strong = [l for l, c in sizes.items() if c >= min_core]
    if not strong:
        return labels

    for l in sorted((l for l, c in sizes.items() if c < min_core), key=lambda l: sizes[l]):
        members = np.where(labels == l)[0]
        ranked = []
        for s in strong:
            others = np.where(labels == s)[0]
            if not block[np.ix_(members, others)].any():
                ranked.append((float(D[np.ix_(members, others)].mean()), s))
        ranked.sort()
        if not ranked or ranked[0][0] >= assign_t:
            continue
        if len(ranked) > 1 and ranked[1][0] - ranked[0][0] < TIE_MARGIN:
            ranked = sorted(ranked[:2], key=lambda r: (int((labels == r[1]).sum()), r[0]))
        labels[members] = ranked[0][1]

    for new, old in enumerate(sorted(set(labels.tolist()))):
        labels[labels == old] = -new - 1
    return -labels - 1


def stage7():
    lm, embed = landmarker(), Embedder()
    prepared = [(v.name, *prepare(v, lm, embed)) for v in VIDEOS]
    print(f"{'core':>6} {'assign':>7}" + "".join(f"{n.split('_')[-1][:-4]:>9}" for n, *_ in prepared))
    good = []
    for core_t in np.arange(0.30, 0.56, 0.02):
        for assign_t in np.arange(0.55, 0.86, 0.05):
            counts = [len(set(cluster_two_pass(D, b, core_t, assign_t).tolist()))
                      for _, _, D, b in prepared]
            if all(c == 5 for c in counts):
                good.append((core_t, assign_t))
                print(f"{core_t:6.2f} {assign_t:7.2f}" + "".join(f"{c:9d}" for c in counts) + "   <-- 5")
    if good:
        cs = sorted({c for c, _ in good}); as_ = sorted({a for _, a in good})
        print(f"\ncore plateau {cs[0]:.2f}-{cs[-1]:.2f}  assign plateau {as_[0]:.2f}-{as_[-1]:.2f}")
        print(f"midpoints: core={np.mean([cs[0],cs[-1]]):.3f} assign={np.mean([as_[0],as_[-1]]):.3f}")




def segment(times: list[float], cuts: list[float]) -> list[tuple[float, float]]:
    """Split a person's sighting times into appearances.

    Two triggers: a gap longer than MAX_GAP_S, and a scene cut crossed without a
    gap - the latter catches a cut from a close-up to a wider shot that still
    contains the same person, which the brief counts as a new appearance.
    """
    if not times:
        return []
    times = sorted(times)
    spans, start, prev = [], times[0], times[0]
    for t in times[1:]:
        crossed = any(prev < c <= t for c in cuts)
        if t - prev > MAX_GAP_S or crossed:
            spans.append((start, prev))
            start = t
        prev = t
    spans.append((start, prev))
    step = 1 / SAMPLE_FPS
    return [(a, b + step) for a, b in spans if (b + step) - a >= MIN_SEGMENT_S]


def analyse(video: Path, lm, embed):
    frames = decode(video)
    cuts = scene_cuts(frames)
    faces = collect(video, lm, embed)
    tracks = [t for t in build_tracklets(faces, cuts) if t.faces]
    E = np.stack([t.embedding for t in tracks])
    labels = cluster_two_pass(1 - E @ E.T, cannot_link_tracks(tracks),
                              CORE_THRESHOLD, ASSIGN_THRESHOLD, MIN_CORE_TRACKS)

    people = {}
    for label, track in zip(labels.tolist(), tracks):
        people.setdefault(label, []).append(track)

    out = []
    for label, group in people.items():
        times = sorted({round(f.t, 3) for tr in group for f in tr.faces})
        spans = segment(times, cuts)
        best = max((tr.best for tr in group),
                   key=lambda f: f.quality - (0.25 if f.clipped else 0))
        out.append(dict(spans=spans, best=best,
                        screen=sum(b - a for a, b in spans)))
    return sorted(out, key=lambda p: p["spans"][0][0] if p["spans"] else 0)


def stage8():
    lm, embed = landmarker(), Embedder()
    for video in VIDEOS:
        people = analyse(video, lm, embed)
        total = sum(len(p["spans"]) for p in people)
        print(f"\n{video.name}")
        print(f"  {len(people)} people, {total} appearances")
        for i, p in enumerate(people):
            spans = " ".join(f"{a:.1f}-{b:.1f}" for a, b in p["spans"])
            print(f"   {chr(65+i)}  {len(p['spans'])}x  {p['screen']:4.1f}s  "
                  f"best t={p['best'].t:5.1f} q={p['best'].quality:.2f}   {spans}")


if __name__ == "__main__":
    {"1": stage1, "2": stage2, "4": stage4, "5": stage5, "6": stage6, "7": stage7, "8": stage8}[sys.argv[1] if len(sys.argv) > 1 else "1"]()
