#version 300 es
precision highp float;
// Blends the newest depth into the running depth map (steadier, no flicker) and softens it a
// little so edges don't tear when shifted.
uniform highp sampler2D uNew;
uniform highp sampler2D uPrev;
uniform vec2 uTexel;
uniform float uMix;   // share of the new depth (1 = replace, e.g. after a cut or seek)
in vec2 vTexCoords;
out vec4 outColor;

void main() {
    float n = 0.0;
    float w = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float k = (x == 0 && y == 0) ? 4.0 : ((x == 0 || y == 0) ? 2.0 : 1.0);
            n += texture(uNew, vTexCoords + vec2(float(x), float(y)) * uTexel).r * k;
            w += k;
        }
    }
    n /= w;
    float p = texture(uPrev, vTexCoords).r;
    outColor = vec4(mix(p, n, uMix), 0.0, 0.0, 1.0);
}
