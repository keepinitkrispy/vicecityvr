package vcvr.bridge;

import android.content.SharedPreferences;

// Live settings, shared by the phone screen (writes) and the render thread (reads every frame).
final class S {
    // name, default, min, max  (slider order on the phone screen)
    static final String[] NAME = {"3D strength", "Screen depth plane", "Near limit", "Screen size",
        "Lens k1", "Lens k2", "Left eye X", "Left eye Y", "Right eye X", "Right eye Y", "Eye image size"};
    static final String[] KEY = {"str", "conv", "near", "screen", "k1", "k2", "lx", "ly", "rx", "ry", "esc"};
    static final float[] DEF = {0.025f, 0.35f, 0.92f, 0.78f, 0.22f, 0.24f, 0, 0, 0, 0, 1f};
    static final float[] MIN = {0f, 0f, 0.3f, 0.3f, 0f, 0f, -200, -200, -200, -200, 0.6f};
    static final float[] MAX = {0.07f, 1f, 1f, 1.3f, 0.6f, 0.6f, 200, 200, 200, 200, 1.4f};
    static final int STR = 0, CONV = 1, NEAR = 2, SCREEN = 3, K1 = 4, K2 = 5, LX = 6, LY = 7, RX = 8, RY = 9, ESC = 10;

    static final float[] v = DEF.clone();
    static volatile boolean depthOn = true, lensOn = true, swap = false;

    static void load(SharedPreferences p) {
        for (int i = 0; i < KEY.length; i++) v[i] = p.getFloat(KEY[i], DEF[i]);
        depthOn = p.getBoolean("depth", true); lensOn = p.getBoolean("lens", true); swap = p.getBoolean("swap", false);
    }

    static void save(SharedPreferences p) {
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < KEY.length; i++) e.putFloat(KEY[i], v[i]);
        e.putBoolean("depth", depthOn).putBoolean("lens", lensOn).putBoolean("swap", swap).apply();
    }
}
