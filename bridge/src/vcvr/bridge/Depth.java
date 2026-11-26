package vcvr.bridge;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;

// Depth Anything V2 Small on the CPU, run as often as it finishes (never blocks the picture).
// Input: a small RGBA readback of the whole captured frame (row 0 = bottom, as glReadPixels gives it).
// It finds the game picture inside any black bars, feeds that region to the network, and returns
// a 0..1 nearness map (1 = nearest), bottom-up rows to match GL texture orientation.
// No Android classes: the same code runs in the desktop test (test/DepthTest.java).
final class Depth {
    static final int W = 266, H = 154;          // network input (multiples of 14); output is the same size
    static final int RW = 532, RH = 308;        // readback size of the full captured frame
    static final float[] MEAN = {0.485f, 0.456f, 0.406f}, STD = {0.229f, 0.224f, 0.225f};

    final OrtEnvironment env;
    final OrtSession sess;
    final byte[] in = new byte[RW * RH * 4];
    final FloatBuffer tensor = FloatBuffer.allocate(3 * W * H);
    final float[] ema = new float[W * H];
    boolean emaValid = false;

    // published results (read by the render thread)
    final byte[] near = new byte[W * H];
    final byte[] shift = new byte[W * H * 2];      // RG8: left-eye, right-eye source offset (see shiftRow)
    final float[] crop = {0, 0, 1, 1};           // content region in GL uv: x0, y0, x1, y1
    volatile boolean fresh = false;
    volatile long lastMs = 0;

    int[] cand = null; int candCount = 0;         // border detection hysteresis
    int[] box = {0, 0, RW - 1, RH - 1};           // current content box in readback pixels: c0, r0, c1, r1

    Depth(byte[] model, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(threads);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        sess = env.createSession(model, so);
    }

