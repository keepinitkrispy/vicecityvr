#!/usr/bin/env python3
"""Applies Vice City VR's changes to a Momiji checkout (pinned commit, see build workflow).
Every edit is an exact-text replacement that must match exactly once, so upstream drift fails loudly instead of silently.
usage: apply.py <momiji-dir>"""
import os, shutil, sys

M = sys.argv[1]
HERE = os.path.dirname(os.path.abspath(__file__))
CPP = 'android/app/src/main/cpp/'
KT = 'android/app/src/main/java/com/metallic/chiaki/'
RES = 'android/app/src/main/res/values/'

def edit(path, old, new):
    p = os.path.join(M, path); s = open(p, encoding='utf-8').read()
    n = s.count(old)
    if n != 1: sys.exit(f'apply.py: anchor matched {n} times in {path}:\n{old}')
    open(p, 'w', encoding='utf-8').write(s.replace(old, new))

# new sources
for root, _, files in os.walk(os.path.join(HERE, 'overlay')):
    for f in files:
        src = os.path.join(root, f); dst = os.path.join(M, os.path.relpath(src, os.path.join(HERE, 'overlay')))
        os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy(src, dst)

# --- native: feed every encoded frame to the motion-vector extractor too
edit(CPP + 'video-decoder.h', '#include <chiaki/thread.h>\n', '#include <chiaki/thread.h>\n\n#include "mv-extractor.h"\n')
edit(CPP + 'video-decoder.h', '} AndroidChiakiVideoDecoder;', '\n  // Vice City VR: the PS5 encoder\'s motion vectors, read beside the hardware decoder (H.264 only)\n  MvExtractor *mv;\n} AndroidChiakiVideoDecoder;')
edit(CPP + 'video-decoder.c', '  return chiaki_mutex_init(&decoder->codec_mutex, false);',
     '  decoder->mv = chiaki_codec_is_h265(codec) ? NULL : mv_extractor_new();\n  CHIAKI_LOGI(log, "Vice City VR: motion vectors %s", decoder->mv ? "on" : "off (HEVC stream; choose H.264)");\n  return chiaki_mutex_init(&decoder->codec_mutex, false);')
edit(CPP + 'video-decoder.c', '  chiaki_mutex_fini(&decoder->codec_mutex);\n}', '  mv_extractor_free(decoder->mv);\n  decoder->mv = NULL;\n  chiaki_mutex_fini(&decoder->codec_mutex);\n}')
edit(CPP + 'video-decoder.c', '  decoder->last_sample_us = now;\n', '  decoder->last_sample_us = now;\n  mv_extractor_feed(decoder->mv, buf, buf_size);\n')
edit(CPP + 'chiaki-jni.c', '\tjfloat stats[8] = {', '\tfloat mvs[6];\n\tmv_extractor_stats(decoder->mv, mvs);\n\tjfloat stats[14] = {')
edit(CPP + 'chiaki-jni.c', '\t\t(jfloat)android_chiaki_audio_output_buffered_ms(session->audio_output)\n\t};',
     '\t\t(jfloat)android_chiaki_audio_output_buffered_ms(session->audio_output),\n\t\tdecoder->mv ? 1.0f : 0.0f, mvs[0], mvs[1], mvs[2], mvs[3], mvs[4]\n\t};')
edit(CPP + 'chiaki-jni.c', 'SetFloatArrayRegion(env, stats_array, 0, 8, stats);', 'SetFloatArrayRegion(env, stats_array, 0, 14, stats);')
edit('android/app/CMakeLists.txt', 'target_link_libraries(chiaki-jni chiaki-lib)\n', '''target_link_libraries(chiaki-jni chiaki-lib)

# Vice City VR: motion-vector extraction with a minimal static FFmpeg (H.264 decoder only)
if(NOT VCVR_FFMPEG_DIR)
	message(FATAL_ERROR "Set VCVR_FFMPEG_DIR to the FFmpeg build (see .github/workflows/stream.yml)")
endif()
target_sources(chiaki-jni PRIVATE src/main/cpp/mv-extractor.c)
target_include_directories(chiaki-jni PRIVATE "${VCVR_FFMPEG_DIR}/include")
target_link_libraries(chiaki-jni "${VCVR_FFMPEG_DIR}/lib/libavcodec.a" "${VCVR_FFMPEG_DIR}/lib/libavutil.a" m)
''')

# --- app identity and build
edit('android/app/build.gradle', 'applicationId "io.github.yazanj082.momiji"', 'applicationId "io.github.keepinitkrispy.vicecityvr"')
edit('android/app/build.gradle', 'versionName "1.1.3"', 'versionName "1.1.3-vcvr"\n        ndk { abiFilters "arm64-v8a" }   // the FFmpeg build is arm64 only')
edit('android/app/build.gradle', 'arguments "-DCHIAKI_ENABLE_TESTS=OFF",', 'arguments "-DVCVR_FFMPEG_DIR=" + (System.getenv("VCVR_FFMPEG_DIR") ?: ""),\n                        "-DCHIAKI_ENABLE_TESTS=OFF",')
edit(RES + 'strings.xml', '<string name="app_name" translatable="false">Momiji</string>', '<string name="app_name" translatable="false">Vice City VR</string>')
edit(RES + 'strings.xml', '    <string name="stream_stats_timing">', '''    <string name="stream_stats_motion" translatable="false">Motion vectors %1$.1f ms/frame (max %2$.0f) · %3$.0f%% of picture · %4$.1f px/frame avg · %5$.0f dropped</string>
    <string name="stream_stats_motion_off" translatable="false">Motion vectors off: stream is HEVC (choose H.264 in settings)</string>
    <string name="stream_stats_timing">''')

# --- stats overlay
edit(KT + 'lib/Chiaki.kt', '\tval audioBufferMs: Float\n)', '''\tval audioBufferMs: Float,
	/** Vice City VR: motion vectors read from the stream */
	val mvActive: Boolean = false,
	val mvMsAverage: Float = 0f,
	val mvMsMax: Float = 0f,
	/** 0 to 1 */
	val mvCoverage: Float = 0f,
	val mvMotionPx: Float = 0f,
	val mvDropped: Float = 0f
)''')
edit(KT + 'lib/Chiaki.kt', '\t\tval stats = FloatArray(8)', '\t\tval stats = FloatArray(14)')
edit(KT + 'lib/Chiaki.kt', 'return StreamStats(stats[0], stats[1], stats[2], stats[3].toLong(), stats[4], stats[5], stats[6], stats[7])',
     'return StreamStats(stats[0], stats[1], stats[2], stats[3].toLong(), stats[4], stats[5], stats[6], stats[7],\n\t\t\tstats[8] > 0f, stats[9], stats[10], stats[11], stats[12], stats[13])')
