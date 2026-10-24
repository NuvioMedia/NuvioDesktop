//!HOOK LUMA
//!BIND HOOKED
//!DESC FidelityFX Contrast Adaptive Sharpening (CAS)

// AMD FidelityFX Contrast Adaptive Sharpening
// Ported for mpv LUMA hook with contrast adaptation

#define SHARPENING 0.9
#define CAS_BETTER_DIAGONALS 1

vec4 hook() {
    float e = HOOKED_tex(HOOKED_pos).r;
    float f = HOOKED_texOff(vec2(1.0, 0.0)).r;
    float h = HOOKED_texOff(vec2(0.0, 1.0)).r;
    float a = HOOKED_texOff(vec2(-1.0, -1.0)).r;
    float i = HOOKED_texOff(vec2(1.0, 1.0)).r;
    float b = HOOKED_texOff(vec2(0.0, -1.0)).r;
    float d = HOOKED_texOff(vec2(-1.0, 0.0)).r;
    float c = HOOKED_texOff(vec2(1.0, -1.0)).r;
    float g = HOOKED_texOff(vec2(-1.0, 1.0)).r;

    // Soft min and max across neighborhood
    float mnL = min(min(min(d, e), min(f, b)), h);
    float mxL = max(max(max(d, e), max(f, b)), h);
    float mnL2 = min(mnL, min(min(a, c), min(g, i)));
    mnL += mnL2;
    float mxL2 = max(mxL, max(max(a, c), max(g, i)));
    mxL += mxL2;

    // Smooth minimum distance to signal limit divided by smooth max
    float ampL = clamp(min(mnL, 2.0 - mxL) / max(mxL, 1e-5), 0.0, 1.0);
    ampL = sqrt(ampL);

    // Shaping amount of sharpening
    float peak = -(mix(8.0, 5.0, clamp(SHARPENING, 0.0, 1.0)));
    float wL = ampL / peak;

    // Filter weighting
    float Weight = 1.0 + 4.0 * wL;
    vec4 pix = vec4(0.0, 0.0, 0.0, 1.0);
    pix.r = clamp((((b + d + f + h) * wL) + e) / Weight, 0.0, 1.0);
    return pix;
}
