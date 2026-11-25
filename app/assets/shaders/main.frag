#version 300 es
precision highp float;
// Every display refresh: one game frame + the response grid + motion -> two eye views, predicted forward in time.
uniform sampler2D src, stateTex, flowTex;
uniform vec2 res; uniform mat3 headR, scrInv; uniform vec3 eyeL, eyeR, eyeC;
uniform float flatTan, sbs, tanHalf, k1, k2, ca, lensX, lensY, fillX, fillY, lensOn, swapEyes;
uniform vec3 calL, calR;   // per-eye x, y offset (lens units, y down) and scale, from the alignment app
uniform float dist, cylR, arcHalf, scrH, imgAspect;
uniform float depthK, conv, ambient, floorK, grid, bright, flowT;
out vec4 o;
float taper(vec2 uv){ float e = min(min(uv.x, 1.0-uv.x), min(uv.y, 1.0-uv.y)); return smoothstep(0.0, 0.07, e); }
float disp(vec2 uv){ return depthK * 0.018 * (textureLod(stateTex, uv, 0.0).a - conv) * taper(uv); }
// Which point of the newest game frame should appear at uv, for this eye, flowT game-frames after it arrived.
// Stereo: the eye offset times that patch's parallax response. Time: follow each patch's own motion forward.
vec2 lookup(vec2 uv, float sgn){
  float x = uv.x;
  for (int i = 0; i < 4; i++) x = uv.x - sgn * disp(vec2(x, uv.y));
  vec2 p = vec2(x, uv.y), q = p;
  if (flowT > 0.0) { q = p - textureLod(flowTex, q, 0.0).rg * flowT; q = p - textureLod(flowTex, q, 0.0).rg * flowT; }
  return q;
}
// ray -> curved screen (cylinder) uv; returns false if no hit
bool screenUV(vec3 ro, vec3 rd, out vec2 suv){
  ro = scrInv * ro; rd = scrInv * rd;
  float cz = -dist + cylR;
  vec2 oc = vec2(ro.x, ro.z - cz), d = rd.xz;
  float a = dot(d, d); if (a < 1e-6) return false;
  float b = dot(oc, d), c = dot(oc, oc) - cylR * cylR, disc = b*b - a*c;
  if (disc < 0.0) return false;
  float t = (-b + sqrt(disc)) / a; if (t <= 0.0) return false;
  vec3 h = ro + t * rd;
  float ang = atan(h.x, -(h.z - cz));
  suv = vec2(ang / arcHalf * 0.5 + 0.5, 0.5 - h.y / scrH);
  return true;
}
// shift: the stereo (DIBR) displacement. Computed once, on the green ray, and reused for red and blue:
// the three rays differ by ~1% (chromatic correction) while the iterative depth lookup is the expensive part.
vec3 sampleScreen(vec3 ro, vec3 rd, float sgn, bool first, inout vec2 shift, out float hitMask){
  vec2 suv; hitMask = 0.0;
  if (!screenUV(ro, rd, suv)) return vec3(0);
  vec2 uc = clamp(suv, 0.0, 1.0);
  vec2 od = (suv - uc) * vec2(1.0, imgAspect);
  float outD = length(od);
  if (outD <= 0.0) {
    hitMask = 1.0;
    if (first) shift = lookup(suv, sgn) - suv;
    return textureLod(src, suv + shift, 0.0).rgb;
  }
  // peripheral fill: blurred edge colours bleeding outward - peripheral vision is low-res, it only needs motion + light
  return textureLod(src, uc, 6.5).rgb * ambient * 0.75 * exp(-outD * 4.0);
}
void main(){
  vec2 fc = gl_FragCoord.xy / res;
  if (sbs > 2.5) { vec2 uv = vec2(fc.x, 1.0 - fc.y); o = vec4(textureLod(src, lookup(uv, 0.0), 0.0).rgb * bright, 1.0); return; }   // direct view (tests)
  float e = 0.0; vec2 euv = fc; vec3 eye = eyeC;
  bool warp = sbs > 0.5 && sbs < 1.5, cross = sbs > 1.5;
  if (sbs > 0.5) {
    e = fc.x < 0.5 ? 0.0 : 1.0; if (cross) e = 1.0 - e;
    if (warp && swapEyes > 0.5) e = 1.0 - e;               // left half of the panel shows the right eye's view
    euv = vec2(fract(fc.x * 2.0), fc.y); eye = e < 0.5 ? eyeL : eyeR;
  }
  float eyeAspect = warp ? (960.0/1080.0) : (sbs > 0.5 ? res.x * 0.5 / res.y : res.x / res.y);
  vec2 p = (euv - 0.5) * 2.0 * vec2(eyeAspect * fillX, fillY);
  float sgn = e < 0.5 ? 1.0 : -1.0;
  if (sbs < 0.5) { sgn = 0.0; }
  if (warp) {
    vec3 cal = e < 0.5 ? calL : calR;                     // same transform as the alignment app: move, then scale
    p = (p - vec2(cal.x, -cal.y)) / cal.z;
    p -= vec2(e < 0.5 ? lensX : -lensX, lensY);
  }
  float r2 = dot(p, p);
  bool lens = warp && lensOn > 0.5;
  float distort = lens ? (1.0 + k1 * r2 + k2 * r2 * r2) : 1.0;
  float th = warp ? tanHalf : flatTan;
  vec3 col = vec3(0); float hitG = 0.0; vec3 rdG = vec3(0, 0, -1);
  vec2 shift = vec2(0.0);
  for (int k = 0; k < 3; k++) {
    int ch = k == 0 ? 1 : (k == 1 ? 0 : 2);                // green first: it computes the shared stereo shift
    float s = distort * (lens ? (1.0 + ca * float(ch - 1)) : 1.0);
    vec3 rd = headR * normalize(vec3(p * s * th, -1.0));
    float hm; vec3 c = sampleScreen(eye, rd, sgn, k == 0, shift, hm);
    col[ch] = c[ch];
    if (ch == 1) { hitG = hm; rdG = rd; }
  }
  // floor: a stable "rest frame" under the screen calms the vestibular system; screen light spills onto it
  float ft = (-1.15 - eye.y) / min(rdG.y, -1e-3);
  vec3 fh = eye + ft * rdG;
  vec2 ff = 0.5 - abs(fract(fh.xz) - 0.5); vec2 fw = fwidth(fh.xz) * 1.2 + 1e-4;
  if (rdG.y < -0.01 && floorK > 0.0) {
    float line = max(smoothstep(fw.x, 0.0, ff.x), smoothstep(fw.y, 0.0, ff.y));
    vec3 hs = scrInv * fh;
    float spill = exp(-length(hs.xz - vec2(0.0, -dist)) * 0.55);
    vec3 avg = textureLod(src, vec2(0.5), 12.0).rgb;
    vec3 fl = (vec3(0.30, 0.36, 0.45) * line * 0.22 + avg * spill * 0.35) * floorK * exp(-ft * 0.12);
    col += (1.0 - hitG) * fl;
  }
  if (grid > 0.5 && warp) {
    vec2 tp = p * distort * tanHalf * 5.0;
    vec2 f = abs(fract(tp + 0.5) - 0.5); vec2 w = fwidth(tp) * 1.2;
    float l = max(smoothstep(w.x, 0.0, f.x), smoothstep(w.y, 0.0, f.y));
    float cross = (abs(p.x) < 0.004 || abs(p.y) < 0.004) ? 1.0 : 0.0;
    col = mix(col, vec3(0.2, 1.0, 0.4), max(l * 0.8, cross));
  }
  float vig = lens ? smoothstep(1.55, 1.25, sqrt(r2)) : 1.0;
  o = vec4(col * bright * vig, 1.0);
}