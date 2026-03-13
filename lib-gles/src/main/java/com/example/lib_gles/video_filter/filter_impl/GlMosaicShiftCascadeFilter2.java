package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Variant of GlMosaicShiftCascadeFilter:
 * - Timeline is still configured with mosaic/shake keyframes.
 * - During shake windows, mosaic is disabled and the image goes through
 *   a ScaleBlur-like chain: scale -> directional stretch -> coarse sample -> gaussian blur.
 */
public class GlMosaicShiftCascadeFilter2 extends GlFilter {

    public static final int SHAKE_MODE_PULSE = 0;
    public static final int SHAKE_MODE_OSCILLATE = 1;

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uBlockSize;\n"
            + "uniform vec2 uOffset;\n"
            + "uniform vec2 uScale;\n"
            + "uniform vec2 uMotionDir;\n"
            + "uniform float uStretchStrength;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uGaussianBlurStrength;\n"
            + "void main() {\n"
            + "    vec2 scale = max(uScale, vec2(1.0));\n"
            + "    vec2 zoomedUv = (textureCoordinate - vec2(0.5)) / scale + vec2(0.5);\n"
            + "    vec2 shiftedUv = clamp(zoomedUv - uOffset, 0.0, 1.0);\n"
            + "    vec2 stretchedUv = shiftedUv;\n"
            + "    if (uStretchStrength > 0.001) {\n"
            + "        vec2 dir = uMotionDir;\n"
            + "        float len = max(length(dir), 0.0001);\n"
            + "        dir = dir / len;\n"
            + "        vec2 delta = shiftedUv - vec2(0.5);\n"
            + "        float parallel = dot(delta, dir);\n"
            + "        vec2 parallelVec = dir * parallel;\n"
            + "        vec2 perpendicularVec = delta - parallelVec;\n"
            + "        float stretch = 1.0 + uStretchStrength;\n"
            + "        stretchedUv = clamp(vec2(0.5) + parallelVec / stretch + perpendicularVec, 0.0, 1.0);\n"
            + "    }\n"
            + "    vec2 sampledUv = stretchedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((stretchedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        float sampleMix = clamp((uSampleScale - 1.0) / 12.0, 0.0, 0.75);\n"
            + "        sampledUv = mix(stretchedUv, coarseUv, sampleMix);\n"
            + "    }\n"
            + "    vec4 base;\n"
            + "    if (uBlockSize <= 1.0) {\n"
            + "        base = texture2D(sTexture, sampledUv);\n"
            + "    } else {\n"
            + "        vec2 pixel = sampledUv * uResolution;\n"
            + "        vec2 block = floor(pixel / uBlockSize) * uBlockSize + vec2(uBlockSize * 0.5);\n"
            + "        vec2 uv = clamp(block / uResolution, 0.0, 1.0);\n"
            + "        base = texture2D(sTexture, uv);\n"
            + "    }\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec2 stepVec = texel * (uGaussianBlurStrength * 220.0);\n"
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
            + "    gl_FragColor = mix(base, blur, clamp(uGaussianBlurStrength * 36.0, 0.0, 1.0));\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int blockSizeHandle = -1;
    private int offsetHandle = -1;
    private int scaleHandle = -1;
    private int motionDirHandle = -1;
    private int stretchStrengthHandle = -1;
    private int sampleScaleHandle = -1;
    private int gaussianBlurStrengthHandle = -1;

    private float mosaicMaxBlockSize = 40f;
    private float firstPresentationMs = -1f;
    private float shakeScalePadding = 0.02f;
    private float maxShakeScale = 3.0f;
    private float maxShakeStretch = 0.55f;
    private float maxShakeGaussianBlur = 0.03f;
    private float maxShakeSampleScale = 8.0f;

    private final List<MosaicKeyframe> mosaicKeyframes = new ArrayList<>();
    private final List<ShakeEvent> shakeEvents = new ArrayList<>();