edit(KT + 'stream/StreamActivity.kt', '\t\t\tformat(R.string.stream_stats_timing, stats.frameGapMsMax, stats.audioBufferMs),',
     '\t\t\tformat(R.string.stream_stats_timing, stats.frameGapMsMax, stats.audioBufferMs),\n'
     '\t\t\tif(stats.mvActive) format(R.string.stream_stats_motion, stats.mvMsAverage, stats.mvMsMax, stats.mvCoverage * 100f, stats.mvMotionPx, stats.mvDropped)\n'
     '\t\t\telse getString(R.string.stream_stats_motion_off),')
# --- direct calibrated PSVR gameplay output
edit("android/app/src/main/AndroidManifest.xml", "    <queries>\n", "    <queries>\n        <provider android:authorities=\"cal.psvr.alignment\" />\n")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\tprivate var videoRenderer: VideoRenderer? = null", "\tprivate var videoRenderer: VideoRenderer? = null\n\tprivate var psvrOutput: PsvrOutput? = null")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\t\tviewModel.session.detachSurface()\n\t\tval upscale", "\t\tviewModel.session.detachSurface()\n\t\tval psvrDisplay = PsvrOutput.find(this)\n\t\tif (psvrDisplay != null) {\n\t\t\tval profile = viewModel.session.connectInfo.videoProfile\n\t\t\tval output = PsvrOutput(this, psvrDisplay, profile.width, profile.height) { surface ->\n\t\t\t\tviewModel.session.attachToSurface(surface)\n\t\t\t}\n\t\t\ttry {\n\t\t\t\toutput.show()\n\t\t\t\tpsvrOutput = output\n\t\t\t\tbinding.surfaceView.visibility = View.GONE\n\t\t\t\tbinding.debandSurfaceView.visibility = View.GONE\n\t\t\t\tval align = android.widget.Button(this).apply {\n\t\t\t\t\ttext = \"Align / Save\"\n\t\t\t\t\tsetOnClickListener { output.showAlignment() }\n\t\t\t\t}\n\t\t\t\tbinding.root.addView(align, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))\n\t\t\t\treturn\n\t\t\t} catch (e: WindowManager.InvalidDisplayException) {\n\t\t\t\tLog.w(TAG, \"PSVR display unavailable\", e)\n\t\t\t}\n\t\t}\n\t\tval upscale")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\t\tval display = if(Preferences(this).secondScreen && started && !isInPictureInPictureMode)", "\t\tval display = if(psvrOutput == null && Preferences(this).secondScreen && started && !isInPictureInPictureMode)")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\t\tacquireWifiLock()\n\t\tviewModel.session.resume()", "\t\tpsvrOutput?.resumeOutput()\n\t\tacquireWifiLock()\n\t\tviewModel.session.resume()")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\t\tviewModel.session.pause()\n\t\tcountPlayTime()", "\t\tviewModel.session.pause()\n\t\tif (psvrOutput != null) { viewModel.session.detachSurface(); psvrOutput?.releaseOutput(); psvrOutput = null }\n\t\tcountPlayTime()")
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "\t\tvideoRenderer?.release()\n\t\tcontrolsDisposable.dispose()", "\t\tvideoRenderer?.release()\n\t\tpsvrOutput?.releaseOutput()\n\t\tcontrolsDisposable.dispose()")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        // 3. Bilinear scaling to the screen, where SGSR isn't used (FBO -> screen)", "        // PSVR panel: two independently calibrated 960x1080 halves, aspect-correct shared source.\n        private const val PSVR_FRAGMENT_SHADER = \"\"\"\n            #version 300 es\n            precision highp float;\n            in highp vec2 v_TexCoord;\n            out vec4 outColor;\n            uniform sampler2D u_Texture;\n            uniform vec3 u_Left;\n            uniform vec3 u_Right;\n            uniform vec2 u_Lens;\n            uniform vec2 u_Picture;\n            void main() {\n                vec3 eye = v_TexCoord.x < .5 ? u_Left : u_Right;\n                vec2 p = vec2(fract(v_TexCoord.x * 2.0) * 960.0, (1.0 - v_TexCoord.y) * 1080.0);\n                p = (p - vec2(480.0,540.0) - eye.xy) / eye.z;\n                vec2 n = p / 540.0;\n                float r2 = dot(n,n);\n                p *= 1.0 + u_Lens.x*r2 + u_Lens.y*r2*r2;\n                vec2 uv = p/u_Picture + .5;\n                if (any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0)))) {\n                    outColor = vec4(0.,0.,0.,1.); return;\n                }\n                outColor = vec4(texture(u_Texture,vec2(uv.x,1.0-uv.y)).rgb,1.0);\n            }\n        \"\"\"\n\n        // 3. Bilinear scaling to the screen, where SGSR isn't used (FBO -> screen)")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "    private var blitProgram: Program? = null", "    private var blitProgram: Program? = null\n    @Volatile var psvrCalibration: PsvrCalibration? = null\n    private var psvrProgram: Program? = null")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        debandProgram = if (deband) {", "        psvrProgram = if (psvrCalibration != null) Program(createProgram(EFFECT_VERTEX_SHADER, PSVR_FRAGMENT_SHADER)).also {\n            GLES30.glUseProgram(it.id)\n            GLES30.glUniform1i(it.uniformLoc(\"u_Texture\"), 0)\n        } else null\n        debandProgram = if (deband) {")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        var frame = videoTarget.textureId\n", "        var frame = videoTarget.textureId\n        val cal = psvrCalibration\n        val stereo = psvrProgram\n        if (cal != null && stereo != null) {\n            val l = cal.eye(if (cal.swap) 1 else 0)\n            val r = cal.eye(if (cal.swap) 0 else 1)\n            val fit = minOf(960f/videoWidth,1080f/videoHeight)\n            drawPass(stereo,0,surfaceWidth,surfaceHeight,GLES30.GL_TEXTURE_2D,frame) {\n                GLES30.glUniform3f(stereo.uniformLoc(\"u_Left\"),l.x,l.y,l.scale)\n                GLES30.glUniform3f(stereo.uniformLoc(\"u_Right\"),r.x,r.y,r.scale)\n                GLES30.glUniform2f(stereo.uniformLoc(\"u_Lens\"),if(cal.warp)cal.k1 else 0f,if(cal.warp)cal.k2 else 0f)\n                GLES30.glUniform2f(stereo.uniformLoc(\"u_Picture\"),videoWidth*fit,videoHeight*fit)\n            }\n            return\n        }\n")


