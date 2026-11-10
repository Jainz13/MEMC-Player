#version 300 es
precision highp float;
// Small RGB copy of a frame for the AI model (RIFE), which expects an ordinary 0..1 picture.
// HDR frames (linear light, 1.0 = 1000 nits) are compressed with a soft curve first.
uniform sampler2D uTex;
uniform int uHdr;
in vec2 vTexCoords;
out vec4 outColor;
void main() {
    vec3 c = max(texture(uTex, vTexCoords).rgb, 0.0);
    if (uHdr == 1) {
        c = c * 10.0;
        c = pow(c / (1.0 + c), vec3(0.6));
    }
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
