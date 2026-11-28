#version 300 es
precision highp float;
// Blends the newest depth into the running depth map (steadier, no flicker). The rough
// picture-cue depth is softened a little; AI depth keeps its sharp edges.
uniform highp sampler2D uNew;
uniform highp sampler2D uPrev;
uniform vec2 uTexel;
uniform float uMix;   // share of the new depth (1 = replace, e.g. after a cut or seek)
uniform float uBlur;  // 1 = soften (rough picture-cue depth), 0 = keep the AI depth's edges sharp
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
    // Near objects grow by one texel each side (max), so their edges don't carry a background halo.
    float sharp = max(texture(uNew, vTexCoords).r,
                  max(texture(uNew, vTexCoords - vec2(uTexel.x, 0.0)).r,
                      texture(uNew, vTexCoords + vec2(uTexel.x, 0.0)).r));
    n = (uBlur > 0.5) ? max(n, sharp) : sharp;
    float p = texture(uPrev, vTexCoords).r;
    outColor = vec4(mix(p, n, uMix), 0.0, 0.0, 1.0);
}
