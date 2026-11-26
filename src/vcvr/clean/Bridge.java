package vcvr.clean;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.projection.*;
import android.opengl.*;
import android.os.*;
import android.util.Log;
import android.view.*;
import java.nio.*;
public class Bridge extends Service implements DisplayManager.DisplayListener {
 static final String TAG="VCVR-Clean";
 final Handler main=new Handler(Looper.getMainLooper());
 MediaProjection mp; VirtualDisplay vd; WindowManager wm; SurfaceView view; Renderer renderer; DisplayManager dm; int target=-1; boolean stopped=false,started=false;
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent i,int flags,int id){
 if(i==null||"stop".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
 if(started)return START_NOT_STICKY; started=true;
 NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("vr","VR capture",NotificationManager.IMPORTANCE_LOW));
 PendingIntent pi=PendingIntent.getService(this,1,new Intent(this,Bridge.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
 startForeground(1,new Notification.Builder(this,"vr").setSmallIcon(android.R.drawable.ic_media_pause).setContentTitle("Vice City VR Clean").addAction(new Notification.Action.Builder(null,"Stop",pi).build()).setOngoing(true).build(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
 try{
 dm=getSystemService(DisplayManager.class); Display h=null;
 for(Display d:dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))if(d.getDisplayId()!=0 && d.getName().toUpperCase(java.util.Locale.ROOT).contains("HMD")){h=d;break;}
 if(h==null)throw new IllegalStateException("No identified HMD display; refusing fallback");
 target=h.getDisplayId();dm.registerDisplayListener(this,main);
 mp=getSystemService(MediaProjectionManager.class).getMediaProjection(i.getIntExtra("code",0),i.getParcelableExtra("data",Intent.class));
 if(mp==null)throw new IllegalStateException("Projection denied");
 mp.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}},main);
 Context c=createDisplayContext(h).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);wm=c.getSystemService(WindowManager.class);
 view=new SurfaceView(c);view.getHolder().setFixedSize(1920,1080);
 view.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder holder){if(stopped||renderer!=null)return;renderer=new Renderer(holder.getSurface());renderer.start();}public void surfaceChanged(SurfaceHolder h,int f,int w,int he){}public void surfaceDestroyed(SurfaceHolder h){stopSelf();}});
 WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
 wm.addView(view,lp);Log.i(TAG,"Started; target="+target);
 }catch(Exception e){fail(e);}
 return START_NOT_STICKY;
 }
 void fail(Throwable e){Log.e(TAG,"Stopping capture",e);main.post(()->{if(!stopped){android.widget.Toast.makeText(this,"VR stopped: "+e.getMessage(),1).show();stopSelf();}});}
 @Override public void onDisplayAdded(int id){}
 @Override public void onDisplayChanged(int id){}
 @Override public void onDisplayRemoved(int id){if(id==target)stopSelf();}
 @Override public void onDestroy(){
 stopped=true;if(dm!=null)dm.unregisterDisplayListener(this);
 if(vd!=null){vd.release();vd=null;}
 if(mp!=null){mp.stop();mp=null;}
 if(renderer!=null){renderer.close();renderer=null;}
 if(view!=null&&wm!=null){try{wm.removeView(view);}catch(Exception ignored){}view=null;}
 stopForeground(STOP_FOREGROUND_REMOVE);Log.i(TAG,"Stopped");super.onDestroy();
 }
 final class Renderer {
 final Surface out;final HandlerThread thread=new HandlerThread("vcvr-render");Handler h;volatile boolean closed=false;boolean scheduled=false;long last=0;int tex,program;SurfaceTexture st;Surface input;EGLDisplay ed;EGLContext ec;EGLSurface es;final float[] matrix=new float[16];final FloatBuffer vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
 Renderer(Surface s){out=s;vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).flip();}
 void start(){thread.start();h=new Handler(thread.getLooper());h.post(()->{try{if(closed)return;init();main.post(()->{if(stopped||closed||mp==null)return;try{vd=mp.createVirtualDisplay("vcvr-clean",1280,720,240,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,input,null,null);Log.i(TAG,"Capture attached");}catch(Exception e){fail(e);}});}catch(Exception e){fail(e);}});}
 void init(){
 ed=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);check(EGL14.eglInitialize(ed,new int[2],0,new int[2],0),"eglInitialize");
 EGLConfig[] cfg=new EGLConfig[1];int[] n=new int[1];
 check(EGL14.eglChooseConfig(ed,new int[]{EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE},0,cfg,0,1,n,0)&&n[0]>0,"eglChooseConfig");
 ec=EGL14.eglCreateContext(ed,cfg[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);check(ec!=EGL14.EGL_NO_CONTEXT,"eglCreateContext");
 es=EGL14.eglCreateWindowSurface(ed,cfg[0],out,new int[]{EGL14.EGL_NONE},0);check(es!=EGL14.EGL_NO_SURFACE,"eglCreateWindowSurface");
 check(EGL14.eglMakeCurrent(ed,es,es,ec),"eglMakeCurrent");EGL14.eglSwapInterval(ed,1);
 int[] t=new int[1];GLES20.glGenTextures(1,t,0);tex=t[0];int ext=GLES11Ext.GL_TEXTURE_EXTERNAL_OES;GLES20.glBindTexture(ext,tex);GLES20.glTexParameteri(ext,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(ext,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(ext,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(ext,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
 program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; varying vec2 uv; void main(){uv=p*.5+.5;gl_Position=vec4(p,0.,1.);}"));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; uniform mat4 m; varying vec2 uv; void main(){gl_FragColor=texture2D(image,(m*vec4(uv,0.,1.)).xy);}"));GLES20.glBindAttribLocation(program,0,"p");GLES20.glLinkProgram(program);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);check(ok[0]!=0,GLES20.glGetProgramInfoLog(program));
 st=new SurfaceTexture(tex);st.setDefaultBufferSize(1280,720);input=new Surface(st);
 st.setOnFrameAvailableListener(s->{if(closed||scheduled)return;scheduled=true;h.postDelayed(this::draw,Math.max(0,34-(SystemClock.uptimeMillis()-last)));},h);
 }
 void draw(){scheduled=false;if(closed)return;try{st.updateTexImage();st.getTransformMatrix(matrix);last=SystemClock.uptimeMillis();
 GLES20.glViewport(0,0,1920,1080);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
 GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,matrix,0);vertices.position(0);GLES20.glEnableVertexAttribArray(0);GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);
 for(int eye=0;eye<2;eye++){GLES20.glViewport(eye*960,270,960,540);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);}
 check(GLES20.glGetError()==GLES20.GL_NO_ERROR,"GL draw failed");check(EGL14.eglSwapBuffers(ed,es),"eglSwapBuffers failed");
 }catch(Exception e){closed=true;fail(e);}}
 void close(){closed=true;h.removeCallbacksAndMessages(null);h.post(()->{try{if(st!=null)st.setOnFrameAvailableListener(null);if(input!=null)input.release();if(st!=null)st.release();if(ed!=null&&ed!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(ed,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(es!=null&&es!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(ed,es);if(ec!=null&&ec!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(ed,ec);EGL14.eglTerminate(ed);EGL14.eglReleaseThread();}}finally{thread.quitSafely();}});}
 int shader(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);check(ok[0]!=0,GLES20.glGetShaderInfoLog(s));return s;}
 void check(boolean ok,String msg){if(!ok)throw new IllegalStateException(msg);}
 }
}