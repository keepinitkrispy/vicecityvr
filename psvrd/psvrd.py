#!/usr/bin/env python3
"""psvrd - PSVR1 head tracking + control daemon for Termux, standard library only.

Serves the stereo renderer on http://127.0.0.1:8765 and streams head pose over a WebSocket.

  termux-usb -r -e "python3 psvrd.py --vr" /dev/bus/usb/001/00N    # real headset
  python3 psvrd.py --fake                                          # no headset: synthetic head motion

termux-usb passes the usbfs file descriptor as the last argument.

Protocol sources (cross-checked): Monado src/xrt/drivers/psvr, OpenHMD src/drv_psvr.
  interface 4 = IMU sensor reports (64 bytes, 2 samples each, ~1 kHz sample rate)
  interface 5 = control: 8-byte commands out, 20-byte 0xF0 status reports in
"""
import argparse, base64, ctypes, hashlib, json, math, os, socket, struct, sys, threading, time
import urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RENDERER = os.path.join(ROOT, 'renderer')
STATE = os.path.expanduser('~/.config/psvr-control')
SENSOR_IFACE, CONTROL_IFACE = 4, 5
BTN_VOL_UP, BTN_VOL_DOWN, BTN_MUTE = 2, 4, 8
GYRO_SCALE = 0.00105            # rad/s per LSB (Monado, OpenHMD)
ACCEL_SCALE = 1.0 / 16384.0     # g per LSB
TICK_S = 1e-6                   # sensor tick = 1 microsecond, 24-bit counter

def log(*a):
    print(time.strftime('%H:%M:%S'), *a, file=sys.stderr, flush=True)

# ----------------------------------------------------------------------------- usbfs
USBDEVFS_BULK, USBDEVFS_CLAIM, USBDEVFS_RELEASE, USBDEVFS_DISC_CLAIM = 0xC0185502, 0x8004550F, 0x80045510, 0x8108551B

class Bulk(ctypes.Structure):
    _fields_ = [('ep', ctypes.c_uint), ('len', ctypes.c_uint), ('timeout', ctypes.c_uint), ('data', ctypes.c_void_p)]

class DiscClaim(ctypes.Structure):
    _fields_ = [('interface', ctypes.c_uint), ('flags', ctypes.c_uint), ('driver', ctypes.c_char * 256)]

def parse_endpoints(desc):
    """{interface: {'in': addr, 'out': addr}} from raw usbfs descriptor bytes."""
    eps, iface, i = {}, None, 0
    while i + 2 <= len(desc) and desc[i] >= 2:
        ln, typ = desc[i], desc[i + 1]
        if typ == 4 and ln >= 9:
            iface = desc[i + 2]; eps.setdefault(iface, {})
        elif typ == 5 and ln >= 7 and iface is not None:
            addr = desc[i + 2]; eps[iface].setdefault('in' if addr & 0x80 else 'out', addr)
        i += ln
    return eps

class USB:
    def __init__(self, fd):
        self.fd = fd
        self.libc = ctypes.CDLL(None, use_errno=True)
        os.lseek(fd, 0, os.SEEK_SET)
        self.eps = parse_endpoints(os.read(fd, 4096))
        for need in (SENSOR_IFACE, CONTROL_IFACE):
            if 'in' not in self.eps.get(need, {}):
                raise RuntimeError(f'interface {need} missing: is this the PSVR processor unit? found {self.eps}')
        for n in (SENSOR_IFACE, CONTROL_IFACE):
            self.claim(n)
        self.wlock = threading.Lock()

    def ioctl(self, req, arg):
        r = self.libc.ioctl(self.fd, ctypes.c_ulong(req), arg)
        return r, ctypes.get_errno()

    def claim(self, n):
        r, _ = self.ioctl(USBDEVFS_CLAIM, ctypes.byref(ctypes.c_uint(n)))
        if r == 0: return
        dc = DiscClaim(interface=n, flags=0)           # detach whatever kernel driver (usbhid) holds it
        r, e = self.ioctl(USBDEVFS_DISC_CLAIM, ctypes.byref(dc))
        if r != 0: raise OSError(e, f'claim interface {n}: {os.strerror(e)}')

    def transfer(self, ep, buf, timeout_ms):
        b = Bulk(ep=ep, len=len(buf), timeout=timeout_ms, data=ctypes.cast(buf, ctypes.c_void_p))
        r, e = self.ioctl(USBDEVFS_BULK, ctypes.byref(b))
        if r < 0:
            if e in (110, 62): return b''                  # ETIMEDOUT / ETIME
            raise OSError(e, os.strerror(e))
        return bytes(buf[:r])

    def read(self, iface, size=64, timeout_ms=200):
        return self.transfer(self.eps[iface]['in'], ctypes.create_string_buffer(size), timeout_ms)

    def send(self, cmd, value):
        data = ctypes.create_string_buffer(bytes([cmd, 0, 0xAA, 4, value, 0, 0, 0]), 8)
        with self.wlock:
            return len(self.transfer(self.eps[CONTROL_IFACE]['out'], data, 1000)) == 8

    def close(self):
        for n in (SENSOR_IFACE, CONTROL_IFACE):
            self.ioctl(USBDEVFS_RELEASE, ctypes.byref(ctypes.c_uint(n)))