# --- PS5-frame-derived response stereo; no AI model, second decoder or GPU readback ---
edit("android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt", "import android.util.Log", "import android.util.Log\nimport android.view.Gravity\nimport android.view.WindowManager")
edit("android/app/src/main/cpp/video-decoder.c", "decoder->mv = chiaki_codec_is_h265(codec) ? NULL : mv_extractor_new();", "decoder->mv = NULL;")
edit("android/app/src/main/res/values/strings.xml", "Motion vectors off: stream is HEVC (choose H.264 in settings)", "Motion vector decoder disabled to conserve power")
# (Momiji's latch-every-queued-frame loop stays: latching one buffer per draw lets a backlog build up until
#  the decoder runs out of buffers and the picture freezes, which is what the heavier headset path triggered.)
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        private const val PSVR_FRAGMENT_SHADER = \"\"\"\n            #version 300 es", "        private const val PSVR_FRAGMENT_SHADER = \"\"\"\n            #version 300 es\n            #extension GL_OES_EGL_image_external_essl3 : require")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "            uniform vec2 u_Picture;\n            void main() {", "            uniform vec2 u_Picture;\n            vec3 sampleGame(vec2 p) {\n                return texture(u_Texture,(u_STMatrix*vec4(clamp(p,0.,1.),0.,1.)).xy).rgb;\n            }\n            void main() {")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "    private fun createRenderTarget(): RenderTarget {", "    private fun createRenderTarget(width:Int=videoWidth,height:Int=videoHeight): RenderTarget {")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "GLES30.GL_RGBA8, videoWidth, videoHeight, 0,", "GLES30.GL_RGBA8, width, height, 0,")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        // --- PASS 1: OES to FBO ---\n        drawPass(copyProgram, videoTarget.fboId, videoWidth, videoHeight, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId) {\n            GLES30.glUniformMatrix4fv(uCopySTMatrixLoc, 1, false, stMatrix, 0)\n        }\n        var frame = videoTarget.textureId\n        val cal = psvrCalibration", "        val cal = psvrCalibration")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        // --- PASS 2: debanding", "        // --- PASS 1: OES to FBO (ordinary phone output only) ---\n        drawPass(copyProgram, videoTarget.fboId, videoWidth, videoHeight, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId) {\n            GLES30.glUniformMatrix4fv(uCopySTMatrixLoc, 1, false, stMatrix, 0)\n        }\n        var frame=videoTarget.textureId\n\n        // --- PASS 2: debanding")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "            uniform sampler2D u_Texture;\n            uniform vec3 u_Left;", "            uniform samplerExternalOES u_Texture;\n            uniform sampler2D u_Response;\n            uniform mat4 u_STMatrix;\n            uniform float u_Ready;\n            uniform float u_Swap;\n            uniform vec2 u_Texel;\n            uniform vec3 u_Left;")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "                outColor = vec4(texture(u_Texture,vec2(uv.x,1.0-uv.y)).rgb,1.0);", "                vec2 src=vec2(uv.x,1.0-uv.y);\n                bool left=(v_TexCoord.x<.5)!=(u_Swap>.5);\n                float sg=left?-1.:1.;\n                float taper=smoothstep(0.,.06,min(src.x,1.-src.x));\n                float d=texture(u_Response,src).a;\n                src.x+=sg*.022*(d-.46)*taper*u_Ready;\n                vec3 c=sampleGame(src);\n                vec3 a=sampleGame(src-vec2(u_Texel.x,0.)), b=sampleGame(src+vec2(u_Texel.x,0.));\n                vec3 lo=min(c,min(a,b)), hi=max(c,max(a,b));\n                outColor=vec4(clamp(c+.12*(2.*c-a-b),max(vec3(0.),lo-.025),min(vec3(1.),hi+.025)),1.);")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        private const val PSVR_FRAGMENT_SHADER = \"\"\"", "        private const val RESPONSE_FLOW = \"\"\"\n            #version 300 es\n            precision mediump float;\n            in highp vec2 v_TexCoord;\n            uniform sampler2D u_Cur;\n            uniform sampler2D u_Prev;\n            out vec4 outColor;\n            const vec2 px=vec2(1./192.,1./108.);\n            float lu(sampler2D t,vec2 p){return texture(t,p).a;}\n            void main(){\n                vec2 uv=v_TexCoord;\n                float c=lu(u_Cur,uv), l=lu(u_Cur,uv-vec2(2.*px.x,0.)),\n                      r=lu(u_Cur,uv+vec2(2.*px.x,0.)),\n                      b=lu(u_Cur,uv-vec2(0.,2.*px.y)),\n                      a=lu(u_Cur,uv+vec2(0.,2.*px.y));\n                float best=100.; vec2 match=vec2(0.);\n                for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){\n                    vec2 f=vec2(float(i),float(j))*2.*px,p=uv-f;\n                    float err=abs(c-lu(u_Prev,p))\n                      +abs(l-lu(u_Prev,p-vec2(2.*px.x,0.)))\n                      +abs(r-lu(u_Prev,p+vec2(2.*px.x,0.)))\n                      +abs(b-lu(u_Prev,p-vec2(0.,2.*px.y)))\n                      +abs(a-lu(u_Prev,p+vec2(0.,2.*px.y)))\n                      +.008*float(abs(i)+abs(j));\n                    if(err<best){best=err;match=f;}\n                }\n                float contrast=max(max(max(max(c,l),r),b),a)-min(min(min(min(c,l),r),b),a);\n                outColor=vec4(match/(8.*px)+.5,clamp(contrast*3.,0.,1.),clamp(best*.2,0.,1.));\n            }\n        \"\"\"\n        private const val RESPONSE_STATE = \"\"\"\n            #version 300 es\n            precision highp float;\n            in highp vec2 v_TexCoord;\n            uniform sampler2D u_Flow;\n            uniform sampler2D u_PrevState;\n            uniform float u_HasPrev;\n            out vec4 outColor;\n            const vec2 extent=vec2(8./192.,8./108.);\n            vec2 motion(vec2 p){return (texture(u_Flow,p).rg-.5)*extent;}\n            void main(){\n                vec2 uv=v_TexCoord,p=uv-.5;\n                vec4 F=texture(u_Flow,uv);\n                vec2 m=motion(uv);\n                vec2 l=motion(vec2(.2,.5)),r=motion(vec2(.8,.5));\n                vec2 b=motion(vec2(.5,.2)),t=motion(vec2(.5,.8));\n                vec2 center=motion(vec2(.5,.5));\n                float h=(r.x-l.x)/.6, v=(t.y-b.y)/.6;\n                float expand=(h+v)*.5;\n                float coherence=1.-smoothstep(.012,.05,abs(h-v));\n                float cut=max(max(texture(u_Flow,vec2(.2,.2)).a,texture(u_Flow,vec2(.8,.8)).a),\n                              texture(u_Flow,vec2(.5,.5)).a);\n                float prior=.10+.75*clamp((.55-uv.y)/.55,0.,1.);\n                bool keep=u_HasPrev>.5 && cut<.18;\n                vec4 old=keep?texture(u_PrevState,uv-m):vec4(prior,0.,0.,prior);\n                vec2 radial=expand*p;\n                float travel=dot(radial,radial);\n                float evidence=smoothstep(.000002,.00004,travel)*coherence*F.b*(1.-smoothstep(.035,.12,F.a));\n                float observed=clamp(.46*dot(m-center,radial)/max(travel,1e-7),0.,1.);\n                float response=mix(old.r,observed,.32*evidence);\n                float hud=keep?texture(u_PrevState,uv).g:0.;\n                if(length(center+radial)>.007 && F.b>.15) {\n                    hud=clamp(hud+(length(m)<.0025?.10:-.22),0.,1.);\n                }\n                float d=mix(response,.46,smoothstep(.45,.9,hud));\n                outColor=vec4(response,hud,evidence,d);\n            }\n        \"\"\"\n\n        private const val PSVR_FRAGMENT_SHADER = \"\"\"")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "    private var psvrProgram: Program? = null", "    private var psvrProgram: Program? = null\n    private var flowProgram: Program?=null\n    private var responseProgram: Program?=null\n    private var smallFrames: Array<RenderTarget>?=null\n    private var responseFrames: Array<RenderTarget>?=null\n    private var flowTarget: RenderTarget?=null\n    private var previousSmall=0\n    private var previousResponse=0\n    private var haveSmall=false\n    private var haveResponse=false\n    private var lastResponseMs=0L\n    private fun prepareResponse() {\n        fun program(source:String):Program?=try {\n            val id=createProgram(EFFECT_VERTEX_SHADER,source)\n            if(id==0)null else Program(id)\n        } catch(error:RuntimeException){Log.e(TAG,\"Response shader unavailable\",error);null}\n        flowProgram=program(RESPONSE_FLOW)\n        responseProgram=program(RESPONSE_STATE)\n        if(flowProgram==null || responseProgram==null)return\n        smallFrames=Array(2){createRenderTarget(192,108)}\n        responseFrames=Array(2){createRenderTarget(48,27)}\n        flowTarget=createRenderTarget(48,27)\n        previousSmall=0; previousResponse=0\n        haveSmall=false;haveResponse=false;lastResponseMs=0L\n    }\n    private fun updateResponse(hasFrame:Boolean) {\n        if(!hasFrame)return\n        val small=smallFrames?:return\n        val responses=responseFrames?:return\n        val flow=flowTarget?:return\n        val fp=flowProgram?:return\n        val rp=responseProgram?:return\n        val now=android.os.SystemClock.elapsedRealtime()\n        if(now-lastResponseMs<66L)return\n        lastResponseMs=now\n        val next=1-previousSmall\n        drawPass(copyProgram,small[next].fboId,192,108,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTextureId){\n            GLES30.glUniformMatrix4fv(uCopySTMatrixLoc,1,false,stMatrix,0)\n        }\n        if(haveSmall){\n            drawPass(fp,flow.fboId,48,27,GLES30.GL_TEXTURE_2D,small[next].textureId){\n                GLES30.glUniform1i(fp.uniformLoc(\"u_Cur\"),0)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)\n                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,small[previousSmall].textureId)\n                GLES30.glUniform1i(fp.uniformLoc(\"u_Prev\"),1)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)\n            }\n            val nr=1-previousResponse\n            drawPass(rp,responses[nr].fboId,48,27,GLES30.GL_TEXTURE_2D,flow.textureId){\n                GLES30.glUniform1i(rp.uniformLoc(\"u_Flow\"),0)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)\n                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,responses[previousResponse].textureId)\n                GLES30.glUniform1i(rp.uniformLoc(\"u_PrevState\"),1)\n                GLES30.glUniform1f(rp.uniformLoc(\"u_HasPrev\"),if(haveResponse)1f else 0f)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)\n            }\n            previousResponse=nr;haveResponse=true\n        }\n        previousSmall=next;haveSmall=true\n    }")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "        videoTarget = createRenderTarget()", "        if (psvrCalibration == null || psvrProgram == null) videoTarget = createRenderTarget() else prepareResponse()")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "            drawPass(stereo,0,surfaceWidth,surfaceHeight,GLES30.GL_TEXTURE_2D,frame) {", "            updateResponse(pending>0)\n            drawPass(stereo,0,surfaceWidth,surfaceHeight,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTextureId) {")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "                GLES30.glUniform2f(stereo.uniformLoc(\"u_Picture\"),videoWidth*fit,videoHeight*fit)", "                GLES30.glUniform2f(stereo.uniformLoc(\"u_Picture\"),videoWidth*fit,videoHeight*fit)\n                GLES30.glUniformMatrix4fv(stereo.uniformLoc(\"u_STMatrix\"),1,false,stMatrix,0)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)\n                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,responseFrames?.get(previousResponse)?.textureId ?: 0)\n                GLES30.glUniform1i(stereo.uniformLoc(\"u_Response\"),1)\n                GLES30.glUniform1f(stereo.uniformLoc(\"u_Ready\"),if(haveResponse)1f else 0f)\n                GLES30.glUniform1f(stereo.uniformLoc(\"u_Swap\"),if(cal.swap)1f else 0f)\n                GLES30.glUniform2f(stereo.uniformLoc(\"u_Texel\"),1f/videoWidth,1f/videoHeight)\n                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)")
edit("android/app/src/main/java/com/metallic/chiaki/stream/VideoRenderer.kt", "                float d=texture(u_Response,src).a;", "                float d=u_Ready>.5 ? texture(u_Response,src).a : .46;")

