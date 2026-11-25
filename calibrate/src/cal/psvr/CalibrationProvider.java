package cal.psvr;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the matching live stream app can read the saved optical calibration. No writes. */
public final class CalibrationProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"io.github.keepinitkrispy.vicecityvr".equals(getCallingPackage()))
            throw new SecurityException("Only Vice City VR may read calibration");
        if (!"read".equals(method)) throw new IllegalArgumentException("Unknown method");
        SharedPreferences p = getContext().getSharedPreferences("cal",0);
        try {
            JSONArray eyes = new JSONArray();
            for (int i=0;i<2;i++) {
                eyes.put(new JSONObject().put("x",(double)p.getFloat("x"+i,0))
                    .put("y",(double)p.getFloat("y"+i,0)).put("scale",(double)p.getFloat("s"+i,1)));
            }
            JSONObject cal = new JSONObject().put("version",1).put("eyes",eyes)
                .put("swap",p.getBoolean("swap",false)).put("warp",p.getBoolean("warp",false))
                .put("k1",(double)p.getFloat("k1",.22f)).put("k2",(double)p.getFloat("k2",.24f));
            Bundle b = new Bundle(); b.putString("calibration",cal.toString()); return b;
        } catch (Exception ex) { throw new IllegalStateException("Could not read alignment",ex); }
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
