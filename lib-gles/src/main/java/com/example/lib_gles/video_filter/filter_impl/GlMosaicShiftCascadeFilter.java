package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Configurable composite filter with 3 independent modules:
 * 1) Mosaic keyframes (time -> block size, linear interpolation)
 * 2) Zoom events (time window + scale curve)
 * 3) Shake events (time window + amplitude + pattern)
 *
 * TimeScale should be handled by TimeScaleFilter outside this class.
 */
public class GlMosaicShiftCascadeFilter extends GlFilter {

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
            + "uniform float uScale;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uBlurStrength;\n"
            + "void main() {\n"
            + "    float scale = max(uScale, 1.0);\n"
            + "    vec2 zoomedUv = (textureCoordinate - vec2(0.5)) / scale + vec2(0.5);\n"
            + "    vec2 shiftedUv = clamp(zoomedUv - uOffset, 0.0, 1.0);\n"
            + "    vec2 sampledUv = shiftedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((shiftedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        float sampleMix = clamp((uSampleScale - 1.0) / 12.0, 0.0, 0.65);\n"
            + "        sampledUv = mix(shiftedUv, coarseUv, sampleMix);\n"
            + "    }\n"
            + "    vec4 color;\n"
            + "    if (uBlockSize <= 1.0) {\n"
            + "        color = texture2D(sTexture, sampledUv);\n"
            + "    } else {\n"
            + "        vec2 pixel = sampledUv * uResolution;\n"
            + "        vec2 block = floor(pixel / uBlockSize) * uBlockSize + vec2(uBlockSize * 0.5);\n"
            + "        vec2 uv = block / uResolution;\n"
            + "        color = texture2D(sTexture, uv);\n"
            + "    }\n"
            + "    if (uBlurStrength > 0.001) {\n"
            + "        vec2 texel = vec2(1.0) / uResolution;\n"
            + "        vec2 stepVec = texel * (uBlurStrength * 220.0);\n"
            + "        vec4 blur = vec4(0.0);\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, -stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(0.0, -stepVec.y), 0.0, 1.0)) * 0.1250;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, -stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.2500;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(-stepVec.x, stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(0.0, stepVec.y), 0.0, 1.0)) * 0.1250;\n"
            + "        blur += texture2D(sTexture, clamp(sampledUv + vec2(stepVec.x, stepVec.y), 0.0, 1.0)) * 0.0625;\n"
            + "        color.rgb = mix(color.rgb, blur.rgb, clamp(uBlurStrength * 40.0, 0.0, 1.0));\n"
            + "    }\n"
            + "    gl_FragColor = color;\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int blockSizeHandle = -1;
    private int offsetHandle = -1;
    private int scaleHandle = -1;
    private int sampleScaleHandle = -1;
    private int blurStrengthHandle = -1;

    private float mosaicMaxBlockSize = 40f;
    private float firstPresentationMs = -1f;
    private float shakeScalePadding = 0.02f;
    private float maxShakeScale = 3.0f;
    private float maxShakeBlur = 0.018f;
    private float maxShakeSampleScale = 6.0f;

    private final List<MosaicKeyframe> mosaicKeyframes = new ArrayList<>();
    private final List<ZoomEvent> zoomEvents = new ArrayList<>();
    private final List<ShakeEvent> shakeEvents = new ArrayList<>();

    public GlMosaicShiftCascadeFilter() {
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
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        blurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurStrength");
    }

    @Override
    protected void onDraw(long presentationTimeUs, Map<String, Integer> extraTextureIds) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        // In this pipeline, presentationTimeUs is actually fed as nanoseconds.
        float nowMs = presentationTimeUs / 1_000_000f;
        if (firstPresentationMs < 0f) {
            firstPresentationMs = nowMs;
        }
        float tMs = Math.max(0f, nowMs - firstPresentationMs);

