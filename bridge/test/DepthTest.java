package vcvr.bridge;
import java.nio.ByteBuffer; import java.nio.file.*;
// Desktop check of Depth.java with the real model: black-bar crop, orientation, timing.
public class DepthTest {
    public static void main(String[] a) throws Exception {
        Depth d = new Depth(Files.readAllBytes(Paths.get(a[0])), 4);
        ByteBuffer f = ByteBuffer.wrap(Files.readAllBytes(Paths.get(a[1])));
        for (int i = 0; i < 4; i++) { d.load(f); d.process(0.025f, 0.35f, 0.92f); }       // box needs 3 identical sightings
        System.out.printf("crop uv x %.3f..%.3f  y %.3f..%.3f   last run %d ms%n", d.crop[0], d.crop[2], d.crop[1], d.crop[3], d.lastMs);
        int W = Depth.W, H = Depth.H;
        double bottom = 0, top = 0, box = 0, beside = 0;
        for (int x = 0; x < W; x++) { bottom += d.near[5 * W + x] & 255; top += d.near[(H - 6) * W + x] & 255; }
        // box occupies screen x 0.70..0.82, from 35% down to the bottom; sample it at 60% down (texture row from bottom = 40%)
        int r = (int) (H * 0.40);
        for (int x = (int) (W * 0.72); x < (int) (W * 0.80); x++) box += d.near[r * W + x] & 255;
        for (int x = (int) (W * 0.40); x < (int) (W * 0.48); x++) beside += d.near[r * W + x] & 255;
        int n = (int) (W * 0.80) - (int) (W * 0.72);
        System.out.printf("nearness 0..255: texture bottom row %.0f, top row %.0f | pillar %.0f vs ground beside it %.0f%n",
            bottom / W, top / W, box / n, beside / n);
        java.io.FileOutputStream o = new java.io.FileOutputStream(a[2]);
        o.write(("P5 " + W + " " + H + " 255\n").getBytes());
        for (int y = H - 1; y >= 0; y--) o.write(d.near, y * W, W);    // write top-down for viewing
        o.close();
        java.nio.file.Files.write(Paths.get(a[3]), d.shift);
    }
}
