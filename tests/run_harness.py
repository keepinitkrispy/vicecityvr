#!/usr/bin/env python3
"""Runs tests/harness.html in headless Chrome and checks the numbers. Needs playwright + Chrome."""
import asyncio, functools, http.server, json, os, sys, threading
from playwright.async_api import async_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def serve():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=ROOT); h.log_message = lambda *a: None
    s = http.server.ThreadingHTTPServer(('127.0.0.1', 8791), h); threading.Thread(target=s.serve_forever, daemon=True).start()
async def main():
    serve()
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path=os.environ.get('CHROME', '/usr/bin/google-chrome'), args=['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'])
        pg = await b.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto('http://127.0.0.1:8791/tests/harness.html')
        try: R = await pg.evaluate('run()')
        except Exception as e: print('PAGE ERROR', e, errs); sys.exit(1)
        await b.close()
    print(json.dumps(R, indent=1, default=lambda x: round(x, 3)))
    near = lambda v, t, tol: abs(v[0] - t) <= tol and abs(v[1]) <= tol
    checks = [
        ('motion: near post = 6 px/frame', near(R['flow_near'], 6, 1.0)),
        ('motion: mid building = 2 px/frame', near(R['flow_mid'], 2, 1.0)),
        ('motion: far building = 0.75 px/frame', near(R['flow_far'], 0.75, 1.0)),
        ('motion: HUD = 0', near(R['flow_hud'], 0, 0.75)),
        ('response order near > mid > far', R['resp_near'] > R['resp_mid'] > R['resp_far']),
        ('HUD found after a turn', R['hud_minimap_max'] > 0.9), ('world not HUD', R['hud_world'] < 0.1 and R['hud_during_travel_world'] < 0.1),
        ('response order kept through the turn', R['resp_after_turn'][0] > R['resp_after_turn'][1] > R['resp_after_turn'][2]),
        ('frame gen beats holding (half frame)', R['err_predict'] < 0.7 * R['err_hold']),
        ('frame gen beats holding (full frame)', R['err_predict_full'] < 0.7 * R['err_hold_full']),
        ('duplicate frame detected', R['dup'] == 'dup'),
        ('turn: motion = 10 px/frame', near(R['turn_flow_mid'], 10, 1.0)),
        ('turn carries no parallax evidence', R['evidence_turn'] < 0.15 and R['evidence_translate'] > 0.5),
        ('frame gen beats holding (turn)', R['turn_err_predict'] < 0.7 * R['turn_err_hold']),
        ('prediction-first search saves >50% of search work', R['search_work_saved'] > 0.5),
        ('cut detected, prediction off', R['cut'] == 'cut' and R['flowValid_after_cut'] == 0),
        ('HUD survives the cut', R['hud_survives_cut'] > 0.9), ('no GL error', R['err'] == 0),
    ]
    bad = 0
    for name, ok in checks: print('PASS' if ok else 'FAIL', name); bad += not ok
    sys.exit(1 if bad else 0)
asyncio.run(main())
