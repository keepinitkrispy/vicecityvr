#version 300 es
// encode any (float) texture into RGBA8 for CPU readback in tests
precision highp float;
uniform sampler2D t; uniform vec2 size; uniform vec4 scale, bias; uniform float lod;
out vec4 o;
void main(){ o = clamp(textureLod(t, gl_FragCoord.xy / size, lod) * scale + bias, 0.0, 1.0); }
