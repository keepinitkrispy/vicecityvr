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

/** A streaming activity's USB handshake with the original PSVR processor unit. */
internal class PsvrUsb(private val context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val permission = context.packageName + ".PSVR_USB_PERMISSION"
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
            val granted = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (started && granted != null && granted == device && manager?.hasPermission(granted) == true)
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
            val confirmed = runCatching { enterVr(target) }.getOrDefault(false)
            main.post {
                if (started && attempt == generation && target == device) {
                    ready = confirmed
                    changed()
                    if (!confirmed) android.widget.Toast.makeText(context,
                        "PSVR did not confirm VR mode. Reconnect the processor box USB to retry.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "psvr-usb").start()
    }

    private fun enterVr(target: UsbDevice): Boolean {
        val usb = manager?.openDevice(target) ?: return false
        try {
            val control = (0 until target.interfaceCount).map(target::getInterface)
                .firstOrNull { it.id == 5 } ?: return false
            if (!usb.claimInterface(control, true)) return false
            try {
                val endpoints = (0 until control.endpointCount).map(control::getEndpoint)
                val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return false
                val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return false
                return command(usb, input, output, 0x17, 4) &&
                    command(usb, input, output, 0x23, 11)
            } finally {
                usb.releaseInterface(control)
            }
        } finally {
            usb.close()
        }
    }

    private fun command(usb: UsbDeviceConnection, input: UsbEndpoint, output: UsbEndpoint,
                        opcode: Int, statusOffset: Int): Boolean {
        val packet = byteArrayOf(opcode.toByte(), 0, 0xaa.toByte(), 4, 1, 0, 0, 0)
        val status = ByteArray(64)
        repeat(3) {
            if (usb.bulkTransfer(output, packet, packet.size, 1000) == packet.size) {
                val deadline = SystemClock.uptimeMillis() + 2000
                while (SystemClock.uptimeMillis() < deadline) {
                    if (usb.bulkTransfer(input, status, status.size, 200) == 20 &&
                        status[0] == 0xf0.toByte() &&
                        (if (statusOffset == 4) status[4].toInt() and 1 else status[11].toInt()) == 1)
                        return true
                }
            }
        }
        return false
    }
}
