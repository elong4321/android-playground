package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Fixed image-processing chain:
 * 1) scale up
 * 2) stretch vertically
 * 3) coarse sample
 * 4) gaussian blur
 */
public class VerticalScaleBlurFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uScale;\n"
            + "uniform float uStretchY;\n"
            + "uniform float uOffsetY;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uBlurStrength;\n"
            + "void main() {\n"
            + "    float scale = max(uScale, 1.0);\n"
            + "    float stretchY = max(uStretchY, 1.0);\n"
            + "    vec2 centered = textureCoordinate - vec2(0.5);\n"
            + "    vec2 scaledUv = centered / scale + vec2(0.5);\n"
            + "    vec2 stretchedUv = vec2(scaledUv.x, 0.5 + (scaledUv.y - 0.5) / stretchY);\n"
            + "    stretchedUv.y -= uOffsetY;\n"
            + "    stretchedUv = clamp(stretchedUv, 0.0, 1.0);\n"
            + "\n"
            + "    vec2 sampledUv = stretchedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((stretchedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        float sampleMix = clamp((uSampleScale - 1.0) / 12.0, 0.0, 0.75);\n"
            + "        sampledUv = mix(stretchedUv, coarseUv, sampleMix);\n"
            + "    }\n"
            + "\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec2 stepVec = texel * (uBlurStrength * 220.0);\n"
            + "    vec4 blur = vec4(0.0);\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, -stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(0.0, -stepVec.y), 0.0, 1.0)) * 0.1250;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, -stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.2500;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(0.0, stepVec.y), 0.0, 1.0)) * 0.1250;\n"
            + "    blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "\n"
            + "    vec4 base = texture2D(sTexture, sampledUv);\n"
            + "    gl_FragColor = mix(base, blur, clamp(uBlurStrength * 36.0, 0.0, 1.0));\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int scaleHandle = -1;
    private int stretchYHandle = -1;
    private int offsetYHandle = -1;
    private int sampleScaleHandle = -1;
    private int blurStrengthHandle = -1;

    private float scale = 1.2f;
    private float stretchY = 1.2f;
    private float offsetY = 0.0f;
    private float sampleScale = 6.0f;
    private float blurStrength = 0.03f;
    private float switchOffsetAtMs = -1f;
    private float switchToOppositeDurationMs = 200f;
    private float returnToCenterDurationMs = 200f;
    private float firstPresentationMs = -1f;

    public VerticalScaleBlurFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        stretchYHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStretchY");
        offsetYHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOffsetY");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        blurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurStrength");
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
        float baseScale = Math.max(1.0f, scale);
        float baseStretchY = Math.max(1.0f, stretchY);
        float transformProgress = resolveTransformProgress(tMs);
        float safeScale = lerp(baseScale, 1.0f, transformProgress);
        float safeStretchY = lerp(baseStretchY, 1.0f, transformProgress);
        float appliedSampleScale = lerp(Math.max(1.0f, sampleScale), 1.0f, transformProgress);
        float appliedBlurStrength = lerp(clamp(blurStrength, 0.0f, 0.12f), 0.0f, transformProgress);
        float initialMaxOffsetY = computeMaxOffsetY(baseScale, baseStretchY);
        float currentMaxOffsetY = computeMaxOffsetY(safeScale, safeStretchY);
        float baseOffsetY = clamp(offsetY, -initialMaxOffsetY, initialMaxOffsetY);
        float appliedOffsetY = resolveOffsetY(tMs, baseOffsetY, currentMaxOffsetY);
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(scaleHandle, safeScale);
        GLES20.glUniform1f(stretchYHandle, safeStretchY);
        GLES20.glUniform1f(offsetYHandle, appliedOffsetY);
        GLES20.glUniform1f(sampleScaleHandle, appliedSampleScale);
        GLES20.glUniform1f(blurStrengthHandle, appliedBlurStrength);
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

    public VerticalScaleBlurFilter setScale(float scale) {
        this.scale = Math.max(1.0f, scale);
        return this;
    }

    public VerticalScaleBlurFilter setStretchY(float stretchY) {
        this.stretchY = Math.max(1.0f, stretchY);
        return this;
    }

    public VerticalScaleBlurFilter setSampleScale(float sampleScale) {
        this.sampleScale = Math.max(1.0f, sampleScale);
        return this;
    }

    public VerticalScaleBlurFilter setOffsetY(float offsetY) {
        this.offsetY = offsetY;
        return this;
    }

    public VerticalScaleBlurFilter setBlurStrength(float blurStrength) {
        this.blurStrength = clamp(blurStrength, 0.0f, 0.12f);
        return this;
    }

    public VerticalScaleBlurFilter setSwitchOffsetAtMs(float switchOffsetAtMs) {
        this.switchOffsetAtMs = switchOffsetAtMs;
        return this;
    }

    public VerticalScaleBlurFilter setSwitchToOppositeDurationMs(float switchToOppositeDurationMs) {
        this.switchToOppositeDurationMs = Math.max(1f, switchToOppositeDurationMs);
        return this;
    }

    public VerticalScaleBlurFilter setReturnToCenterDurationMs(float returnToCenterDurationMs) {
        this.returnToCenterDurationMs = Math.max(1f, returnToCenterDurationMs);
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float computeMaxOffsetY(float scale, float stretchY) {
        float visibleHeight = 1.0f / (scale * stretchY);
        return Math.max(0f, (1.0f - visibleHeight) * 0.5f);
    }

    private float resolveOffsetY(float tMs, float baseOffsetY, float maxOffsetY) {
        if (switchOffsetAtMs < 0f) {
            return clamp(baseOffsetY, -maxOffsetY, maxOffsetY);
        }
        float oppositeOffsetY = -Math.signum(baseOffsetY == 0f ? -1f : baseOffsetY) * maxOffsetY;
        float t0 = switchOffsetAtMs;
        float t1 = t0 + switchToOppositeDurationMs;
        float t2 = t1 + returnToCenterDurationMs;
        if (tMs < t0) {
            return clamp(baseOffsetY, -maxOffsetY, maxOffsetY);
        }
        if (tMs < t1) {
            float p = easeInOut(norm(tMs, t0, t1));
            return clamp(lerp(baseOffsetY, oppositeOffsetY, p), -maxOffsetY, maxOffsetY);
        }
        if (tMs < t2) {
            float p = easeInOut(norm(tMs, t1, t2));
            return clamp(lerp(oppositeOffsetY, 0f, p), -maxOffsetY, maxOffsetY);
        }
        return 0f;
    }

    private float resolveTransformProgress(float tMs) {
        if (switchOffsetAtMs < 0f) {
            return 0f;
        }
        float t0 = switchOffsetAtMs;
        float t2 = t0 + switchToOppositeDurationMs + returnToCenterDurationMs;
        if (tMs <= t0) {
            return 0f;
        }
        if (tMs >= t2) {
            return 1f;
        }
        return easeInOut(norm(tMs, t0, t2));
    }

    private static float norm(float value, float start, float end) {
        float d = Math.max(1f, end - start);
        return clamp((value - start) / d, 0f, 1f);
    }

    private static float easeInOut(float p) {
        float t = clamp(p, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * clamp(t, 0f, 1f);
    }
}
