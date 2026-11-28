#version 300 es
precision highp float;
// Rough depth without AI (TV boxes and phones without a Snapdragon NPU), 1 = near.
// Cues: lower in the picture is usually nearer, in-focus detail is usually the subject, and
// saturated colours tend to be closer than hazy, washed-out background.
uniform highp sampler2D uTex;   // small 0..1 RGB picture
uniform vec2 uTexel;
in vec2 vTexCoords;
out vec4 outColor;

void main() {
    // Smooth, low-detail cues only: per-pixel detail made textures wobble when shifted.
    vec3 c = vec3(0.0);
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            c += texture(uTex, vTexCoords + vec2(float(x), float(y)) * uTexel * 2.0).rgb;
        }
    }
    c /= 25.0;
    float sat = clamp((max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b))) * 1.5, 0.0, 1.0);
    // Texture y runs upwards: the bottom of the picture is y = 0 (usually the ground, nearest).
    float low = smoothstep(0.0, 1.0, 1.0 - vTexCoords.y);
    float centre = 1.0 - abs(vTexCoords.x - 0.5) * 0.4;
    float d = 0.8 * low * centre + 0.2 * sat;
    outColor = vec4(clamp(d, 0.0, 1.0), 0.0, 0.0, 1.0);
}
