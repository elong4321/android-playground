package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * One-shot shake effect:
 * - quickly moves the frame left
 * - quickly pulls it back to center
 * - adds overscan scale, horizontal stretch and dynamic blur during the motion
 */
public class ShakeFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uScale;\n"
            + "uniform float uStretchX;\n"
            + "uniform float uOffsetX;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uSampleMix;\n"
            + "uniform float uSmearStrength;\n"
            + "uniform float uSoftBlurStrength;\n"
            + "\n"
            + "void main() {\n"
            + "    float scale = max(uScale, 1.0);\n"
            + "    float stretchX = max(uStretchX, 1.0);\n"
            + "    vec2 centered = textureCoordinate - vec2(0.5);\n"
            + "    vec2 scaledUv = centered / scale + vec2(0.5);\n"
            + "    vec2 stretchedUv = vec2(0.5 + (scaledUv.x - 0.5) / stretchX, scaledUv.y);\n"
            + "    stretchedUv.x -= uOffsetX;\n"
            + "    stretchedUv = clamp(stretchedUv, 0.0, 1.0);\n"
            + "\n"
            + "    vec2 sampledUv = stretchedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((stretchedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        sampledUv = mix(stretchedUv, coarseUv, clamp(uSampleMix, 0.0, 1.0));\n"
            + "    }\n"
            + "\n"
            + "    vec4 base = texture2D(sTexture, sampledUv);\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec2 smearStep = vec2(texel.x * uSmearStrength * 34.0, 0.0);\n"
            + "    vec4 smear = vec4(0.0);\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -4.0, 0.0, 1.0)) * 0.08;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -3.0, 0.0, 1.0)) * 0.12;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -2.0, 0.0, 1.0)) * 0.16;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -1.0, 0.0, 1.0)) * 0.18;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.18;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 1.0, 0.0, 1.0)) * 0.14;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 2.0, 0.0, 1.0)) * 0.09;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 3.0, 0.0, 1.0)) * 0.05;\n"
            + "\n"
            + "    vec2 blurStep = texel * (uSoftBlurStrength * 420.0);\n"
            + "    vec4 soft = vec4(0.0);\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 3.0, -blurStep.y * 1.5), 0.0, 1.0)) * 0.030;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 2.0, -blurStep.y * 1.5), 0.0, 1.0)) * 0.050;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x, -blurStep.y * 1.2), 0.0, 1.0)) * 0.085;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(0.0, -blurStep.y), 0.0, 1.0)) * 0.11;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x, -blurStep.y * 1.2), 0.0, 1.0)) * 0.085;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 2.0, -blurStep.y * 1.5), 0.0, 1.0)) * 0.050;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 3.0, -blurStep.y * 1.5), 0.0, 1.0)) * 0.030;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 3.0, 0.0), 0.0, 1.0)) * 0.035;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 2.0, 0.0), 0.0, 1.0)) * 0.055;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x, 0.0), 0.0, 1.0)) * 0.090;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.110;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x, 0.0), 0.0, 1.0)) * 0.090;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 2.0, 0.0), 0.0, 1.0)) * 0.055;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 3.0, 0.0), 0.0, 1.0)) * 0.035;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 3.0, blurStep.y * 1.5), 0.0, 1.0)) * 0.030;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 2.0, blurStep.y * 1.5), 0.0, 1.0)) * 0.050;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x, blurStep.y * 1.2), 0.0, 1.0)) * 0.085;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(0.0, blurStep.y), 0.0, 1.0)) * 0.11;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x, blurStep.y * 1.2), 0.0, 1.0)) * 0.085;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 2.0, blurStep.y * 1.5), 0.0, 1.0)) * 0.050;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 3.0, blurStep.y * 1.5), 0.0, 1.0)) * 0.030;\n"
            + "\n"
            + "    vec3 color = mix(base.rgb, smear.rgb, clamp(uSmearStrength * 0.45, 0.0, 1.0));\n"
            + "    color = mix(color, soft.rgb, clamp(uSoftBlurStrength * 6.2, 0.0, 1.0));\n"
            + "    gl_FragColor = vec4(color, base.a);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int scaleHandle = -1;
    private int stretchXHandle = -1;
    private int offsetXHandle = -1;
    private int sampleScaleHandle = -1;
    private int sampleMixHandle = -1;
    private int smearStrengthHandle = -1;
    private int softBlurStrengthHandle = -1;

    private float durationMs = 220f;
    private float attackRatio = 0.16f;
    private float peakHoldRatio = 0.08f;
    private float maxOffsetX = -0.14f;
    private float maxScale = 1.18f;
    private float maxStretchX = 1.28f;
    private float maxSampleScale = 8.0f;
    private float sampleMix = 0.68f;
    private float maxSmearStrength = 0.85f;
    private float maxSoftBlurStrength = 0.065f;
    private float firstPresentationMs = -1f;

    public ShakeFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        stretchXHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStretchX");
        offsetXHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOffsetX");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        sampleMixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleMix");
        smearStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSmearStrength");
        softBlurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSoftBlurStrength");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        float nowMs = presentationTimeUs / 1_000_000f;
        if (firstPresentationMs < 0f) {
            firstPresentationMs = nowMs;
        }
        float tMs = Math.max(0f, nowMs - firstPresentationMs);
        float progress = durationMs <= 0f ? 1f : clamp01(tMs / durationMs);

        float offsetStrength = resolveOffsetStrength(progress);
        float tailStrength = resolveTailStrength(progress, offsetStrength);

        float offsetX = maxOffsetX * offsetStrength;
        float scale = lerp(1.0f, maxScale, tailStrength);
        float stretchX = lerp(1.0f, maxStretchX, tailStrength);
        float sampleScale = lerp(1.0f, maxSampleScale, tailStrength);
        float smearStrength = lerp(0.0f, maxSmearStrength, tailStrength);
        float softBlurStrength = lerp(0.0f, maxSoftBlurStrength, tailStrength);

        float safeMaxOffset = computeMaxOffsetX(scale, stretchX);
        offsetX = clamp(offsetX, -safeMaxOffset, safeMaxOffset);

        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(scaleHandle, Math.max(1.0f, scale));
        GLES20.glUniform1f(stretchXHandle, Math.max(1.0f, stretchX));
        GLES20.glUniform1f(offsetXHandle, offsetX);
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScale));
        GLES20.glUniform1f(sampleMixHandle, clamp(sampleMix, 0.0f, 1.0f));
        GLES20.glUniform1f(smearStrengthHandle, Math.max(0.0f, smearStrength));
        GLES20.glUniform1f(softBlurStrengthHandle, clamp(softBlurStrength, 0.0f, 0.12f));
    }

    @Override
    public void setup() {
        firstPresentationMs = -1f;
        super.setup();
    }

    @Override
    public void release() {
        firstPresentationMs = -1f;
        super.release();
    }

    public ShakeFilter setDurationMs(float durationMs) {
        this.durationMs = Math.max(1f, durationMs);
        return this;
    }

    public ShakeFilter setAttackRatio(float attackRatio) {
        this.attackRatio = clamp(attackRatio, 0.05f, 0.8f);
        return this;
    }

    public ShakeFilter setPeakHoldRatio(float peakHoldRatio) {
        this.peakHoldRatio = clamp(peakHoldRatio, 0.0f, 0.3f);
        return this;
    }

    public ShakeFilter setMaxOffsetX(float maxOffsetX) {
        this.maxOffsetX = maxOffsetX;
        return this;
    }

    public ShakeFilter setMaxScale(float maxScale) {
        this.maxScale = Math.max(1.0f, maxScale);
        return this;
    }

    public ShakeFilter setMaxStretchX(float maxStretchX) {
        this.maxStretchX = Math.max(1.0f, maxStretchX);
        return this;
    }

    public ShakeFilter setMaxSampleScale(float maxSampleScale) {
        this.maxSampleScale = Math.max(1.0f, maxSampleScale);
        return this;
    }

    public ShakeFilter setSampleMix(float sampleMix) {
        this.sampleMix = clamp(sampleMix, 0.0f, 1.0f);
        return this;
    }

    public ShakeFilter setMaxSmearStrength(float maxSmearStrength) {
        this.maxSmearStrength = Math.max(0.0f, maxSmearStrength);
        return this;
    }

    public ShakeFilter setMaxSoftBlurStrength(float maxSoftBlurStrength) {
        this.maxSoftBlurStrength = clamp(maxSoftBlurStrength, 0.0f, 0.12f);
        return this;
    }

    private float resolveOffsetStrength(float progress) {
        float attack = clamp(attackRatio, 0.05f, 0.8f);
        float hold = clamp(peakHoldRatio, 0.0f, Math.max(0.0f, 0.95f - attack));
        float holdEnd = attack + hold;
        if (progress <= attack) {
            float p = clamp01(progress / attack);
            return easeOutExpo(p);
        }
        if (progress <= holdEnd) {
            return 1.0f;
        }
        float p = clamp01((progress - holdEnd) / Math.max(0.001f, 1.0f - holdEnd));
        return 1.0f - easeInCubic(p);
    }

    private float resolveTailStrength(float progress, float offsetStrength) {
        float p = clamp01(progress);
        float envelope = 1.0f - easeInCubic(p);
        return Math.max(offsetStrength * 0.92f, envelope * 0.65f);
    }

    private static float computeMaxOffsetX(float scale, float stretchX) {
        float visibleWidth = 1.0f / Math.max(1.0f, scale * stretchX);
        return Math.max(0f, (1.0f - visibleWidth) * 0.5f);
    }

    private static float easeOutCubic(float t) {
        float p = clamp01(t);
        float inv = 1.0f - p;
        return 1.0f - inv * inv * inv;
    }

    private static float easeOutExpo(float t) {
        float p = clamp01(t);
        if (p >= 1.0f) {
            return 1.0f;
        }
        return (float) (1.0 - Math.pow(2.0, -10.0 * p));
    }

    private static float easeInCubic(float t) {
        float p = clamp01(t);
        return p * p * p;
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * clamp01(t);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }
}
