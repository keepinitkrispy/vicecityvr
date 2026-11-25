# Code review: state on the Pixel as of 2026-11-25, and what changed

Files reviewed: `~/psvr-control/vrmode.c`, `panel.py`, `calibrate/` (Main.java, manifest, index.html), `neural/`,
the published "PSVR Stereo Renderer" page. `psvrd` (the tracking server the renderer depends on) was **not on the phone**:
it existed only as a zip in an earlier chat. It has been rebuilt here.

## vrmode.c → native/psvrctl.c
| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | Sent VR mode (0x23) without powering the headset first (0x17). Both Monado and OpenHMD power on first. | High | psvrctl sends power, then VR mode |
| 2 | "8 bytes sent" was treated as success. The headset reports its real state in a 0xF0 status report on interface 5; nothing read it. | High | Waits for the status report to confirm power and VR mode, retries up to 3× |
| 3 | Endpoint 4 hard-coded. | Medium | Endpoints read from the device's own USB descriptors |
| 4 | Disconnect-claim only if the bound driver is named `usbhid`. | Low | Detaches whatever driver holds it |
| 5 | No way back to cinematic mode or off. | Low | `cinematic`, `off`, `status` commands |

## calibrate/ (PSVR Alignment app)
| # | Finding | Fix |
|---|---|---|
| 6 | Position only; FIRST-PRINCIPLES §8 needs position **and scale** | L2/R2 scale per eye |
| 7 | No eye-identity test, but the last headset test "felt swapped" | "Eye ID" pattern (red L / blue R) + Create = swap eyes |
| 8 | No identical-image fusion test | "Same image" pattern |
| 9 | No optical correction to toggle | Barrel pre-distortion mesh, R3 toggles it |
| 10 | Didn't switch VR mode itself; relied on a separate termux-usb run | Requests USB permission and switches VR mode itself, shows confirmed state |
| 11 | Calibration stayed inside the app | Also POSTs `calibration.json` to psvrd; renderer loads it |
| 12 | On a non-16:9 phone screen the preview stretched | Letterboxed to true aspect |
| 13 | targetSdk 32 / old PendingIntent flags | targetSdk 34, FLAG_MUTABLE + explicit intent, receiver NOT_EXPORTED |
| 14 | index.html (browser twin of the app) duplicated the app with less features | Superseded by the app; not carried forward |

## panel.py → tools/panel.py
| # | Finding | Fix |
|---|---|---|
| 15 | Plain `adb` only. Today `adb devices` was empty: wireless debugging had dropped, so the panel was dead | Shizuku (`rish`) first, adb second |
| 16 | Display ids hard-coded (`17653077146897649589`, `6`); they change when the adapter is re-plugged | Discovered from dumpsys each time |
| 17 | `rish` fails without `RISH_APPLICATION_ID` (seen today) | Set automatically |

## Renderer (published page → renderer/index.html)
| # | Finding | Fix |
|---|---|---|
| 18 | Depth priors tuned for **third-person** (car low-centre). GTA 6 first-person view has no car there; hands/gun bottom-right would get huge pop-out | `profile` setting (first-person default); `nearcap` caps pop-out |
| 19 | HUD handled by fixed corner masks positioned for GTA V's layout | Automatic screen-locked detection (works for any game); fixed mask kept as option |
| 20 | No reaction to R3 look-back / camera switches: temporal smoothing smeared the old view's depth into the new one | Adaptive cut detection resets history |
| 21 | Ground prior was an arbitrary smoothstep curve | Exact linear-from-horizon form (FIRST-PRINCIPLES §4) |
| 22 | The iterative stereo lookup ran 3× per pixel (once per colour) | Once per pixel, reused |
| 23 | AI depth pulled a model from the internet at runtime | Uses the phone's own `depth.onnx` via psvrd; optional |
| 24 | No swap-eyes, per-eye calibration, or lens on/off | Added; loaded from the alignment app |
| 25 | 8-bit state texture would round away the small per-frame changes the HUD detector integrates | Half-float when available |

## psvrd (rebuilt)
Standard library only. Found and fixed during testing: the gravity (tilt) correction had the wrong sign and drove a
23° error to 132°; with the fix it converges to under 0.3° in 10 s (test `test_tilt_converges`).

## Tests
- `tests/test_psvrd.py`: packet parsing, descriptor parsing, tilt convergence, yaw integration, gyro-bias learning, HTTP + WebSocket. 6/6 pass.
- psvrctl: compiled with `-Wall -Wextra`, zero warnings; descriptor parsing checked against a synthetic PSVR descriptor.
- Renderer: run in headless Chrome against `psvrd --fake` on the first-person test scene: no page errors, both look-back cuts detected,
  HUD edges flagged (minimap, money: max 1.0), world/road 0%, near pop-out capped at 0.76. Output image: `docs/test-headset-output.png`.
- **Nothing here has run on the headset yet.** USB calls, IMU axis directions, lens values and the Android app's USB path are untested on hardware.
