package vcvr.bridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import java.util.Locale;

// PSVR processor unit control over USB (interface 5): power on + VR mode, each confirmed by the 0xF0 status report.
// Same protocol as native/psvrctl.c and the alignment app, which both switched this headset into VR mode on the Pixel.
final class Psvr {
    static final int SONY = 0x054c, PSVR = 0x09af, CONTROL_IFACE = 5;

    static UsbDevice find(UsbManager um) {
        for (UsbDevice d : um.getDeviceList().values())
            if (d.getVendorId() == SONY && d.getProductId() == PSVR) return d;
        return null;
    }

    // vr=true: VR mode (each eye gets its own half of the 1920x1080 picture). vr=false: cinematic (flat screen).
    static String setMode(UsbManager um, UsbDevice d, boolean vr) {
        UsbDeviceConnection c = um.openDevice(d);
        if (c == null) return "headset: could not open USB";
        try {
            UsbInterface ctl = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getId() == CONTROL_IFACE) ctl = d.getInterface(i);
            if (ctl == null || !c.claimInterface(ctl, true)) return "headset: control interface unavailable";
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < ctl.getEndpointCount(); i++) {
                UsbEndpoint e = ctl.getEndpoint(i);
                if (e.getDirection() == UsbConstants.USB_DIR_IN) { if (in == null) in = e; } else if (out == null) out = e;
            }
            if (in == null || out == null) return "headset: endpoints missing";
            boolean ok = setAndWait(c, in, out, 0x17, 1, true) && setAndWait(c, in, out, 0x23, vr ? 1 : 0, false);
            int[] s = readStatus(c, in, 1500);
            c.releaseInterface(ctl);
            if (s == null) return "headset: no status report";
            boolean confirmed = ok && s[1] == (vr ? 1 : 0);
            return String.format(Locale.US, "headset: %s %s (power %d, worn %d)", vr ? "VR mode" : "cinematic mode",
                confirmed ? "CONFIRMED" : "NOT confirmed", s[0], s[2]);
        } finally { c.close(); }
    }

    static boolean setAndWait(UsbDeviceConnection c, UsbEndpoint in, UsbEndpoint out, int cmd, int val, boolean power) {
        byte[] b = {(byte) cmd, 0, (byte) 0xAA, 4, (byte) val, 0, 0, 0};
        for (int attempt = 0; attempt < 3; attempt++) {
            if (c.bulkTransfer(out, b, 8, 1000) != 8) continue;
            long t0 = SystemClock.uptimeMillis();
            while (SystemClock.uptimeMillis() - t0 < 2000) {
                int[] s = readStatus(c, in, 300);
                if (s != null && (power ? s[0] : s[1]) == val) return true;
            }
        }
        return false;
    }

    // {power, vr, worn} from a 20-byte 0xF0 status report, or null
    static int[] readStatus(UsbDeviceConnection c, UsbEndpoint in, int timeoutMs) {
        byte[] b = new byte[64]; long t0 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t0 < timeoutMs) {
            int n = c.bulkTransfer(in, b, 64, 200);
            if (n == 20 && (b[0] & 0xff) == 0xF0) return new int[]{b[4] & 1, b[11] == 1 ? 1 : 0, (b[4] >> 1) & 1};
        }
        return null;
    }
}
