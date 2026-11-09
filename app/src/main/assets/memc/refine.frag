#version 300 es
precision highp float;
precision highp int;
// Fine block matching at the working resolution, seeded by the coarse vectors
// (own block, its four neighbours and zero), each refined within +-2 pixels.
uniform highp sampler2D uCur;
uniform highp sampler2D uPrev;
uniform highp sampler2D uCoarse;    // coarse vectors (pixels of the half-size level)
uniform vec2 uSizeF;                // working luma size
uniform vec2 uCoarseBlocksF;        // coarse grid size
uniform int uBlock;
out vec4 outColor;

float sad(ivec2 o, ivec2 v) {
    float s = 0.0;
    ivec2 hi = ivec2(uSizeF) - 1;
    for (int y = 0; y < uBlock; y++) {
        for (int x = 0; x < uBlock; x++) {
            ivec2 p = o + ivec2(x, y);
            s += abs(texelFetch(uCur, clamp(p, ivec2(0), hi), 0).r
                   - texelFetch(uPrev, clamp(p + v, ivec2(0), hi), 0).r);
        }
    }
    return s / float(uBlock * uBlock);
}

vec2 coarseAt(ivec2 c) {
    return texelFetch(uCoarse, clamp(c, ivec2(0), ivec2(uCoarseBlocksF) - 1), 0).xy * 2.0;
}

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy);
    ivec2 o = b * uBlock;
    ivec2 cb = b / 2;
    vec2 preds[6];
    preds[0] = coarseAt(cb);
    preds[1] = coarseAt(cb + ivec2(1, 0));
    preds[2] = coarseAt(cb - ivec2(1, 0));
    preds[3] = coarseAt(cb + ivec2(0, 1));
    preds[4] = coarseAt(cb - ivec2(0, 1));
    preds[5] = vec2(0.0);

    float best = 1e9;
    ivec2 bv = ivec2(0);
    for (int i = 0; i < 6; i++) {
        ivec2 c0 = ivec2(round(preds[i]));
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                ivec2 v = c0 + ivec2(dx, dy);
                float c = sad(o, v) + 0.0006 * length(vec2(v));
                if (v == ivec2(0)) c -= 0.004;
                if (c < best) {
                    best = c;
                    bv = v;
                }
            }
        }
    }
    outColor = vec4(vec2(bv), sad(o, bv), 1.0);
}
