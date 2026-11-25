// psvrctl - PSVR1 processor-unit control over a usbfs file descriptor (Termux:API termux-usb).
//
// Usage (Termux):
//   termux-usb -r -e "./psvrctl vr" /dev/bus/usb/001/00N      # power on + VR mode, verified by status report
//   termux-usb -r -e "./psvrctl cinematic" ...                 # back to cinematic (2D big-screen) mode
//   termux-usb -r -e "./psvrctl off" ...                       # VR mode off + headset power off
//   termux-usb -r -e "./psvrctl status" ...                    # print one status report
// termux-usb appends the usbfs fd as the LAST argument.
//
// Protocol (cross-checked against Monado src/xrt/drivers/psvr and OpenHMD drv_psvr):
//   control interface 5, 8-byte commands  [cmd, 0x00, 0xAA, len=4, value, 0,0,0]
//     0x17 = headset power (1 on / 0 off)      0x23 = VR mode (1 VR / 0 cinematic)
//   status report on the same interface: 20 bytes, byte0 = 0xF0
//     byte4 = status bits (bit0 power, bit1 worn, bit2 cinematic), byte11 = vr_mode (1 on)
// Endpoints are read from the device's own descriptors, not hard-coded.
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

#define CONTROL_IFACE 5

static int ep_in = -1, ep_out = -1;

static int find_endpoints(int fd) {
    unsigned char d[4096];
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    ssize_t n = read(fd, d, sizeof d);           // usbfs: device descriptor followed by config descriptors
    if (n < 18) return -1;
    int iface = -1;
    for (ssize_t i = 0; i + 2 <= n && d[i] >= 2; i += d[i]) {
        if (d[i + 1] == 4 && d[i] >= 9) iface = d[i + 2];                 // interface descriptor
        else if (d[i + 1] == 5 && d[i] >= 7 && iface == CONTROL_IFACE) {  // endpoint descriptor
            int addr = d[i + 2];
            if (addr & 0x80) { if (ep_in < 0) ep_in = addr; } else if (ep_out < 0) ep_out = addr;
        }
    }
    return (ep_in >= 0 && ep_out >= 0) ? 0 : -1;
}

static int claim(int fd) {
    unsigned int ifn = CONTROL_IFACE;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifn) == 0) return 0;
    struct usbdevfs_disconnect_claim dc = { .interface = CONTROL_IFACE, .flags = 0 };
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) == 0) return 0;        // detach usbhid (any driver) and claim
    perror("claim interface 5");
    return -1;
}

static int xfer(int fd, int ep, unsigned char *buf, int len, int timeout_ms) {
    struct usbdevfs_bulktransfer t = { .ep = (unsigned)ep, .len = (unsigned)len, .timeout = (unsigned)timeout_ms, .data = buf };
    return ioctl(fd, USBDEVFS_BULK, &t);           // the kernel routes this to interrupt endpoints too
}

static int send_cmd(int fd, uint8_t cmd, uint8_t value) {
    unsigned char b[8] = { cmd, 0x00, 0xAA, 0x04, value, 0, 0, 0 };
    int n = xfer(fd, ep_out, b, 8, 1000);
    if (n != 8) { fprintf(stderr, "send 0x%02x=%u failed: %s\n", cmd, value, n < 0 ? strerror(errno) : "short write"); return -1; }
    return 0;
}

typedef struct { int ok, power, worn, cinematic, vr; } status_t;

static status_t read_status(int fd, int timeout_ms) {
    status_t s = {0};
    struct timespec t0; clock_gettime(CLOCK_MONOTONIC, &t0);
    for (;;) {
        unsigned char b[64]; int n = xfer(fd, ep_in, b, sizeof b, 200);
        if (n == 20 && b[0] == 0xF0) {
            s.ok = 1; s.power = b[4] & 1; s.worn = (b[4] >> 1) & 1; s.cinematic = (b[4] >> 2) & 1; s.vr = b[11] == 1;
            return s;
        }
        struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
        if ((t.tv_sec - t0.tv_sec) * 1000 + (t.tv_nsec - t0.tv_nsec) / 1000000 > timeout_ms) return s;
    }
}

// send, then poll status until the wanted state shows up (Monado's wait_for_* does the same)
static int set_and_wait(int fd, uint8_t cmd, uint8_t value, int want_power, int want_vr) {
    for (int attempt = 0; attempt < 3; attempt++) {
        if (send_cmd(fd, cmd, value) < 0) return -1;
        for (int i = 0; i < 10; i++) {
            status_t s = read_status(fd, 300);
            if (!s.ok) continue;
            if ((want_power < 0 || s.power == want_power) && (want_vr < 0 || s.vr == want_vr)) return 0;
        }
    }
    fprintf(stderr, "headset did not confirm 0x%02x=%u\n", cmd, value);
    return -1;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: psvrctl vr|cinematic|off|status <usbfs-fd>\n"); return 1; }
    const char *mode = argv[1];
    int fd = atoi(argv[argc - 1]);
    if (find_endpoints(fd) < 0) { fprintf(stderr, "PSVR control interface 5 not found on this device (is the processor unit's USB plugged in, not the HDMI only?)\n"); return 2; }
    if (claim(fd) < 0) return 2;
    int rc = 0;
    if (!strcmp(mode, "vr")) {
        rc = set_and_wait(fd, 0x17, 1, 1, -1);
        if (!rc) rc = set_and_wait(fd, 0x23, 1, -1, 1);
    } else if (!strcmp(mode, "cinematic")) {
        rc = set_and_wait(fd, 0x23, 0, -1, 0);
    } else if (!strcmp(mode, "off")) {
        rc = set_and_wait(fd, 0x23, 0, -1, 0);
        if (!rc) rc = set_and_wait(fd, 0x17, 0, 0, -1);
    } else if (strcmp(mode, "status")) { fprintf(stderr, "unknown mode %s\n", mode); rc = 1; }
    status_t s = read_status(fd, 1500);
    if (s.ok) printf("{\"power\":%d,\"worn\":%d,\"cinematic\":%d,\"vr\":%d,\"ok\":%s}\n", s.power, s.worn, s.cinematic, s.vr, rc ? "false" : "true");
    else { printf("{\"ok\":false,\"error\":\"no status report\"}\n"); if (!rc) rc = 3; }
    unsigned int ifn = CONTROL_IFACE; ioctl(fd, USBDEVFS_RELEASEINTERFACE, &ifn);
    return rc ? 3 : 0;
}
