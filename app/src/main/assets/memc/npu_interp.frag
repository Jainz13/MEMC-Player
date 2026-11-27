#version 300 es
precision highp float;
// Builds the in-between frame from the motion the AI model (RIFE on the NPU) estimated.
// uFlow: the model's four flow planes stacked top to bottom in one single-channel texture
//        (x0, y0 = where this pixel comes from in the previous frame, x1, y1 = in the current
//        frame), as offsets in pixels of the model's input size.
// uMask: how much of the previous-frame side to use (0..1).
// Export runs the model for each exact moment (uFlowScale = 1,1). Live playback runs it once
// per source-frame pair at the midpoint and rescales the motion for other moments:
// uFlowScale = (alpha / 0.5, (1 - alpha) / 0.5), with the blend following alpha.
uniform highp sampler2D uPrev;
uniform highp sampler2D uCur;
uniform highp sampler2D uFlow;
uniform highp sampler2D uMask;
uniform vec2 uFlowToUv;        // 1.0 / model input size
uniform float uFlowHalfTexel;  // 0.5 / model input height
uniform vec2 uFlowScale;
uniform float uAlpha;
uniform int uTimeBlend;
in vec2 vTexCoords;
out vec4 outColor;

// One plane of the stacked flow texture. The row is kept half a texel inside the plane so
// filtering never blends in the neighbouring plane.
float plane(float k, vec2 uv) {
    float y = clamp(uv.y, uFlowHalfTexel, 1.0 - uFlowHalfTexel);
    return texture(uFlow, vec2(uv.x, (k + y) * 0.25)).r;
}

void main() {
    vec4 flow = vec4(plane(0.0, vTexCoords), plane(1.0, vTexCoords), plane(2.0, vTexCoords), plane(3.0, vTexCoords));
    float m = clamp(texture(uMask, vTexCoords).r, 0.0, 1.0);
    vec4 p = texture(uPrev, vTexCoords + flow.xy * uFlowScale.x * uFlowToUv);
    vec4 c = texture(uCur, vTexCoords + flow.zw * uFlowScale.y * uFlowToUv);
    float w = m;
    if (uTimeBlend == 1) {
        float a = (1.0 - uAlpha) * m;
        float b = uAlpha * (1.0 - m);
        w = a / max(a + b, 1e-4);
    }
    outColor = mix(c, p, w);
}