# ----------------------------------------------------------------------------- packets
def parse_sensor(p):
    """64-byte sensor report -> (buttons, [(tick, gyro(3), accel(3))], proximity) or None."""
    if len(p) != 64: return None
    buttons = p[0]
    samples = []
    for off in (16, 32):
        tick = struct.unpack_from('<I', p, off)[0] & 0xFFFFFF
        g = struct.unpack_from('<3h', p, off + 4)
        a = struct.unpack_from('<3h', p, off + 10)
        # raw order is (y, x, z); Monado/OpenHMD: x = raw[1], y = raw[0], z = -raw[2]
        gyro = (g[1] * GYRO_SCALE, g[0] * GYRO_SCALE, -g[2] * GYRO_SCALE)
        accel = (a[1] * ACCEL_SCALE, a[0] * ACCEL_SCALE, -a[2] * ACCEL_SCALE)
        samples.append((tick, gyro, accel))
    prox = struct.unpack_from('<H', p, 55)[0]
    return buttons, samples, prox

def parse_status(p):
    if len(p) != 20 or p[0] != 0xF0: return None
    return {'power': p[4] & 1, 'worn': (p[4] >> 1) & 1, 'cinematic': (p[4] >> 2) & 1,
            'volume': p[5], 'vr': int(p[11] == 1)}

# ----------------------------------------------------------------------------- fusion
def qmul(a, b):
    return (a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3], a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
            a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1], a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0])

def qnorm(q):
    n = math.sqrt(sum(c * c for c in q)) or 1.0
    return tuple(c / n for c in q)

def qrot(q, v):  # rotate vector v (body) into world
    p = qmul(qmul(q, (0.0, *v)), (q[0], -q[1], -q[2], -q[3]))
    return p[1:]

class Fusion:
    """Gyro integration + accelerometer tilt correction (Mahony-style), gyro bias learned while still.
    Yaw has no absolute reference on PSVR1 (no magnetometer): it drifts slowly; recenter fixes it."""
    def __init__(self):
        self.reset()

    def reset(self):
        self.q = (1.0, 0.0, 0.0, 0.0); self.w = (0.0, 0.0, 0.0)
        self.bias = [0.0, 0.0, 0.0]; self.bias_n = 0; self.calibrated = False
        self.last_tick = None; self.still_t = 0.0

    def feed(self, tick, gyro, accel):
        if self.last_tick is None:
            self.last_tick = tick; return
        dt_ticks = (tick - self.last_tick) & 0xFFFFFF
        self.last_tick = tick
        if not 200 <= dt_ticks <= 5000: dt_ticks = 500            # packet loss or rollover glitch
        dt = dt_ticks * TICK_S
        gn = math.sqrt(sum(c * c for c in gyro)); an = math.sqrt(sum(c * c for c in accel))
        still = gn < 0.05 and abs(an - 1.0) < 0.05
        if not self.calibrated:                                      # learn bias over ~1.5 s of stillness
            if still:
                self.bias_n += 1
                for i in range(3): self.bias[i] += (gyro[i] - self.bias[i]) / self.bias_n
                if self.bias_n >= 1500: self.calibrated = True
            else:
                self.bias_n = 0
        elif still:                                                  # keep tracking slow bias drift
            for i in range(3): self.bias[i] += (gyro[i] - self.bias[i]) * 0.0005
        w = [gyro[i] - self.bias[i] for i in range(3)]
        if an > 0.5:                                                 # tilt correction toward gravity (+Y up)
            down_body = tuple(-c / an for c in accel)               # accel reads +1g opposite gravity
            qi = (self.q[0], -self.q[1], -self.q[2], -self.q[3])
            want = qrot(qi, (0.0, -1.0, 0.0))                        # world down expressed in body frame
            e = (down_body[1]*want[2]-down_body[2]*want[1], down_body[2]*want[0]-down_body[0]*want[2],
                 down_body[0]*want[1]-down_body[1]*want[0])
            k = 2.0 if not self.calibrated else 0.6
            w = [w[i] + k * e[i] for i in range(3)]                 # Mahony: omega += Kp * (measured x estimated)
        self.w = tuple(w)
        dq = (1.0, w[0] * dt / 2, w[1] * dt / 2, w[2] * dt / 2)
        self.q = qnorm(qmul(self.q, dq))

