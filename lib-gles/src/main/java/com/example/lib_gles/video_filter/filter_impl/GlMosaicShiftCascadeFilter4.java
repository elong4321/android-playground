package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Same capability set as GlMosaicShiftCascadeFilter3, but mosaic stays enabled during shake.
 * The intended composition is:
 * 1) mosaic
 * 2) shake / scale / stretch / sample blur
 */
public class GlMosaicShiftCascadeFilter4 extends GlFilter {

    public static final int EASE_LINEAR = 0;
    public static final int EASE_SMOOTH = 1;

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
            + "uniform vec2 uStretch;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uBlurStrength;\n"
            + "void main() {\n"
            + "    vec2 scale = max(uScale, vec2(1.0));\n"
            + "    vec2 centered = textureCoordinate - vec2(0.5);\n"
            + "    vec2 scaledUv = centered / scale + vec2(0.5);\n"
            + "    vec2 shiftedUv = clamp(scaledUv - uOffset, 0.0, 1.0);\n"
            + "    vec2 stretchedUv = vec2(0.5 + (shiftedUv.x - 0.5) / max(uStretch.x, 1.0), 0.5 + (shiftedUv.y - 0.5) / max(uStretch.y, 1.0));\n"
            + "    stretchedUv = clamp(stretchedUv, 0.0, 1.0);\n"
            + "\n"
            + "    vec2 mosaicUv = stretchedUv;\n"
            + "    if (uBlockSize > 1.0) {\n"
            + "        vec2 pixel = stretchedUv * uResolution;\n"
            + "        vec2 block = floor(pixel / uBlockSize) * uBlockSize + vec2(uBlockSize * 0.5);\n"
            + "        mosaicUv = clamp(block / uResolution, 0.0, 1.0);\n"
            + "    }\n"
            + "\n"
            + "    vec2 sampledUv = mosaicUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((mosaicUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        float sampleMix = clamp((uSampleScale - 1.0) / 12.0, 0.0, 0.75);\n"
            + "        sampledUv = mix(mosaicUv, coarseUv, sampleMix);\n"
            + "    }\n"
            + "\n"
            + "    vec4 base = texture2D(sTexture, sampledUv);\n"
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
            + "    gl_FragColor = mix(base, blur, clamp(uBlurStrength * 36.0, 0.0, 1.0));\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int blockSizeHandle = -1;
    private int offsetHandle = -1;
    private int scaleHandle = -1;
    private int stretchHandle = -1;
    private int sampleScaleHandle = -1;
    private int blurStrengthHandle = -1;

    private float firstPresentationMs = -1f;
    private float mosaicMaxBlockSize = 40f;
    private float shakeScalePadding = 0.02f;
    private float maxShakeScale = 1.3f;
    private float maxShakeStretchX = 1.35f;
    private float maxShakeStretchY = 1.0f;
    private float maxShakeBlur = 0.05f;
    private float maxShakeSampleScale = 8.0f;

    private final List<MosaicKeyframe> mosaicKeyframes = new ArrayList<>();
    private final List<ZoomEvent> zoomEvents = new ArrayList<>();
    private final List<ShakeEvent> shakeEvents = new ArrayList<>();