        float scale = resolveScale(tMs);
        float shiftX = resolveShakeX(tMs);
        float shiftY = resolveShakeY(tMs);
        float shakeStrength = resolveShakeStrength(tMs);
        boolean shaking = Math.abs(shiftX) > 1e-5f || Math.abs(shiftY) > 1e-5f;
        float blockSize = shaking ? 0f : resolveMosaic(tMs);
        if (shaking) {
            scale = Math.max(scale, computeShakeScale(shiftX, shiftY));
            float maxOffset = computeMaxOffsetForScale(scale);
            shiftX = clamp(shiftX, -maxOffset, maxOffset);
            shiftY = clamp(shiftY, -maxOffset, maxOffset);
        }
        float blurStrength = shaking ? maxShakeBlur * shakeStrength : 0f;
        float sampleScale = shaking ? lerp(1.0f, maxShakeSampleScale, shakeStrength) : 1.0f;

        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(blockSizeHandle, Math.max(0f, blockSize));
        // Screen-space Y grows downward in the desired effect semantics.
        GLES20.glUniform2f(offsetHandle, shiftX, -shiftY);
        GLES20.glUniform1f(scaleHandle, Math.max(1.0f, scale));
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

    private void configureDefaultEffect1Timeline() {
        clearMosaicKeyframes()
                .addMosaicKeyframe(0f, 40f)
                .addMosaicKeyframe(2000f, 40f)
                .addMosaicKeyframe(3000f, 0f);

        clearZoomEvents()
                .addZoomEvent(2000f, 3000f, 1.0f, 1.16f, EASE_SMOOTH);

        clearShakeEvents()
                .addOscillatingShakeEvent(0f, 2000f, 0.03f, 0f, 4)      // 0~2s, 4x horizontal
                .addPulseShakeEvent(3000f, 4000f, 0f, -0.03f)           // 3~4s, vertical down
                .addPulseShakeEvent(5000f, 6000f, 0.03f, 0f)            // 5~6s, horizontal
                .addPulseShakeEvent(8000f, 9000f, 0.03f, 0f)            // 8~9s, horizontal
                .addPulseShakeEvent(10000f, 11000f, 0.03f, -0.03f)      // 10~11s, diagonal
                .addPulseShakeEvent(13000f, 14000f, 0.03f, 0f);         // 13~14s, horizontal
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

    private float resolveScale(float tMs) {
        float scale = 1.0f;
        for (ZoomEvent event : zoomEvents) {
            if (tMs < event.startMs || tMs >= event.endMs) {
                continue;
            }
            float p = normProgress(tMs, event.startMs, event.endMs);
            p = applyEase(p, event.ease);
            scale *= lerp(event.fromScale, event.toScale, p);
        }
        return Math.max(1.0f, scale);
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

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float computeShakeScale(float shiftX, float shiftY) {
        float maxOffset = Math.max(Math.abs(shiftX), Math.abs(shiftY));
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

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public GlMosaicShiftCascadeFilter clearMosaicKeyframes() {
        mosaicKeyframes.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter addMosaicKeyframe(float timeMs, float blockSize) {
        mosaicKeyframes.add(new MosaicKeyframe(Math.max(0f, timeMs), Math.max(0f, blockSize)));
        Collections.sort(mosaicKeyframes, new Comparator<MosaicKeyframe>() {
            @Override
            public int compare(MosaicKeyframe a, MosaicKeyframe b) {
                return Float.compare(a.timeMs, b.timeMs);
            }
        });
        return this;
    }

    public GlMosaicShiftCascadeFilter setMosaicMaxBlockSize(float mosaicMaxBlockSize) {
        this.mosaicMaxBlockSize = Math.max(1f, mosaicMaxBlockSize);
        return this;
    }

    public GlMosaicShiftCascadeFilter addMosaicLevelKeyframe(float timeMs, float level) {
        float clampedLevel = clamp01(level);
        return addMosaicKeyframe(timeMs, clampedLevel * mosaicMaxBlockSize);
    }

    public GlMosaicShiftCascadeFilter clearZoomEvents() {
        zoomEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter addZoomEvent(float startMs, float endMs, float fromScale, float toScale, int ease) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        zoomEvents.add(new ZoomEvent(s, e, Math.max(1.0f, fromScale), Math.max(1.0f, toScale), ease));
        return this;
    }

    public GlMosaicShiftCascadeFilter clearShakeEvents() {
        shakeEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter addShakeEvent(
            float startMs, float endMs, float ampX, float ampY, int mode, int cycles) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        shakeEvents.add(new ShakeEvent(s, e, ampX, ampY, mode, Math.max(1, cycles)));
        return this;
    }

    public GlMosaicShiftCascadeFilter addPulseShakeEvent(float startMs, float endMs, float ampX, float ampY) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_PULSE, 1);
    }

    public GlMosaicShiftCascadeFilter addOscillatingShakeEvent(
            float startMs, float endMs, float ampX, float ampY, int cycles) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_OSCILLATE, cycles);
    }

