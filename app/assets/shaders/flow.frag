#version 300 es
// Motion evidence: where did this patch of picture come from in the previous game frame?
// Coarse-to-fine block matching on the luma mip chain. Output rg = motion in uv (prev -> cur), b = texture strength, a = match cost.
// Normally seeded with last frame's motion and run only at fine levels; the full coarse-to-fine search runs only
// where that prediction failed (gate). (With a chiaki-ng fork the PS5 encoder's own motion vectors replace this pass.)
precision highp float;
uniform sampler2D cur, prev, init, gateTex; uniform vec2 size, srcSize; uniform float lod, radius, hasInit, gate, gateThr;
out vec4 o;
void main(){
  vec2 uv = gl_FragCoord.xy / size;
  if (gate > 0.5) { vec4 G = textureLod(gateTex, uv, 0.0); if (G.a < gateThr) { o = G; return; } }   // prediction held: no search
  vec2 base = hasInit > 0.5 ? textureLod(init, uv, 0.0).rg : vec2(0.0);
  float ls = max(lod, 2.0);
  vec2 tap = 1.5 * exp2(ls) / srcSize, stp = exp2(lod) / srcSize;
  float c[9]; int n = 0;
  for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) c[n++] = textureLod(cur, uv + vec2(x, y) * tap, ls).a;
  float best = 1e9; vec2 bf = base;
  for (int j = -3; j <= 3; j++) for (int i = -3; i <= 3; i++) {
    if (abs(float(i)) > radius || abs(float(j)) > radius) continue;
    vec2 f = base + vec2(i, j) * stp; float s = 0.0; int k = 0;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) s += abs(c[k++] - textureLod(prev, uv + vec2(x, y) * tap - f, ls).a);
    s += 0.003 * length(vec2(i, j));
    if (s < best) { best = s; bf = f; }
  }
  float lo = c[0], hi = c[0]; for (int k = 1; k < 9; k++) { lo = min(lo, c[k]); hi = max(hi, c[k]); }
  o = vec4(bf, hi - lo, best);   // b = how much texture there was to match on
}
