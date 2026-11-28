#version 300 es
precision highp float;
// Rough depth without AI (TV boxes and phones without a Snapdragon NPU), 1 = near.
// Cues: lower in the picture is usually nearer, in-focus detail is usually the subject, and
// saturated colours tend to be closer than hazy, washed-out background.
uniform highp sampler2D uTex;   // small 0..1 RGB picture
uniform vec2 uTexel;
in vec2 vTexCoords;
out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec3 c = texture(uTex, vTexCoords).rgb;
    float l = luma(c);
    // Local detail: difference to the 4 neighbours two texels away.
    float g = 0.0;
    g += abs(l - luma(texture(uTex, vTexCoords + vec2(2.0 * uTexel.x, 0.0)).rgb));
    g += abs(l - luma(texture(uTex, vTexCoords - vec2(2.0 * uTexel.x, 0.0)).rgb));
    g += abs(l - luma(texture(uTex, vTexCoords + vec2(0.0, 2.0 * uTexel.y)).rgb));
    g += abs(l - luma(texture(uTex, vTexCoords - vec2(0.0, 2.0 * uTexel.y)).rgb));
    float detail = clamp(g * 3.0, 0.0, 1.0);
    float sat = clamp((max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b))) * 1.5, 0.0, 1.0);
    // Texture y runs upwards: the bottom of the picture is y = 0.
    float low = 1.0 - vTexCoords.y;
    float centre = 1.0 - abs(vTexCoords.x - 0.5) * 0.6;
    float d = 0.55 * low * centre + 0.30 * detail + 0.15 * sat;
    outColor = vec4(clamp(d, 0.0, 1.0), 0.0, 0.0, 1.0);
}
