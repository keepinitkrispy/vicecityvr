package vcvr.clean;
import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
public class Main extends Activity {
 @Override public void onCreate(Bundle b) {
 super.onCreate(b);
 LinearLayout l=new LinearLayout(this); l.setOrientation(1); l.setPadding(24,24,24,24);
 TextView info=new TextView(this); info.setText("Clean test: headset must already be connected and in VR mode. Start Remote Play first. Choose that app only in Android screen sharing. Two identical eye images; no AI depth or upscaling yet."); l.addView(info);
 Button p=new Button(this); p.setText("Allow headset overlay"); p.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))); l.addView(p);
 Button s=new Button(this); s.setText("Start capture"); s.setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"Overlay permission required",0).show();return;} startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1);}); l.addView(s);
 Button stop=new Button(this); stop.setText("Stop"); stop.setOnClickListener(v->stopService(new Intent(this,Bridge.class))); l.addView(stop); setContentView(l);
 }
 @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==1&&res==RESULT_OK&&data!=null){startForegroundService(new Intent(this,Bridge.class).putExtra("code",res).putExtra("data",data));finish();}}
}