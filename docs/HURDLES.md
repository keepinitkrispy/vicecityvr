# Hurdles, found before we hit them

Status: **SOLVED** = handled in code now · **MEASURE** = needs one test on the real setup, plan included · **DECIDE** = needs a choice.

## 1. Getting game frames into the renderer (the biggest open item): MEASURE, then DECIDE
The renderer runs in Chrome on the phone. Chrome for Android has **no screen capture API** (`getDisplayMedia` is desktop-only),
so the page cannot grab the Remote Play app's picture by itself. Options, best last:
- **A. MediaProjection → local stream.** A capture app (e.g. ScreenStream from F-Droid) streams the phone's screen as MJPEG on
  localhost; renderer source "MJPEG stream URL", which psvrd proxies so the page can read pixels. Works today, costs ~1 frame of
  JPEG encode/decode latency and some battery.
- **B. Capture card.** Not available on the phone.
- **C. chiaki-ng with the renderer inside it.** chiaki-ng (already cloned at `~/psvr-control/neural/chiaki`, commit 43728af) decodes
  the stream itself; drawing the stereo shader on its decoded texture removes capture, encode and copy entirely. Lowest latency,
  biggest build. This is the end state.
- Risk: if the Remote Play app marks its window secure, capture (A) returns black. **Measure:** with Remote Play showing a game,
  run `rish -c 'screencap -p' > /sdcard/rp.png` and look at it. Black → use chiaki-ng for capture (it does not set the secure flag).

## 2. Which app has the controller: MEASURE
With Remote Play on one display and Chrome on the headset display, Android sends DualSense input to the *focused* window.
If Chrome takes focus, the game stops receiving input. The renderer needs no controller input (headset buttons + phone remote cover it),
so: start the renderer on the headset display first, then open Remote Play last so it holds focus. If focus still jumps,
the renderer page can be driven entirely from `/control`-style messages over psvrd (already supported: `set`, `nudge`, `preset`).

## 3. Headset in VR mode, really: SOLVED
Power-on before VR mode, then wait for the 0xF0 status report to confirm (psvrctl, psvrd, alignment app).

## 4. Images felt swapped / misaligned: SOLVED (tools) + MEASURE (result)
Monado/OpenHMD: left half of the panel → left eye. The alignment app's "Eye ID" pattern settles it in seconds (close one eye);
Create swaps if needed. Misalignment usually comes from Android *mirroring* the phone onto HDMI (taller phone screen letterboxed into 16:9).
The app draws straight to the external display at 1920×1080 with Android's Presentation API, so mirroring can't distort it.

## 5. USB permission prompt and kernel driver: SOLVED
Android's HID driver owns interfaces 4/5; both psvrctl/psvrd and the app detach it (proven by your earlier `vrmode` log).
termux-usb shows a permission dialog on each re-plug: approve on the phone screen. The app gets its own permission once per plug.

## 6. ADB keeps dropping: SOLVED
Wireless debugging turns off on Wi-Fi changes (it was off today). Everything now goes through Shizuku (`rish`) first, with
`RISH_APPLICATION_ID` set automatically (missing it made `rish` fail today).

## 7. First-person GTA 6 specifics: SOLVED in renderer, tune on the real game
GTA 6 has no full first-person mode. First person exists while standing still, aiming, and with the phone out while driving,
and moving drops back to third person. So the view will flip between first and third person often:
- every flip and every R3 look-back is caught by the cut detector, which drops the old view's depth history instead of smearing it;
- `profile` = first-person stops the renderer assuming a car in the lower middle;
- `nearcap` stops hands/gun/weapon from popping out uncomfortably far;
- the HUD is found automatically (no assumptions about GTA 6's layout), which also covers "all games later".

## 8. Head tracking drift and axes: MEASURE
PSVR1 has gyro + accelerometer, no magnetometer: yaw drifts slowly. Mute button on the headset cable recenters.
Axis directions follow Monado/OpenHMD but haven't been checked on your head: if looking left turns the view right,
use the renderer's invert yaw/pitch/roll switches. Gravity correction is tested (converges to <0.3°).

## 9. Head-look vs the game camera: DECIDE (later)
Head tracking moves the virtual screen, not GTA's camera. For first person, mapping head yaw onto the right stick would make it feel native,
but stick acceleration in GTA makes that loop nauseating if latency is high. Possible only through chiaki-ng (it sends the controller state;
we can add head yaw to it) or Shizuku input injection. Keep the screen-in-space approach until 1–4 are proven.

## 10. Latency budget: MEASURE
Remote Play (~1–3 frames) + capture (option A: ~1 frame) + render (1 frame). Head movement doesn't wait for the game:
the renderer redraws every display refresh with the newest predicted head pose (`predict` setting), so turning your head stays responsive
even at the game's 30 fps. Measure: film a stopwatch on the TV vs the headset view.

## 11. Refresh rate the PSVR accepts from the phone: MEASURE
PSVR VR mode is designed for 90/120 Hz; the phone likely outputs 60 Hz. Your earlier test already showed an image in each eye,
so 60 Hz displays. If it ever shows black in VR mode, check the mode the phone negotiates (`rish -c 'dumpsys display'`).

## 12. Heat and power: MEASURE
Remote Play decode + GPU stereo + HDMI out + optional AI depth will heat the phone. Mitigations already in: AI depth off by default
and capped at ~7 Hz, depth state at 256×144. Power the phone through the adapter's power-in port. Watch for throttling:
`rish -c 'dumpsys thermalservice'`.

## 13. Disocclusion (gaps at depth edges): accepted
Stereo is synthesized from one image, so the background behind a near edge is unknown. Shifts stay small (≤1.8% of width) and
edges taper at the frame border, so the stretch is narrow. A proper fill is future work only if it's visible in practice.
