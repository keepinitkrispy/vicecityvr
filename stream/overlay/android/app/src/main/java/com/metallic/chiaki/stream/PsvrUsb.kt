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
 */
internal class PsvrUsb(private val context: Context, private val changed: () -> Unit,
                       private val head: (Float, Float, Float) -> Unit = { _, _, _ -> },
                       private val stick: (Float, Float) -> Unit = { _, _ -> }) {
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
    @Volatile var screenSize = prefs.getFloat("screen_size", 0.95f)  // the window: nearly all of the view
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
        var yaw = 0f; var pitch = 0f; var pitchZero = 0f; var lastTick = -1
        var lastSent = 0L
        var buttons = 0
        var muteDownAt = 0L
        // camera model: where the game camera points (same frame as the head), and its recent history
        var camYaw = 0f; var camPitch = 0f; var lastHy = 0f; var lastHp = 0f; var wasAiming = true
        var behind = 0f   // 0 or pi: which half of the sphere the head looks around
        var offYaw = 0f; var offPitch = 0f   // the player's stick corrections, kept on top of the head
        val histYaw = FloatArray(128); val histPitch = FloatArray(128); var histPos = 0
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
                        camSpeed = prefs.getFloat("cam_speed", 4.0f); delayMs = prefs.getInt("cam_delay_ms", 80)
                        screenSize = prefs.getFloat("screen_size", 0.95f)
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
                        pitchZero = pitch; lastHp -= d; offPitch += d
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
                        stick(0f, 0f); head(0f, 0f, screenSize)
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
                    val back = (delayMs / 4).coerceIn(0, histYaw.size - 1)
                    val then = (histPos - back + histYaw.size) % histYaw.size
                    // a big catch-up (the 180-degree turn) is shown as the stream has it, not shifted
                    val dy = hy - histYaw[then]; val dp = hp - histPitch[then]
                    head(if (kotlin.math.abs(dy) > 0.4f) 0f else dy, if (kotlin.math.abs(dp) > 0.4f) 0f else dp, screenSize)
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
