#version 300 es
precision highp float;
// Builds the side-by-side 3D frame: left eye in the left half, right eye in the right half.
//  uMode 0: 2D to 3D. Each eye is the source picture shifted by its depth (depth-image-based
//           rendering): near things move apart, far things stay put. The source position is
//           found by a few fixed-point steps (backward mapping), which also stretches the
//           background into the small gaps that open beside near objects.
//  uMode 1: half side-by-side video (each eye squeezed into half the width): stretched back.
//  uMode 2: full side-by-side video: copied as it is.
uniform highp sampler2D uSrc;
uniform highp sampler2D uDepth;
uniform int uMode;
uniform float uStrength;  // largest shift, as a fraction of the eye's width
uniform float uConv;      // depth of the screen plane (0 far .. 1 near)
uniform int uSwap;        // 1 = swap the eyes (for glasses wired the other way round)
in vec2 vTexCoords;
out vec4 outColor;

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
    float dir = srcRight ? -1.0 : 1.0;
    float x = uv.x;
    for (int i = 0; i < 5; i++) {
        float d = texture(uDepth, vec2(x, uv.y)).r;
        x = uv.x - dir * uStrength * (d - uConv);
    }
    outColor = texture(uSrc, vec2(clamp(x, 0.0, 1.0), uv.y));
}
