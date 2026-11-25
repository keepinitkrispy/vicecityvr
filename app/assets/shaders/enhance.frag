#version 300 es
// Once per game frame: resample to the working resolution, soften compression blocking in flat areas,
// then contrast-adaptive sharpening (weights adapt to local contrast so edges don't ring). alpha = luma.
precision highp float;
uniform sampler2D raw; uniform vec2 size, rawSize; uniform float sharp, deblock;
out vec4 o;
float lu(vec3 c){ return dot(c, vec3(.299,.587,.114)); }
void main(){
  vec2 uv = gl_FragCoord.xy / size, px = 1.0 / rawSize;
  vec3 c = texture(raw, uv).rgb;
  vec3 n = texture(raw, uv - vec2(0, px.y)).rgb, s = texture(raw, uv + vec2(0, px.y)).rgb;
  vec3 w = texture(raw, uv - vec2(px.x, 0)).rgb, e = texture(raw, uv + vec2(px.x, 0)).rgb;
  float lc = lu(c), ln = lu(n), ls = lu(s), lw = lu(w), le = lu(e);
  float mn = min(lc, min(min(ln, ls), min(lw, le))), mx = max(lc, max(max(ln, ls), max(lw, le)));
  vec3 mean = (c + n + s + w + e) * 0.2;
  c = mix(c, mean, deblock * (1.0 - smoothstep(0.012, 0.045, mx - mn)));
  float amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, 1e-3), 0.0, 1.0));
  float k = -amp * sharp * 0.2;
  vec3 r = clamp((c + (n + s + w + e) * k) / (1.0 + 4.0 * k), 0.0, 1.0);
  o = vec4(r, lu(r));
}
