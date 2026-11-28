#version 300 es
precision highp float;
// Writes a depth map (0 far .. 1 near) as a grey picture for the 3D export's depth video.
uniform highp sampler2D uDepth;
in vec2 vTexCoords;
out vec4 outColor;
void main() {
    float d = clamp(texture(uDepth, vTexCoords).r, 0.0, 1.0);
    outColor = vec4(d, d, d, 1.0);
}
