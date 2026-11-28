#version 300 es
precision highp float;
// Builds the side-by-side 3D frame: left eye in the left half, right eye in the right half.
//  uMode 0: 2D to 3D. Each eye is the source picture shifted by its depth (depth-image-based
//           rendering): near things move apart, far things stay put. Near objects hide what
//           is behind them, and gaps that open beside them are filled from the background.
//  uMode 1: half side-by-side video (each eye squeezed into half the width): stretched back.
//  uMode 2: full side-by-side video: copied as it is.
uniform highp sampler2D uSrc;
uniform highp sampler2D uDepth;
uniform int uMode;
uniform float uStrength;  // largest shift, as a fraction of the eye's width
uniform float uConv;      // depth of the screen plane (0 far .. 1 near)
uniform int uSwap;        // 1 = swap the eyes (for glasses wired the other way round)
uniform float uDepthTexel; // one depth-map texel across
in vec2 vTexCoords;
out vec4 outColor;

// Depth at x (already grown a little around near objects by the smoothing pass, so their
// outline carries no fringe of the background).
float depthAt(float x, float y) {
    return texture(uDepth, vec2(x, y)).r;
}

void main() {
    bool rightEye = vTexCoords.x >= 0.5;
    vec2 uv = vec2(fract(vTexCoords.x * 2.0), vTexCoords.y);
    if (uMode == 2) {
        outColor = texture(uSrc, vTexCoords);
        return;
    }
    bool srcRight = (uSwap == 1) ? !rightEye : rightEye;
    if (uMode == 1) {
        outColor = texture(uSrc, vec2((srcRight ? 0.5 : 0.0) + uv.x * 0.5, uv.y));
        return;
    }
    // Left eye sees near objects shifted right, right eye shifted left (in front of the screen).
    // Each source point x lands at x + dir * S * (depth(x) - conv) in this eye. Walk the source
    // positions that could land on this pixel and find where one does: where several do, the
    // nearest wins (it hides the others), so the edges of near objects stay sharp. Where none
    // does, the background behind a near object has come into view: fill it from the background
    // side, like make-it-3d's z-ordered splatting.
    float dir = srcRight ? -1.0 : 1.0;
    float S = uStrength;
    float u = uv.x;
    float a = u + dir * S * uConv;
    float b = u - dir * S * (1.0 - uConv);
    float xs = min(a, b);
    float xe = max(a, b);
    float step = 0.5 * max(uDepthTexel, 1e-4);
    int n = int(min(ceil((xe - xs) / step), 64.0));
    float bestD = -1.0;
    float bestX = u;
    float minD = 1.0;
    float px = xs;
    float pf = px + dir * S * (depthAt(px, uv.y) - uConv) - u;
    for (int i = 1; i <= 64; i++) {
        if (i > n) break;
        float x = xs + float(i) * step;
        float d = depthAt(x, uv.y);
        minD = min(minD, d);
        float f = x + dir * S * (d - uConv) - u;
        // A crossing on a steep step in depth only smears the object's edge pixels across the
        // gap (rubber-sheet look): count it only where the picture is stretched less than 2x.
        float stretch = abs(f - pf) / step;
        if (stretch < 2.0 && ((pf <= 0.0 && f >= 0.0) || (pf >= 0.0 && f <= 0.0))) {
            // Crosses this pixel between the last two samples: the exact spot by interpolation.
            float t = (abs(f - pf) > 1e-7) ? pf / (pf - f) : 0.5;
            float xc = mix(px, x, t);
            float dc = depthAt(xc, uv.y);
            if (dc > bestD) {
                bestD = dc;
                bestX = xc;
            }
        }
        px = x;
        pf = f;
    }
    if (bestD < 0.0) {
        // Uncovered background: shift as the farthest thing nearby would be.
        bestX = u - dir * S * (minD - uConv);
    }
    outColor = texture(uSrc, vec2(clamp(bestX, 0.0, 1.0), uv.y));
}