    // Find the picture inside black bars. Returns {c0, r0, c1, r1} in readback pixels.
    static int[] contentBox(byte[] rgba, int w, int h) {
        int[] colMax = new int[w], rowMax = new int[h];
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x++) {
            int i = (y * w + x) * 4;
            int l = ((rgba[i] & 255) * 3 + (rgba[i + 1] & 255) * 6 + (rgba[i + 2] & 255)) / 10;
            if (l > colMax[x]) colMax[x] = l;
            if (l > rowMax[y]) rowMax[y] = l;
        }
        int t = 20, c0 = 0, c1 = w - 1, r0 = 0, r1 = h - 1;
        while (c0 < w - 1 && colMax[c0] <= t) c0++;
        while (c1 > c0 && colMax[c1] <= t) c1--;
        while (r0 < h - 1 && rowMax[r0] <= t) r0 += 1;
        while (r1 > r0 && rowMax[r1] <= t) r1--;
        r0 &= ~1; r1 |= 1; if (r1 > h - 1) r1 = h - 1;        // rows were sampled every 2
        if (c1 - c0 < w * 0.3f || r1 - r0 < h * 0.3f) return new int[]{0, 0, w - 1, h - 1};   // nearly black frame: don't crop
        return new int[]{c0, r0, c1, r1};
    }

    // Accept a new box only after it has been seen 3 times in a row (stops flicker on dark scenes).
    void updateBox(int[] b) {
        if (cand != null && Math.abs(b[0] - cand[0]) <= 2 && Math.abs(b[1] - cand[1]) <= 2
            && Math.abs(b[2] - cand[2]) <= 2 && Math.abs(b[3] - cand[3]) <= 2) candCount++;
        else { cand = b; candCount = 1; }
        if (candCount >= 3 && !Arrays.equals(cand, box)) { box = cand.clone(); emaValid = false; }
    }

    // Runs on the worker thread.
    void process(float str, float conv, float nearLim) throws Exception {
        long t0 = System.nanoTime();
        updateBox(contentBox(in, RW, RH));
        int c0 = box[0], r0 = box[1], cw = box[2] - box[0] + 1, rh = box[3] - box[1] + 1;
        tensor.clear();
        float[] buf = new float[3 * W * H];
        for (int y = 0; y < H; y++) {
            int sy = r0 + (int) ((H - 1 - y + 0.5f) * rh / H);            // tensor row 0 = top = last readback row
            for (int x = 0; x < W; x++) {
                int sx = c0 + (int) ((x + 0.5f) * cw / W);
                int i = (sy * RW + sx) * 4, o = y * W + x;
                for (int c = 0; c < 3; c++) buf[c * W * H + o] = ((in[i + c] & 255) / 255f - MEAN[c]) / STD[c];
            }
        }
        tensor.put(buf); tensor.rewind();
        float[] d = new float[W * H];
        try (OnnxTensor t = OnnxTensor.createTensor(env, tensor, new long[]{1, 3, H, W});
             OrtSession.Result r = sess.run(Collections.singletonMap("pixel_values", t))) {
            ((OnnxTensor) r.get(0)).getFloatBuffer().get(d);
        }
        // robust 0..1: 2nd..98th percentile of a subsample
        float[] s = new float[(W * H + 7) / 8];
        for (int i = 0, j = 0; i < W * H; i += 8) s[j++] = d[i];
        Arrays.sort(s);
        float lo = s[(int) (s.length * 0.02f)], hi = s[(int) (s.length * 0.98f)], k = hi > lo ? 1f / (hi - lo) : 0;
        float a = emaValid ? 0.6f : 1f;
        for (int i = 0; i < W * H; i++) {
            float v = Math.max(0f, Math.min(1f, (d[i] - lo) * k));
            ema[i] = ema[i] + a * (v - ema[i]);
        }
        emaValid = true;
        float[] sl = new float[W], sr = new float[W];
        synchronized (this) {
            for (int y = 0; y < H; y++) {                                    // output row 0 = top -> texture row 0 = bottom
                int ty = H - 1 - y;
                for (int x = 0; x < W; x++) near[ty * W + x] = (byte) Math.round(ema[y * W + x] * 255f);
                shiftRow(ema, y * W, str, conv, nearLim, -1f, sl);
                shiftRow(ema, y * W, str, conv, nearLim, +1f, sr);
                for (int x = 0; x < W; x++) {
                    shift[(ty * W + x) * 2] = enc(sl[x]);
                    shift[(ty * W + x) * 2 + 1] = enc(sr[x]);
                }
            }
            crop[0] = c0 / (float) RW; crop[1] = r0 / (float) RH;
            crop[2] = (box[2] + 1) / (float) RW; crop[3] = (box[3] + 1) / (float) RH;
            fresh = true;
        }
        lastMs = (System.nanoTime() - t0) / 1_000_000;
    }

    void load(ByteBuffer rgba) { rgba.rewind(); rgba.get(in); }

    // Stereo for one row and one eye, computed once per depth update instead of per headset pixel.
    // A source pixel at u with nearness n appears in this eye at t = u - sg*str*(n - conv)  (sg = -1 left, +1 right),
    // so near things (n > conv) come out in front of the screen and far things behind it.
    // Forward splat with "nearest wins", then holes (disocclusions) are filled by repeating the farther neighbour's pixel.
    // Output: for each target pixel, (source u - target u), which the shader adds to its screen coordinate.
    static final float SHIFT_RANGE = 0.08f;                 // encoded range: +-0.08 of the picture width
    static byte enc(float s) { return (byte) Math.round(Math.max(0f, Math.min(1f, 0.5f + s / (2f * SHIFT_RANGE))) * 255f); }

    static void shiftRow(float[] nearMap, int off, float str, float conv, float nearLim, float sg, float[] out) {
        int w = out.length;
        float[] best = new float[w], src = new float[w];
        java.util.Arrays.fill(best, -1f);
        for (int x = 0; x < w; x++) {
            float n = Math.min(nearMap[off + x], nearLim);
            float tf = x - sg * str * (n - conv) * w;            // target position in grid pixels
            int a = (int) Math.floor(tf);
            for (int tx = a; tx <= a + 1; tx++) {
                if (tx < 0 || tx >= w || n <= best[tx]) continue;
                best[tx] = n; src[tx] = x + (tx - tf);           // source position that lands exactly on tx
            }
        }
        for (int tx = 0; tx < w; tx++) {
            if (best[tx] >= 0) continue;
            int l = tx - 1, r = tx + 1;
            while (l >= 0 && best[l] < 0) l--;
            while (r < w && best[r] < 0) r++;
            int pick = l < 0 ? r : r >= w ? l : (best[l] <= best[r] ? l : r);   // background (farther) side
            src[tx] = pick < 0 || pick >= w ? tx : src[pick];          // stretch the background edge; never read into the object
        }
        for (int tx = 0; tx < w; tx++) out[tx] = (src[tx] - tx) / w;           // in picture-width units
    }
}