# --- accelerator-only neural stereo, with existing GPU response as fallback ---
from pathlib import Path
import urllib.request
depth_src = Path(HERE).parent / 'bridge/src/vcvr/bridge/Depth.java'
depth_dst = Path(M) / (KT + 'stream/Depth.java')
depth_code = depth_src.read_text().replace('package vcvr.bridge;', 'package com.metallic.chiaki.stream;')
depth_code = depth_code.replace('RW = 532, RH = 308', 'RW = 266, RH = 154')
depth_code = depth_code.replace('sess = env.createSession(model, so);', '''try {
            so.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED));
            so.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            sess = env.createSession(model, so);
        } finally { so.close(); }''')
depth_dst.write_text(depth_code, encoding='utf-8')
model = Path(M) / 'android/app/src/main/assets/depth.onnx'
model.parent.mkdir(parents=True, exist_ok=True)
if not model.exists():
    urllib.request.urlretrieve('https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx', str(model))
edit('android/app/build.gradle', 'dependencies {\n    implementation fileTree', "dependencies {\n    implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.22.0'\n    implementation fileTree")
edit(KT+'stream/PsvrOutput.kt', 'val renderer = VideoRenderer(width,height,false,0f,null,{ glView.requestRender() },onSurface)', 'val renderer = VideoRenderer(width,height,false,0f,null,{ glView.requestRender() },onSurface).also { it.neuralContext = activity.applicationContext }')
edit(KT+'stream/VideoRenderer.kt', '    private var flowProgram: Program?=null', '''    var neuralContext: Context? = null
    private val neuralBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val neuralWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var neuralEngine: Depth? = null
    @Volatile private var neuralDisabled = false
    @Volatile private var neuralClosed = false
    @Volatile private var nextNeuralMs = 0L
    private var neuralTarget: RenderTarget? = null
    private var neuralBuffer: ByteBuffer? = null
    private var neuralUpload: ByteBuffer? = null
    private var neuralTexture = 0
    private var neuralStamp = 0L
    private var thermal = 0
    private var thermalStamp = 0L
    private val neuralCrop = floatArrayOf(0f,0f,1f,1f)
    private fun prepareNeural() {
        if(neuralContext == null) return
        neuralTarget = createRenderTarget(Depth.RW,Depth.RH)
        neuralBuffer = ByteBuffer.allocateDirect(Depth.RW*Depth.RH*4)
        neuralUpload = ByteBuffer.allocateDirect(Depth.W*Depth.H*2)
        val ids=IntArray(1)
        GLES30.glGenTextures(1,ids,0)
        neuralTexture=ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,neuralTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RG8,Depth.W,Depth.H,0,GLES30.GL_RG,GLES30.GL_UNSIGNED_BYTE,null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
        neuralStamp=0L
        nextNeuralMs=android.os.SystemClock.elapsedRealtime()+5000L
    }
    private fun updateNeural(hasFrame:Boolean) {
        val ctx=neuralContext?:return
        val target=neuralTarget?:return
        val buf=neuralBuffer?:return
        val upload=neuralUpload?:return
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-thermalStamp>=1000L) {
            thermalStamp=now
            if(android.os.Build.VERSION.SDK_INT>=29)
                thermal=ctx.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus?:0
        }
        if(neuralDisabled || neuralClosed || thermal>=2) return
        val engine=neuralEngine
        if(engine!=null && engine.fresh) {
            synchronized(engine) {
                upload.clear(); upload.put(engine.shift); upload.flip()
                engine.crop.copyInto(neuralCrop); engine.fresh=false
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,neuralTexture)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,Depth.W,Depth.H,GLES30.GL_RG,GLES30.GL_UNSIGNED_BYTE,upload)
            neuralStamp=now
        }
        if(!hasFrame || now<nextNeuralMs || !neuralBusy.compareAndSet(false,true)) return
        try {
            // Initialize on the worker before copying any frame. No CPU fallback.
            if(engine==null) {
                neuralWorker.execute {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        neuralEngine=Depth(ctx.assets.open("depth.onnx").use { it.readBytes() },1)
                        Log.i(TAG,"Accelerator-only depth session ready")
                    } catch(e:Exception) { neuralDisabled=true; Log.w(TAG,"Accelerated depth unavailable; GPU stereo retained",e) }
                    catch(e:LinkageError) { neuralDisabled=true; Log.w(TAG,"Neural runtime unavailable",e) }
                    finally { nextNeuralMs=android.os.SystemClock.elapsedRealtime()+1000L; neuralBusy.set(false) }
                }
                return
            }
            drawPass(copyProgram,target.fboId,Depth.RW,Depth.RH,GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oesTextureId) {
                GLES30.glUniformMatrix4fv(uCopySTMatrixLoc,1,false,stMatrix,0)
            }
            buf.clear()
            GLES30.glReadPixels(0,0,Depth.RW,Depth.RH,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
            neuralWorker.execute {
                val start=android.os.SystemClock.elapsedRealtime()
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    engine.load(buf); engine.process(.022f,.5f,.9f)
                    if(android.os.SystemClock.elapsedRealtime()-start>750L) neuralDisabled=true
                } catch(e:Exception) { neuralDisabled=true; Log.w(TAG,"Depth inference failed; GPU stereo retained",e) }
                catch(e:LinkageError) { neuralDisabled=true }
                finally {
                    val end=android.os.SystemClock.elapsedRealtime()
                    nextNeuralMs=end+maxOf(500L,(end-start)*4L)
                    neuralBusy.set(false)
                }
            }
        } catch(e:RuntimeException) { neuralDisabled=true; neuralBusy.set(false); Log.w(TAG,"Depth scheduling failed",e) }
    }
    private var flowProgram: Program?=null''')
