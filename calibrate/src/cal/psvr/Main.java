package cal.psvr;

// PSVR Alignment: the "foundation" step. Proves, one at a time and with no depth or tracking involved:
//   1. the headset is really in VR mode (switched here over USB and confirmed by its status report)
//   2. each eye gets its own half of the image (pattern "Eye ID")
//   3. both eyes can show identical imagery that fuses (pattern "Same image")
//   4. per-eye position and scale, adjusted with the controller
//   5. saved calibration (on the phone, and pushed to psvrd for the renderer)
//   6. lens pre-distortion that can be switched on and off independently
//
// DualSense (Bluetooth to the phone):
//   L1 / R1 select left / right eye      D-pad or left stick move it      L2 / R2 shrink / grow it
//   Square  fine / coarse steps           Cross  save                      Triangle  reset this eye
//   Options next pattern                  Create swap eyes                 R3  lens correction on/off
//   L3      send VR mode again            Circle  exit
// Touch: use the phone-only buttons for every controller action; drag either preview eye to adjust, then Save.

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class Main extends Activity implements DisplayManager.DisplayListener {
    static final int SONY = 0x054c, PSVR = 0x09af, CONTROL_IFACE = 5;
    static final String ACTION_USB = "cal.psvr.USB_PERMISSION";
    static final String[] PATTERNS = {"Crosshair", "Eye ID", "Same image", "Lens grid"};

    // per-eye calibration in panel pixels (each eye is 960x1080); index 0 = left eye, 1 = right eye
    float[] ox = new float[2], oy = new float[2], sc = {1f, 1f};
    boolean swap = false, warp = false, saved = true;
    float k1 = 0.22f, k2 = 0.24f;
    int eye = 1, pattern = 0; float step = 2f;
    String usbState = "headset: not found";

    Panel phone, headset; Presentation pres; DisplayManager dm; SharedPreferences prefs;
    TextView phoneStatus; Button eyeLeft, eyeRight, stepButton, patternButton, swapButton, warpButton, saveButton;
    Bitmap[] eyeImg = new Bitmap[2]; int imgPattern = -1;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive(getWindow());
        prefs = getSharedPreferences("cal", 0); load();
        phone = new Panel(this, true); setContentView(buildPhoneControls());
        dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.registerDisplayListener(this, ui);
        attachHeadsetDisplay();
        IntentFilter f = new IntentFilter(ACTION_USB);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, 4 /* RECEIVER_NOT_EXPORTED */);
        else registerReceiver(usbReceiver, f);
        findHeadset(true);
    }

    @Override protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(usbReceiver); } catch (Exception e) {} dm.unregisterDisplayListener(this); }

    // All touch controls are on the phone only; the external presentation stays clean.
    LinearLayout buildPhoneControls() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.addView(phone, new LinearLayout.LayoutParams(0, -1, 0.54f));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.46f));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        controls.setPadding(pad, pad, pad, pad);
        scroll.addView(controls);
        phoneStatus = new TextView(this);
        phoneStatus.setTextColor(Color.WHITE);
        phoneStatus.setTextSize(13);
        controls.addView(phoneStatus);
        LinearLayout row = controlRow(controls);
        eyeLeft = control(row, "Left eye", () -> { eye = 0; redraw(); });
        eyeRight = control(row, "Right eye", () -> { eye = 1; redraw(); });
        stepButton = control(row, "Fine", () -> { step = step == 2f ? 10f : 2f; redraw(); });
        row = controlRow(controls);
        control(row, "Left", () -> move(-step, 0));
        control(row, "Up", () -> move(0, -step));
        control(row, "Right", () -> move(step, 0));
        row = controlRow(controls);
        control(row, "Smaller", () -> zoom(step > 2 ? 0.98f : 0.996f));
        control(row, "Down", () -> move(0, step));
        control(row, "Larger", () -> zoom(step > 2 ? 1.02f : 1.004f));
        row = controlRow(controls);
        control(row, "Reset eye", () -> { ox[eye] = oy[eye] = 0; sc[eye] = 1; saved = false; redraw(); });
        patternButton = control(row, "Pattern", () -> { pattern = (pattern + 1) % PATTERNS.length; redraw(); });
        swapButton = control(row, "Swap eyes", () -> { swap = !swap; saved = false; redraw(); });
        row = controlRow(controls);
        warpButton = control(row, "Lens off", () -> { warp = !warp; saved = false; redraw(); });
        control(row, "Retry USB", () -> findHeadset(true));
        saveButton = control(row, "Save", this::save);
        row = controlRow(controls);
        control(row, "Reload saved", () -> { load(); saved = true; redraw(); });
        control(row, "Exit", this::finish);
        redraw();
        return root;
    }

    LinearLayout controlRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    Button control(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(4, 0, 4, 0);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    static void immersive(Window w) {
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------------ headset display (draw natively, never mirror)
    void attachHeadsetDisplay() {
        if (pres != null && pres.isShowing()) return;
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length == 0) { headset = null; redraw(); return; }
        try {
            pres = new Presentation(this, ds[0]);
            headset = new Panel(pres.getContext(), false);
            pres.setContentView(headset);
            pres.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            immersive(pres.getWindow());
            pres.show();
        } catch (Exception e) { pres = null; headset = null; }
        redraw();
    }
    public void onDisplayAdded(int id) { attachHeadsetDisplay(); }
    public void onDisplayChanged(int id) { attachHeadsetDisplay(); }
    public void onDisplayRemoved(int id) { if (pres != null && pres.getDisplay().getDisplayId() == id) { pres.dismiss(); pres = null; headset = null; redraw(); } }

    // ------------------------------------------------------------------ USB: power on + VR mode, confirmed by status
    final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) { findHeadset(true); return; }
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startVr(d);
            else status("headset: USB permission denied (tap Retry USB or press L3)");
        }
    };

    void findHeadset(boolean ask) {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() != SONY || d.getProductId() != PSVR) continue;
            if (um.hasPermission(d)) { startVr(d); return; }
            if (!ask) return;
            Intent pi = new Intent(ACTION_USB).setPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
            um.requestPermission(d, PendingIntent.getBroadcast(this, 0, pi, flags));
            status("headset: allow USB access on the phone screen");
            return;
        }
        status("headset: PSVR USB not found (plug the processor unit's USB into the hub)");
    }

    void startVr(final UsbDevice d) {
        new Thread(() -> {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            UsbDeviceConnection c = um.openDevice(d);
            if (c == null) { status("headset: could not open USB"); return; }
            UsbInterface ctl = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getId() == CONTROL_IFACE) ctl = d.getInterface(i);
            if (ctl == null || !c.claimInterface(ctl, true)) { status("headset: control interface 5 unavailable"); c.close(); return; }
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < ctl.getEndpointCount(); i++) {
                UsbEndpoint e = ctl.getEndpoint(i);
                if (e.getDirection() == UsbConstants.USB_DIR_IN) { if (in == null) in = e; } else if (out == null) out = e;
            }
            if (in == null || out == null) { status("headset: endpoints missing"); c.close(); return; }
            boolean ok = setAndWait(c, in, out, 0x17, 1, true) && setAndWait(c, in, out, 0x23, 1, false);
            int[] s = readStatus(c, in, 1500);
            status(s == null ? "headset: no status report" :
                String.format(Locale.US, "headset: %s  power=%d  vr=%d  worn=%d", ok && s[1] == 1 ? "VR MODE CONFIRMED" : "NOT confirmed", s[0], s[1], s[2]));
            c.releaseInterface(ctl); c.close();
        }, "psvr-usb").start();
    }

    static boolean setAndWait(UsbDeviceConnection c, UsbEndpoint in, UsbEndpoint out, int cmd, int val, boolean power) {
        byte[] b = {(byte) cmd, 0, (byte) 0xAA, 4, (byte) val, 0, 0, 0};
        for (int attempt = 0; attempt < 3; attempt++) {
            if (c.bulkTransfer(out, b, 8, 1000) != 8) continue;
            long t0 = SystemClock.uptimeMillis();
            while (SystemClock.uptimeMillis() - t0 < 2000) {
                int[] s = readStatus(c, in, 300);
                if (s != null && (power ? s[0] : s[1]) == val) return true;
            }
        }
        return false;
    }

    // returns {power, vr, worn} from a 20-byte 0xF0 status report, or null
    static int[] readStatus(UsbDeviceConnection c, UsbEndpoint in, int timeoutMs) {
        byte[] b = new byte[64]; long t0 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            int n = c.bulkTransfer(in, b, 64, 200);
            if (n == 20 && (b[0] & 0xff) == 0xF0) return new int[]{b[4] & 1, b[11] == 1 ? 1 : 0, (b[4] >> 1) & 1};
        }
        return null;
    }

    void status(String s) { usbState = s; ui.post(this::redraw); }

    // ------------------------------------------------------------------ calibration state
    void load() {
        for (int i = 0; i < 2; i++) { ox[i] = prefs.getFloat("x" + i, 0); oy[i] = prefs.getFloat("y" + i, 0); sc[i] = prefs.getFloat("s" + i, 1); }
        swap = prefs.getBoolean("swap", false); warp = prefs.getBoolean("warp", false);
        k1 = prefs.getFloat("k1", 0.22f); k2 = prefs.getFloat("k2", 0.24f);
    }

    void save() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < 2; i++) { e.putFloat("x" + i, ox[i]); e.putFloat("y" + i, oy[i]); e.putFloat("s" + i, sc[i]); }
        e.putBoolean("swap", swap).putBoolean("warp", warp).putFloat("k1", k1).putFloat("k2", k2).apply();
        saved = true; redraw();
        final String json = String.format(Locale.US,
            "{\"version\":1,\"eyes\":[{\"x\":%.1f,\"y\":%.1f,\"scale\":%.4f},{\"x\":%.1f,\"y\":%.1f,\"scale\":%.4f}],\"swap\":%b,\"warp\":%b,\"k1\":%.3f,\"k2\":%.3f}",
            ox[0], oy[0], sc[0], ox[1], oy[1], sc[1], swap, warp, k1, k2);
        new Thread(() -> {   // hand the same numbers to the renderer through psvrd, if it is running
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("http://127.0.0.1:8765/calibration.json").openConnection();
                h.setRequestMethod("POST"); h.setDoOutput(true); h.setConnectTimeout(500); h.setReadTimeout(500);
                h.setRequestProperty("Content-Type", "application/json");
                OutputStream os = h.getOutputStream(); os.write(json.getBytes("UTF-8")); os.close();
                h.getResponseCode(); h.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    void move(float dx, float dy) {
        ox[eye] = clamp(ox[eye] + dx, -440, 440); oy[eye] = clamp(oy[eye] + dy, -490, 490); saved = false; redraw();
    }
    void zoom(float f) { sc[eye] = clamp(sc[eye] * f, 0.5f, 1.6f); saved = false; redraw(); }
    static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    void redraw() {
        if (phone != null) phone.invalidate();
        if (headset != null) headset.invalidate();
        if (phoneStatus != null) {
            phoneStatus.setText(usbState + (headset == null ? "\nNo headset display" : "") +
                String.format(Locale.US, "\n%s | %s eye | X %.0f Y %.0f Size %.3f | %s",
                PATTERNS[pattern], eye == 0 ? "LEFT" : "RIGHT", ox[eye], oy[eye], sc[eye], saved ? "SAVED" : "UNSAVED"));
            eyeLeft.setText(eye == 0 ? "Left ✓" : "Left eye");
            eyeRight.setText(eye == 1 ? "Right ✓" : "Right eye");
            stepButton.setText(step > 2 ? "Coarse" : "Fine");
            patternButton.setText("Next pattern");
            swapButton.setText(swap ? "Swap on" : "Swap off");
            warpButton.setText(warp ? "Lens on" : "Lens off");
            saveButton.setText(saved ? "Saved ✓" : "Save");
        }
    }

    // ------------------------------------------------------------------ input
    @Override public boolean dispatchKeyEvent(KeyEvent k) {
        if (k.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(k);
        float big = step;
        switch (k.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-big, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(big, 0); break;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -big); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, big); break;
            case KeyEvent.KEYCODE_BUTTON_L1: eye = 0; redraw(); break;
            case KeyEvent.KEYCODE_BUTTON_R1: eye = 1; redraw(); break;
            case KeyEvent.KEYCODE_BUTTON_L2: zoom(step > 2 ? 0.98f : 0.996f); break;
            case KeyEvent.KEYCODE_BUTTON_R2: zoom(step > 2 ? 1.02f : 1.004f); break;
            case KeyEvent.KEYCODE_BUTTON_X: step = step == 2f ? 10f : 2f; redraw(); break;          // Square
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: save(); break;        // Cross
            case KeyEvent.KEYCODE_BUTTON_Y: ox[eye] = 0; oy[eye] = 0; sc[eye] = 1; saved = false; redraw(); break; // Triangle
            case KeyEvent.KEYCODE_BUTTON_START: pattern = (pattern + 1) % PATTERNS.length; redraw(); break;     // Options
            case KeyEvent.KEYCODE_BUTTON_SELECT: swap = !swap; saved = false; redraw(); break;                  // Create
            case KeyEvent.KEYCODE_BUTTON_THUMBR: warp = !warp; saved = false; redraw(); break;                  // R3
            case KeyEvent.KEYCODE_BUTTON_THUMBL: findHeadset(true); break;                                     // L3
            case KeyEvent.KEYCODE_BUTTON_B: finish(); break;                                                   // Circle
            default: return super.dispatchKeyEvent(k);
        }
        return true;
    }

    long lastStick = 0;
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return super.onGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > 0.5f) x = hx; if (Math.abs(hy) > 0.5f) y = hy;
        long now = SystemClock.uptimeMillis();
        if (now - lastStick > 60 && (Math.abs(x) > 0.25f || Math.abs(y) > 0.25f)) {
            move(Math.abs(x) > 0.25f ? x * step : 0, Math.abs(y) > 0.25f ? y * step : 0); lastStick = now;
        }
        return true;
    }

    // ------------------------------------------------------------------ eye images (identical source for both eyes)
    Bitmap eyeImage(int which) {
        if (imgPattern != pattern) { eyeImg[0] = eyeImg[1] = null; imgPattern = pattern; }
        int idx = pattern == 1 ? which : 0;           // only "Eye ID" differs per eye; everything else is identical
        if (eyeImg[idx] != null) return eyeImg[idx];
        Bitmap b = Bitmap.createBitmap(960, 1080, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(Color.BLACK);
        float cx = 480, cy = 540;
        switch (pattern) {
            case 0: // crosshair: align the two centres until they fuse into one
                p.setColor(0xffdddddd); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3);
                c.drawCircle(cx, cy, 64, p); c.drawCircle(cx, cy, 200, p);
                c.drawLine(cx - 300, cy, cx + 300, cy, p); c.drawLine(cx, cy - 300, cx, cy + 300, p);
                p.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, 6, p); break;
            case 1: // eye id: the LEFT eye must see a red L, the right eye a blue R; close one eye at a time
                p.setColor(which == 0 ? 0xffff4040 : 0xff4080ff); p.setTextSize(520); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(which == 0 ? "L" : "R", cx, cy + 190, p);
                p.setTextSize(48); c.drawText(which == 0 ? "LEFT EYE" : "RIGHT EYE", cx, cy + 330, p); break;
            case 2: // same image: rich detail, zero disparity. Should look flat and single, never doubled.
                for (int gy = 0; gy < 12; gy++) for (int gx = 0; gx < 10; gx++) {
                    p.setColor(((gx + gy) & 1) == 0 ? 0xff303844 : 0xff1c2028); c.drawRect(gx * 96, gy * 90, gx * 96 + 96, gy * 90 + 90, p);
                }
                p.setColor(0xffffd34d); c.drawCircle(cx, cy, 120, p);
                p.setColor(Color.WHITE); p.setTextSize(64); p.setTextAlign(Paint.Align.CENTER);
                c.drawText("ONE IMAGE?", cx, cy - 200, p); c.drawText("not doubled", cx, cy + 250, p); break;
            default: // lens grid: straight lines should look straight through the lens with correction on
                p.setColor(0xff40ff70); p.setStrokeWidth(2);
                for (int x = 0; x <= 960; x += 60) c.drawLine(x, 0, x, 1080, p);
                for (int y = 0; y <= 1080; y += 60) c.drawLine(0, y, 960, y, p);
                p.setColor(Color.WHITE); p.setStrokeWidth(4); c.drawLine(cx, 0, cx, 1080, p); c.drawLine(0, cy, 960, cy, p);
        }
        eyeImg[idx] = b; return b;
    }

    // barrel pre-distortion mesh: output point p (lens-centred, y in [-1,1]) shows source point p*(1+k1 r^2+k2 r^4),
    // the same mapping the renderer's shader uses, so calibration carries over unchanged.
    static final int MW = 32, MH = 36;
    float[] mesh(float k1, float k2) {
        float[] v = new float[(MW + 1) * (MH + 1) * 2]; int i = 0;
        for (int gy = 0; gy <= MH; gy++) for (int gx = 0; gx <= MW; gx++) {
            float sx = (gx / (float) MW - 0.5f) * 2f * (960f / 1080f), sy = (gy / (float) MH - 0.5f) * 2f;  // source point
            float px = sx, py = sy;                       // invert s = p(1+k1 r^2+k2 r^4) by fixed-point iteration
            for (int it = 0; it < 12; it++) { float r2 = px * px + py * py, f = 1 + k1 * r2 + k2 * r2 * r2; px = sx / f; py = sy / f; }
            v[i++] = 480 + px * 540; v[i++] = 540 + py * 540;
        }
        return v;
    }

    class Panel extends View {
        final boolean controls; final Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); float px, py;
        Panel(Context c, boolean ctl) { super(c); controls = ctl; setFocusable(true); setFocusableInTouchMode(true); requestFocus(); }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            // always map the full 1920x1080 panel; on a non-16:9 phone screen keep aspect (letterbox) instead of stretching
            float s = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            c.save(); c.translate((getWidth() - 1920 * s) / 2, (getHeight() - 1080 * s) / 2); c.scale(s, s);
            float[] m = warp ? mesh(k1, k2) : null;
            for (int half = 0; half < 2; half++) {
                int e = swap ? 1 - half : half;           // which eye's image goes to this half of the panel
                c.save(); c.clipRect(half * 960, 0, half * 960 + 960, 1080);
                c.translate(half * 960 + ox[e], oy[e]);
                c.scale(sc[e], sc[e], 480, 540);
                Bitmap b = eyeImage(e);
                if (m != null) c.drawBitmapMesh(b, MW, MH, m, 0, null, 0, q); else c.drawBitmap(b, 0, 0, q);
                if (controls && e == eye) { q.setStyle(Paint.Style.STROKE); q.setStrokeWidth(6); q.setColor(0xff2f5fd0); c.drawRect(4, 4, 956, 1076, q); q.setStyle(Paint.Style.FILL); }
                c.restore();
            }
            c.restore();

        }

        @Override public boolean performClick() { super.performClick(); return true; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!controls) return true;
            float s = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: px = e.getX(); py = e.getY(); int half = px < getWidth() / 2f ? 0 : 1; eye = swap ? 1 - half : half; redraw(); return true;
                case MotionEvent.ACTION_MOVE: move((e.getX() - px) / s, (e.getY() - py) / s); px = e.getX(); py = e.getY(); return true;
                case MotionEvent.ACTION_UP: performClick(); return true;
                case MotionEvent.ACTION_CANCEL: return true;
            }
            return true;
        }
    }
}