    // Compatibility wrappers (legacy calls map to modular API).
    public GlMosaicShiftCascadeFilter setHoldMs(float holdMs) {
        if (mosaicKeyframes.size() >= 2) {
            mosaicKeyframes.get(1).timeMs = Math.max(0f, holdMs);
            Collections.sort(mosaicKeyframes, new Comparator<MosaicKeyframe>() {
                @Override
                public int compare(MosaicKeyframe a, MosaicKeyframe b) {
                    return Float.compare(a.timeMs, b.timeMs);
                }
            });
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter setStepMs(float stepMs) {
        return this;
    }

    public GlMosaicShiftCascadeFilter setStepIntervalMs(float stepIntervalMs) {
        return this;
    }

    public GlMosaicShiftCascadeFilter setShiftX(float shiftX) {
        float ampX = Math.max(0f, shiftX) * 0.1f;
        for (ShakeEvent event : shakeEvents) {
            if (Math.abs(event.ampX) > 1e-7f) {
                event.ampX = Math.signum(event.ampX) * ampX;
            }
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter setMosaicLevels(float start, float level1, float level2, float end) {
        if (mosaicKeyframes.isEmpty()) {
            addMosaicKeyframe(0f, start);
            addMosaicKeyframe(3000f, end);
            return this;
        }
        mosaicKeyframes.get(0).blockSize = Math.max(0f, start);
        mosaicKeyframes.get(mosaicKeyframes.size() - 1).blockSize = Math.max(0f, end);
        return this;
    }

    public GlMosaicShiftCascadeFilter setMosaicClearEndMs(float mosaicClearEndMs) {
        if (!mosaicKeyframes.isEmpty()) {
            mosaicKeyframes.get(mosaicKeyframes.size() - 1).timeMs = Math.max(1f, mosaicClearEndMs);
            Collections.sort(mosaicKeyframes, new Comparator<MosaicKeyframe>() {
                @Override
                public int compare(MosaicKeyframe a, MosaicKeyframe b) {
                    return Float.compare(a.timeMs, b.timeMs);
                }
            });
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter setPushInScale(float pushInScale) {
        if (!zoomEvents.isEmpty()) {
            zoomEvents.get(0).toScale = Math.max(1.0f, pushInScale);
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter setPushInWindowMs(float startMs, float endMs) {
        if (!zoomEvents.isEmpty()) {
            ZoomEvent e = zoomEvents.get(0);
            e.startMs = Math.max(0f, startMs);
            e.endMs = Math.max(e.startMs + 1f, endMs);
        }
        return this;
    }

    public GlMosaicShiftCascadeFilter setShakeAmplitude(float ampX, float ampY) {
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

    public GlMosaicShiftCascadeFilter setShakeScalePadding(float shakeScalePadding) {
        this.shakeScalePadding = Math.max(0f, shakeScalePadding);
        return this;
    }

    public GlMosaicShiftCascadeFilter setMaxShakeScale(float maxShakeScale) {
        this.maxShakeScale = Math.max(1.0f, maxShakeScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter setMaxShakeBlur(float maxShakeBlur) {
        this.maxShakeBlur = Math.max(0f, Math.min(0.05f, maxShakeBlur));
        return this;
    }

    public GlMosaicShiftCascadeFilter setMaxShakeSampleScale(float maxShakeSampleScale) {
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

    private static class ZoomEvent {
        float startMs;
        float endMs;
        float fromScale;
        float toScale;
        int ease;

        ZoomEvent(float startMs, float endMs, float fromScale, float toScale, int ease) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.fromScale = fromScale;
            this.toScale = toScale;
            this.ease = ease;
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
