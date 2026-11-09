#version 300 es
precision highp float;
// Copies (and, with linear filtering, box-downsamples by 2) a texture.
uniform sampler2D uTex;
in vec2 vTexCoords;
out vec4 outColor;
void main() {
    outColor = texture(uTex, vTexCoords);
}
