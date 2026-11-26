# Runs ON the phone (Termux + Shizuku/rish). Fixes "background too close / can't line it up" in XR Screen Utility:
# swaps eyes if it has a swap/reverse option, otherwise turns the 3D depth strength down; then returns to Momiji.
import os, re, subprocess, time
os.environ['RISH_APPLICATION_ID'] = 'com.termux'
def sh(c): return subprocess.run(['rish', '-c', c], capture_output=True, text=True).stdout
def dump():
    x = sh('uiautomator dump /data/local/tmp/ui.xml >/dev/null; cat /data/local/tmp/ui.xml')
    out = []
    for m in re.finditer(r'<node [^>]*>', x):
        n = m.group(0); g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ''])[1]
        b = list(map(int, re.findall(r'\d+', g('bounds')))) or [0, 0, 0, 0]
        out.append(dict(t=g('text'), d=g('content-desc'), c=g('class').split('.')[-1], ch=g('checked'),
                        pkg=g('package'), x1=b[0], y1=b[1], x2=b[2], y2=b[3], x=(b[0]+b[2])//2, y=(b[1]+b[3])//2))
    return out
def label(a): return (a['t'] + ' ' + a['d']).strip()
sh('am start -n "$(cmd package resolve-activity --brief com.xr.screenutil | tail -1)" >/dev/null 2>&1'); time.sleep(2.5)
done = None; seen = []
for page in range(6):
    n = dump()
    if not any(a['pkg'] == 'com.xr.screenutil' for a in n): print('XR Screen Utility not in front'); break
    for a in n:
        if label(a) and label(a) not in seen: seen.append(label(a))
    swap = [a for a in n if re.search(r'swap|revers|invert.*(eye|3d|depth)|(eye|left).*(right|swap)', label(a), re.I)]
    for s in swap:
        sw = [a for a in n if a['c'] in ('Switch', 'CheckBox', 'SwitchMaterial') and abs(a['y'] - s['y']) < 90]
        if sw and 'about' not in label(s).lower():
            sh(f"input tap {sw[0]['x']} {sw[0]['y']}"); time.sleep(0.8); done = f"toggled '{label(s)}' (was {sw[0]['ch']})"; break
    if done: break
    dep = [a for a in n if re.search(r'depth|strength|3d effect|parallax', label(a), re.I) and a['c'] == 'TextView']
    for s in dep:
        bars = [a for a in n if a['c'] in ('SeekBar', 'Slider') and 0 <= a['y'] - s['y'] < 220]
        if bars:
            b = bars[0]; sh(f"input tap {b['x1'] + (b['x2'] - b['x1']) * 0.15:.0f} {b['y']}"); time.sleep(0.8)
            done = f"set '{label(s)}' low"; break
    if done: break
    sh('input swipe 540 1900 540 800 300'); time.sleep(0.8)
ok = [a for a in dump() if a['t'] in ('OK', 'Save', 'Apply')]
if ok: sh(f"input tap {ok[0]['x']} {ok[0]['y']}")
print('RESULT:', done or 'no 3D swap/depth control found')
print('XR settings seen:', ' | '.join(seen)[:1500])
sh('monkey -p io.github.yazanj082.momiji -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1')
print('back to Momiji')
