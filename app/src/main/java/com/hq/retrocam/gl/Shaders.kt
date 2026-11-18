package com.hq.retrocam.gl

object Shaders {

    const val VERTEX = """#version 300 es
in vec4 aPos;
in vec2 aUv;
uniform float uFlipY;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos.x, aPos.y * uFlipY, 0.0, 1.0);
}
"""

    private const val HEADER_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
"""

    private const val HEADER_2D = """#version 300 es
precision highp float;
uniform sampler2D uTex;
"""

    /**
     * Shared look pipeline. vUv is upright content space (0,0 bottom-left).
     * uTexMatrix maps content uv -> texture coords (SurfaceTexture matrix for camera).
     */
    private const val BODY = """
in vec2 vUv;
out vec4 fragColor;
uniform mat4 uTexMatrix;
uniform int uStyle;
uniform float uIntensity;
uniform float uGrain;
uniform float uVignette;
uniform float uLeak;
uniform float uTime;
uniform float uAspect;   // width / height of (cropped) content
uniform vec4 uCrop;      // xy offset, zw scale into full content uv
uniform float uPeaking;
uniform float uZebra;
uniform vec2 uTexel;     // one source pixel in output uv

vec3 src(vec2 uv) {
    uv = uCrop.xy + clamp(uv, vec2(0.001), vec2(0.999)) * uCrop.zw;
    return texture(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

uint hashu(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v.x ^ v.y ^ v.z;
}
float hash(vec2 p, float t) {
    uvec3 u = uvec3(uvec2(ivec2(floor(p)) + 65536), uint(t));
    return float(hashu(u) & 0xFFFFFFu) / 16777215.0;
}

vec3 sat(vec3 c, float s) { float l = luma(c); return mix(vec3(l), c, s); }
vec3 contrast(vec3 c, float k, float pivot) { return (c - pivot) * k + pivot; }
vec3 sCurve(vec3 c, float k) {
    vec3 x = clamp(c, 0.0, 1.0);
    vec3 s = x * x * (3.0 - 2.0 * x);
    return mix(x, s, k);
}
vec3 lift(vec3 c, float l) { return c * (1.0 - l) + l; }
vec3 splitTone(vec3 c, vec3 shadow, vec3 high) {
    float l = luma(c);
    return c + shadow * (1.0 - smoothstep(0.0, 0.55, l)) + high * smoothstep(0.45, 1.0, l);
}
vec3 softClip(vec3 c) { vec3 x = max(c - 0.75, 0.0); return min(c, vec3(0.75)) + 0.25 * (1.0 - exp(-x * 4.0)); }

vec3 blur5(vec2 uv, vec2 px) {
    vec3 c = src(uv) * 0.4;
    c += src(uv + vec2(px.x, 0.0)) * 0.15;
    c += src(uv - vec2(px.x, 0.0)) * 0.15;
    c += src(uv + vec2(0.0, px.y)) * 0.15;
    c += src(uv - vec2(0.0, px.y)) * 0.15;
    return c;
}

const mat3 RGB2YIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
const mat3 YIQ2RGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);

// Low-res video look: soft luma with edge enhancement, smeared chroma.
vec3 videoLook(vec2 uv, float lines, float chromaSmear, float sharpen) {
    vec2 px = vec2(1.0 / (lines * uAspect), 1.0 / lines);
    vec3 soft = blur5(uv, px);
    vec3 wider = blur5(uv, px * 2.0);
    float y = luma(soft) + (luma(soft) - luma(wider)) * sharpen;
    vec3 ch = vec3(0.0);
    float o = px.x * chromaSmear;
    ch += src(uv + vec2(o * 0.5, 0.0));
    ch += src(uv + vec2(o * 1.5, 0.0));
    ch += src(uv + vec2(o * 2.5, 0.0));
    ch += src(uv - vec2(o * 0.5, 0.0));
    ch /= 4.0;
    vec3 yiq = RGB2YIQ * ch;
    yiq.x = y;
    return YIQ2RGB * yiq;
}

vec3 styleDV(vec2 uv) {
    vec3 c = videoLook(uv, 640.0, 3.0, 0.9);
    c = sat(c, 1.18);
    c = lift(c, 0.035);
    c += vec3(-0.012, 0.0, 0.03);
    c = min(c * 1.07, vec3(1.0));
    float scan = 0.96 + 0.04 * sin(uv.y * 640.0 * 3.14159);
    c *= scan;
    // a touch of chroma noise
    float n = hash(uv * vec2(480.0 * uAspect, 480.0), uTime * 30.0) - 0.5;
    c += vec3(n * 0.02, -n * 0.01, n * 0.025);
    return c;
}

vec3 styleCCD(vec2 uv) {
    vec2 px = vec2(1.0 / (1300.0 * uAspect), 1.0 / 1300.0);
    vec3 s = blur5(uv, px);
    vec3 c = src(uv);
    c = c + (c - s) * 1.2; // crisp digital sharpening
    c *= 1.1;
    c = contrast(c, 1.12, 0.45);
    c = sat(c, 1.22);
    c = splitTone(c, vec3(-0.02, 0.03, 0.06), vec3(0.03, 0.02, 0.0));
    float l = luma(c);
    c = mix(c, vec3(1.0), smoothstep(0.82, 1.05, l) * 0.5); // blown highlights
    return c;
}

vec3 stylePortra(vec2 uv) {
    vec3 c = src(uv);
    c += vec3(0.04, 0.012, -0.03);
    c = contrast(c, 0.9, 0.5);
    c = lift(c, 0.03);
    c = sat(c, 0.93);
    c = splitTone(c, vec3(0.0, 0.01, 0.02), vec3(0.03, 0.015, -0.01));
    c = softClip(c);
    return c;
}

vec3 styleClassicNeg(vec2 uv) {
    vec3 c = src(uv);
    c = sCurve(c, 0.75);
    c = sat(c, 0.82);
    c = splitTone(c, vec3(-0.03, 0.04, 0.02), vec3(0.05, 0.01, -0.03));
    c.r = c.r * 0.97 + 0.02;
    c.b *= 0.94;
    return c;
}

vec3 styleNostalgic(vec2 uv) {
    vec3 c = src(uv);
    c = contrast(c, 0.88, 0.5);
    c = lift(c, 0.045);
    c = splitTone(c, vec3(0.02, 0.0, -0.01), vec3(0.08, 0.04, -0.05));
    c = sat(c, 0.95);
    c = softClip(c * 1.03);
    return c;
}

vec3 styleChrome(vec2 uv) {
    vec3 c = src(uv);
    c = sCurve(c, 0.9);
    c = contrast(c, 1.08, 0.5);
    c = sat(c, 1.28);
    c = splitTone(c, vec3(0.0, 0.0, 0.03), vec3(0.02, 0.01, 0.0));
    c.b = mix(c.b, c.b * 0.9, smoothstep(0.3, 0.8, c.b - c.r));
    return c;
}

vec3 styleBW(vec2 uv) {
    vec3 c = src(uv);
    float l = dot(c, vec3(0.42, 0.46, 0.12));
    vec3 g = vec3(l);
    g = sCurve(g, 1.0);
    g = contrast(g, 1.1, 0.5);
    g = lift(g, 0.02);
    return g;
}

vec3 styleVHS(vec2 uv) {
    float t = uTime;
    float wob = sin(uv.y * 40.0 + t * 3.0) * 0.0008 + sin(uv.y * 300.0 + t * 20.0) * 0.0004;
    // tracking band rolling up the frame
    float bandY = fract(t * 0.07);
    float band = smoothstep(0.03, 0.0, abs(uv.y - bandY));
    wob += band * (hash(vec2(uv.y * 600.0, 0.0), t * 60.0) - 0.5) * 0.02;
    vec2 u = vec2(uv.x + wob, uv.y);
    vec3 c = videoLook(u, 480.0, 7.0, 0.5);
    c = sat(c, 0.9);
    c = lift(c, 0.06);
    c += vec3(0.02, -0.01, 0.025);
    c = contrast(c, 0.95, 0.5);
    c += band * (hash(uv * vec2(400.0, 300.0), t * 60.0) * 0.25);
    return c;
}

vec3 styleSuper8(vec2 uv) {
    float f = floor(uTime * 18.0);
    vec2 weave = vec2(hash(vec2(f, 1.0), 7.0) - 0.5, hash(vec2(f, 2.0), 9.0) - 0.5) * vec2(0.003, 0.004);
    vec2 u = uv + weave;
    vec2 px = vec2(1.0 / (700.0 * uAspect), 1.0 / 700.0);
    vec3 c = blur5(u, px);
    c = contrast(c, 0.92, 0.5);
    c = lift(c, 0.07);
    c = splitTone(c, vec3(0.01, 0.0, -0.01), vec3(0.09, 0.04, -0.06));
    c = sat(c, 0.88);
    c *= 0.97 + 0.06 * hash(vec2(f, 3.0), 11.0);
    return c;
}

vec3 applyStyle(vec2 uv) {
    if (uStyle == 1) return styleDV(uv);
    if (uStyle == 2) return styleCCD(uv);
    if (uStyle == 3) return stylePortra(uv);
    if (uStyle == 4) return styleClassicNeg(uv);
    if (uStyle == 5) return styleNostalgic(uv);
    if (uStyle == 6) return styleChrome(uv);
    if (uStyle == 7) return styleBW(uv);
    if (uStyle == 8) return styleVHS(uv);
    if (uStyle == 9) return styleSuper8(uv);
    return src(uv);
}

void main() {
    vec2 uv = vUv;
    vec3 orig = src(uv);
    vec3 c = applyStyle(uv);
    c = mix(orig, c, uIntensity);

    // light leak (screen blend, slowly drifting warm glow from the right edge)
    if (uLeak > 0.0) {
        vec2 p = vec2(1.05 + 0.05 * sin(uTime * 0.3), 0.75 + 0.15 * sin(uTime * 0.17));
        vec2 d = (uv - p) * vec2(uAspect, 1.0);
        float g = exp(-dot(d, d) * 6.0);
        vec2 p2 = vec2(-0.05, 0.15 + 0.1 * cos(uTime * 0.21));
        vec2 d2 = (uv - p2) * vec2(uAspect, 1.0);
        float g2 = exp(-dot(d2, d2) * 10.0) * 0.6;
        vec3 leak = vec3(1.0, 0.42, 0.12) * g + vec3(1.0, 0.25, 0.2) * g2;
        c = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - leak * uLeak);
    }

    // vignette
    if (uVignette > 0.0) {
        vec2 d = (uv - 0.5) * vec2(uAspect, 1.0) / max(uAspect, 1.0);
        float v = smoothstep(0.85, 0.2, length(d) * 1.25);
        c *= mix(1.0, v, uVignette);
    }

    // film grain, strongest in the midtones, ~900 grains across the short side
    if (uGrain > 0.0) {
        vec2 grid = uv * vec2(900.0 * uAspect / min(uAspect, 1.0), 900.0 / min(uAspect, 1.0)) * 0.6;
        float t = floor(uTime * 24.0);
        float n = (hash(grid, t) + hash(grid + 17.0, t + 3.0)) - 1.0;
        float l = luma(c);
        float amt = uGrain * 0.11 * (1.0 - abs(l - 0.5) * 1.3);
        c += n * amt;
    }

    // focus peaking (Sobel on source luma)
    if (uPeaking > 0.0) {
        vec2 t = uTexel * 1.5;
        float tl = luma(src(uv + vec2(-t.x, t.y))), tc = luma(src(uv + vec2(0.0, t.y))), tr = luma(src(uv + t));
        float ml = luma(src(uv - vec2(t.x, 0.0))), mr = luma(src(uv + vec2(t.x, 0.0)));
        float bl = luma(src(uv - t)), bc = luma(src(uv - vec2(0.0, t.y))), br = luma(src(uv + vec2(t.x, -t.y)));
        float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
        float gy = (tl + 2.0 * tc + tr) - (bl + 2.0 * bc + br);
        float e = length(vec2(gx, gy));
        c = mix(c, vec3(1.0, 0.12, 0.08), smoothstep(0.22, 0.4, e));
    }

    // zebra stripes on clipped highlights
    if (uZebra > 0.0) {
        float l = luma(c);
        if (l > 0.95) {
            float st = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 14.0));
            c = mix(c, vec3(0.05), st * 0.7);
        }
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    const val FRAGMENT_OES = HEADER_OES + BODY
    const val FRAGMENT_2D = HEADER_2D + BODY

    const val OVERLAY_FRAGMENT = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
void main() {
    fragColor = texture(uTex, vec2(vUv.x, 1.0 - vUv.y));
}
"""
}
