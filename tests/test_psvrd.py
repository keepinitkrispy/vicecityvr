import math, os, struct, sys, json, socket, base64, threading, time, urllib.request
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'psvrd'))
import psvrd as P

def packet(tick, gyro_raw, accel_raw, buttons=0, prox=900):
    p = bytearray(64); p[0] = buttons
    for i, off in enumerate((16, 32)):
        struct.pack_into('<I', p, off, tick + 500 * i)
        struct.pack_into('<3h', p, off + 4, *gyro_raw); struct.pack_into('<3h', p, off + 10, *accel_raw)
    struct.pack_into('<H', p, 55, prox)
    return bytes(p)

def test_parse():
    b, s, prox = P.parse_sensor(packet(1000, (100, 200, 300), (16384, 0, 0), buttons=8, prox=1023))
    assert b == 8 and prox == 1023 and len(s) == 2
    tick, g, a = s[0]
    assert tick == 1000 and abs(g[0] - 200 * 0.00105) < 1e-9 and abs(g[1] - 100 * 0.00105) < 1e-9 and abs(g[2] + 300 * 0.00105) < 1e-9
    assert abs(a[1] - 1.0) < 1e-9 and s[1][0] == 1500
    assert P.parse_sensor(b'\0' * 63) is None
    st = bytes([0xF0, 0, 0xAA, 16, 0b011, 30, 0, 0, 5, 0xFF, 0, 1] + [0] * 8)
    assert P.parse_status(st) == {'power': 1, 'worn': 1, 'cinematic': 0, 'volume': 30, 'vr': 1}

def test_endpoints():
    d = open('/tmp/fakedesc.bin', 'rb').read() if os.path.exists('/tmp/fakedesc.bin') else None
    if d: assert P.parse_endpoints(d) == {4: {'in': 0x83}, 5: {'in': 0x84, 'out': 0x04}}

def angle(q):  # rotation angle of quaternion
    return 2 * math.acos(min(1, abs(q[0])))

def test_tilt_converges():
    f = P.Fusion(); f.calibrated = True
    f.q = P.qnorm((math.cos(0.2), math.sin(0.2), 0, 0))    # start 23 degrees pitched wrongly; headset is level
    tick = 0
    for _ in range(10000):                                 # 10 s at 1 kHz
        tick += 1000; f.feed(tick & 0xFFFFFF, (0, 0, 0), (0, 1.0, 0))
    assert angle(f.q) < math.radians(0.3), math.degrees(angle(f.q))

def test_yaw_integrates():
    f = P.Fusion(); f.calibrated = True; tick = 0
    for _ in range(1000):                                  # 1 s of 90 deg/s about +Y
        tick += 1000; f.feed(tick, (0, math.pi / 2, 0), (0, 1.0, 0))
    fwd = P.qrot(f.q, (0, 0, -1)); yaw = math.degrees(math.atan2(-fwd[0], -fwd[2]))
    assert abs(abs(yaw) - 90) < 2, yaw

def test_bias_learned():
    f = P.Fusion(); tick = 0
    for _ in range(1600):
        tick += 1000; f.feed(tick, (0.01, -0.02, 0.005), (0, 1.0, 0))
    assert f.calibrated and abs(f.bias[1] + 0.02) < 1e-3

def test_server():
    sys.argv = ['psvrd', '--fake', '--port', '8799', '--hz', '50']
    threading.Thread(target=P.main, daemon=True).start(); time.sleep(0.8)
    st = json.loads(urllib.request.urlopen('http://127.0.0.1:8799/api/status').read())
    assert st['source'] == 'fake'
    page = urllib.request.urlopen('http://127.0.0.1:8799/').read()
    assert b'PSVR' in page
    try: urllib.request.urlopen('http://127.0.0.1:8799/../../etc/passwd'); assert False
    except urllib.error.HTTPError as e: assert e.code == 404
    req = urllib.request.Request('http://127.0.0.1:8799/calibration.json', data=b'{"eyes":[1]}', method='POST')
    assert urllib.request.urlopen(req).read() == b'ok'
    assert json.loads(urllib.request.urlopen('http://127.0.0.1:8799/calibration.json').read()) == {'eyes': [1]}
    s = socket.create_connection(('127.0.0.1', 8799))
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(f'GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
    s.settimeout(3); buf = b''
    while b'\r\n\r\n' not in buf: buf += s.recv(4096)
    assert b'101' in buf.split(b'\r\n')[0]
    msgs = []; rest = buf.split(b'\r\n\r\n', 1)[1]
    c = P.WSClient(s); c._recv_exact = (lambda orig: (lambda n: _take(n)))(None)
    data = [rest]
    def _take(n):
        while len(data[0]) < n: data[0] += s.recv(4096)
        out, data[0] = data[0][:n], data[0][n:]; return out
    for _ in range(5): msgs.append(json.loads(c.recv()))
    types = [m['type'] for m in msgs]
    assert types[0] == 'hello' and 'pose' in types, types
    pose = [m for m in msgs if m['type'] == 'pose'][0]
    assert len(pose['q']) == 4 and pose['cal'] is True

if __name__ == '__main__':
    os.environ['HOME'] = '/tmp/psvrtest'; P.STATE = '/tmp/psvrtest/.config/psvr-control'
    for name, fn in list(globals().items()):
        if name.startswith('test_'):
            try: fn(); print('PASS', name)
            except AssertionError as e: print('FAIL', name, e)
