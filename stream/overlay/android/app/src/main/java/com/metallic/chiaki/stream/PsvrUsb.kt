package com.metallic.chiaki.stream

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * A streaming activity's USB link to the original PSVR processor unit: the VR-mode handshake, then head-aim,
 * like sitting in a bubble window: wherever you look is what you see. The head is the camera: its rotation is
 * sent as right-stick input ([stick], -1..1, up negative), so the game camera turns with the head as fast as the
 * game allows. The picture fills your view; while the game catches up (Remote Play delay) it is shifted with the
 * head ([head]: yaw/pitch offset, radians, left/up positive, and the screen size), so the world doesn't drag.
 * The right stick fine-tunes on top ([userStick]); holding L2 ([aiming]) hands aiming to the right stick alone,
 * and afterwards the head carries on from where you aimed. Right-stick click ([lookBehind]): turn around 180
 * degrees. Level the view: L3 + R3 ([recenter]). Align panel: camera speed, delay, screen size.
 * [pan]: the stream's whole-picture shift since it started (x, y px, frames), from the encoder's own motion
 * vectors: compared with the turns sent, it measures the game's real turn speed and the stream's real delay, so
 * the picture shift and the camera follow use measured values instead of guesses (the Align panel values are
 * only the starting point). The measurement only tunes; the picture isn't locked to it, so a turning car still
 * turns your view.
 * [body]: the head's yaw/pitch from straight ahead (radians, left/up positive), for the minimap pinned low-left
 * in front of you, which stays put while the head turns.
 */
