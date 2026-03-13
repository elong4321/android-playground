package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Diamond / prismatic crystallization effect.
 * Pipeline:
 * 1) coarse sample
 * 2) gaussian blur
 * 3) diamond crystallization with random spacing
 */
public class PrismaticFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uCellSize;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uBlurStrength;\n"
            + "uniform float uSpacingJitter;\n"
            + "uniform float uEdgeHighlight;\n"
            + "uniform float uEdgeWidth;\n"
            + "uniform float uWhiteThreshold;\n"
            + "uniform float uWhiteSoftness;\n"
            + "uniform float uFacetContrast;\n"
            + "\n"
            + "float hash12(vec2 p) {\n"
            + "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"
            + "    p3 += dot(p3, p3.yzx + 33.33);\n"
            + "    return fract((p3.x + p3.y) * p3.z);\n"
            + "}\n"
            + "\n"
            + "vec2 coarseSampleUv(vec2 uv) {\n"
            + "    if (uSampleScale <= 1.001) {\n"
            + "        return clamp(uv, 0.0, 1.0);\n"
            + "    }\n"
            + "    vec2 pixel = uv * uResolution;\n"
            + "    vec2 samplePixel = floor(pixel / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "    return clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "vec4 blurSample(vec2 uv) {\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec2 stepVec = texel * (uBlurStrength * 180.0);\n"
            + "    vec4 c = vec4(0.0);\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(-stepVec.x, -stepVec.y), 0.0, 1.0))) * 0.0625;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(0.0, -stepVec.y), 0.0, 1.0))) * 0.1250;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(stepVec.x, -stepVec.y), 0.0, 1.0))) * 0.0625;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(-stepVec.x, 0.0), 0.0, 1.0))) * 0.1250;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv, 0.0, 1.0))) * 0.2500;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(stepVec.x, 0.0), 0.0, 1.0))) * 0.1250;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(-stepVec.x, stepVec.y), 0.0, 1.0))) * 0.0625;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(0.0, stepVec.y), 0.0, 1.0))) * 0.1250;\n"
            + "    c += texture2D(sTexture, coarseSampleUv(clamp(uv + vec2(stepVec.x, stepVec.y), 0.0, 1.0))) * 0.0625;\n"
            + "    return c;\n"
            + "}\n"
            + "\n"
            + "vec2 nearestDiamondCenter(vec2 uv, float cellSizePx) {\n"
            + "    vec2 pixel = uv * uResolution;\n"
            + "    float s = max(cellSizePx, 1.0);\n"
            + "    vec2 diag = vec2(pixel.x + pixel.y, pixel.x - pixel.y);\n"
            + "    vec2 diagBase = floor(diag / s);\n"
            + "    vec2 bestCenter = pixel;\n"
            + "    float bestDist = 1e9;\n"
            + "    for (int j = -1; j <= 1; ++j) {\n"
            + "        for (int i = -1; i <= 1; ++i) {\n"
            + "            vec2 cell = diagBase + vec2(float(i), float(j));\n"
            + "            float jx = (hash12(cell + vec2(7.1, 13.7)) - 0.5) * uSpacingJitter * s;\n"
            + "            float jy = (hash12(cell + vec2(19.3, 3.9)) - 0.5) * uSpacingJitter * s;\n"
            + "            vec2 centerDiag = (cell + vec2(0.5)) * s + vec2(jx, jy);\n"
            + "            float cx = 0.5 * (centerDiag.x + centerDiag.y);\n"
            + "            float cy = 0.5 * (centerDiag.x - centerDiag.y);\n"
            + "            vec2 center = vec2(cx, cy);\n"
            + "            vec2 delta = center - pixel;\n"
            + "            float dist = abs(delta.x) + abs(delta.y);\n"
            + "            if (dist < bestDist) {\n"
            + "                bestDist = dist;\n"
            + "                bestCenter = center;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    return clamp(bestCenter / uResolution, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "float whiteMask(vec3 rgb) {\n"
            + "    float minCh = min(rgb.r, min(rgb.g, rgb.b));\n"
            + "    float maxCh = max(rgb.r, max(rgb.g, rgb.b));\n"
            + "    float saturation = maxCh - minCh;\n"
            + "    float whiteness = minCh;\n"
            + "    float threshold = clamp(uWhiteThreshold, 0.0, 1.0);\n"
            + "    float softness = max(uWhiteSoftness, 0.001);\n"
            + "    float brightMask = smoothstep(threshold - softness, threshold + softness, whiteness);\n"
            + "    float lowSaturationMask = 1.0 - smoothstep(0.08, 0.28, saturation);\n"
            + "    return clamp(brightMask * lowSaturationMask, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "float diamondEdgeMask(vec2 uv, vec2 centerUv, float cellSizePx) {\n"
            + "    vec2 pixel = uv * uResolution;\n"
            + "    vec2 center = centerUv * uResolution;\n"
            + "    float radius = max(cellSizePx * 0.5, 1.0);\n"
            + "    vec2 delta = abs(pixel - center);\n"
            + "    float diamondDist = (delta.x + delta.y) / radius;\n"
            + "    float edgeWidth = max(uEdgeWidth, 0.001);\n"
            + "    return smoothstep(1.0, 1.0 - edgeWidth, diamondDist);\n"
            + "}\n"
            + "\n"
            + "float facetShade(vec2 uv, vec2 centerUv, float cellSizePx) {\n"
            + "    vec2 pixel = uv * uResolution;\n"
            + "    vec2 center = centerUv * uResolution;\n"
            + "    float radius = max(cellSizePx * 0.5, 1.0);\n"
            + "    vec2 delta = (pixel - center) / radius;\n"
            + "    float n = hash12(floor(center * 0.1));\n"
            + "    vec2 dir = normalize(vec2(cos(n * 6.2831853), sin(n * 6.2831853)) + vec2(0.0001));\n"
            + "    float plane = dot(delta, dir);\n"
            + "    return plane * 0.5 + 0.5;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 base = blurSample(textureCoordinate);\n"
            + "    float crystalMask = whiteMask(base.rgb);\n"
            + "    if (crystalMask <= 0.001) {\n"
            + "        gl_FragColor = base;\n"
            + "        return;\n"
            + "    }\n"
            + "    vec2 sampleUv = nearestDiamondCenter(textureCoordinate, uCellSize);\n"
            + "    vec4 color = blurSample(sampleUv);\n"
            + "    float edge = diamondEdgeMask(textureCoordinate, sampleUv, uCellSize);\n"
            + "    float facet = facetShade(textureCoordinate, sampleUv, uCellSize);\n"
            + "    float facetContrast = clamp(uFacetContrast, 0.0, 1.0);\n"
            + "    vec3 faceted = color.rgb * mix(0.78, 1.22, facet * facetContrast + (1.0 - facetContrast) * 0.5);\n"
            + "    vec3 highlighted = mix(faceted, min(faceted + vec3(1.0), vec3(1.0)), edge * clamp(uEdgeHighlight, 0.0, 1.0));\n"
            + "    vec4 crystal = vec4(highlighted, color.a);\n"
            + "    gl_FragColor = mix(base, crystal, clamp(crystalMask * 1.15, 0.0, 1.0));\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int cellSizeHandle = -1;
    private int sampleScaleHandle = -1;
    private int blurStrengthHandle = -1;
    private int spacingJitterHandle = -1;
    private int edgeHighlightHandle = -1;
    private int edgeWidthHandle = -1;
    private int whiteThresholdHandle = -1;
    private int whiteSoftnessHandle = -1;
    private int facetContrastHandle = -1;

    private float cellSizePx = 24f;
    private float sampleScalePx = 6f;
    private float blurStrength = 0.025f;
    private float spacingJitter = 0.6f;
    private float edgeHighlight = 0.35f;
    private float edgeWidth = 0.12f;
    private float whiteThreshold = 0.82f;
    private float whiteSoftness = 0.08f;
    private float facetContrast = 0.65f;

    public PrismaticFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        cellSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCellSize");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        blurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurStrength");
        spacingJitterHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSpacingJitter");
        edgeHighlightHandle = GLES20.glGetUniformLocation(mProgramHandle, "uEdgeHighlight");
        edgeWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uEdgeWidth");
        whiteThresholdHandle = GLES20.glGetUniformLocation(mProgramHandle, "uWhiteThreshold");
        whiteSoftnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uWhiteSoftness");
        facetContrastHandle = GLES20.glGetUniformLocation(mProgramHandle, "uFacetContrast");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(cellSizeHandle, Math.max(1.0f, cellSizePx));
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScalePx));
        GLES20.glUniform1f(blurStrengthHandle, clamp(blurStrength, 0.0f, 0.12f));
        GLES20.glUniform1f(spacingJitterHandle, clamp(spacingJitter, 0.0f, 1.4f));
        GLES20.glUniform1f(edgeHighlightHandle, clamp(edgeHighlight, 0.0f, 1.0f));
        GLES20.glUniform1f(edgeWidthHandle, clamp(edgeWidth, 0.01f, 0.6f));
        GLES20.glUniform1f(whiteThresholdHandle, clamp(whiteThreshold, 0.0f, 1.0f));
        GLES20.glUniform1f(whiteSoftnessHandle, clamp(whiteSoftness, 0.001f, 0.5f));
        GLES20.glUniform1f(facetContrastHandle, clamp(facetContrast, 0.0f, 1.0f));
    }

    public PrismaticFilter setCellSizePx(float cellSizePx) {
        this.cellSizePx = Math.max(1.0f, cellSizePx);
        return this;
    }

    public PrismaticFilter setSampleScalePx(float sampleScalePx) {
        this.sampleScalePx = Math.max(1.0f, sampleScalePx);
        return this;
    }

    public PrismaticFilter setBlurStrength(float blurStrength) {
        this.blurStrength = clamp(blurStrength, 0.0f, 0.12f);
        return this;
    }

    public PrismaticFilter setSpacingJitter(float spacingJitter) {
        this.spacingJitter = clamp(spacingJitter, 0.0f, 1.4f);
        return this;
    }

    public PrismaticFilter setEdgeHighlight(float edgeHighlight) {
        this.edgeHighlight = clamp(edgeHighlight, 0.0f, 1.0f);
        return this;
    }

    public PrismaticFilter setEdgeWidth(float edgeWidth) {
        this.edgeWidth = clamp(edgeWidth, 0.01f, 0.6f);
        return this;
    }

    public PrismaticFilter setWhiteThreshold(float whiteThreshold) {
        this.whiteThreshold = clamp(whiteThreshold, 0.0f, 1.0f);
        return this;
    }

    public PrismaticFilter setWhiteSoftness(float whiteSoftness) {
        this.whiteSoftness = clamp(whiteSoftness, 0.001f, 0.5f);
        return this;
    }

    public PrismaticFilter setFacetContrast(float facetContrast) {
        this.facetContrast = clamp(facetContrast, 0.0f, 1.0f);
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
