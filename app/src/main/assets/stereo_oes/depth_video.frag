#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
// Reads one frame of the 3D export's depth video (grey, white = near) into a depth map.
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
in vec2 vTexCoords;
out vec4 outColor;
void main() {
    vec2 uv = (uTexMatrix * vec4(vTexCoords, 0.0, 1.0)).xy;
    vec3 c = texture(uTex, uv).rgb;
    outColor = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
}
