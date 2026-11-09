#version 300 es
precision highp float;
precision highp int;
// Coarse block matching: for each block of the current frame, finds where it was
// in the previous frame (full search within +-uRange pixels).
// Output: xy = vector current->previous in pixels of this level, z = mean abs difference.
uniform highp sampler2D uCur;
uniform highp sampler2D uPrev;
uniform vec2 uSizeF;    // luma texture size
uniform int uBlock;     // block size in pixels
uniform int uStep;      // pixel step inside a block (2 = every other pixel)
uniform int uRange;     // search radius in pixels
out vec4 outColor;

float sad(ivec2 o, ivec2 v) {
    float s = 0.0;
    float n = 0.0;
    ivec2 hi = ivec2(uSizeF) - 1;
    for (int y = 0; y < uBlock; y += uStep) {
        for (int x = 0; x < uBlock; x += uStep) {
            ivec2 p = o + ivec2(x, y);
            float c = texelFetch(uCur, clamp(p, ivec2(0), hi), 0).r;
            float q = texelFetch(uPrev, clamp(p + v, ivec2(0), hi), 0).r;
            s += abs(c - q);
            n += 1.0;
        }
    }
    return s / n;
}

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy);
    ivec2 o = b * uBlock;
    float zero = sad(o, ivec2(0));
    float best = zero - 0.004;          // prefer "no motion" when it is nearly as good
    ivec2 bv = ivec2(0);
    for (int dy = -uRange; dy <= uRange; dy++) {
        for (int dx = -uRange; dx <= uRange; dx++) {
            ivec2 v = ivec2(dx, dy);
            float c = sad(o, v) + 0.0006 * length(vec2(v));
            if (c < best) {
                best = c;
                bv = v;
            }
        }
    }
    float cost = (bv == ivec2(0)) ? zero : sad(o, bv);
    outColor = vec4(vec2(bv), cost, 1.0);
}
