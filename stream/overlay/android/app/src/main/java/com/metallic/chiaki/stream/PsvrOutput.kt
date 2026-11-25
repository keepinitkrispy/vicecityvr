// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

data class PsvrEye(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f)
data class PsvrCalibration(val left: PsvrEye = PsvrEye(), val right: PsvrEye = PsvrEye(),
                           val swap: Boolean = false, val warp: Boolean = false,
                           val k1: Float = .22f, val k2: Float = .24f) {
    fun eye(i: Int) = if (i == 0) left else right
    fun withEye(i: Int, e: PsvrEye) = if (i == 0) copy(left=e) else copy(right=e)
    fun json(): String {
        fun obj(e: PsvrEye) = JSONObject().put("x",e.x.toDouble()).put("y",e.y.toDouble()).put("scale",e.scale.toDouble())
        return JSONObject().put("version",1).put("eyes",org.json.JSONArray().put(obj(left)).put(obj(right)))
            .put("swap",swap).put("warp",warp).put("k1",k1.toDouble()).put("k2",k2.toDouble()).toString()
    }
    companion object {
        fun parse(s: String): PsvrCalibration {
            val j = JSONObject(s); val eyes = j.getJSONArray("eyes")
            fun eye(i: Int): PsvrEye {
                val e=eyes.getJSONObject(i)
                return PsvrEye(e.getDouble("x").toFloat().coerceIn(-440f,440f),
                    e.getDouble("y").toFloat().coerceIn(-490f,490f),
                    e.getDouble("scale").toFloat().coerceIn(.5f,1.6f))
            }
            return PsvrCalibration(eye(0),eye(1),j.optBoolean("swap"),j.optBoolean("warp"),
                j.optDouble("k1",.22).toFloat(),j.optDouble("k2",.24).toFloat())
        }
    }
}

/** Direct MediaCodec -> GL -> PSVR presentation. Phone stays the input surface. */
class PsvrOutput(private val activity: Activity, display: Display, width: Int, height: Int,
                 onSurface: (android.view.Surface) -> Unit): Presentation(activity,display) {
    companion object {
        fun find(context: Context): Display? =
            context.getSystemService(DisplayManager::class.java)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .firstOrNull { it.isValid && (it.name.contains("SIE",true) || it.name.contains("PSVR",true)) &&
                    (it.mode.physicalWidth == 1920 && it.mode.physicalHeight == 1080) }
    }
    private val prefs = activity.getSharedPreferences("psvr_live",Context.MODE_PRIVATE)
    private fun imported(): PsvrCalibration? = try {
        val result=activity.contentResolver.call(Uri.parse("content://cal.psvr.alignment"),"read",null,null)
        result?.getString("calibration")?.let { PsvrCalibration.parse(it) }
    } catch (_: Exception) { null }
    private var calibration = prefs.getString("calibration",null)?.let {
        try { PsvrCalibration.parse(it) } catch (_: Exception) { null }
    } ?: imported() ?: PsvrCalibration()
    private val glView = GLSurfaceView(context)
    val renderer = VideoRenderer(width,height,false,0f,null,{ glView.requestRender() },onSurface)
    private var released = false
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.decorView?.systemUiVisibility = 5894
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8,8,8,0,0,0)
        glView.preserveEGLContextOnPause = true
        renderer.psvrCalibration = calibration
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        setContentView(glView)
    }
    fun resumeOutput() { if (!released) glView.onResume() }
    fun releaseOutput() {
        if (released) return
        released=true
        glView.onPause()
        renderer.release()
        dismiss()
    }
    private fun apply(c: PsvrCalibration) {
        calibration=c; renderer.psvrCalibration=c; glView.requestRender()
    }
    private fun save() { prefs.edit().putString("calibration",calibration.json()).apply() }
    fun showAlignment() {
        if (released) return
        var eye=0; var step=2f
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(12,8,12,8) }
        val status=TextView(activity).apply { setTextColor(Color.WHITE); gravity=Gravity.CENTER }
        layout.addView(status)
        fun refresh() { val e=calibration.eye(eye); status.text =
            "${if(eye==0) "LEFT" else "RIGHT"} | X ${e.x.toInt()} Y ${e.y.toInt()} | Scale ${"%.3f".format(java.util.Locale.US,e.scale)} | ${if(step==2f) "Fine" else "Coarse"} | Lens ${calibration.warp} | Swap ${calibration.swap}" }
        fun change(dx:Float=0f,dy:Float=0f,mult:Float=1f) {
            val e=calibration.eye(eye)
            apply(calibration.withEye(eye,PsvrEye((e.x+dx).coerceIn(-440f,440f),(e.y+dy).coerceIn(-490f,490f),(e.scale*mult).coerceIn(.5f,1.6f))))
            refresh()
        }
        fun row(vararg buttons: Pair<String,()->Unit>) {
            val r=LinearLayout(activity); layout.addView(r)
            for ((name,action) in buttons) {
                val b=Button(activity).apply { text=name; isAllCaps=false; minWidth=0; minimumWidth=0; setPadding(0,0,0,0); setOnClickListener { action() } }
                r.addView(b,LinearLayout.LayoutParams(0,-2,1f))
            }
        }
        row("Left eye" to {eye=0;refresh()}, "Right eye" to {eye=1;refresh()}, "Fine/coarse" to {step=if(step==2f)10f else 2f;refresh()})
        row("Left" to {change(dx=-step)}, "Up" to {change(dy=-step)}, "Right" to {change(dx=step)})
        row("Smaller" to {change(mult=if(step==2f).996f else .98f)}, "Down" to {change(dy=step)}, "Larger" to {change(mult=if(step==2f)1.004f else 1.02f)})
        row("Reset eye" to {apply(calibration.withEye(eye,PsvrEye()));refresh()}, "Swap" to {apply(calibration.copy(swap=!calibration.swap));refresh()},
            "Lens" to {apply(calibration.copy(warp=!calibration.warp));refresh()})
        row("Import saved alignment" to {
            val c=imported()
            if(c!=null){apply(c);refresh()}else android.widget.Toast.makeText(activity,"Alignment app does not expose saved values yet",android.widget.Toast.LENGTH_LONG).show()
        })
        refresh()
        AlertDialog.Builder(activity).setTitle("Live PSVR alignment").setView(layout)
            .setPositiveButton("Save and return") { _,_ -> save() }.setOnCancelListener { save() }.show()
    }
}
