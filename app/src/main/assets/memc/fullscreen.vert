#version 300 es
// Full-screen quad; vTexCoords runs 0..1 across the target texture.
in vec4 aFramePosition;
out vec2 vTexCoords;
void main() {
    gl_Position = aFramePosition;
    vTexCoords = aFramePosition.xy * 0.5 + 0.5;
}
