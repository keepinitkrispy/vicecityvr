#version 300 es
// test path: an ordinary texture (row 0 = top) -> raw RGBA, alpha = luma
precision highp float;
uniform sampler2D src; uniform vec2 size; uniform vec4 crop;
out vec4 o;
void main(){
  vec2 uv = crop.xy + (gl_FragCoord.xy / size) * crop.zw;
  vec3 c = texture(src, uv).rgb;
  o = vec4(c, dot(c, vec3(.299,.587,.114)));
}
