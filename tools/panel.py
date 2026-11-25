#!/usr/bin/env python3
"""Headset-display remote: shows what the external (headset) display is showing and lets you tap on it.
http://127.0.0.1:18746  (localhost only)

Old version: hard-coded display ids and plain `adb`, which breaks whenever wireless-debugging drops
(it does on every Wi-Fi change). This one runs shell commands through Shizuku (rish) first, adb second,
and finds the external display's ids itself.
"""
import os, re, subprocess, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 18746
HOST = f'127.0.0.1:{PORT}'
os.environ.setdefault('RISH_APPLICATION_ID', 'com.termux')   # rish refuses to start without it

def shell(cmd, timeout=6, binary=False):
    """Run an Android shell command as the shell user: rish (Shizuku) if it works, else adb."""
    for runner in (['rish', '-c', cmd], ['adb', 'exec-out', cmd]):
        try:
            r = subprocess.run(runner, capture_output=True, timeout=timeout)
            if r.returncode == 0 and r.stdout: return r.stdout if binary else r.stdout.decode(errors='replace')
        except (OSError, subprocess.TimeoutExpired):
            continue
    raise RuntimeError('no shell: start Shizuku, or reconnect adb (wireless debugging)')

def external_display():
    """(logical id for `input -d`, physical id for `screencap -d`) of the first non-built-in display."""
    logical = None
    for m in re.finditer(r'mDisplayId=(\d+)', shell('dumpsys display')):
        if m.group(1) != '0': logical = m.group(1); break
    phys = None
    ids = re.findall(r'Display (\d+) \(HWC display \d+\)', shell('dumpsys SurfaceFlinger --display-id'))
    if len(ids) > 1: phys = ids[1]
    if logical is None or phys is None: raise RuntimeError('no external display (is the HDMI adapter connected?)')
    return logical, phys

PAGE = b'''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Headset display</title>
<style>body{background:#121318;color:#fff;font:16px system-ui;margin:12px}img{width:100%;touch-action:manipulation;background:#000}button{padding:12px;font:inherit}</style>
<h3>Headset display</h3><p>Tap the picture to tap on the headset display.</p><img id="s"><p><button id="back">Back</button> <span id="t"></span></p>
<script>
const img=document.getElementById('s'),t=document.getElementById('t');let W=1920,H=1080;
function refresh(){img.src='/frame?'+Date.now()}
img.onload=()=>{W=img.naturalWidth||W;H=img.naturalHeight||H;setTimeout(refresh,500)};
img.onerror=async()=>{t.textContent=await (await fetch('/why')).text();setTimeout(refresh,2000)};
async function act(p){let r=await fetch(p,{method:'POST'});t.textContent=r.ok?'sent':await r.text()}
img.onclick=e=>{let b=img.getBoundingClientRect();act('/tap/'+Math.floor(W*(e.clientX-b.left)/b.width)+'/'+Math.floor(H*(e.clientY-b.top)/b.height))};
document.getElementById('back').onclick=()=>act('/back');refresh();
</script>'''

LAST_ERR = ['']

class Panel(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, data, mime):
        self.send_response(code); self.send_header('Content-Type', mime); self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Frame-Options', 'DENY'); self.send_header('Content-Length', str(len(data))); self.end_headers()
        self.wfile.write(data)
    def do_GET(self):
        if self.headers.get('Host') != HOST: return self.reply(403, b'', 'text/plain')   # blocks DNS-rebinding pages
        path = self.path.partition('?')[0]
        if path == '/': return self.reply(200, PAGE, 'text/html')
        if path == '/why': return self.reply(200, LAST_ERR[0].encode(), 'text/plain')
        if path != '/frame': return self.reply(404, b'', 'text/plain')
        try:
            _, phys = external_display()
            png = shell(f'screencap -d {phys} -p', binary=True)
            if not png.startswith(b'\x89PNG'): raise RuntimeError('screencap returned no image (secure window?)')
            self.reply(200, png, 'image/png')
        except Exception as e:
            LAST_ERR[0] = str(e); self.reply(503, str(e).encode(), 'text/plain')
    def do_POST(self):
        if self.headers.get('Origin') != 'http://' + HOST or self.headers.get('Host') != HOST: return self.reply(403, b'', 'text/plain')
        m = re.fullmatch(r'/tap/(\d{1,4})/(\d{1,4})', self.path)
        try:
            logical, _ = external_display()
            if m: shell(f'input -d {logical} tap {int(m[1])} {int(m[2])}')
            elif self.path == '/back': shell(f'input -d {logical} keyevent BACK')
            else: return self.reply(400, b'', 'text/plain')
            self.reply(200, b'ok', 'text/plain')
        except Exception as e:
            self.reply(503, str(e).encode(), 'text/plain')

if __name__ == '__main__':
    print(f'headset display remote: http://{HOST}/', file=sys.stderr)
    ThreadingHTTPServer(('127.0.0.1', PORT), Panel).serve_forever()