# ----------------------------------------------------------------------------- websocket (RFC 6455, minimal)
WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

class WSClient:
    def __init__(self, sock):
        self.sock = sock; self.lock = threading.Lock(); self.alive = True

    def send_text(self, text):
        data = text.encode(); n = len(data)
        hdr = bytes([0x81, n]) if n < 126 else (bytes([0x81, 126]) + struct.pack('>H', n) if n < 65536 else bytes([0x81, 127]) + struct.pack('>Q', n))
        try:
            with self.lock: self.sock.sendall(hdr + data)
        except OSError:
            self.alive = False

    def _recv_exact(self, n):
        b = b''
        while len(b) < n:
            c = self.sock.recv(n - len(b))
            if not c: raise ConnectionError('closed')
            b += c
        return b

    def recv(self):
        """Next text message, or None when the connection closes."""
        buf = b''
        while True:
            h = self._recv_exact(2); op = h[0] & 0x0F; fin = h[0] & 0x80; ln = h[1] & 0x7F
            if ln == 126: ln = struct.unpack('>H', self._recv_exact(2))[0]
            elif ln == 127: ln = struct.unpack('>Q', self._recv_exact(8))[0]
            if ln > 1 << 20: return None
            mask = self._recv_exact(4) if h[1] & 0x80 else b'\0\0\0\0'
            payload = bytes(c ^ mask[i % 4] for i, c in enumerate(self._recv_exact(ln)))
            if op == 8: return None
            if op == 9:
                with self.lock: self.sock.sendall(bytes([0x8A, len(payload)]) + payload)
                continue
            if op in (0, 1): buf += payload
            if fin and op in (0, 1): return buf.decode('utf-8', 'replace')

# ----------------------------------------------------------------------------- hub
class Hub:
    def __init__(self):
        self.fusion = Fusion(); self.lock = threading.Lock()
        self.clients = set(); self.usb = None; self.prox = 0
        self.status = {'power': None, 'vr': None, 'worn': None, 'source': 'none'}
        self.recenter_q = (1.0, 0.0, 0.0, 0.0)

    def pose_msg(self):
        with self.lock:
            q = qnorm(qmul(self.recenter_q, self.fusion.q)); w = self.fusion.w
            return json.dumps({'type': 'pose', 'q': [round(c, 6) for c in q], 'w': [round(c, 5) for c in w],
                               'cal': self.fusion.calibrated, 'prox': self.prox,
                               'status': 'vr' if self.status.get('vr') else ('on' if self.status.get('power') else self.status['source'])})

    def broadcast(self, msg, exclude=None):
        for c in list(self.clients):
            if c is exclude: continue
            c.send_text(msg)
            if not c.alive: self.clients.discard(c)

    def recenter(self):
        with self.lock:   # remove current yaw only; pitch/roll stay true to gravity
            q = self.fusion.q
            fwd = qrot(q, (0.0, 0.0, -1.0)); yaw = math.atan2(-fwd[0], -fwd[2])
            self.recenter_q = (math.cos(-yaw / 2), 0.0, math.sin(-yaw / 2), 0.0)

    def command(self, name):
        if not self.usb:
            log(f'(no headset) ignoring {name}'); return False
        seq = {'vr': [(0x17, 1), (0x23, 1)], 'cinematic': [(0x23, 0)], 'power_off': [(0x23, 0), (0x17, 0)],
               'power_on': [(0x17, 1)]}.get(name)
        if not seq: return False
        for cmd, val in seq:
            ok = self.usb.send(cmd, val); log(f'cmd 0x{cmd:02x}={val}: {"sent" if ok else "FAILED"}')
            t0 = time.time()                                     # wait for the status report to agree
            key, want = ('power', val) if cmd == 0x17 else ('vr', val)
            while time.time() - t0 < 2.0 and self.status.get(key) != want: time.sleep(0.05)
            if self.status.get(key) != want: log(f'headset did not confirm {key}={want} (status {self.status})')
        return True