edit(KT+'stream/VideoRenderer.kt', 'else prepareResponse()', 'else { prepareResponse(); prepareNeural() }')
edit(KT+'stream/VideoRenderer.kt', '            updateResponse(pending>0)', '            updateResponse(pending>0)\n            updateNeural(pending>0)')
edit(KT+'stream/VideoRenderer.kt', '            uniform sampler2D u_Response;', '''            uniform sampler2D u_Response;
            uniform sampler2D u_Neural;
            uniform vec4 u_NeuralCrop;
            uniform float u_NeuralWeight;''')
edit(KT+'stream/VideoRenderer.kt', '                src.x+=sg*.022*(d-.46)*taper*u_Ready;', '''                float motionShift=sg*.022*(d-.46)*taper*u_Ready;
                vec2 local=(src-u_NeuralCrop.xy)/max(u_NeuralCrop.zw-u_NeuralCrop.xy,vec2(.0001));
                float offset=motionShift;
                if(u_NeuralWeight>.001 && all(greaterThanEqual(local,vec2(0.))) && all(lessThanEqual(local,vec2(1.)))) {
                    vec2 shifts=texture(u_Neural,local).rg;
                    float ns=((left?shifts.r:shifts.g)-128./255.)*.16*(u_NeuralCrop.z-u_NeuralCrop.x)*taper;
                    offset=mix(motionShift,clamp(ns,-.025,.025),u_NeuralWeight);
                }
                src.x+=offset;''')