    public GlMosaicShiftCascadeFilter2() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
        configureDefaultEffect1Timeline();
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        blockSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlockSize");
        offsetHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOffset");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        motionDirHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMotionDir");
        stretchStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStretchStrength");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        gaussianBlurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGaussianBlurStrength");
    }

    @Override
    protected void onDraw(long presentationTimeUs, Map<String, Integer> extraTextureIds) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        float nowMs = presentationTimeUs / 1_000_000f;
        if (firstPresentationMs < 0f) {
            firstPresentationMs = nowMs;
        }
        float tMs = Math.max(0f, nowMs - firstPresentationMs);

        float shiftX = resolveShakeX(tMs);
        float shiftY = resolveShakeY(tMs);
        float shakeStrength = resolveShakeStrength(tMs);
        boolean shaking = Math.abs(shiftX) > 1e-5f || Math.abs(shiftY) > 1e-5f;
        float blockSize = shaking ? 0f : resolveMosaic(tMs);
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float stretchStrength = 0f;
        float sampleScale = 1.0f;
        float gaussianBlur = 0f;
        if (shaking) {
            float shakeScaleX = computeShakeScaleForOffset(shiftX);
            float shakeScaleY = computeShakeScaleForOffset(shiftY);
            scaleX = lerp(1.0f, shakeScaleX, shakeStrength);
            scaleY = lerp(1.0f, shakeScaleY, shakeStrength);
            float maxOffsetX = computeMaxOffsetForScale(scaleX);
            float maxOffsetY = computeMaxOffsetForScale(scaleY);
            shiftX = clamp(shiftX, -maxOffsetX, maxOffsetX);
            shiftY = clamp(shiftY, -maxOffsetY, maxOffsetY);
            stretchStrength = maxShakeStretch * shakeStrength;
            sampleScale = lerp(1.0f, maxShakeSampleScale, shakeStrength);
            gaussianBlur = maxShakeGaussianBlur * shakeStrength;
        }

        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(blockSizeHandle, Math.max(0f, blockSize));
        GLES20.glUniform2f(offsetHandle, shiftX, -shiftY);
        GLES20.glUniform2f(scaleHandle, Math.max(1.0f, scaleX), Math.max(1.0f, scaleY));
        GLES20.glUniform2f(motionDirHandle, shiftX, -shiftY);
        GLES20.glUniform1f(stretchStrengthHandle, Math.max(0f, stretchStrength));
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScale));
        GLES20.glUniform1f(gaussianBlurStrengthHandle, Math.max(0f, gaussianBlur));
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

    private void configureDefaultEffect1Timeline() {
        clearMosaicKeyframes()
                .addMosaicKeyframe(0f, 40f)
                .addMosaicKeyframe(2000f, 40f)
                .addMosaicKeyframe(3000f, 0f);

        clearShakeEvents()
                .addOscillatingShakeEvent(0f, 2000f, 0.03f, 0f, 4)
                .addPulseShakeEvent(3000f, 3200f, 0f, 0.03f)
                .addPulseShakeEvent(5000f, 6000f, 0.03f, 0f)
                .addPulseShakeEvent(8000f, 9000f, 0.03f, 0f)
                .addPulseShakeEvent(10000f, 11000f, 0.03f, 0.03f)
                .addPulseShakeEvent(13000f, 14000f, 0.03f, 0f);
    }

    private float resolveMosaic(float tMs) {
        if (mosaicKeyframes.isEmpty()) {
            return 0f;
        }
        if (mosaicKeyframes.size() == 1) {
            return mosaicKeyframes.get(0).blockSize;
        }
        MosaicKeyframe first = mosaicKeyframes.get(0);
        if (tMs <= first.timeMs) {
            return first.blockSize;
        }
        for (int i = 1; i < mosaicKeyframes.size(); i++) {
            MosaicKeyframe prev = mosaicKeyframes.get(i - 1);
            MosaicKeyframe next = mosaicKeyframes.get(i);
            if (tMs <= next.timeMs) {
                float p = normProgress(tMs, prev.timeMs, next.timeMs);
                return lerp(prev.blockSize, next.blockSize, p);
            }
        }
        return mosaicKeyframes.get(mosaicKeyframes.size() - 1).blockSize;
    }

    private float resolveShakeX(float tMs) {
        float out = 0f;
        for (ShakeEvent event : shakeEvents) {
            out += sampleShake(event, tMs, true);
        }
        return out;
    }

    private float resolveShakeY(float tMs) {
        float out = 0f;
        for (ShakeEvent event : shakeEvents) {
            out += sampleShake(event, tMs, false);
        }
        return out;
    }

    private float resolveShakeStrength(float tMs) {
        float out = 0f;
        for (ShakeEvent event : shakeEvents) {
            out = Math.max(out, sampleShakeFactor(event, tMs));
        }
        return clamp01(out);
    }

    private static float sampleShake(ShakeEvent event, float tMs, boolean xAxis) {
        if (tMs < event.startMs || tMs >= event.endMs) {
            return 0f;
        }
        float amp = xAxis ? event.ampX : event.ampY;
        if (Math.abs(amp) < 1e-7f) {
            return 0f;
        }
        return amp * sampleShakeFactor(event, tMs);
    }

    private static float sampleShakeFactor(ShakeEvent event, float tMs) {
        float p = normProgress(tMs, event.startMs, event.endMs);
        if (event.mode == SHAKE_MODE_OSCILLATE) {
            int cycles = Math.max(1, event.cycles);
            float cycleProgress = clamp01((p * cycles) % 1.0f);
            return 1.0f - applyEaseOut(cycleProgress);
        }
        return 1.0f - applyEaseOut(p);
    }

    private static float normProgress(float t, float start, float end) {
        float d = Math.max(1f, end - start);
        return clamp01((t - start) / d);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float applyEaseOut(float t) {
        float p = clamp01(t);
        float inv = 1.0f - p;
        return 1.0f - inv * inv * inv;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float computeShakeScaleForOffset(float offset) {
        float maxOffset = Math.abs(offset);
        float safeDenominator = Math.max(0.05f, 1.0f - 2.0f * maxOffset);
        float requiredScale = 1.0f / safeDenominator;
        return Math.min(maxShakeScale, requiredScale + shakeScalePadding);
    }

    private static float computeMaxOffsetForScale(float scale) {
        if (scale <= 1.0f) {
            return 0f;
        }
        return (1.0f - (1.0f / scale)) * 0.5f;
    }

    public GlMosaicShiftCascadeFilter2 clearMosaicKeyframes() {
        mosaicKeyframes.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter2 addMosaicKeyframe(float timeMs, float blockSize) {
        mosaicKeyframes.add(new MosaicKeyframe(Math.max(0f, timeMs), Math.max(0f, blockSize)));
        Collections.sort(mosaicKeyframes, new Comparator<MosaicKeyframe>() {
            @Override
            public int compare(MosaicKeyframe a, MosaicKeyframe b) {
                return Float.compare(a.timeMs, b.timeMs);
            }
        });
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setMosaicMaxBlockSize(float mosaicMaxBlockSize) {
        this.mosaicMaxBlockSize = Math.max(1f, mosaicMaxBlockSize);
        return this;
    }

    public GlMosaicShiftCascadeFilter2 addMosaicLevelKeyframe(float timeMs, float level) {
        return addMosaicKeyframe(timeMs, clamp01(level) * mosaicMaxBlockSize);
    }

    public GlMosaicShiftCascadeFilter2 clearShakeEvents() {
        shakeEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter2 addShakeEvent(float startMs, float endMs, float ampX, float ampY, int mode, int cycles) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        shakeEvents.add(new ShakeEvent(s, e, ampX, ampY, mode, Math.max(1, cycles)));
        return this;
    }

    public GlMosaicShiftCascadeFilter2 addPulseShakeEvent(float startMs, float endMs, float ampX, float ampY) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_PULSE, 1);
    }

    public GlMosaicShiftCascadeFilter2 addOscillatingShakeEvent(float startMs, float endMs, float ampX, float ampY, int cycles) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_OSCILLATE, cycles);
    }

    public GlMosaicShiftCascadeFilter2 setShakeAmplitude(float ampX, float ampY) {
        float x = Math.max(0f, ampX);
        float y = Math.max(0f, ampY);
        for (ShakeEvent event : shakeEvents) {
            if (Math.abs(event.ampX) > 1e-7f) {
                event.ampX = Math.signum(event.ampX) * x;
            }
            if (Math.abs(event.ampY) > 1e-7f) {
                event.ampY = Math.signum(event.ampY) * y;
            }
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setShakeScalePadding(float shakeScalePadding) {
        this.shakeScalePadding = Math.max(0f, shakeScalePadding);
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setMaxShakeScale(float maxShakeScale) {
        this.maxShakeScale = Math.max(1.0f, maxShakeScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setMaxShakeBlur(float maxShakeMotionBlur) {
        this.maxShakeGaussianBlur = Math.max(0f, Math.min(0.12f, maxShakeMotionBlur));
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setMaxShakeStretch(float maxShakeStretch) {
        this.maxShakeStretch = Math.max(0f, Math.min(1.5f, maxShakeStretch));
        return this;
    }

    public GlMosaicShiftCascadeFilter2 setMaxShakeSampleScale(float maxShakeSampleScale) {
        this.maxShakeSampleScale = Math.max(1.0f, maxShakeSampleScale);
        return this;
    }

    private static class MosaicKeyframe {
        float timeMs;
        float blockSize;

        MosaicKeyframe(float timeMs, float blockSize) {
            this.timeMs = timeMs;
            this.blockSize = blockSize;
        }
    }

    private static class ShakeEvent {
        float startMs;
        float endMs;
        float ampX;
        float ampY;
        int mode;
        int cycles;

        ShakeEvent(float startMs, float endMs, float ampX, float ampY, int mode, int cycles) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.ampX = ampX;
            this.ampY = ampY;
            this.mode = mode;
            this.cycles = cycles;
        }
    }
}