HUB = Hub()

def sensor_loop(usb):
    prev = 0
    while True:
        try: pkt = usb.read(SENSOR_IFACE, 64, 200)
        except OSError as e:
            log('sensor read error:', e); HUB.status['source'] = 'usb-error'; time.sleep(0.5); continue
        p = parse_sensor(pkt) if pkt else None
        if not p: continue
        btn, samples, prox = p
        with HUB.lock:
            for tick, g, a in samples: HUB.fusion.feed(tick, g, a)
            HUB.prox = prox
        pressed = btn & ~prev; prev = btn
        if pressed & BTN_MUTE: HUB.recenter(); HUB.broadcast(json.dumps({'type': 'recenter'}))
        if pressed & BTN_VOL_UP: HUB.broadcast(json.dumps({'type': 'nudge', 'key': 'depth', 'delta': 0.1}))
        if pressed & BTN_VOL_DOWN: HUB.broadcast(json.dumps({'type': 'nudge', 'key': 'depth', 'delta': -0.1}))

def control_loop(usb):
    while True:
        try: pkt = usb.read(CONTROL_IFACE, 64, 500)
        except OSError as e: log('control read error:', e); time.sleep(0.5); continue
        s = parse_status(pkt) if pkt else None
        if s: HUB.status.update(s)

def fake_loop():
    """No headset: slow figure-eight head motion so the renderer can be tested end to end."""
    HUB.status['source'] = 'fake'; t0 = time.time(); tick = 0
    while True:
        t = time.time() - t0
        yaw_rate = 0.5 * math.cos(t * 0.5); pitch_rate = 0.25 * math.cos(t)
        with HUB.lock:
            for _ in range(2):
                tick = (tick + 500) & 0xFFFFFF
                HUB.fusion.feed(tick, (pitch_rate, yaw_rate, 0.0), (0.0, 1.0, 0.0))
            HUB.fusion.calibrated = True
        time.sleep(0.001)

def pose_loop(hz):
    while True:
        if HUB.clients: HUB.broadcast(HUB.pose_msg())
        time.sleep(1.0 / hz)

# ----------------------------------------------------------------------------- http
MIME = {'.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.json': 'application/json',
        '.css': 'text/css', '.onnx': 'application/octet-stream', '.wasm': 'application/wasm', '.png': 'image/png'}

