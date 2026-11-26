package vcvr.bridge;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Locale;

// Controller adjustment while the game is running. Android gives the DualSense to the focused app (Momiji);
// an accessibility key filter sees button presses first. Press BOTH STICKS (L3+R3) to enter adjust mode:
// from then on the buttons go to us (the game doesn't see them) until L3+R3 again. Stick movement is never touched.
public class Keys extends AccessibilityService {
    static volatile boolean adjust = false;
    static volatile int eyeSel = 0;          // 0 both, 1 left, 2 right
    static volatile int setting = 0;         // which value L2/R2 change
    static final int[] SETTINGS = {S.SCREEN, S.STR, S.CONV, S.ESC, S.K1};
    static final float[] STEP = {0.02f, 0.004f, 0.05f, 0.02f, 0.02f};
    static final String[] EYES = {"BOTH eyes", "LEFT eye", "RIGHT eye"};
    static volatile boolean service = false;
    boolean l3, r3;

    @Override protected void onServiceConnected() { service = true; }
    @Override public void onDestroy() { service = false; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BUTTON_THUMBL) l3 = down;
        if (k == KeyEvent.KEYCODE_BUTTON_THUMBR) r3 = down;
        if (l3 && r3 && down && e.getRepeatCount() == 0) {
            adjust = !adjust;
            if (!adjust) S.save(getSharedPreferences("vcvr", 0));
            return true;
        }
        if (!adjust || !KeyEvent.isGamepadButton(k) && !isDpad(k)) return false;
        if (down) handle(k);
        return true;                         // in adjust mode the game never sees the buttons
    }

    static boolean isDpad(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    static void handle(int k) {
        float dx = 0, dy = 0;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_DPAD_UP: dy = -4; break;     // triangle: up
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_DOWN: dy = 4; break;    // cross: down
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_DPAD_LEFT: dx = -4; break;   // square: left
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 4; break;   // circle: right
            case KeyEvent.KEYCODE_BUTTON_L1: eyeSel = (eyeSel + 1) % 3; return;
            case KeyEvent.KEYCODE_BUTTON_R1: setting = (setting + 1) % SETTINGS.length; return;
            case KeyEvent.KEYCODE_BUTTON_L2: bump(-1); return;
            case KeyEvent.KEYCODE_BUTTON_R2: bump(+1); return;
            case KeyEvent.KEYCODE_BUTTON_SELECT: S.swap = !S.swap; return;                      // Create
            case KeyEvent.KEYCODE_BUTTON_START: S.depthOn = !S.depthOn; return;                 // Options
            default: return;
        }
        if (eyeSel != 2) { S.v[S.LX] = clamp(S.LX, S.v[S.LX] + dx); S.v[S.LY] = clamp(S.LY, S.v[S.LY] + dy); }
        if (eyeSel != 1) { S.v[S.RX] = clamp(S.RX, S.v[S.RX] + dx); S.v[S.RY] = clamp(S.RY, S.v[S.RY] + dy); }
    }

    static void bump(int dir) { int i = SETTINGS[setting]; S.v[i] = clamp(i, S.v[i] + dir * STEP[setting]); }
    static float clamp(int i, float v) { return Math.max(S.MIN[i], Math.min(S.MAX[i], v)); }

    static String label() {
        int i = SETTINGS[setting];
        return String.format(Locale.US,
            "ADJUST (L3+R3 = back to game)\n%s  [L1 changes]\n%s = %.3f  [R1 changes, L2 - / R2 +]\n" +
            "move: triangle/cross/square/circle\n3D %s [Options]   eyes %s [Create]",
            EYES[eyeSel], S.NAME[i], S.v[i], S.depthOn ? "ON" : "OFF", S.swap ? "SWAPPED" : "normal");
    }
}
