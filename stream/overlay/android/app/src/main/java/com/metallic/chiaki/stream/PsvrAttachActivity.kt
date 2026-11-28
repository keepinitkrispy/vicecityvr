// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Invisible: Android starts it when the PSVR processor unit is plugged in. Being started this way grants
 * the app USB access to the unit (and with "always" chosen, without asking again), which it hands to a
 * running stream's PsvrUsb. It never comes to the front, so a running stream keeps playing.
 */
class PsvrAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null)
            sendBroadcast(Intent(PsvrUsb.permissionAction(this)).setPackage(packageName)
                .putExtra(UsbManager.EXTRA_DEVICE, device))
        finish()
    }
}