edit(KT+'stream/VideoRenderer.kt', '                GLES30.glUniform1i(stereo.uniformLoc("u_Response"),1)', '''                GLES30.glUniform1i(stereo.uniformLoc("u_Response"),1)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,neuralTexture)
                GLES30.glUniform1i(stereo.uniformLoc("u_Neural"),2)
                GLES30.glUniform4f(stereo.uniformLoc("u_NeuralCrop"),neuralCrop[0],neuralCrop[1],neuralCrop[2],neuralCrop[3])
                val age=android.os.SystemClock.elapsedRealtime()-neuralStamp
                val weight=if(neuralStamp!=0L && !neuralDisabled && thermal<2) ((1500L-age)/750f).coerceIn(0f,1f) else 0f
                GLES30.glUniform1f(stereo.uniformLoc("u_NeuralWeight"),weight)''')
edit(KT+'stream/VideoRenderer.kt', '    fun release() {\n        surface?.release()', '''    fun release() {
        neuralClosed=true
        if(!neuralWorker.isShutdown) {
            neuralWorker.execute { try { neuralEngine?.sess?.close() } catch(_:Exception) {} }
            neuralWorker.shutdown()
        }
        surface?.release()''')

# Calibration uses the game renderer's viewport, eye transforms, and lens warp.
video_renderer = KT + 'stream/VideoRenderer.kt'
edit(video_renderer, '    private var psvrProgram: Program? = null', '''    private var psvrProgram: Program? = null
    @Volatile var psvrView = PsvrView.FLAT''')
edit(video_renderer, '            uniform vec2 u_Picture;', '''            uniform vec2 u_Picture;
            uniform int u_PsvrView;''')
edit(video_renderer, '                vec2 uv = p/u_Picture + .5;', '''                if (u_PsvrView >= 2) {
                    bool left = (v_TexCoord.x < .5) != (u_Swap > .5);
                    vec2 a = abs(p);
                    float crosshair = float((a.x < 2. && a.y < 54.) || (a.y < 2. && a.x < 54.));
                    vec2 grid = abs(mod(p + 40., 80.) - 40.);
                    float line = float(min(grid.x, grid.y) < 1.);
                    float ring = float(abs(length(p) - 110.) < 2.);
                    float box = float(abs(max(a.x, a.y) - 200.) < 2.);
                    float fine = float(a.x < 70. && a.y > 140. && a.y < 160.
                                       && mod(floor(p.x / 4.), 2.) == 0.);
                    float mark = max(max(crosshair, ring), max(box, fine));
                    vec3 tint = u_PsvrView == 3
                        ? (left ? vec3(1., .15, .15) : vec3(.15, .55, 1.))
                        : vec3(1.);
                    outColor = vec4(max(vec3(.04 + .13 * line), tint * mark), 1.);
                    return;
                }
                vec2 uv = p/u_Picture + .5;''')
edit(video_renderer, '                src.x+=offset;', '                if (u_PsvrView == 1) src.x += offset;')
edit(video_renderer, '            updateResponse(pending>0)\n            updateNeural(pending>0)', '''            val view = psvrView
            if (view == PsvrView.STEREO) {
                updateResponse(pending > 0)
                updateNeural(pending > 0)
            } else {
                haveSmall = false
                haveResponse = false
                neuralStamp = 0L
            }''')
edit(video_renderer, '                GLES30.glUniform1f(stereo.uniformLoc("u_Ready"),if(haveResponse)1f else 0f)', '''                GLES30.glUniform1i(stereo.uniformLoc("u_PsvrView"), view.ordinal)
                GLES30.glUniform1f(stereo.uniformLoc("u_Ready"),if(haveResponse)1f else 0f)''')

# Keep the phone output alive so USB or HDMI hotplug can change the decoder destination.
activity_file = KT + 'stream/StreamActivity.kt'
p = os.path.join(M, activity_file)
source = open(p, encoding='utf-8').read()
start = source.index('\t\tval psvrDisplay = PsvrOutput.find(this)')
end = source.index('\t\tval upscale', start)
edit(activity_file, source[start:end], '')
edit(activity_file, '\tprivate var psvrOutput: PsvrOutput? = null', '''    private var psvrOutput: PsvrOutput? = null
    private var psvrAlignButton: android.widget.Button? = null
    private val psvrUsb by lazy { PsvrUsb(this, ::refreshPsvrOutput,
        head = { yaw, pitch, size -> psvrOutput?.setHead(yaw, pitch, size) },
        stick = { x, y -> viewModel.input.setHeadLook(x, y) }) }

    private fun refreshPsvrOutput() {
        if (!started || isInPictureInPictureMode) return
        val display = if (psvrUsb.ready) PsvrOutput.find(this) else null
        val current = psvrOutput
        if (current != null && current.isShowing && current.display.displayId == display?.displayId) return
        if (current != null) {
            psvrOutput = null
            // Move the running decoder to the phone surface first (AMediaCodec_setOutputSurface), then
            // release the headset's. Detaching would shut the decoder down, and a new decoder fed only
            // P-frames shows a frozen picture until the PS5 happens to send a keyframe.
            viewModel.session.headsetSurface = null
            current.releaseOutput()
            psvrAlignButton?.let { binding.root.removeView(it) }
            psvrAlignButton = null
        }
        if (display == null) return
        val profile = viewModel.session.connectInfo.videoProfile
        lateinit var output: PsvrOutput
        output = PsvrOutput(this, display, profile.width, profile.height) { surface ->
            runOnUiThread {
                if (psvrOutput === output && surface.isValid) viewModel.session.headsetSurface = surface
            }
        }
        try {
            psvrOutput = output
            output.show()
            secondScreen?.dismiss()
            secondScreen = null
            val align = android.widget.Button(this).apply {
                text = "Align / Save"
                setOnClickListener { output.showAlignment() }
            }
            psvrAlignButton = align
            binding.root.addView(align, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        } catch (e: WindowManager.InvalidDisplayException) {
            psvrOutput = null
            output.releaseOutput()
            Log.w(TAG, "PSVR display unavailable", e)
        }
    }''')
