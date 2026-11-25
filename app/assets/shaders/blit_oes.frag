#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// captured screen (SurfaceTexture) -> raw RGBA, row 0 = top of picture, alpha = luma
precision highp float;
uniform samplerExternalOES src; uniform mat4 stm; uniform vec2 size; uniform vec4 crop;   // crop: offset.xy, scale.xy
out vec4 o;
void main(){
  vec2 uv = crop.xy + (gl_FragCoord.xy / size) * crop.zw;
  vec3 c = texture(src, (stm * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy).rgb;
  o = vec4(c, dot(c, vec3(.299,.587,.114)));
}
