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
print('apply.py: all edits applied')
