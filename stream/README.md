# Stream app (step 1 + 2 of the ladder)

PS5 Remote Play on the Pixel, plus the PS5 encoder's own motion vectors read out of the H.264 stream.

- Base: [Momiji](https://github.com/yazanj082/momiji) 1.1.3 (chiaki-ng based, AGPL-3.0), pinned in `.github/workflows/stream.yml`.
- `apply.py` patches it; `overlay/` adds `mv-extractor.c`: a second, software H.264 decoder on a low-priority thread that
  skips the inverse transform and deblocking and exports motion vectors. The hardware decoder still makes the picture.
- The stream statistics overlay gains a line: extraction CPU ms per frame, share of the picture with vectors,
  average motion, dropped frames. Choose **H.264** in the app's video settings (HEVC streams have no extractor yet).
- Built on GitHub Actions; the APK is on the `stream-latest` release. Signed with `vcvr-debug.keystore`
  (a debug key, committed on purpose so every build installs over the last; not a secret).

Tested here: `test/mvtest.c` feeds a clip of a texture sliding exactly 4 px/frame; the extractor reports (-4.00, 0.00)
with 99% of the picture covered, about 5 ms CPU per 1080p frame on a desktop core.
Found on the way: FFmpeg only compiles H.264 vector export when an MPEG-video decoder is also enabled.

License: this directory's changes to Momiji are AGPL-3.0 like Momiji itself.
