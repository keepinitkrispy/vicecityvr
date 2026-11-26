package vcvr.bridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.InputStream;
import java.util.Locale;
import rikka.shizuku.Shizuku;

// Phone screen: one button to start, live sliders for the 3D and per-eye fit.
// One tap: opening the app (or plugging in the headset) does everything:
// Shizuku pre-approves screen sharing + drawing on the headset (once) -> headset to VR mode -> capture -> opens Momiji.
// Without Shizuku it still works, Android just asks to share each time.
public class Main extends Activity {
    static final String ACTION_USB = "vcvr.bridge.USB";
    static final int REQ_CAPTURE = 7;
    SharedPreferences prefs; TextView status, usb; final Handler ui = new Handler(Looper.getMainLooper());
    String usbState = "headset USB: not checked";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("vcvr", 0); S.load(prefs);
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);

        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14); col.setPadding(pad, pad, pad, pad); col.setBackgroundColor(Color.rgb(16, 18, 24));
        TextView title = text("Vice City VR", 24); col.addView(title);
        status = text("", 14); col.addView(status);
        usb = text("", 14); col.addView(usb);

        Button start = new Button(this); start.setText("Start again");
        start.setOnClickListener(v -> go()); col.addView(start);
        Button stop = new Button(this); stop.setText("Stop");
        stop.setOnClickListener(v -> startService(new Intent(this, Bridge.class).setAction("stop"))); col.addView(stop);
        Button vr = new Button(this); vr.setText("Headset: VR mode again");
        vr.setOnClickListener(v -> headset(true)); col.addView(vr);
        Button cin = new Button(this); cin.setText("Headset: back to cinematic (flat)");
        cin.setOnClickListener(v -> headset(false)); col.addView(cin);

        col.addView(check("3D from depth", S.depthOn, c -> S.depthOn = c));
        col.addView(check("Lens correction", S.lensOn, c -> S.lensOn = c));
        col.addView(check("Swap eyes (if depth looks inside-out)", S.swap, c -> S.swap = c));
        for (int i = 0; i < S.NAME.length; i++) col.addView(slider(i));
        Button reset = new Button(this); reset.setText("Reset all to defaults");
        reset.setOnClickListener(v -> { System.arraycopy(S.DEF, 0, S.v, 0, S.DEF.length); S.save(prefs); recreate(); });
        col.addView(reset);
        col.addView(text("\nOpening this app starts everything: headset VR mode, capture, then Momiji.\n" +
            "First time only: allow Shizuku, and tick \"always\" if Android asks about the headset USB.\n" +
            "In the game: press BOTH sticks (L3+R3) to adjust in the headset; press both again to go back.", 14));
        ScrollView sc = new ScrollView(this); sc.addView(col); setContentView(sc);
        IntentFilter f = new IntentFilter(ACTION_USB);
        registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        tick();
        if (!Bridge.running) ui.postDelayed(this::go, 300);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (!Bridge.running) go(); }

    @Override protected void onResume() {
        super.onResume();
        if (waitingOverlay && Settings.canDrawOverlays(this)) { waitingOverlay = false; begin(); }
    }

    @Override protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {} ui.removeCallbacksAndMessages(null); }

    void tick() {
        status.setText("bridge: " + Bridge.status);
        usb.setText(usbState);
        ui.postDelayed(this::tick, 500);
    }

    // ------------------------------------------------------------------ one-tap start
    static final String[] GAME_APPS = {"io.github.yazanj082.momiji", "com.metallic.chiaki", "com.playstation.remoteplay"};
    boolean askedShizuku = false, waitingOverlay = false, going = false;

    void go() {
        if (Bridge.running) { launchGame(); return; }
        if (going) return;
        going = true;
        if (Settings.canDrawOverlays(this) && prefs.getBoolean("grants3", false)) { begin(); return; }
        Bridge.status = "setting up (first run)";
        waitShizuku(0);
    }

    void waitShizuku(int tries) {
        boolean up = false;
        try { up = Shizuku.pingBinder(); } catch (Throwable ignored) {}
        if (!up) {
            if (tries < 12) { ui.postDelayed(() -> waitShizuku(tries + 1), 150); return; }
            usbState = "Shizuku isn't running, so Android will ask to share each time"; begin(); return;
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (askedShizuku) { usbState = "Shizuku not allowed, so Android will ask to share each time"; begin(); return; }
            askedShizuku = true;
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int code, int result) {
                    Shizuku.removeRequestPermissionResultListener(this);
                    ui.post(() -> waitShizuku(0));
                }
            });
            Shizuku.requestPermission(1);
            return;
        }
        new Thread(() -> {
            String pkg = getPackageName();
            String svc = pkg + "/" + pkg + ".Keys";
            String out = shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; appops set " + pkg + " PROJECT_MEDIA allow; "
                + "cur=$(settings get secure enabled_accessibility_services); "
                + "case \"$cur\" in *" + svc + "*) ;; null|'') settings put secure enabled_accessibility_services " + svc + " ;; "
                + "*) settings put secure enabled_accessibility_services \"$cur:" + svc + "\" ;; esac; "
                + "settings put secure accessibility_enabled 1; appops get " + pkg + " PROJECT_MEDIA");
            boolean ok = out.contains("allow");
            prefs.edit().putBoolean("grants3", ok).apply();
            usbState = ok ? "screen sharing pre-approved through Shizuku" : "Shizuku approval failed: " + out.trim();
            ui.post(this::begin);
        }, "shizuku-grant").start();
    }

    // Shizuku runs this as the shell user (same as adb shell). newProcess is private in API 13, hence reflection.
    static String shell(String cmd) {
        try {
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
            StringBuilder sb = new StringBuilder(); byte[] b = new byte[4096]; int n;
            try (InputStream in = p.getInputStream()) { while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n)); }
            p.waitFor();
            return sb.toString();
        } catch (Throwable e) { return "error: " + e; }
    }

    void begin() {
        going = false;
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            usbState = "turn on \"Display over other apps\" for Vice City VR, then press Back"; return;
        }
        if (Bridge.findHeadset(this) == null) { Bridge.status = "headset display not found (is the HDMI connected?)"; return; }
        headset(true);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    void launchGame() {
        for (String p : GAME_APPS) {
            Intent li = getPackageManager().getLaunchIntentForPackage(p);
            if (li != null) { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        }
        usbState = "no Remote Play app found to open";
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { Bridge.status = "screen capture was not allowed"; return; }
        startForegroundService(new Intent(this, Bridge.class).putExtra("code", res).putExtra("data", data));
        ui.postDelayed(this::launchGame, 800);
    }

    // ------------------------------------------------------------------ USB headset mode
    boolean wantVr = true;
    void headset(boolean vr) {
        wantVr = vr;
        UsbManager um = getSystemService(UsbManager.class);
        UsbDevice d = Psvr.find(um);
        if (d == null) { usbState = "headset USB not found (plug the processor unit's USB into the hub)"; return; }
        if (!um.hasPermission(d)) {
            Intent pi = new Intent(ACTION_USB).setPackage(getPackageName());
            um.requestPermission(d, PendingIntent.getBroadcast(this, 0, pi, PendingIntent.FLAG_MUTABLE));
            usbState = "allow USB access to the headset"; return;
        }
        usbState = "headset: switching...";
        new Thread(() -> usbState = Psvr.setMode(um, d, wantVr), "psvr-usb").start();
    }

    final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) headset(wantVr);
            else usbState = "USB access denied";
        }
    };

    // ------------------------------------------------------------------ controls
    interface Bool { void set(boolean b); }
    View check(String label, boolean on, Bool f) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setTextColor(Color.WHITE); c.setChecked(on);
        c.setOnCheckedChangeListener((v, b) -> { f.set(b); S.save(prefs); });
        return c;
    }

    View slider(final int i) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        final TextView t = text("", 14); row.addView(t);
        SeekBar s = new SeekBar(this); s.setMax(1000);
        s.setProgress(Math.round((S.v[i] - S.MIN[i]) / (S.MAX[i] - S.MIN[i]) * 1000));
        Runnable label = () -> t.setText(String.format(Locale.US, "%s: %.3f", S.NAME[i], S.v[i]));
        label.run();
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) { S.v[i] = S.MIN[i] + (S.MAX[i] - S.MIN[i]) * p / 1000f; label.run(); } }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { S.save(prefs); }
        });
        row.addView(s);
        return row;
    }

    TextView text(String s, int sp) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.WHITE); return t; }
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
