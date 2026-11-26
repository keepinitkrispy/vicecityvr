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
import java.util.Locale;

// Phone screen: one button to start, live sliders for the 3D and per-eye fit.
// Flow: Start -> headset to VR mode over USB -> Android asks what to share -> pick "A single app" -> Momiji.
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

        Button start = new Button(this); start.setText("Start: pick Momiji when asked");
        start.setOnClickListener(v -> begin()); col.addView(start);
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
        col.addView(text("\nHow to use:\n1. Close XR Screen Utility (it draws to the same headset display).\n" +
            "2. Tap Start. Allow \"Display over other apps\" the first time, then allow USB access.\n" +
            "3. When Android asks what to share, choose \"A single app\" and pick Momiji.\n" +
            "4. Open Momiji, rotate to landscape, start the stream. The controller stays with Momiji.\n" +
            "5. Come back here any time to adjust; changes show in the headset immediately.", 14));

        ScrollView sc = new ScrollView(this); sc.addView(col); setContentView(sc);
        IntentFilter f = new IntentFilter(ACTION_USB);
        registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        tick();
    }

    @Override protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {} ui.removeCallbacksAndMessages(null); }

    void tick() {
        status.setText("bridge: " + Bridge.status);
        usb.setText(usbState);
        ui.postDelayed(this::tick, 500);
    }

    void begin() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            usbState = "allow \"Display over other apps\", come back, tap Start again"; return;
        }
        if (Bridge.findHeadset(this) == null) { Bridge.status = "headset display not found (is the HDMI connected?)"; return; }
        headset(true);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { Bridge.status = "screen capture was not allowed"; return; }
        startForegroundService(new Intent(this, Bridge.class).putExtra("code", res).putExtra("data", data));
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