edit(activity_file, 'private fun updateSecondScreen()\n\t{', '''private fun updateSecondScreen()
    {
        psvrUsb.refresh()
        refreshPsvrOutput()''')
edit(activity_file, '\t\tstarted = true\n\t\tupdateSecondScreen()', '\t\tstarted = true\n\t\tpsvrUsb.start()\n\t\tupdateSecondScreen()')
edit(activity_file, '\t\tviewModel.session.pause()\n\t\tif (psvrOutput != null)', '\t\tpsvrUsb.stop()\n\t\tviewModel.session.pause()\n\t\tif (psvrOutput != null)')
edit(video_renderer, '    private var surface: Surface? = null', '''    @Volatile private var surface: Surface? = null''')

# While the headset shows the stream, only the headset may hold the decoder. The phone's own views
# (plain SurfaceView, or the debanding GLSurfaceView) re-attach themselves whenever they are rebuilt,
# e.g. when the stream shrinks to picture-in-picture, which took the decoder away from the headset and
# left it showing one frozen frame.
session_file = KT + 'session/StreamSession.kt'
edit(session_file, '''	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder)
			{
				val surface = holder.surface
				this@StreamSession.surface = surface
				session?.setSurface(surface)
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				this@StreamSession.surface = null
				session?.setSurface(null)
			}
		})
		
		val surface = surfaceView.holder.surface
		if (surface?.isValid == true) {
			this.surface = surface
			session?.setSurface(surface)
		}
	}''', '''	/** Vice City VR: the phone's own video surface, used whenever the headset doesn't show the stream */
	private var phoneSurface: Surface? = null

	/** Vice City VR: while set, the decoder draws only here; null hands it back to the phone */
	var headsetSurface: Surface? = null
		set(value)
		{
			field = value
			val target = value ?: phoneSurface?.takeIf { it.isValid }
			this.surface = target
			session?.setSurface(target)
		}

	private fun usePhoneSurface(surface: Surface?)
	{
		phoneSurface = surface
		if (headsetSurface != null) return
		this.surface = surface
		session?.setSurface(surface)
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) = usePhoneSurface(holder.surface)

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

			override fun surfaceDestroyed(holder: SurfaceHolder) = usePhoneSurface(null)
		})
		
		val surface = surfaceView.holder.surface
		if (surface?.isValid == true) usePhoneSurface(surface)
	}''')
edit(session_file, '''	fun attachToSurface(surface: Surface)
	{
		this.surface = surface
		session?.setSurface(surface)
	}

	fun detachSurface()
	{
		this.surface = null
		session?.setSurface(null)
	}''', '''	fun attachToSurface(surface: Surface) = usePhoneSurface(surface)

	fun detachSurface()
	{
		phoneSurface = null
		headsetSurface = null
	}''')

# PSVR processor unit as this app's USB device, handled by an invisible activity: Android then offers
# "always" on the permission prompt and keeps the grant across replugs and reboots, and a running stream
# is never interrupted by an activity coming to the front.
edit("android/app/src/main/AndroidManifest.xml", """        <!-- Enabled in the settings to make Chiaki the home screen""", """        <activity android:name=".stream.PsvrAttachActivity"
            android:theme="@android:style/Theme.NoDisplay"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity="">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/psvr_device_filter" />
        </activity>

        <!-- Enabled in the settings to make Chiaki the home screen""")

# Touchpad clicks (in-game phone camera glitch): over Remote Play a fast press and release can reach the
# console within one game frame, and the game never sees the press. Every click is replayed in order with
# a minimum press and release time, so each one lands on a frame.
input_file = KT + 'session/StreamInput.kt'
edit(input_file, '''		state.buttons = if(clicked) ControllerState.BUTTON_TOUCHPAD else 0U
		controllerStateUpdated()
		return true
	}''', '''		queueTouchpadClick(clicked)
		controllerStateUpdated()
		return true
	}

	/** Vice City VR: shortest press and release of the touchpad button the console gets, in ms */
	private val touchpadClickMinMs = 40L
	private val touchpadClickHandler = android.os.Handler(android.os.Looper.getMainLooper())
	/** Press (true) / release (false) changes still to send, oldest first */
	private val touchpadClickQueue = ArrayDeque<Boolean>()
	private var touchpadClickPhysical = false
	private var touchpadClickSentAt = 0L
	private val touchpadClickStep = object: Runnable {
		override fun run()
		{
			val next = touchpadClickQueue.removeFirstOrNull() ?: return
			touchpadControllerState.buttons = if(next) ControllerState.BUTTON_TOUCHPAD else 0U
			touchpadClickSentAt = android.os.SystemClock.uptimeMillis()
			controllerStateUpdated()
			if(touchpadClickQueue.isNotEmpty())
				touchpadClickHandler.postDelayed(this, touchpadClickMinMs)
		}
	}

	/** Called on the main thread with the physical click state of every touchpad event */
	private fun queueTouchpadClick(clicked: Boolean)
	{
		if(clicked == touchpadClickPhysical)
			return
		touchpadClickPhysical = clicked
		// a long backlog of spam is trimmed to whole clicks, keeping the order press, release
		while(touchpadClickQueue.size >= 8)
		{
			touchpadClickQueue.removeFirst()
			touchpadClickQueue.removeFirst()
		}
		val idle = touchpadClickQueue.isEmpty()
		touchpadClickQueue.addLast(clicked)
		if(idle)
		{
			val wait = touchpadClickSentAt + touchpadClickMinMs - android.os.SystemClock.uptimeMillis()
			touchpadClickHandler.removeCallbacks(touchpadClickStep)
			if(wait > 0) touchpadClickHandler.postDelayed(touchpadClickStep, wait)
			else touchpadClickStep.run()
		}
	}''')
