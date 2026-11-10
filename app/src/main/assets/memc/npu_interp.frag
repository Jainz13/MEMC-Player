#version 300 es
precision highp float;
// Builds the in-between frame from the motion the AI model (RIFE on the NPU) estimated.
// uFlow: xy = where this pixel comes from in the previous frame, zw = in the current frame,
//        as offsets in pixels of the model's input size.
// uMask: how much of the previous-frame side to use (0..1).
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform sampler2D uFlow;
uniform sampler2D uMask;
uniform vec2 uFlowToUv;   // 1.0 / model input size
in vec2 vTexCoords;
out vec4 outColor;
void main() {
    vec4 flow = texture(uFlow, vTexCoords);
    float m = clamp(texture(uMask, vTexCoords).r, 0.0, 1.0);
    vec4 p = texture(uPrev, vTexCoords + flow.xy * uFlowToUv);
    vec4 c = texture(uCur, vTexCoords + flow.zw * uFlowToUv);
    outColor = mix(c, p, m);
}
