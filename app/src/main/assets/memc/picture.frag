#version 300 es
precision highp float;
// Picture adjustments that run before MEMC in the effect pipeline.
//   uMode 0: SDR video  - brightness boost on the gamma-encoded BT.709 picture
//                         (colour-preserving, soft highlight roll-off)
//   uMode 1: HDR video  - brightness boost in nits with a soft knee towards the panel peak
//   uMode 2: Auto HDR   - the SDR video was flagged as PQ BT.2020 so the pipeline runs in HDR;
//                         undo that decoding, recover the original BT.709 picture, and expand
//                         it into real HDR (inverse tone mapping).
// Working-space conventions (Media3): HDR = linear BT.2020, 1.0 = 1000 nits.
// Brightness is always changed by scaling all three channels of a pixel by the same factor,
// so hue and saturation stay put (no washed-out look).
uniform sampler2D uTex;
uniform int uMode;
uniform float uBoost;          // 0..1
uniform float uSdrWhiteNits;   // Auto HDR: where SDR white lands
uniform float uPeakNits;       // highlight ceiling
in vec2 vTexCoords;
out vec4 outColor;

const vec3 K709 = vec3(0.2126, 0.7152, 0.0722);
const vec3 K2020 = vec3(0.2627, 0.6780, 0.0593);

// YUV (offsets removed, range-scaled) -> RGB, full-range forms. Column-major.
const mat3 YUV_TO_RGB_709 = mat3(1.0, 1.0, 1.0,  0.0, -0.1873, 1.8556,  1.5748, -0.4681, 0.0);
const mat3 YUV_TO_RGB_2020 = mat3(1.0, 1.0, 1.0,  0.0, -0.16455, 1.8814,  1.4746, -0.57135, 0.0);
// Linear BT.709 primaries -> BT.2020 primaries. Column-major.
const mat3 P709_TO_2020 = mat3(0.6274, 0.0691, 0.0164,  0.3293, 0.9195, 0.0880,  0.0433, 0.0114, 0.8956);

const float M1 = 2610.0 / 16384.0;
const float M2 = (2523.0 / 4096.0) * 128.0;
const float C1 = 3424.0 / 4096.0;
const float C2 = (2413.0 / 4096.0) * 32.0;
const float C3 = (2392.0 / 4096.0) * 32.0;

// Linear (1.0 = 10000 nits) -> PQ signal; exact inverse of Media3's pqEotf.
vec3 pqOetf(vec3 l) {
    vec3 p = pow(clamp(l, 0.0, 1.0), vec3(M1));
    return pow((C1 + C2 * p) / (1.0 + C3 * p), vec3(M2));
}

// Soft knee: identity below `knee`, then approaches `ceiling` asymptotically.
float softKnee(float x, float knee, float ceiling) {
    if (x <= knee) return x;
    float r = ceiling - knee;
    return knee + r * (1.0 - exp(-(x - knee) / r));
}

vec3 scaleTo(vec3 rgb, float lumIn, float lumOut) {
    return rgb * (lumOut / max(lumIn, 1e-6));
}

void main() {
    vec4 c = texture(uTex, vTexCoords);
    vec3 rgb = c.rgb;

    if (uMode == 2) {
        // 1. Back to the clamped PQ-coded RGB the pipeline computed with the BT.2020 matrix.
        vec3 coded = pqOetf(rgb / 10.0);
        // 2. Undo that matrix and apply the BT.709 one the video was really encoded with.
        vec3 yuv = inverse(YUV_TO_RGB_2020) * coded;
        vec3 sdr = clamp(YUV_TO_RGB_709 * yuv, 0.0, 1.0);
        // 3. SDR display light (BT.1886, gamma 2.4).
        vec3 lin = pow(sdr, vec3(2.4));
        float ys = dot(lin, K709);
        // 4. Inverse tone map on luminance: SDR white -> uSdrWhiteNits, the top of the range
        //    expands smoothly towards uPeakNits (highlights get real HDR punch).
        float white = uSdrWhiteNits;
        float highlight = smoothstep(0.55, 1.0, ys);
        float nits = white * ys + (uPeakNits - white) * highlight * highlight * ys;
        vec3 outNits = P709_TO_2020 * scaleTo(lin, ys, nits);
        rgb = outNits / 1000.0;
    } else if (uBoost > 0.001) {
        if (uMode == 1) {
            float nits = dot(rgb, K2020) * 1000.0;
            float boosted = softKnee(nits * (1.0 + 0.8 * uBoost), 400.0, uPeakNits);
            rgb = scaleTo(rgb, nits, boosted);
        } else {
            // SDR frames are gamma-encoded here. Scaling R'G'B' together keeps hue and
            // saturation; x1.5 on the encoded signal is about x2.4 in light for mid-tones.
            float y = dot(rgb, K709);
            float boosted = softKnee(y * (1.0 + 0.5 * uBoost), 0.80, 1.0);
            rgb = scaleTo(rgb, y, boosted);
            float m = max(max(rgb.r, rgb.g), rgb.b);
            if (m > 1.0) rgb /= m;   // keep the hue when a channel would clip
        }
    }
    outColor = vec4(rgb, c.a);
}