edit(input_file, '''		val buttons = if(clicked) ControllerState.BUTTON_TOUCHPAD else 0U
		if(buttons != touchpadControllerState.buttons)
		{
			touchpadControllerState.buttons = buttons
			controllerStateUpdated()
		}
		return true''', '''		queueTouchpadClick(clicked)
		return true''')
# a new touch must not drop queued clicks; losing focus or the pointer still releases everything
edit(input_file, '''	fun stopTouchpadTouches()
	{
		touchpadTouches.clear()
		touchpadControllerState.touches.forEach { touchpadControllerState.stopTouch(it.id.toUByte()) }
		touchpadControllerState.buttons = 0U
		controllerStateUpdated()''', '''	fun stopTouchpadTouches(keepClick: Boolean = false)
	{
		touchpadTouches.clear()
		touchpadControllerState.touches.forEach { touchpadControllerState.stopTouch(it.id.toUByte()) }
		if(!keepClick)
		{
			touchpadClickHandler.removeCallbacks(touchpadClickStep)
			touchpadClickQueue.clear()
			touchpadClickPhysical = false
			touchpadControllerState.buttons = 0U
		}
		controllerStateUpdated()''')
edit(input_file, '''				if(event.actionMasked == MotionEvent.ACTION_DOWN)
					stopTouchpadTouches()''', '''				if(event.actionMasked == MotionEvent.ACTION_DOWN)
					stopTouchpadTouches(keepClick = true)''')

# Head tracking: the game picture is a screen fixed in space, wider than the view, and turning your head looks
# across it. Done in the headset renderer from the PSVR gyro, so it reacts at once; the game camera (aiming)
# is untouched.
edit(video_renderer, '''            uniform int u_PsvrView;''', '''            uniform int u_PsvrView;
            uniform vec2 u_Head;
            uniform float u_Screen;''')
edit(video_renderer, '''                vec2 uv = p/u_Picture + .5;''', '''                // view angle of this pixel (480 px focal length: the 960-px picture spans 90 degrees at size 1),
                // minus the head's yaw/pitch (left/up positive; p.y grows downward), over the screen's angular size
                vec2 ang = atan(p / 480.0) - u_Head;
                vec2 uv = ang / (2.0 * atan(u_Picture / 960.0) * u_Screen) + .5;''')
edit(video_renderer, '''    @Volatile var psvrView = PsvrView.FLAT''', '''    @Volatile var psvrView = PsvrView.FLAT
    /** Head yaw and pitch (radians, left/up positive) and screen size, from the PSVR's head tracking */
    @Volatile var headYaw = 0f
    @Volatile var headPitch = 0f
    @Volatile var screenSize = 1.3f''')
edit(video_renderer, '''                GLES30.glUniform1i(stereo.uniformLoc("u_PsvrView"), view.ordinal)''', '''                GLES30.glUniform1i(stereo.uniformLoc("u_PsvrView"), view.ordinal)
                GLES30.glUniform2f(stereo.uniformLoc("u_Head"), headYaw, headPitch)
                GLES30.glUniform1f(stereo.uniformLoc("u_Screen"), screenSize)''')

# Recenter the headset view from the controller (L3 + R3 held for a second): headsets without the cable remote
# have no other button for it.
edit(input_file, '''		// After sending, so the released buttons of the menu are the last state the console gets
		if(comboPressed)
			menuComboCallback?.invoke()
	}''', '''		// After sending, so the released buttons of the menu are the last state the console gets
		if(comboPressed)
			menuComboCallback?.invoke()
		val recenterHeld = state.buttons and RECENTER_COMBO == RECENTER_COMBO
		if(recenterHeld && !recenterPending)
		{
			recenterPending = true
			recenterHandler.postDelayed(recenterCheck, 1000)
		}
		else if(!recenterHeld && recenterPending)
		{
			recenterPending = false
			recenterHandler.removeCallbacks(recenterCheck)
		}
	}

	/** Vice City VR: called when L3 + R3 have been held for a second */
	var recenterCallback: (() -> Unit)? = null
	private val RECENTER_COMBO = ControllerState.BUTTON_L3 or ControllerState.BUTTON_R3
	private var recenterPending = false
	private val recenterHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val recenterCheck = Runnable { if(recenterPending) recenterCallback?.invoke() }''')
edit(activity_file, '''		viewModel.input.menuComboCallback = { showStreamMenu() }''', '''		viewModel.input.menuComboCallback = { showStreamMenu() }
		viewModel.input.recenterCallback = { psvrUsb.recenter() }''')

# Head-driven camera turns (looking past the comfort zone): a right-stick state of its own, merged like every
# input source (the larger deflection wins), so the real right stick still aims.
edit(input_file, '''	private val touchpadControllerState = ControllerState() // from the controller's touchpad''', '''	private val touchpadControllerState = ControllerState() // from the controller's touchpad
	private val headControllerState = ControllerState() // Vice City VR: camera turns from looking far
	private val headHandler = android.os.Handler(android.os.Looper.getMainLooper())

	/** Vice City VR: right-stick deflection from the head, -1..1, stick up negative; any thread */
	fun setHeadLook(x: Float, y: Float)
	{
		val rx = (x.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
		val ry = (y.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
		headHandler.post {
			if(headControllerState.rightX == rx && headControllerState.rightY == ry)
				return@post
			headControllerState.rightX = rx
			headControllerState.rightY = ry
			controllerStateUpdated()
		}
	}''')
edit(input_file, '''		val controllerState = sensorControllerState or keyControllerState or motionControllerState or touchpadControllerState
''', '''		val controllerState = sensorControllerState or keyControllerState or motionControllerState or touchpadControllerState or headControllerState
''')

print('apply.py: all edits applied')
