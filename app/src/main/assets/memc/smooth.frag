#version 300 es
precision highp float;
precision highp int;
// Vector median over 3x3 blocks removes stray vectors; the match cost is then
// re-measured for the chosen vector so the interpolator knows how reliable it is.
uniform highp sampler2D uMv;
uniform highp sampler2D uCur;
uniform highp sampler2D uPrev;
uniform vec2 uBlocksF;
uniform vec2 uSizeF;
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

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy);
    vec2 n[9];
    int k = 0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            n[k] = texelFetch(uMv, clamp(b + ivec2(dx, dy), ivec2(0), ivec2(uBlocksF) - 1), 0).xy;
            k++;
        }
    }
    float bestSum = 1e9;
    vec2 m = n[4];
    for (int i = 0; i < 9; i++) {
        float s = 0.0;
        for (int j = 0; j < 9; j++) {
            s += length(n[i] - n[j]);
        }
        if (s < bestSum) {
            bestSum = s;
            m = n[i];
        }
    }
    outColor = vec4(m, sad(b * uBlock, ivec2(m)), 1.0);
}
