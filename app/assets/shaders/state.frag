#version 300 es
// The response grid. Per cell: r = parallax response (inverse depth, relative), g = screen-locked (HUD) confidence,
// b = how strongly this frame's camera travel tested the cell, a = final stereo value.
//
// Camera model, fitted on the CPU each game frame from all cells (6 numbers):
//   motion(p) = turn(p) + r(p) * travel(p)
//   turn(p)   = yaw*(1+x^2, xy) + pitch*(xy, 1+y^2) + roll*(-y, x)     exact perspective rotation, depth-free
//   travel(p) = side + fwd * p                                          sideways/vertical + forward (expansion)
// A cell is only updated when what it saw contradicts what the model predicted for it, and only in proportion to
// how much travel there was: a pure turn carries no depth information, so it cannot change r.
// r travels WITH the picture (sampled at uv - motion), so what was learned stays attached to what it was learned about.
precision highp float;
uniform sampler2D src, flow, prevState; uniform vec2 size;
uniform vec3 rot; uniform vec2 side; uniform float fwd, tanV, aspect, hasModel;
uniform float hasPrev, cut, horizon, nearcap, conv, hudAuto, skyK, rate, pxw;
out vec4 o;
float lu(vec3 c){ return dot(c, vec3(.299,.587,.114)); }
void main(){
  vec2 uv = gl_FragCoord.xy / size;
  vec4 F = textureLod(flow, uv, 0.0);
  vec2 sc = 2.0 * tanV * vec2(aspect, 1.0), p = (uv - 0.5) * sc, fn = F.rg * sc;   // normalized camera coords
  float pxn = sc.x / pxw;                                                           // one source pixel in these units
  // seed for cells never tested by travel: ground plane (exact: response linear in height below the horizon), sky
  vec3 c1 = textureLod(src, uv, 3.0).rgb, c2 = textureLod(src, uv, 5.5).rgb; float detail = length(c1 - c2);
  float gp = clamp((uv.y - horizon) / max(0.05, 1.0 - horizon), 0.0, 1.0);
  float prior = 0.10 + 0.80 * gp;
  float sky = smoothstep(horizon + 0.02, horizon - 0.25, uv.y) * smoothstep(0.25, 0.65, lu(c1)) * (1.0 - smoothstep(0.02, 0.10, detail))
            * (0.55 + 0.45 * smoothstep(-0.05, 0.10, c1.b - c1.r));
  prior = mix(prior, 0.0, skyK * sky) + 0.10 * smoothstep(0.03, 0.16, detail) * (1.0 - gp);
  bool keep = hasPrev > 0.5 && cut < 0.5;
  vec4 P = keep ? textureLod(prevState, uv - F.rg, 0.0) : vec4(prior, 0.0, 0.0, 0.0);
  float r = P.r, evidence = 0.0;
  // what the model predicts here, and what was seen
  vec2 tr = vec2(rot.x * (1.0 + p.x * p.x) + rot.y * p.x * p.y - rot.z * p.y,
                 rot.x * p.x * p.y + rot.y * (1.0 + p.y * p.y) + rot.z * p.x);
  vec2 g = side + fwd * p;
  float gg = dot(g, g);
  if (keep && hasModel > 0.5 && F.b > 0.02) {
    float rObs = dot(fn - tr, g) / max(gg, 1e-9);
    float surprise = length(fn - tr - r * g) / pxn;                     // pixels of disagreement
    evidence = gg / (gg + 4.0 * pxn * pxn);                              // ~0 unless travel moved things > ~2 px
    float k = rate * evidence * smoothstep(0.3, 1.5, surprise);         // update only where contradicted
    r = mix(r, clamp(rObs, -0.2, 1.6), k);
  }
  // screen-locked: an edge that stays put while the model says this spot should be moving
  float hud = hasPrev > 0.5 ? textureLod(prevState, uv, 0.0).g : 0.0;
  float expect = length(tr + max(r, 0.0) * g) / pxn, seen = length(fn) / pxn;
  float edge = length(textureLod(src, uv, 0.0).rgb - textureLod(src, uv, 2.0).rgb);
  if (keep && hasModel > 0.5 && expect > 2.0) {
    if (edge > 0.04 && seen < 0.2 * expect) hud = min(1.0, hud + 0.06);
    else if (seen > 0.6 * expect) hud = max(0.0, hud - 0.2);
  }
  float d = min(clamp(r, 0.0, 1.0), conv + nearcap * (1.0 - conv));
  d = mix(d, conv, hudAuto * smoothstep(0.5, 0.9, hud));
  o = vec4(r, hud, evidence, d);
}