internal class PsvrUsb(private val context: Context, private val changed: () -> Unit,
                       private val head: (Float, Float, Float) -> Unit = { _, _, _ -> },
                       private val stick: (Float, Float) -> Unit = { _, _ -> },
                       private val body: (Float, Float) -> Unit = { _, _ -> },
                       private val pan: (FloatArray) -> Boolean = { false }) {
    private val manager = context.getSystemService(UsbManager::class.java)
    companion object {
        fun permissionAction(context: Context) = context.packageName + ".PSVR_USB_PERMISSION"
    }
    private val permission = permissionAction(context)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var device: UsbDevice? = null
    private var generation = 0
    var ready = false
        private set

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permission) {
                refresh()
                return
            }
            // from the permission prompt, or from PsvrAttachActivity (which can arrive before the attach broadcast)
            val granted = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (!started || granted == null || manager?.hasPermission(granted) != true) return
            if (granted.vendorId != 0x054c || granted.productId != 0x09af) return
            device = granted
            initialize(granted)
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(permission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        generation++
        ready = false
        device = null
        context.unregisterReceiver(receiver)
    }

    fun refresh() {
        if (!started || manager == null) return
        val current = manager.deviceList.values.firstOrNull {
            it.vendorId == 0x054c && it.productId == 0x09af
        }
        if (current == device) return
        device = current
        generation++
        ready = false
        changed()
        if (current == null) return
        if (manager.hasPermission(current)) initialize(current)
        else manager.requestPermission(current, PendingIntent.getBroadcast(
            context, 0, Intent(permission).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))
    }

    private fun initialize(target: UsbDevice) {
        val attempt = ++generation
        Thread({
            val usb = manager?.openDevice(target)
            try {
                val confirmed = usb != null && runCatching { enterVr(usb, target) }.getOrDefault(false)
                main.post {
                    if (started && attempt == generation && target == device) {
                        ready = confirmed
                        changed()
                        if (!confirmed) android.widget.Toast.makeText(context,
                            "PSVR did not confirm VR mode. Reconnect the processor box USB to retry.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                if (confirmed && usb != null) runCatching { headLoop(usb, target, attempt) }
            } finally {
                head(0f, 0f, screenSize)
                stick(0f, 0f)
                usb?.close()
            }
        }, "psvr-usb").start()
    }

    // ---- head tracking
    private val prefs = context.getSharedPreferences("psvr_live", Context.MODE_PRIVATE)
    /** Screen width as a multiple of the view's width: above 1 there is picture to look at beyond the edges */
    @Volatile var screenSize = prefs.getFloat("screen_size", 0.85f)  // the window: HUD corners within an easy eye glance
        private set
    @Volatile private var recenterRequested = false
    /** Puts the screen straight in front of where the head points now; any thread */
    fun recenter() { levelRequested = true }
    @Volatile private var userX = 0f
    @Volatile private var userY = 0f
    /** The player's own right stick, -1..1, up negative; any thread */
    fun userStick(x: Float, y: Float) { userX = x; userY = y }
    @Volatile private var levelRequested = false
    @Volatile private var lookBehindRequested = false
    /** Turns the view to the half of the sphere behind you, or back; any thread */
    fun lookBehind() { lookBehindRequested = true }
    /** L2 held: the right stick aims and the head doesn't move the camera; any thread */
    @Volatile var aiming = false
    /** bit 0: left/right flipped, bit 1: up/down flipped */
    private var lookFlip = prefs.getInt("look_flip", 0)
    private val lookFlipNames = arrayOf("normal", "left/right flipped", "up/down flipped", "both flipped")

    private fun toast(text: String) = main.post {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Reads the headset's sensor interface until this link is replaced or stopped. */
    private fun headLoop(usb: UsbDeviceConnection, target: UsbDevice, attempt: Int) {
        val sensor = (0 until target.interfaceCount).map(target::getInterface).firstOrNull { it.id == 4 } ?: return
        if (!usb.claimInterface(sensor, true)) return
        val input = (0 until sensor.endpointCount).map(sensor::getEndpoint)
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return
        val report = ByteArray(64)
        val bias = FloatArray(3); var biasSamples = 0; var calibrated = false
        var yaw = 0f; var pitch = 0f; var pitchZero = 0f; var yawZero = 0f; var lastTick = -1
        var lastSent = 0L
        var buttons = 0
        var muteDownAt = 0L
        // camera model: where the game camera points (same frame as the head), and its recent history
        var camYaw = 0f; var camPitch = 0f; var lastHy = 0f; var lastHp = 0f; var wasAiming = true
        var behind = 0f   // 0 or pi: which half of the sphere the head looks around
        var offYaw = 0f; var offPitch = 0f   // the player's stick corrections, kept on top of the head
        val histYaw = FloatArray(512); val histPitch = FloatArray(512); var histPos = 0
        // measuring the real camera response from the stream's motion vectors
        val panBuf = FloatArray(3); var panFrames = -1f; var panX = 0f
        val frameDm = FloatArray(64); val framePos = IntArray(64); var frameN = 0; var learnedAt = 0L
        var camSpeed = prefs.getFloat("cam_speed", 4.0f); var delayMs = prefs.getInt("cam_delay_ms", 80)
        var prefsReadAt = 0L
        // stick deflection for a fraction of full camera speed: GTA ignores small deflections
        fun shape(v: Float) = if (kotlin.math.abs(v) < 0.005f) 0f
            else kotlin.math.sign(v) * (0.15f + 0.85f * kotlin.math.abs(v).coerceAtMost(1f))
        fun s16(i: Int) = ((report[i].toInt() and 0xff) or (report[i + 1].toInt() shl 8)).toShort().toFloat()
        fun u24(i: Int) = (report[i].toInt() and 0xff) or ((report[i + 1].toInt() and 0xff) shl 8) or
            ((report[i + 2].toInt() and 0xff) shl 16)
        head(0f, 0f, screenSize)
        try {
            while (started && attempt == generation) {
                if (usb.bulkTransfer(input, report, report.size, 200) != 64) continue
                val now = report[0].toInt() and 0xff
                val pressed = now and buttons.inv(); val released = buttons and now.inv(); buttons = now
                if (pressed and 8 != 0) muteDownAt = SystemClock.uptimeMillis()                       // mute
                if (released and 8 != 0) {
                    if (SystemClock.uptimeMillis() - muteDownAt >= 1000) {
                        lookFlip = (lookFlip + 1) % 4
                        prefs.edit().putInt("look_flip", lookFlip).apply()
                        toast("Look direction: ${lookFlipNames[lookFlip]}")
                    } else levelRequested = true
                }
                if (pressed and 6 != 0) {                                                             // volume
                    screenSize = (screenSize * if (pressed and 2 != 0) 1.1f else 1f / 1.1f).coerceIn(0.8f, 2.5f)
                    prefs.edit().putFloat("screen_size", screenSize).apply()
                    toast("Screen size ${"%.2f".format(java.util.Locale.US, screenSize)}")
                }
                for (off in intArrayOf(16, 32)) {
                    // raw order (y, x, z); x right, y up, z back (Monado/OpenHMD); 0.00105 rad/s, 1/16384 g per LSB
                    val gx = s16(off + 6) * 0.00105f; val gy = s16(off + 4) * 0.00105f; val gz = -s16(off + 8) * 0.00105f
                    val ax = s16(off + 12) / 16384f; val ay = s16(off + 10) / 16384f; val az = -s16(off + 14) / 16384f
                    val tick = u24(off)
                    var dt = if (lastTick < 0) 0f else (((tick - lastTick) and 0xffffff) * 1e-6f)
                    lastTick = tick
                    if (dt <= 0f || dt > 0.005f) dt = 0.0005f                   // packet loss or rollover
                    val spin = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
                    val g = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    val still = spin < 0.05f && kotlin.math.abs(g - 1f) < 0.05f
                    // looking up tilts gravity toward -z: pitch from the accelerometer
                    val tilt = kotlin.math.atan2(-az, ay)
                    if (!calibrated) {                                          // learn the gyro's resting offset
                        pitch = tilt; pitchZero = tilt
                        if (still) {
                            biasSamples++
                            bias[0] += (gx - bias[0]) / biasSamples; bias[1] += (gy - bias[1]) / biasSamples
                            if (biasSamples >= 1000) calibrated = true
                        } else biasSamples = 0
                        continue
                    }
                    if (still) { bias[0] += (gx - bias[0]) * 0.0005f; bias[1] += (gy - bias[1]) * 0.0005f }
                    yaw += (gy - bias[1]) * dt                                  // the screen stays put; mute recenters
                    pitch += (gx - bias[0]) * dt
                    if (kotlin.math.abs(g - 1f) < 0.2f) pitch += (tilt - pitch) * 0.002f
                }
                val t = SystemClock.uptimeMillis()
                if (calibrated && t - lastSent >= 4) {
                    val dtc = (t - lastSent).coerceAtMost(50L) / 1000f
                    lastSent = t
                    if (t - prefsReadAt > 500) {                                  // tuning from the Align panel
                        prefsReadAt = t
                        if (t - learnedAt > 3000) {                               // not being measured: the panel's values
                            camSpeed = prefs.getFloat("cam_speed", 4.0f); delayMs = prefs.getInt("cam_delay_ms", 80)
                        }
                        screenSize = prefs.getFloat("screen_size", 0.85f)
                    }
                    val sx = if (lookFlip and 1 != 0) -1f else 1f
                    val sp = if (lookFlip and 2 != 0) -1f else 1f
                    if (lookBehindRequested) {
                        lookBehindRequested = false
                        behind = if (behind == 0f) kotlin.math.PI.toFloat() else 0f
                        toast(if (behind == 0f) "Looking ahead" else "Looking behind")
                    }
                    if (levelRequested) {                                         // level the view where the head is now
                        levelRequested = false
                        val d = sp * (pitch - pitchZero)
                        pitchZero = pitch; lastHp -= d; offPitch += d; yawZero = sx * yaw
                        toast("View leveled")
                    }
                    val headY = sx * yaw + behind; val headP = sp * (pitch - pitchZero)
                    // how fast the game turns its camera for the player's own stick (it ignores small deflections)
                    fun rate(v: Float) = if (kotlin.math.abs(v) <= 0.15f) 0f
                        else kotlin.math.sign(v) * (kotlin.math.abs(v) - 0.15f) / 0.85f * camSpeed
                    val uy = -rate(userX); val up = -rate(userY)                    // stick right/up = camera right/up
                    if (aiming) {                                                 // the stick has the crosshair
                        wasAiming = true
                        camYaw += uy * dtc; camPitch = (camPitch + up * dtc).coerceIn(-1.3f, 1.3f)
                        lastHy = headY; lastHp = headP
                        stick(0f, 0f); head(0f, 0f, screenSize); body(sx * yaw - yawZero, headP)
                        histPos = (histPos + 1) % histYaw.size
                        histYaw[histPos] = camYaw; histPitch[histPos] = camPitch
                        continue
                    }
                    if (wasAiming) {                                              // carry on from where the camera points
                        wasAiming = false
                        offYaw = camYaw - headY; offPitch = camPitch - headP; lastHy = headY; lastHp = headP
                    }
                    offYaw += uy * dtc; offPitch += up * dtc                      // stick nudges stay
                    val hy = headY + offYaw; val hp = headP + offPitch
                    // head turn rate, plus catching up whatever the game hasn't turned yet
                    val wy = ((headY - lastHy) / dtc + (hy - camYaw) * 6f).coerceIn(-camSpeed, camSpeed)
                    val wp = ((headP - lastHp) / dtc + (hp - camPitch) * 6f).coerceIn(-camSpeed, camSpeed)
                    lastHy = headY; lastHp = headP
                    camYaw += (wy + uy) * dtc; camPitch = (camPitch + (wp + up) * dtc).coerceIn(-1.3f, 1.3f)
                    // looking left = stick left (negative); looking up = stick up (negative); added to the player's stick
                    stick(shape(-wy / camSpeed), shape(-wp / camSpeed))
                    // the stream shows the camera as it was [delayMs] ago: shift the picture by what is still missing
                    histPos = (histPos + 1) % histYaw.size
                    histYaw[histPos] = camYaw; histPitch[histPos] = camPitch
                    // measure: each new frame's whole-picture shift (displayed radians, + = camera turned left) against
                    // the turns sent; the lag where they match best is the delay, their ratio corrects the speed
                    if (pan(panBuf) && panBuf[2] != panFrames) {
                        val dm = if (panFrames < 0f) 0f else (panBuf[0] - panX) * 1.5708f * screenSize / 1920f
                        panX = panBuf[0]; panFrames = panBuf[2]
                        val k = frameN % frameDm.size; frameDm[k] = dm; framePos[k] = histPos; frameN++
                        val n = kotlin.math.min(frameN, frameDm.size) - 1
                        fun sent(f: Int, lag: Int): Float {                       // turn sent between two frames, lag ticks earlier
                            val a = framePos[(f + frameDm.size) % frameDm.size]; val b = framePos[(f - 1 + 2 * frameDm.size) % frameDm.size]
                            val sz = histYaw.size
                            return histYaw[((a - lag) % sz + sz) % sz] - histYaw[((b - lag) % sz + sz) % sz]
                        }
                        var energy = 0f
                        for (i in 0 until n) { val v = sent(k - i, delayMs / 4); energy += v * v }
                        if (n >= 30 && energy > 0.02f) {                           // enough turning to learn from
                            var bestLag = delayMs / 4; var best = -1e9f
                            for (lag in 5..60) {                                   // 20..240 ms
                                var c = 0f
                                for (i in 0 until n) c += frameDm[(k - i + frameDm.size) % frameDm.size] * sent(k - i, lag)
                                if (c > best) { best = c; bestLag = lag }
                            }
                            var num = 0f; var den = 0f
                            for (i in 0 until n) {
                                val s = sent(k - i, bestLag)
                                num += frameDm[(k - i + frameDm.size) % frameDm.size] * s; den += s * s
                            }
                            if (best > 0f && den > 0.01f) {
                                val ratio = (num / den).coerceIn(0.5f, 2f)       // >1: the game turns faster than assumed
                                camSpeed = (camSpeed * (1f + 0.1f * (ratio - 1f))).coerceIn(0.5f, 20f)
                                delayMs = (delayMs + (bestLag * 4 - delayMs) / 5).coerceIn(20, 240)
                                learnedAt = t
                                if (frameN % 120 == 0) prefs.edit().putFloat("cam_speed", camSpeed)
                                    .putInt("cam_delay_ms", delayMs).apply()
                            }
                        }
                    }
                    val back = (delayMs / 4).coerceIn(0, histYaw.size - 1)
                    val then = (histPos - back + histYaw.size) % histYaw.size
                    // a big catch-up (the 180-degree turn) is shown as the stream has it, not shifted
                    val dy = hy - histYaw[then]; val dp = hp - histPitch[then]
                    head(if (kotlin.math.abs(dy) > 0.4f) 0f else dy, if (kotlin.math.abs(dp) > 0.4f) 0f else dp, screenSize)
                    body(sx * yaw - yawZero, headP)
                }
            }
        } finally {
            usb.releaseInterface(sensor)
        }
    }

    private fun enterVr(usb: UsbDeviceConnection, target: UsbDevice): Boolean {
        val control = (0 until target.interfaceCount).map(target::getInterface)
            .firstOrNull { it.id == 5 } ?: return false
        if (!usb.claimInterface(control, true)) return false
        try {
            val endpoints = (0 until control.endpointCount).map(control::getEndpoint)
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return false
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return false
            // The headset reports its state only when the state changes (plus the reports queued
            // since the interface was claimed), so read those first: when it is already powered and
            // in VR mode, a command is only acknowledged (0xA0) and no new report would ever come.
            val state = Status()
            read(usb, input, state, 300)
            if (!state.power && !command(usb, input, output, 0x17, state) { it.power }) return false
            if (!state.vr && !command(usb, input, output, 0x23, state) { it.vr }) return false
            return state.power && state.vr
        } finally {
            usb.releaseInterface(control)
        }
    }

    /** Last 0xF0 status report: byte 4 bit 0 = power, byte 11 = 1 in VR mode. */
    private class Status { var power = false; var vr = false; var seen = false }

    /** Reads reports for [ms] (or until [until] holds), keeping the newest state in [state]. */
    private fun read(usb: UsbDeviceConnection, input: UsbEndpoint, state: Status, ms: Long,
                     until: (Status) -> Boolean = { false }): Boolean {
        val report = ByteArray(64)
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            val n = usb.bulkTransfer(input, report, report.size, 200)
            if (n == 20 && report[0] == 0xf0.toByte()) {
                state.power = (report[4].toInt() and 1) == 1
                state.vr = report[11].toInt() == 1
                state.seen = true
                if (until(state)) return true
            }
        }
        return until(state)
    }

    private fun command(usb: UsbDeviceConnection, input: UsbEndpoint, output: UsbEndpoint,
                        opcode: Int, state: Status, done: (Status) -> Boolean): Boolean {
        val packet = byteArrayOf(opcode.toByte(), 0, 0xaa.toByte(), 4, 1, 0, 0, 0)
        repeat(3) {
            if (usb.bulkTransfer(output, packet, packet.size, 1000) == packet.size &&
                read(usb, input, state, 2000, done)) return true
        }
        return false
    }
}