def safe_join(base, rel):
    p = os.path.realpath(os.path.join(base, rel.lstrip('/')))
    return p if p.startswith(os.path.realpath(base) + os.sep) or p == os.path.realpath(base) else None

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *a): pass

    def send_body(self, code, body, mime, extra=None):
        self.send_response(code)
        self.send_header('Content-Type', mime); self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path); path = url.path
        if path == '/ws' and self.headers.get('Upgrade', '').lower() == 'websocket': return self.websocket()
        if path == '/api/status':
            return self.send_body(200, json.dumps({**HUB.status, 'calibrated': HUB.fusion.calibrated, 'clients': len(HUB.clients)}).encode(), MIME['.json'])
        if path == '/calibration.json':
            f = os.path.join(STATE, 'calibration.json')
            body = open(f, 'rb').read() if os.path.exists(f) else b'{}'
            return self.send_body(200, body, MIME['.json'])
        if path == '/favicon.ico': return self.send_body(204, b'', 'image/x-icon')
        if path == '/proxy': return self.proxy(urllib.parse.parse_qs(url.query).get('u', [''])[0])
        if path.startswith('/models/'):
            f = safe_join(ARGS.models, path[len('/models/'):])
        else:
            f = safe_join(RENDERER, 'index.html' if path in ('/', '') else path)
        if not f or not os.path.isfile(f): return self.send_body(404, b'not found', 'text/plain')
        body = open(f, 'rb').read()
        self.send_body(200, body, MIME.get(os.path.splitext(f)[1], 'application/octet-stream'))

    def do_POST(self):
        if self.path != '/calibration.json': return self.send_body(404, b'', 'text/plain')
        n = int(self.headers.get('Content-Length', 0) or 0)
        if n > 65536: return self.send_body(413, b'', 'text/plain')
        data = self.rfile.read(n)
        try: json.loads(data)
        except ValueError: return self.send_body(400, b'bad json', 'text/plain')
        os.makedirs(STATE, exist_ok=True)
        tmp = os.path.join(STATE, 'calibration.json.tmp'); open(tmp, 'wb').write(data)
        os.replace(tmp, os.path.join(STATE, 'calibration.json'))
        self.send_body(200, b'ok', 'text/plain')

    def proxy(self, u):
        """Relay an MJPEG/HTTP frame source so the page can read its pixels (same-origin)."""
        host = urllib.parse.urlparse(u).hostname or ''
        try: ip = socket.gethostbyname(host)
        except OSError: return self.send_body(400, b'bad host', 'text/plain')
        if not (ip.startswith(('127.', '10.', '192.168.')) or ip.split('.')[0] == '172' and 16 <= int(ip.split('.')[1]) <= 31):
            return self.send_body(403, b'only local-network sources', 'text/plain')
        try: up = urllib.request.urlopen(u, timeout=5)
        except Exception as e: return self.send_body(502, str(e).encode(), 'text/plain')
        self.send_response(200)
        self.send_header('Content-Type', up.headers.get('Content-Type', 'application/octet-stream'))
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin'); self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close'); self.end_headers()
        try:
            while True:
                chunk = up.read1(65536) if hasattr(up, 'read1') else up.read(65536)
                if not chunk: break
                self.wfile.write(chunk)
        except OSError: pass
        self.close_connection = True

    def websocket(self):
        key = self.headers.get('Sec-WebSocket-Key', '')
        acc = base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()
        self.send_response(101); self.send_header('Upgrade', 'websocket'); self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', acc); self.end_headers(); self.wfile.flush()
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        c = WSClient(self.connection); HUB.clients.add(c)
        c.send_text(json.dumps({'type': 'hello', 'status': HUB.status}))
        try:
            while True:
                text = c.recv()
                if text is None: break
                try: m = json.loads(text)
                except ValueError: continue
                t = m.get('type')
                if t == 'usb': threading.Thread(target=HUB.command, args=(m.get('cmd', ''),), daemon=True).start()
                elif t == 'recal':
                    with HUB.lock: HUB.fusion.reset()
                elif t == 'recenter': HUB.recenter(); HUB.broadcast(text, exclude=c)
                elif t in ('set', 'settings', 'preset', 'nudge'): HUB.broadcast(text, exclude=c)   # control page <-> renderer
        except (ConnectionError, OSError): pass
        finally:
            HUB.clients.discard(c); self.close_connection = True

# ----------------------------------------------------------------------------- main
def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8765)
    ap.add_argument('--bind', default='127.0.0.1')
    ap.add_argument('--vr', action='store_true', help='switch the headset to VR mode at start')
    ap.add_argument('--fake', action='store_true', help='no headset: synthetic head motion')
    ap.add_argument('--hz', type=int, default=250, help='pose messages per second')
    ap.add_argument('--models', default=os.path.join(ROOT, 'models'))
    ap.add_argument('fd', nargs='?', type=int, help='usbfs fd (termux-usb supplies it)')
    ARGS = ap.parse_args()
    if ARGS.fake:
        threading.Thread(target=fake_loop, daemon=True).start()
    elif ARGS.fd is not None:
        HUB.usb = USB(ARGS.fd); HUB.status['source'] = 'usb'
        log('PSVR endpoints:', HUB.usb.eps)
        threading.Thread(target=sensor_loop, args=(HUB.usb,), daemon=True).start()
        threading.Thread(target=control_loop, args=(HUB.usb,), daemon=True).start()
        if ARGS.vr: threading.Thread(target=HUB.command, args=('vr',), daemon=True).start()
    else:
        log('no usb fd and no --fake: serving pages only (run through termux-usb for head tracking)')
    threading.Thread(target=pose_loop, args=(ARGS.hz,), daemon=True).start()
    srv = ThreadingHTTPServer((ARGS.bind, ARGS.port), Handler); srv.daemon_threads = True
    log(f'renderer: http://127.0.0.1:{ARGS.port}/')
    try: srv.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        if HUB.usb: HUB.usb.close()

if __name__ == '__main__':
    main()
