# Architecture

The governing document is [FIRST-PRINCIPLES.md](FIRST-PRINCIPLES.md). The Pixel is a perceptual compositor,
not a second PS5. This page maps each of its principles to the code that implements it, and says what is still open.

```
PS5 ──Remote Play──▶ Pixel: frame source ──▶ renderer (Chrome, WebGL2) ──HDMI──▶ PSVR processor unit ──▶ headset
                                                ▲            ▲
                         psvrd (Termux) ── pose + buttons    calibration.json ◀── PSVR Alignment app
                              ▲ USB (interfaces 4 sensor, 5 control)
```

| Principle (FIRST-PRINCIPLES.md) | Where it lives | Status |
|---|---|---|
| §8 foundation first: VR mode, per-eye delivery, identical imagery, per-eye position + scale, saved calibration, toggleable optics | `calibrate/` (PSVR Alignment app) | Built; VR mode is confirmed by the headset's status report, not assumed |
| §2 behaviour groups instead of per-pixel depth | `renderer/index.html` `FS_DEPTH` | Screen-locked, ground plane, sky/infinite, camera-attached near field, third-person subject |
| §4 ground-plane shortcut (disparity linear in distance below the horizon) | `FS_DEPTH`, `horizon` setting | Exact linear form; horizon is a setting (auto-estimate is future work) |
| Screen-locked group (HUD) | `FS_DEPTH`: an edge that stays still while the frame moves | Tested on synthetic first-person scene: HUD edges flagged, world 0% |
| §3 learn infrequently, reproject continuously | Depth state updates only on new source frames; AI depth at ≤7 Hz; display loop runs every vsync | Built |
| §5 R3 look-back is a discrete viewpoint change | `detectCut()`: adaptive frame-change detector clears temporal history; HUD knowledge survives | Tested: both synthetic look-backs detected |
| §6 decouple headset refresh from game frame rate | `frame()` re-renders every display refresh with the newest head pose + prediction, reusing the last game frame | Built |
| Both eyes from one shared representation | One depth/state texture; each eye is a reprojection of it | Built |
| §9 don't compute what isn't perceived | Textureless regions are deliberately ignored by the HUD detector; stereo shift computed once per pixel, not per colour | Built |
| Neural depth optional, never a prerequisite | `mlTick()`; off by default; loads the phone's own `depth.onnx` | Untested on the phone |

## Calibration contract (shared by app and renderer)
Per eye, in panel pixels of that eye's 960×1080 half: offset `x`, `y` (y down), `scale` about the eye centre.
Lens model: a point `p` on screen (lens-centred, `y` in [-1, 1]) shows source point `p·(1 + k1·r² + k2·r⁴)`.
The app draws it with `Canvas.drawBitmapMesh`, the renderer in its fragment shader; same numbers, same result.
The app POSTs `calibration.json` to psvrd; the renderer loads it at start.

## Phases (each independently testable, per §8)
1. Foundation (now): alignment app → confirm VR mode, eye identity, fusion, save calibration.
2. Flat image to both eyes from the real Remote Play feed (renderer with `3D strength` = 0).
3. Behaviour-group stereo on the real feed; tune `horizon`, `nearcap`, cut sensitivity on GTA 6.
4. Head-relative reprojection with psvrd tracking (already wired; needs on-head axis check).
5. Optional: AI depth blend, head-look → camera (see HURDLES.md #9).
