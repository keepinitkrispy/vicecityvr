#version 300 es
// sparse |new - old| luma, read back on the CPU: duplicate-frame and cut detection
precision highp float;
uniform sampler2D a, b; uniform vec2 size;
out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy / size; float d = abs(texture(a, uv).a - texture(b, uv).a); o = vec4(min(1.0, d * 4.0), d > 0.004 ? 1.0 : 0.0, 0.0, 1.0); }
