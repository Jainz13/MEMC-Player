#version 300 es
precision highp float;
// Builds the in-between frame at time uAlpha (0 = previous, 1 = current).
// Each pixel follows its motion vector back into both frames and blends them.
// Where motion is unreliable (bad match, occlusion, scene cut) it falls back to
// the nearest real frame instead of smearing.
uniform sampler2D uPrev;
uniform sampler2D uCur;
uniform sampler2D uMv;      // xy = current->previous vector (working pixels), z = match cost
uniform float uAlpha;
uniform vec2 uMvToUv;       // 1.0 / working size
uniform vec3 uCoeffs;
uniform float uLumaScale;
uniform vec2 uCost;         // cost range mapped to 0..1 "unreliable"
in vec2 vTexCoords;
out vec4 outColor;

float lum(vec3 c) {
    float y = max(dot(c, uCoeffs) * uLumaScale, 0.0);
    return pow(y / (1.0 + y), 0.6);
}

void main() {
    vec4 mv = texture(uMv, vTexCoords);
    vec2 v = mv.xy * uMvToUv;
    vec4 p = texture(uPrev, vTexCoords + uAlpha * v);
    vec4 c = texture(uCur, vTexCoords - (1.0 - uAlpha) * v);
    vec4 blended = mix(p, c, uAlpha);

    float bad = smoothstep(uCost.x, uCost.y, mv.z);
    bad = max(bad, smoothstep(0.10, 0.28, abs(lum(p.rgb) - lum(c.rgb))));
    vec4 nearest = uAlpha < 0.5 ? texture(uPrev, vTexCoords) : texture(uCur, vTexCoords);
    outColor = mix(blended, nearest, bad);
}
