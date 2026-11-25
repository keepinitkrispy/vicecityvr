#version 300 es
// Confidence-weighted smoothing; cells with nothing to match on take their neighbours' motion.
// Output rg = motion (uv per game frame), b = texture strength, a = match cost.
precision highp float;
uniform sampler2D flow; uniform vec2 size;
out vec4 o;
void main(){
  vec2 uv = gl_FragCoord.xy / size;
  vec2 acc = vec2(0.0); float ws = 0.0;
  for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
    vec4 F = textureLod(flow, uv + vec2(x, y) / size, 0.0); float w = F.b / (0.02 + F.a);
    acc += F.rg * w; ws += w;
  }
  vec4 C = textureLod(flow, uv, 0.0);
  o = vec4(acc / max(ws, 1e-6), C.b, C.a);
}
