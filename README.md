# psvr-control

PS5 games in stereo 3D on a PSVR1 headset, with a Pixel phone as the processor.
Test case: first-person GTA 6 over Remote Play. Goal: any game.

Design: [docs/FIRST-PRINCIPLES.md](docs/FIRST-PRINCIPLES.md) → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Known hurdles and their solutions: [docs/HURDLES.md](docs/HURDLES.md). Review of the earlier code: [docs/REVIEW.md](docs/REVIEW.md).

## Layout
| Path | What |
|---|---|
| `calibrate/` | **PSVR Alignment** Android app: VR mode (confirmed), eye ID, fusion test, per-eye position + scale, lens on/off, saved calibration |
| `native/psvrctl.c` | Headset power / VR / cinematic / status from Termux |
| `psvrd/psvrd.py` | Head tracking + headset buttons + web server for the renderer (standard-library Python) |
| `renderer/index.html` | Stereo renderer: behaviour-group depth, cut detection, lens warp, head-relative curved screen |
| `tools/panel.py` | See and tap the headset display from the phone |
| `start.sh` | One command: find the PSVR, start psvrd in VR mode, open the renderer |
| `tests/` | `python3 tests/test_psvrd.py` |

## Setup (Termux on the Pixel)
```
pkg install python clang openjdk-21 d8 aapt2 apksigner zipalign termux-api git
git clone <this repo> ~/psvr-control-src && cd ~/psvr-control-src
clang -O2 -o native/psvrctl native/psvrctl.c
bash calibrate/build.sh            # -> calibrate/calibrate.apk ; install it
```
AI depth (optional): put Depth Anything V2 Small as ONNX at `~/psvr-control/neural/depth.onnx` (already there on the Pixel).

## Order of testing (FIRST-PRINCIPLES §8)
1. Plug the PSVR processor unit: HDMI from the adapter, USB from the adapter. Open **PSVR Alignment**, allow USB.
   Top line must say `VR MODE CONFIRMED`.
2. Options → **Eye ID**: close your right eye, you must see a red **L**. If you see blue R, press Create (swap).
3. Options → **Same image**: must look single and flat. If doubled, select the eye (L1/R1) and move it with the D-pad until it fuses.
   L2/R2 if one eye looks bigger.
4. Options → **Lens grid**, R3 to turn lens correction on; lines should look straight.
5. Cross to save. Then `./start.sh` and the renderer uses the same calibration.
