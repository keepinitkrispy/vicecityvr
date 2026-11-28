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
 * A streaming activity's USB link to the original PSVR processor unit: the VR-mode handshake, then
 * head-look. Head rotation speed from the headset's gyro becomes right-stick camera input ([headLook],
 * x and y in -1..1, stick up negative), so turning your head turns the first-person camera.
 * Headset cable remote: mute = head-look on/off, volume up/down = head-look speed.
 */
internal class PsvrUsb(private val context: Context, private val changed: () -> Unit,
                       private val headLook: (Float, Float) -> Unit = { _, _ -> }) {
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
                headLook(0f, 0f)
                usb?.close()
            }
        }, "psvr-usb").start()
    }

    // ---- head-look
    private val prefs = context.getSharedPreferences("psvr_live", Context.MODE_PRIVATE)
    @Volatile private var headEnabled = prefs.getBoolean("head_look", true)
    @Volatile private var headSpeed = prefs.getFloat("head_speed", 1f)

    private fun toast(text: String) = main.post {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Stick deflection for a head rotation rate (rad/s): small rates are noise, then it starts at the
     *  game's own stick dead zone so slow head turns still move the camera. */
    private fun stick(rate: Float): Float {
        val a = kotlin.math.abs(rate)
        if (a < 0.06f) return 0f
        val t = ((a - 0.06f) * headSpeed / 2.5f).coerceAtMost(1f)
        return kotlin.math.sign(rate) * (0.2f + 0.8f * t)
    }

    /** Reads the headset's sensor interface until this link is replaced or stopped. */
    private fun headLoop(usb: UsbDeviceConnection, target: UsbDevice, attempt: Int) {
        val sensor = (0 until target.interfaceCount).map(target::getInterface).firstOrNull { it.id == 4 } ?: return
        if (!usb.claimInterface(sensor, true)) return
        val input = (0 until sensor.endpointCount).map(sensor::getEndpoint)
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return
        val report = ByteArray(64)
        val bias = FloatArray(3); var biasSamples = 0; var calibrated = false
        var yawSum = 0f; var pitchSum = 0f; var samples = 0
        var lastSent = SystemClock.uptimeMillis()
        var buttons = 0
        fun s16(i: Int) = ((report[i].toInt() and 0xff) or (report[i + 1].toInt() shl 8)).toShort().toFloat()
        try {
            while (started && attempt == generation) {
                if (usb.bulkTransfer(input, report, report.size, 200) != 64) continue
                val now = report[0].toInt() and 0xff
                val pressed = now and buttons.inv(); buttons = now
                if (pressed and 8 != 0) {                                   // mute: on/off
                    headEnabled = !headEnabled
                    prefs.edit().putBoolean("head_look", headEnabled).apply()
                    toast(if (headEnabled) "Head-look on" else "Head-look off")
                    if (!headEnabled) headLook(0f, 0f)
                }
                if (pressed and 6 != 0) {                                   // volume: speed
                    headSpeed = (headSpeed * if (pressed and 2 != 0) 1.25f else 0.8f).coerceIn(0.25f, 4f)
                    prefs.edit().putFloat("head_speed", headSpeed).apply()
                    toast("Head-look speed ${"%.2f".format(java.util.Locale.US, headSpeed)}")
                }
                for (off in intArrayOf(16, 32)) {
                    // raw order (y, x, z); x right, y up, z back (Monado/OpenHMD); 0.00105 rad/s, 1/16384 g per LSB
                    val gx = s16(off + 6) * 0.00105f; val gy = s16(off + 4) * 0.00105f; val gz = -s16(off + 8) * 0.00105f
                    val ax = s16(off + 12) / 16384f; val ay = s16(off + 10) / 16384f; val az = -s16(off + 14) / 16384f
                    val spin = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
                    val g = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    val still = spin < 0.05f && kotlin.math.abs(g - 1f) < 0.05f
                    if (!calibrated) {                                      // learn the gyro's resting offset
                        if (still) {
                            biasSamples++
                            bias[0] += (gx - bias[0]) / biasSamples; bias[1] += (gy - bias[1]) / biasSamples
                            bias[2] += (gz - bias[2]) / biasSamples
                            if (biasSamples >= 1000) { calibrated = true; toast("Head-look ready") }
                        } else biasSamples = 0
                        continue
                    }
                    if (still) { bias[0] += (gx - bias[0]) * 0.0005f; bias[1] += (gy - bias[1]) * 0.0005f }
                    pitchSum += gx - bias[0]; yawSum += gy - bias[1]; samples++
                }
                val t = SystemClock.uptimeMillis()
                if (t - lastSent >= 8 && samples > 0) {
                    lastSent = t
                    // looking left = positive yaw = stick left (negative); looking up = positive pitch = stick up (negative)
                    if (headEnabled) headLook(-stick(yawSum / samples), -stick(pitchSum / samples))
                    yawSum = 0f; pitchSum = 0f; samples = 0
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
