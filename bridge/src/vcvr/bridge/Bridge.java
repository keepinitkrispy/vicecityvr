package vcvr.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The whole bridge: captured app -> GPU -> two eye views from depth -> per-eye calibration -> lens pre-warp -> headset.
// The Remote Play app stays on the phone screen with keyboard/controller focus; we only draw on the headset display
// in a window that never takes focus, so the DualSense keeps talking to the game.
public class Bridge extends Service {
    static volatile String status = "stopped";
    static volatile boolean running = false;

    final Handler main = new Handler(Looper.getMainLooper());
    MediaProjection mp; VirtualDisplay vd; WindowManager wm; SurfaceView sv; Render gl;
    FrameLayout root; final TextView[] label = new TextView[2]; long startMs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null || "stop".equals(i.getAction())) { stopAll(); stopSelf(); return START_NOT_STICKY; }
        startFg();
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        mp = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra("data"));
        if (mp == null) { status = "screen capture was not allowed"; stopSelf(); return START_NOT_STICKY; }
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { status = "capture ended"; main.post(Bridge.this::stopAll); }
            @Override public void onCapturedContentResize(int w, int h) { if (gl != null) gl.contentSize(w, h); }
        }, main);
        Display hmd = findHeadset(this);
        if (hmd == null) { status = "headset display not found (is the HDMI connected?)"; stopAll(); stopSelf(); return START_NOT_STICKY; }
        showOn(hmd);
        running = true;
        return START_NOT_STICKY;
    }

    static Display findHeadset(Context c) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (Display d : ds) if (d.getName() != null && d.getName().contains("HMD")) return d;   // "SIE  HMD *08"
        return ds.length > 0 ? ds[0] : null;
    }

    void startFg() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("vcvr", "Vice City VR", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, Bridge.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "vcvr").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Vice City VR is drawing to the headset").setContentText("Tap Stop to end")
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    void showOn(Display hmd) {
        Context wc = createDisplayContext(hmd).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = wc.getSystemService(WindowManager.class);
        sv = new SurfaceView(wc);
        sv.getHolder().setFixedSize(1920, 1080);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { gl = new Render(h.getSurface()); gl.start(); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) { if (gl != null) gl.quit(); gl = null; }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // text on the headset, one copy per eye: status at start, and the controls while adjusting
        root = new FrameLayout(wc);
        root.addView(sv, new FrameLayout.LayoutParams(1920, 1080));
        for (int i = 0; i < 2; i++) {
            TextView t = new TextView(wc);
            t.setTextColor(Color.WHITE); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22);
            t.setBackgroundColor(0xB0000000); t.setPadding(12, 8, 12, 8); t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(560, FrameLayout.LayoutParams.WRAP_CONTENT);
            p.leftMargin = i * 960 + 200; p.topMargin = 330;
            root.addView(t, p); label[i] = t;
        }
        startMs = android.os.SystemClock.uptimeMillis();
        main.post(this::updateLabel);
        try { wm.addView(root, lp); status = "starting"; }
        catch (Exception e) { status = "cannot draw on the headset: " + e.getMessage(); }
    }

    void updateLabel() {
        if (root == null) return;
        String st = status;
        boolean early = android.os.SystemClock.uptimeMillis() - startMs < 15000;
        boolean bad = st.contains("fail") || st.contains("error") || st.contains("loading");
        String text = Keys.adjust ? Keys.label() + "\n" + st
            : st + "\nPress BOTH sticks (L3+R3) to adjust" + (Keys.service ? "" : "\n(controller adjust not enabled)");
        for (TextView t : label) {
            t.setText(text);
            t.setVisibility(Keys.adjust || early || bad ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        main.postDelayed(this::updateLabel, 250);
    }

    // called from the render thread once its capture surface exists
    void attachCapture(Surface s, int w, int h) {
        main.post(() -> {
            if (mp == null) return;
            try {
                vd = mp.createVirtualDisplay("vcvr-capture", w, h, 320, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null);
            } catch (Exception e) { status = "capture failed: " + e.getMessage(); }
        });
    }
    void resizeCapture(int w, int h) { main.post(() -> { if (vd != null) vd.resize(w, h, 320); }); }

    void stopAll() {
        running = false;
        if (gl != null) { gl.quit(); gl = null; }
        if (vd != null) { vd.release(); vd = null; }
        if (root != null && wm != null) { try { wm.removeView(root); } catch (Exception ignored) {} root = null; sv = null; }
        Keys.adjust = false;
        if (mp != null) { MediaProjection m = mp; mp = null; try { m.stop(); } catch (Exception ignored) {} }
        if (!status.startsWith("capture ended") && !status.startsWith("cannot") && !status.startsWith("headset display"))
            status = "stopped";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }

    byte[] asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 25); byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    // ===================================================================== render thread
    static final String VS =
        "#version 300 es\nlayout(location=0) in vec2 p; out vec2 uv;\n" +
        "void main(){ uv = p*0.5+0.5; gl_Position = vec4(p,0.0,1.0); }\n";

    // capture -> small readback for the depth network
    static final String FS_SMALL =
        "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;\n" +
        "uniform samplerExternalOES f; uniform mat4 tm; in vec2 uv; out vec4 o;\n" +
        "void main(){ o = vec4(texture(f, (tm*vec4(uv,0.0,1.0)).xy).rgb, 1.0); }\n";

    // the headset picture
    static final String FS_EYES =
        "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\n" +
        "uniform samplerExternalOES f; uniform sampler2D dep; uniform mat4 tm;\n" +
        "uniform vec4 crop;      // content region of the captured frame, GL uv (x0,y0,x1,y1)\n" +
        "uniform float aspect;   // content width/height in pixels\n" +
        "uniform vec4 off;       // per-eye offset in panel pixels, y down: lx,ly,rx,ry\n" +
        "uniform float esc, screen, lens, depthOn, swapEyes, xhair;\n" +
        "uniform vec2 k;\n" +
        "out vec4 o;\n" +
        "void main(){\n" +
        "  float half_ = gl_FragCoord.x < 960.0 ? 0.0 : 1.0;\n" +
        "  float eye = swapEyes > 0.5 ? 1.0 - half_ : half_;          // 0 = left eye, 1 = right eye\n" +
        "  vec2 L = vec2(gl_FragCoord.x - (half_*960.0 + 480.0), 540.0 - gl_FragCoord.y);   // panel px from eye centre, y down\n" +
        "  vec2 e = eye < 0.5 ? off.xy : off.zw;\n" +
        "  vec2 q = (L - e) / esc / 540.0;                             // eye image coords, y in [-1,1]\n" +
        "  if (xhair > 0.5 && (abs(q.x) < 0.003 || abs(q.y) < 0.003)) { o = vec4(0.1,1.0,0.2,1.0); return; }   // eye centre while adjusting\n" +
        "  if (lens > 0.5) { float r2 = dot(q,q); q *= 1.0 + k.x*r2 + k.y*r2*r2; }   // same mapping as the alignment app\n" +
        "  float W = screen * (960.0/540.0), H = W / aspect;           // virtual screen size in eye coords\n" +
        "  vec2 s = vec2(q.x / W + 0.5, 0.5 - q.y / H);               // screen uv, GL orientation\n" +
        "  if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) { o = vec4(0.0,0.0,0.0,1.0); return; }\n" +
        "  float sx = s.x;\n" +
        "  if (depthOn > 0.5) {                                        // per-eye offset, precomputed with occlusion (Depth.shiftRow)\n" +
        "    vec2 sh = texture(dep, s).rg;\n" +
        "    sx = clamp(s.x + ((eye < 0.5 ? sh.r : sh.g) - 0.5) * 0.16, 0.0, 1.0);\n" +
        "  }\n" +
        "  vec2 cu = mix(crop.xy, crop.zw, vec2(sx, s.y));\n" +
        "  vec3 c = texture(f, (tm*vec4(cu,0.0,1.0)).xy).rgb;\n" +
        "  float edge = smoothstep(0.0, 0.01, min(min(s.x,1.0-s.x), min(s.y,1.0-s.y)*aspect));\n" +
        "  o = vec4(c*edge, 1.0);\n" +
        "}\n";

    final class Render extends Thread {
        final Surface out;
        volatile boolean quit = false, frameAvail = false;
        volatile int capW = 1920, capH = 1080; volatile boolean resized = false;
        EGLDisplay ed; EGLContext ctx; EGLSurface es;
        int oes, depTex, fbo, fboTex, progSmall, progEyes, vbo;
        SurfaceTexture st; Surface cap;
        final float[] tm = new float[16];
        Depth depth; String depthErr = null;
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        volatile boolean depthBusy = false;
        final ByteBuffer rb = ByteBuffer.allocateDirect(Depth.RW * Depth.RH * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer depUp = ByteBuffer.allocateDirect(Depth.W * Depth.H * 2);
        final float[] crop = {0, 0, 1, 1};
        boolean haveFrame = false, gotShift = false; long frames = 0, captured = 0, t0;

        Render(Surface out) { super("vcvr-render"); this.out = out; }
        void quit() { quit = true; }

        void contentSize(int w, int h) {                       // fit the captured app into at most 1920x1080, same shape
            if (w <= 0 || h <= 0) return;
            float s = Math.min(1920f / w, 1080f / h);
            capW = Math.max(16, Math.round(w * s) & ~1); capH = Math.max(16, Math.round(h * s) & ~1); resized = true;
        }

        @Override public void run() {
            try {
                egl(); gl();
                st = new SurfaceTexture(oes);
                st.setDefaultBufferSize(capW, capH);
                st.setOnFrameAvailableListener(t -> frameAvail = true, main);
                cap = new Surface(st);
                attachCapture(cap, capW, capH);
                worker.execute(() -> {                          // load the depth network off the render thread
                    try { depth = new Depth(asset("depth.onnx"), 4); }
                    catch (Throwable e) { depthErr = String.valueOf(e.getMessage()); }
                });
                t0 = System.nanoTime();
                while (!quit) frame();
            } catch (Throwable e) {
                status = "render error: " + e;
            } finally {
                worker.shutdownNow();
                if (cap != null) cap.release();
                if (st != null) st.release();
                if (ed != null) {
                    EGL14.eglMakeCurrent(ed, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (es != null) EGL14.eglDestroySurface(ed, es);
                    if (ctx != null) EGL14.eglDestroyContext(ed, ctx);
                    EGL14.eglTerminate(ed);
                }
            }
        }

        void frame() {
            if (resized) { resized = false; st.setDefaultBufferSize(capW, capH); resizeCapture(capW, capH); }
            if (frameAvail) { frameAvail = false; st.updateTexImage(); st.getTransformMatrix(tm); haveFrame = true; captured++; feedDepth(); }
            Depth d = depth;
            if (d != null && d.fresh) {
                synchronized (d) {
                    depUp.clear(); depUp.put(d.shift); depUp.flip(); gotShift = true;
                    System.arraycopy(d.crop, 0, crop, 0, 4);
                    d.fresh = false;
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depTex);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Depth.W, Depth.H, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, depUp);
            }
            drawEyes(gotShift);
            EGL14.eglSwapBuffers(ed, es);                         // paces the loop to the headset's 60 Hz
            frames++;
            long now = System.nanoTime();
            if (now - t0 > 1_000_000_000L) {
                float sec = (now - t0) / 1e9f;
                String ds = depthErr != null ? "depth failed: " + depthErr : d == null ? "depth loading" : "depth " + d.lastMs + " ms";
                status = String.format(Locale.US, "running: headset %.0f fps, game frames %.0f/s, capture %dx%d, %s",
                    frames / sec, captured / sec, capW, capH, ds);
                frames = 0; captured = 0; t0 = now;
            }
        }

        void feedDepth() {
            final Depth d = depth;
            if (d == null || depthBusy || !S.depthOn) return;
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glViewport(0, 0, Depth.RW, Depth.RH);
            GLES30.glUseProgram(progSmall);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(progSmall, "f"), 0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progSmall, "tm"), 1, false, tm, 0);
            quad();
            rb.clear();
            GLES30.glReadPixels(0, 0, Depth.RW, Depth.RH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rb);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            d.load(rb);
            depthBusy = true;
            worker.execute(() -> {
                try { d.process(S.v[S.STR], S.v[S.CONV], S.v[S.NEAR]); } catch (Throwable e) { depthErr = String.valueOf(e.getMessage()); }
                depthBusy = false;
            });
        }

        void drawEyes(boolean depthReady) {
            GLES30.glViewport(0, 0, 1920, 1080);
            GLES30.glClearColor(0, 0, 0, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            if (!haveFrame) return;
            int p = progEyes; GLES30.glUseProgram(p);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "f"), 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depTex);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "dep"), 1);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p, "tm"), 1, false, tm, 0);
            float[] v = S.v;
            GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "crop"), crop[0], crop[1], crop[2], crop[3]);
            float aspect = (crop[2] - crop[0]) * capW / Math.max(1e-3f, (crop[3] - crop[1]) * capH);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "aspect"), aspect);
            GLES30.glUniform4f(GLES30.glGetUniformLocation(p, "off"), v[S.LX], v[S.LY], v[S.RX], v[S.RY]);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "esc"), v[S.ESC]);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "screen"), v[S.SCREEN]);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, "k"), v[S.K1], v[S.K2]);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "lens"), S.lensOn ? 1 : 0);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "depthOn"), S.depthOn && depthReady ? 1 : 0);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "swapEyes"), S.swap ? 1 : 0);
            GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "xhair"), Keys.adjust ? 1 : 0);
            quad();
        }

        void quad() {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        void egl() {
            ed = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(ed, ver, 0, ver, 1)) throw new RuntimeException("eglInitialize");
            int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
            if (!EGL14.eglChooseConfig(ed, attr, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new RuntimeException("eglChooseConfig");
            ctx = EGL14.eglCreateContext(ed, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
            es = EGL14.eglCreateWindowSurface(ed, cfg[0], out, new int[]{EGL14.EGL_NONE}, 0);
            if (es == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface");
            if (!EGL14.eglMakeCurrent(ed, es, es, ctx)) throw new RuntimeException("eglMakeCurrent");
            EGL14.eglSwapInterval(ed, 1);
        }

        void gl() {
            progSmall = program(VS, FS_SMALL); progEyes = program(VS, FS_EYES);
            int[] t = new int[3];
            GLES30.glGenTextures(3, t, 0);
            oes = t[0]; depTex = t[1]; fboTex = t[2];
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depTex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, Depth.W, Depth.H, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, null);
            texParams(GLES30.GL_TEXTURE_2D);
            // hard edges: blending a near object's offset with the background's smears the object across the gap
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, Depth.RW, Depth.RH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            texParams(GLES30.GL_TEXTURE_2D);
            int[] f = new int[1]; GLES30.glGenFramebuffers(1, f, 0); fbo = f[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0);
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw new RuntimeException("fbo incomplete");
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            int[] b = new int[1]; GLES30.glGenBuffers(1, b, 0); vbo = b[0];
            FloatBuffer q = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            q.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).flip();
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, q, GLES30.GL_STATIC_DRAW);
        }

        void texParams(int target) {
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        }

        int program(String vs, String fs) {
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
            GLES30.glLinkProgram(p);
            int[] ok = new int[1]; GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
            return p;
        }

        int shader(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
            int[] ok = new int[1]; GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s));
            return s;
        }
    }
}
