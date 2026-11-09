#version 300 es
precision highp float;
// Full-resolution linear RGB -> perceptual luma at the motion-search resolution.
uniform sampler2D uTex;
uniform vec2 uOutTexel;     // 1.0 / output size
uniform vec3 uCoeffs;       // BT.709 or BT.2020 luma weights
uniform float uLumaScale;   // brings mid-tones to ~1 before the soft curve
in vec2 vTexCoords;
out vec4 outColor;

float lum(vec2 uv) {
    float y = max(dot(texture(uTex, uv).rgb, uCoeffs) * uLumaScale, 0.0);
    return pow(y / (1.0 + y), 0.6);
}

void main() {
    vec2 d = uOutTexel * 0.25;
    float l = 0.25 * (lum(vTexCoords + vec2(-d.x, -d.y)) + lum(vTexCoords + vec2(d.x, -d.y))
                    + lum(vTexCoords + vec2(-d.x, d.y)) + lum(vTexCoords + vec2(d.x, d.y)));
    outColor = vec4(l, l, l, 1.0);
}