    public GlMosaicShiftCascadeFilter4() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        blockSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlockSize");
        offsetHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOffset");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        stretchHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStretch");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        blurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurStrength");
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

        float zoomScale = resolveZoomScale(tMs);
        float shiftX = resolveShakeX(tMs);
        float shiftY = resolveShakeY(tMs);
        float shakeStrength = resolveShakeStrength(tMs);
        boolean shaking = Math.abs(shiftX) > 1e-5f || Math.abs(shiftY) > 1e-5f;

        float blockSize = resolveMosaic(tMs);
        float scaleX = zoomScale;
        float scaleY = zoomScale;
        float stretchX = 1.0f;
        float stretchY = 1.0f;
        float sampleScale = 1.0f;
        float blurStrength = 0f;

        if (shaking) {
            float shakeScaleX = Math.max(1.0f, computeShakeScaleForOffset(shiftX));
            float shakeScaleY = Math.max(1.0f, computeShakeScaleForOffset(shiftY));
            scaleX = Math.max(zoomScale, lerp(1.0f, shakeScaleX, shakeStrength));
            scaleY = Math.max(zoomScale, lerp(1.0f, shakeScaleY, shakeStrength));
            float maxOffsetX = computeMaxOffsetForScale(scaleX);
            float maxOffsetY = computeMaxOffsetForScale(scaleY);
            shiftX = clamp(shiftX, -maxOffsetX, maxOffsetX);
            shiftY = clamp(shiftY, -maxOffsetY, maxOffsetY);
            stretchX = lerp(1.0f, maxShakeStretchX, shakeStrength);
            stretchY = lerp(1.0f, maxShakeStretchY, shakeStrength);
            sampleScale = lerp(1.0f, maxShakeSampleScale, shakeStrength);
            blurStrength = lerp(0.0f, maxShakeBlur, shakeStrength);
        }

        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(blockSizeHandle, Math.max(0f, blockSize));
        GLES20.glUniform2f(offsetHandle, shiftX, -shiftY);
        GLES20.glUniform2f(scaleHandle, Math.max(1.0f, scaleX), Math.max(1.0f, scaleY));
        GLES20.glUniform2f(stretchHandle, Math.max(1.0f, stretchX), Math.max(1.0f, stretchY));
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScale));
        GLES20.glUniform1f(blurStrengthHandle, Math.max(0f, blurStrength));
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

    public GlMosaicShiftCascadeFilter4 clearMosaicKeyframes() {
        mosaicKeyframes.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter4 addMosaicKeyframe(float timeMs, float blockSize) {
        mosaicKeyframes.add(new MosaicKeyframe(Math.max(0f, timeMs), Math.max(0f, blockSize)));
        sortMosaicKeyframes();
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMosaicMaxBlockSize(float mosaicMaxBlockSize) {
        this.mosaicMaxBlockSize = Math.max(1f, mosaicMaxBlockSize);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 addMosaicLevelKeyframe(float timeMs, float level) {
        return addMosaicKeyframe(timeMs, clamp01(level) * mosaicMaxBlockSize);
    }

    public GlMosaicShiftCascadeFilter4 clearZoomEvents() {
        zoomEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter4 addZoomEvent(float startMs, float endMs, float fromScale, float toScale, int ease) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        zoomEvents.add(new ZoomEvent(s, e, Math.max(1.0f, fromScale), Math.max(1.0f, toScale), ease));
        return this;
    }

    public GlMosaicShiftCascadeFilter4 clearShakeEvents() {
        shakeEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter4 addPulseShakeEvent(float startMs, float endMs, float ampX, float ampY) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_PULSE, 1);
    }

    public GlMosaicShiftCascadeFilter4 addOscillatingShakeEvent(float startMs, float endMs, float ampX, float ampY, int cycles) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_OSCILLATE, cycles);
    }

    public GlMosaicShiftCascadeFilter4 addShakeEvent(float startMs, float endMs, float ampX, float ampY, int mode, int cycles) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        shakeEvents.add(new ShakeEvent(s, e, ampX, ampY, mode, Math.max(1, cycles)));
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setShakeScalePadding(float shakeScalePadding) {
        this.shakeScalePadding = Math.max(0f, shakeScalePadding);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMaxShakeScale(float maxShakeScale) {
        this.maxShakeScale = Math.max(1.0f, maxShakeScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMaxShakeStretch(float maxShakeStretchX) {
        this.maxShakeStretchX = Math.max(1.0f, maxShakeStretchX);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMaxShakeStretch(float maxShakeStretchX, float maxShakeStretchY) {
        this.maxShakeStretchX = Math.max(1.0f, maxShakeStretchX);
        this.maxShakeStretchY = Math.max(1.0f, maxShakeStretchY);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMaxShakeBlur(float maxShakeBlur) {
        this.maxShakeBlur = clamp(maxShakeBlur, 0.0f, 0.12f);
        return this;
    }

    public GlMosaicShiftCascadeFilter4 setMaxShakeSampleScale(float maxShakeSampleScale) {
        this.maxShakeSampleScale = Math.max(1.0f, maxShakeSampleScale);
        return this;
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
                return lerp(prev.blockSize, next.blockSize, normProgress(tMs, prev.timeMs, next.timeMs));
            }
        }
        return mosaicKeyframes.get(mosaicKeyframes.size() - 1).blockSize;
    }

    private float resolveZoomScale(float tMs) {
        float out = 1.0f;
        for (ZoomEvent event : zoomEvents) {
            if (tMs < event.startMs || tMs >= event.endMs) {
                continue;
            }
            float p = applyEase(normProgress(tMs, event.startMs, event.endMs), event.ease);
            out *= lerp(event.fromScale, event.toScale, p);
        }
        return Math.max(1.0f, out);
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
            float cycleProgress = clamp01((p * Math.max(1, event.cycles)) % 1.0f);
            return 1.0f - applyEaseOut(cycleProgress);
        }
        return 1.0f - applyEaseOut(p);
    }

    private static float normProgress(float t, float start, float end) {
        float d = Math.max(1f, end - start);
        return clamp01((t - start) / d);
    }

    private static float applyEase(float t, int ease) {
        float p = clamp01(t);
        if (ease == EASE_SMOOTH) {
            return p * p * (3f - 2f * p);
        }
        return p;
    }

    private static float applyEaseOut(float t) {
        float p = clamp01(t);
        float inv = 1.0f - p;
        return 1.0f - inv * inv * inv;
    }

    private float computeShakeScaleForOffset(float offset) {
        float absOffset = Math.abs(offset);
        float safeDenominator = Math.max(0.05f, 1.0f - 2.0f * absOffset);
        float requiredScale = 1.0f / safeDenominator;
        return Math.min(maxShakeScale, requiredScale + shakeScalePadding);
    }

    private static float computeMaxOffsetForScale(float scale) {
        if (scale <= 1.0f) {
            return 0f;
        }
        return (1.0f - (1.0f / scale)) * 0.5f;
    }

    private void sortMosaicKeyframes() {
        Collections.sort(mosaicKeyframes, new Comparator<MosaicKeyframe>() {
            @Override
            public int compare(MosaicKeyframe a, MosaicKeyframe b) {
                return Float.compare(a.timeMs, b.timeMs);
            }
        });
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    private static class MosaicKeyframe {
        final float timeMs;
        final float blockSize;

        MosaicKeyframe(float timeMs, float blockSize) {
            this.timeMs = timeMs;
            this.blockSize = blockSize;
        }
    }

    private static class ZoomEvent {
        final float startMs;
        final float endMs;
        final float fromScale;
        final float toScale;
        final int ease;

        ZoomEvent(float startMs, float endMs, float fromScale, float toScale, int ease) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.fromScale = fromScale;
            this.toScale = toScale;
            this.ease = ease;
        }
    }

    private static class ShakeEvent {
        final float startMs;
        final float endMs;
        final float ampX;
        final float ampY;
        final int mode;
        final int cycles;

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
