package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Based on GlMosaicShiftCascadeFilter3, but shake rendering is rebuilt around ShakeFilter's look:
 * fast lateral hit, overscan scale, horizontal stretch, coarse sample, thick soft blur.
 */
public class GlMosaicShiftCascadeFilter5 extends GlFilter {

    public static final int EASE_LINEAR = 0;
    public static final int EASE_SMOOTH = 1;

    public static final int SHAKE_MODE_PULSE = 0;
    public static final int SHAKE_MODE_OSCILLATE = 1;

    private static final String FRAGMENT_SHADER = ""
            + "precision highp float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uBlockSize;\n"
            + "uniform vec2 uOffset;\n"
            + "uniform vec2 uScale;\n"
            + "uniform vec2 uStretch;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uSampleMix;\n"
            + "uniform float uSmearStrength;\n"
            + "uniform float uSoftBlurStrength;\n"
            + "uniform float uMosaicMaxBlockSize;\n"
            + "uniform float uCrystalThreshold;\n"
            + "uniform float uCrystalSoftness;\n"
            + "uniform float uCrystalSizeScale;\n"
            + "uniform float uCrystalEdgeBoost;\n"
            + "uniform float uBlackFade;\n"
            + "\n"
            + "vec2 coarseSampleUv(vec2 uv, float samplePx, float sampleMix) {\n"
            + "    float s = max(1.0, samplePx);\n"
            + "    vec2 px = uv * uResolution;\n"
            + "    vec2 q = floor(px / s) * s + vec2(s * 0.5);\n"
            + "    vec2 coarse = clamp(q / uResolution, 0.0, 1.0);\n"
            + "    return mix(uv, coarse, clamp(sampleMix, 0.0, 1.0));\n"
            + "}\n"
            + "\n"
            + "vec3 sampledColor(vec2 uv, float samplePx, float sampleMix) {\n"
            + "    return texture2D(sTexture, coarseSampleUv(clamp(uv, 0.0, 1.0), samplePx, sampleMix)).rgb;\n"
            + "}\n"
            + "\n"
            + "vec3 blurFromSampled(vec2 uv, vec2 texel, float radiusPx, float samplePx, float sampleMix) {\n"
            + "    vec2 o = texel * max(0.0, radiusPx);\n"
            + "    vec3 c = vec3(0.0);\n"
            + "    c += sampledColor(uv, samplePx, sampleMix) * 0.24;\n"
            + "    c += sampledColor(uv + vec2( o.x, 0.0), samplePx, sampleMix) * 0.12;\n"
            + "    c += sampledColor(uv + vec2(-o.x, 0.0), samplePx, sampleMix) * 0.12;\n"
            + "    c += sampledColor(uv + vec2(0.0,  o.y), samplePx, sampleMix) * 0.12;\n"
            + "    c += sampledColor(uv + vec2(0.0, -o.y), samplePx, sampleMix) * 0.12;\n"
            + "    c += sampledColor(uv + vec2( o.x,  o.y), samplePx, sampleMix) * 0.07;\n"
            + "    c += sampledColor(uv + vec2(-o.x,  o.y), samplePx, sampleMix) * 0.07;\n"
            + "    c += sampledColor(uv + vec2( o.x, -o.y), samplePx, sampleMix) * 0.07;\n"
            + "    c += sampledColor(uv + vec2(-o.x, -o.y), samplePx, sampleMix) * 0.07;\n"
            + "    return c;\n"
            + "}\n"
            + "\n"
            + "vec2 diamondCenterUv(vec2 uv, float cellSizePx) {\n"
            + "    vec2 px = uv * uResolution;\n"
            + "    float s = 0.70710678;\n"
            + "    mat2 rot = mat2(s, -s, s, s);\n"
            + "    mat2 invRot = mat2(s, s, -s, s);\n"
            + "    float cell = max(1.0, cellSizePx);\n"
            + "    vec2 r = rot * px;\n"
            + "    vec2 q = floor(r / cell) * cell + vec2(cell * 0.5);\n"
            + "    vec2 outPx = invRot * q;\n"
            + "    return clamp(outPx / uResolution, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "float diamondEdgeFactor(vec2 uv, float cellSizePx) {\n"
            + "    vec2 px = uv * uResolution;\n"
            + "    float s = 0.70710678;\n"
            + "    mat2 rot = mat2(s, -s, s, s);\n"
            + "    float cell = max(1.0, cellSizePx);\n"
            + "    vec2 r = rot * px;\n"
            + "    vec2 local = fract(r / cell) - vec2(0.5);\n"
            + "    float d = abs(local.x) + abs(local.y);\n"
            + "    return smoothstep(0.78, 1.0, d);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 scale = max(uScale, vec2(1.0));\n"
            + "    vec2 centered = textureCoordinate - vec2(0.5);\n"
            + "    vec2 scaledUv = centered / scale + vec2(0.5);\n"
            + "    vec2 shiftedUv = clamp(scaledUv - uOffset, 0.0, 1.0);\n"
            + "    vec2 stretchedUv = vec2(0.5 + (shiftedUv.x - 0.5) / max(uStretch.x, 1.0), 0.5 + (shiftedUv.y - 0.5) / max(uStretch.y, 1.0));\n"
            + "    stretchedUv = clamp(stretchedUv, 0.0, 1.0);\n"
            + "\n"
            + "    vec2 sampledUv = stretchedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((stretchedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        sampledUv = mix(stretchedUv, coarseUv, clamp(uSampleMix, 0.0, 1.0));\n"
            + "    }\n"
            + "\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec4 base;\n"
            + "    if (uBlockSize <= 1.0) {\n"
            + "        float preSamplePx = max(1.0, uSampleScale);\n"
            + "        float preSampleMix = clamp(uSampleMix, 0.0, 1.0);\n"
            + "        base = vec4(sampledColor(stretchedUv, preSamplePx, preSampleMix), 1.0);\n"
            + "    } else {\n"
            + "        // Level-driven full-frame pre-sample + blur.\n"
            + "        float level = clamp((uBlockSize - 1.0) / max(1.0, uMosaicMaxBlockSize - 1.0), 0.0, 1.0);\n"
            + "        float preSamplePx = mix(1.2, 7.0, level);\n"
            + "        float preSampleMix = mix(0.08, 0.62, level);\n"
            + "        float preBlurPx = mix(0.7, 6.2, level);\n"
            + "        vec2 preUv = coarseSampleUv(stretchedUv, preSamplePx, preSampleMix);\n"
            + "        vec3 preFrame = blurFromSampled(preUv, texel, preBlurPx, preSamplePx, preSampleMix);\n"
            + "\n"
            + "        // Crystallization runs on top of sampled+blurred basis.\n"
            + "        float cellSize = max(1.0, uBlockSize * max(1.0, uCrystalSizeScale));\n"
            + "        vec2 dUv = diamondCenterUv(stretchedUv, cellSize);\n"
            + "        vec3 crystal = blurFromSampled(dUv, texel, preBlurPx * 0.85, preSamplePx, preSampleMix);\n"
            + "        float edge = diamondEdgeFactor(stretchedUv, cellSize);\n"
            + "        crystal = mix(crystal, crystal * 1.14 + vec3(0.05), edge * 0.56);\n"
            + "\n"
            + "        // Crystal edge emphasis: sharpen + bright rim\n"
            + "        float eBoost = clamp(uCrystalEdgeBoost, 0.0, 2.0);\n"
            + "        vec2 eStep = vec2(cellSize / max(uResolution.x, 1.0), cellSize / max(uResolution.y, 1.0)) * 0.10;\n"
            + "        vec3 cL = blurFromSampled(clamp(dUv - vec2(eStep.x, 0.0), 0.0, 1.0), texel, preBlurPx * 0.82, preSamplePx, preSampleMix);\n"
            + "        vec3 cR = blurFromSampled(clamp(dUv + vec2(eStep.x, 0.0), 0.0, 1.0), texel, preBlurPx * 0.82, preSamplePx, preSampleMix);\n"
            + "        vec3 cU = blurFromSampled(clamp(dUv - vec2(0.0, eStep.y), 0.0, 1.0), texel, preBlurPx * 0.82, preSamplePx, preSampleMix);\n"
            + "        vec3 cD = blurFromSampled(clamp(dUv + vec2(0.0, eStep.y), 0.0, 1.0), texel, preBlurPx * 0.82, preSamplePx, preSampleMix);\n"
            + "        vec3 lap = (crystal * 4.0 - cL - cR - cU - cD);\n"
            + "        float lapLum = max(0.0, dot(abs(lap), vec3(0.299, 0.587, 0.114)) - 0.015);\n"
            + "        float rim = edge * smoothstep(0.02, 0.24, lapLum);\n"
            + "        crystal += lap * (0.28 * eBoost);\n"
            + "        crystal += vec3(1.0) * (rim * 0.22 * eBoost);\n"
            + "        crystal = clamp(crystal, 0.0, 1.0);\n"
            + "\n"
            + "        // Stable highlight mask in crystal space: center-based + 4-neighbor dilation.\n"
            + "        float soft = max(0.001, uCrystalSoftness);\n"
            + "        float stepUv = cellSize / max(min(uResolution.x, uResolution.y), 1.0);\n"
            + "        vec2 du = vec2(stepUv, 0.0);\n"
            + "        vec2 dv = vec2(0.0, stepUv);\n"
            + "        float r = max(0.6, preBlurPx * 0.9);\n"
            + "        float l0 = dot(blurFromSampled(dUv, texel, r, preSamplePx, preSampleMix), vec3(0.299, 0.587, 0.114));\n"
            + "        float l1 = dot(blurFromSampled(clamp(dUv + du, 0.0, 1.0), texel, r, preSamplePx, preSampleMix), vec3(0.299, 0.587, 0.114));\n"
            + "        float l2 = dot(blurFromSampled(clamp(dUv - du, 0.0, 1.0), texel, r, preSamplePx, preSampleMix), vec3(0.299, 0.587, 0.114));\n"
            + "        float l3 = dot(blurFromSampled(clamp(dUv + dv, 0.0, 1.0), texel, r, preSamplePx, preSampleMix), vec3(0.299, 0.587, 0.114));\n"
            + "        float l4 = dot(blurFromSampled(clamp(dUv - dv, 0.0, 1.0), texel, r, preSamplePx, preSampleMix), vec3(0.299, 0.587, 0.114));\n"
            + "        float lum = max(l0, max(max(l1, l2), max(l3, l4)));\n"
            + "        float hiMask = smoothstep(uCrystalThreshold - soft, uCrystalThreshold + soft, lum);\n"
            + "        vec3 mixed = mix(preFrame, crystal, hiMask);\n"
            + "        base = vec4(mixed, 1.0);\n"
            + "    }\n"
            + "\n"
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
            + "    color = mix(color, vec3(0.0), clamp(uBlackFade, 0.0, 1.0));\n"
            + "    gl_FragColor = vec4(color, 1.0);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int blockSizeHandle = -1;
    private int offsetHandle = -1;
    private int scaleHandle = -1;
    private int stretchHandle = -1;
    private int sampleScaleHandle = -1;
    private int sampleMixHandle = -1;
    private int smearStrengthHandle = -1;
    private int softBlurStrengthHandle = -1;
    private int mosaicMaxBlockSizeHandle = -1;
    private int crystalThresholdHandle = -1;
    private int crystalSoftnessHandle = -1;
    private int crystalSizeScaleHandle = -1;
    private int crystalEdgeBoostHandle = -1;
    private int blackFadeHandle = -1;

    private float firstPresentationMs = -1f;
    private float mosaicMaxBlockSize = 40f;
    private float shakeScalePadding = 0.02f;
    private float maxShakeScale = 1.18f;
    private float maxShakeStretchX = 1.28f;
    private float maxShakeStretchY = 1.0f;
    private float maxShakeSampleScale = 8.0f;
    private float maxShakeSampleMix = 0.68f;
    private float maxShakeSmearStrength = 0.85f;
    private float maxShakeSoftBlurStrength = 0.065f;
    private boolean loopEnabled = true;
    private float loopDurationMs = -1f; // <=0 means auto from timeline config
    private float crystalThreshold = 0.80f;
    private float crystalSoftness = 0.07f;
    private float crystalSizeScale = 1.8f;
    private float crystalEdgeBoost = 1.0f;

    private final List<MosaicKeyframe> mosaicKeyframes = new ArrayList<>();
    private final List<ZoomEvent> zoomEvents = new ArrayList<>();
    private final List<ShakeEvent> shakeEvents = new ArrayList<>();
    private final List<BlackFadeEvent> blackFadeEvents = new ArrayList<>();

    public GlMosaicShiftCascadeFilter5() {
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
        sampleMixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleMix");
        smearStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSmearStrength");
        softBlurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSoftBlurStrength");
        mosaicMaxBlockSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMosaicMaxBlockSize");
        crystalThresholdHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCrystalThreshold");
        crystalSoftnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCrystalSoftness");
        crystalSizeScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCrystalSizeScale");
        crystalEdgeBoostHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCrystalEdgeBoost");
        blackFadeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlackFade");
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
        float loopedMs = resolveLoopedTimeMs(tMs);

        float zoomScale = resolveZoomScale(loopedMs);
        float shiftX = resolveShakeX(loopedMs);
        float shiftY = resolveShakeY(loopedMs);
        float shakeStrength = resolveShakeStrength(loopedMs);
        float blackFade = resolveBlackFade(loopedMs);
        boolean shaking = Math.abs(shiftX) > 1e-5f || Math.abs(shiftY) > 1e-5f;

        float blockSize = shaking ? 0f : resolveMosaic(loopedMs);
        float scaleX = zoomScale;
        float scaleY = zoomScale;
        float stretchX = 1.0f;
        float stretchY = 1.0f;
        float sampleScale = 1.0f;
        float sampleMix = 0.0f;
        float smearStrength = 0f;
        float softBlurStrength = 0f;

        if (shaking) {
            // HitShake-like: scale is driven by impact energy first,
            // and extended only when needed to keep translated frame safe.
            float punch = (float) Math.pow(clamp01(shakeStrength), 0.42f);
            float energyScale = lerp(1.0f, maxShakeScale, punch);
            float requiredScaleX = Math.max(1.0f, computeShakeScaleForOffset(shiftX));
            float requiredScaleY = Math.max(1.0f, computeShakeScaleForOffset(shiftY));
            float shakeScaleX = Math.max(energyScale, requiredScaleX);
            float shakeScaleY = Math.max(energyScale, requiredScaleY);
            scaleX = Math.max(zoomScale, zoomScale * shakeScaleX);
            scaleY = Math.max(zoomScale, zoomScale * shakeScaleY);

            float maxOffsetX = computeMaxOffsetForScale(scaleX);
            float maxOffsetY = computeMaxOffsetForScale(scaleY);
            shiftX = clamp(shiftX, -maxOffsetX * 0.995f, maxOffsetX * 0.995f);
            shiftY = clamp(shiftY, -maxOffsetY * 0.995f, maxOffsetY * 0.995f);

            // Stretch/blur follow impact energy and movement amount.
            float motionX = clamp01(Math.abs(shiftX) / 0.12f);
            float motionY = clamp01(Math.abs(shiftY) / 0.12f);
            float stretchFx = clamp01(punch * 0.70f + motionX * 0.45f);
            float stretchFy = clamp01(punch * 0.45f + motionY * 0.40f);
            stretchX = lerp(1.0f, maxShakeStretchX, stretchFx);
            stretchY = lerp(1.0f, maxShakeStretchY, stretchFy);

            sampleScale = lerp(1.0f, maxShakeSampleScale, clamp01(punch * 0.92f));
            sampleMix = lerp(0.0f, maxShakeSampleMix, clamp01(punch * 0.88f));
            smearStrength = lerp(0.0f, maxShakeSmearStrength, clamp01(punch * 0.95f));
            softBlurStrength = lerp(0.0f, maxShakeSoftBlurStrength, clamp01(punch));
        }

        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(blockSizeHandle, Math.max(0f, blockSize));
        GLES20.glUniform2f(offsetHandle, shiftX, -shiftY);
        GLES20.glUniform2f(scaleHandle, Math.max(1.0f, scaleX), Math.max(1.0f, scaleY));
        GLES20.glUniform2f(stretchHandle, Math.max(1.0f, stretchX), Math.max(1.0f, stretchY));
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScale));
        GLES20.glUniform1f(sampleMixHandle, clamp(sampleMix, 0.0f, 1.0f));
        GLES20.glUniform1f(smearStrengthHandle, Math.max(0f, smearStrength));
        GLES20.glUniform1f(softBlurStrengthHandle, clamp(softBlurStrength, 0.0f, 0.12f));
        GLES20.glUniform1f(mosaicMaxBlockSizeHandle, Math.max(1f, mosaicMaxBlockSize));
        GLES20.glUniform1f(crystalThresholdHandle, clamp(crystalThreshold, 0.0f, 1.0f));
        GLES20.glUniform1f(crystalSoftnessHandle, clamp(crystalSoftness, 0.001f, 0.5f));
        GLES20.glUniform1f(crystalSizeScaleHandle, Math.max(1.0f, crystalSizeScale));
        GLES20.glUniform1f(crystalEdgeBoostHandle, clamp(crystalEdgeBoost, 0.0f, 2.0f));
        GLES20.glUniform1f(blackFadeHandle, clamp(blackFade, 0.0f, 1.0f));
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

    public GlMosaicShiftCascadeFilter5 clearMosaicKeyframes() {
        mosaicKeyframes.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter5 addMosaicKeyframe(float timeMs, float blockSize) {
        mosaicKeyframes.add(new MosaicKeyframe(Math.max(0f, timeMs), Math.max(0f, blockSize)));
        sortMosaicKeyframes();
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMosaicMaxBlockSize(float mosaicMaxBlockSize) {
        this.mosaicMaxBlockSize = Math.max(1f, mosaicMaxBlockSize);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 addMosaicLevelKeyframe(float timeMs, float level) {
        return addMosaicKeyframe(timeMs, clamp01(level) * mosaicMaxBlockSize);
    }

    public GlMosaicShiftCascadeFilter5 clearZoomEvents() {
        zoomEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter5 addZoomEvent(float startMs, float endMs, float fromScale, float toScale, int ease) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        zoomEvents.add(new ZoomEvent(s, e, Math.max(1.0f, fromScale), Math.max(1.0f, toScale), ease));
        return this;
    }

    public GlMosaicShiftCascadeFilter5 clearShakeEvents() {
        shakeEvents.clear();
        return this;
    }

    public GlMosaicShiftCascadeFilter5 addPulseShakeEvent(float startMs, float endMs, float ampX, float ampY) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_PULSE, 1);
    }

    public GlMosaicShiftCascadeFilter5 addOscillatingShakeEvent(float startMs, float endMs, float ampX, float ampY, int cycles) {
        return addShakeEvent(startMs, endMs, ampX, ampY, SHAKE_MODE_OSCILLATE, cycles);
    }

    public GlMosaicShiftCascadeFilter5 addShakeEvent(float startMs, float endMs, float ampX, float ampY, int mode, int cycles) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        shakeEvents.add(new ShakeEvent(s, e, ampX, ampY, mode, Math.max(1, cycles)));
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setShakeScalePadding(float shakeScalePadding) {
        this.shakeScalePadding = Math.max(0f, shakeScalePadding);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeScale(float maxShakeScale) {
        this.maxShakeScale = Math.max(1.0f, maxShakeScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeStretch(float maxShakeStretchX) {
        this.maxShakeStretchX = Math.max(1.0f, maxShakeStretchX);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeStretch(float maxShakeStretchX, float maxShakeStretchY) {
        this.maxShakeStretchX = Math.max(1.0f, maxShakeStretchX);
        this.maxShakeStretchY = Math.max(1.0f, maxShakeStretchY);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeSampleScale(float maxShakeSampleScale) {
        this.maxShakeSampleScale = Math.max(1.0f, maxShakeSampleScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeSampleMix(float maxShakeSampleMix) {
        this.maxShakeSampleMix = clamp(maxShakeSampleMix, 0.0f, 1.0f);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeSmearStrength(float maxShakeSmearStrength) {
        this.maxShakeSmearStrength = Math.max(0.0f, maxShakeSmearStrength);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setMaxShakeSoftBlurStrength(float maxShakeSoftBlurStrength) {
        this.maxShakeSoftBlurStrength = clamp(maxShakeSoftBlurStrength, 0.0f, 0.12f);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setLoopEnabled(boolean loopEnabled) {
        this.loopEnabled = loopEnabled;
        return this;
    }

    /**
     * Set cycle duration for looping all effects.
     * <=0 means auto duration (max end time among configured effects).
     */
    public GlMosaicShiftCascadeFilter5 setLoopDurationMs(float loopDurationMs) {
        this.loopDurationMs = loopDurationMs;
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setCrystalThreshold(float crystalThreshold) {
        this.crystalThreshold = clamp(crystalThreshold, 0.0f, 1.0f);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setCrystalSoftness(float crystalSoftness) {
        this.crystalSoftness = clamp(crystalSoftness, 0.001f, 0.5f);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setCrystalSizeScale(float crystalSizeScale) {
        this.crystalSizeScale = Math.max(1.0f, crystalSizeScale);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 setCrystalEdgeBoost(float crystalEdgeBoost) {
        this.crystalEdgeBoost = clamp(crystalEdgeBoost, 0.0f, 2.0f);
        return this;
    }

    public GlMosaicShiftCascadeFilter5 clearBlackFadeEvents() {
        blackFadeEvents.clear();
        return this;
    }

    /**
     * Add a fade-to-black event. from/to are [0..1].
     */
    public GlMosaicShiftCascadeFilter5 addBlackFadeEvent(float startMs, float endMs, float from, float to, int ease) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        blackFadeEvents.add(new BlackFadeEvent(s, e, clamp01(from), clamp01(to), ease));
        return this;
    }

    private float resolveBlackFade(float tMs) {
        float out = 0f;
        for (BlackFadeEvent event : blackFadeEvents) {
            if (tMs < event.startMs || tMs >= event.endMs) {
                continue;
            }
            float p = applyEase(normProgress(tMs, event.startMs, event.endMs), event.ease);
            out = Math.max(out, lerp(event.from, event.to, p));
        }
        return clamp01(out);
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
            out = Math.max(out, sampleShakeStrengthFactor(event, tMs));
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
        return amp * sampleShakeDisplacementFactor(event, tMs);
    }

    /**
     * Displacement curve:
     * - pulse: HitShake style (fast push, then slight pull-back)
     * - oscillate: periodic punch envelope per cycle
     */
    private static float sampleShakeDisplacementFactor(ShakeEvent event, float tMs) {
        float p = normProgress(tMs, event.startMs, event.endMs);
        if (event.mode == SHAKE_MODE_OSCILLATE) {
            float cycleProgress = clamp01((p * Math.max(1, event.cycles)) % 1.0f);
            return snappyDisplacement(cycleProgress);
        }
        return snappyDisplacement(p);
    }

    /**
     * Energy curve used by scale/stretch/blur.
     * Keep non-negative to avoid flicker when displacement enters rebound phase.
     */
    private static float sampleShakeStrengthFactor(ShakeEvent event, float tMs) {
        float p = normProgress(tMs, event.startMs, event.endMs);
        if (event.mode == SHAKE_MODE_OSCILLATE) {
            float cycleProgress = clamp01((p * Math.max(1, event.cycles)) % 1.0f);
            return snappyStrength(cycleProgress);
        }
        return snappyStrength(p);
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

    private static float easeOutBack(float t) {
        float p = clamp01(t);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float q = p - 1.0f;
        return 1.0f + c3 * q * q * q + c1 * q * q;
    }

    /**
     * Same profile as HitShakeFilter:
     * 0~40%: rapid rise
     * 40~100%: rebound/recover
     */
    private static float hitShakeImpactCurve(float p) {
        float t = clamp01(p);
        if (t < 0.4f) {
            return (float) Math.pow(t / 0.4f, 0.5f);
        }
        float tt = (t - 0.4f) / 0.6f;
        return easeOutBack(1.0f - tt);
    }

    /**
     * Displacement with a slight reverse pull in the tail,
     * so motion looks like "push out then pull back".
     */
    private static float hitShakeShiftCurve(float p) {
        float t = clamp01(p);
        float impact = hitShakeImpactCurve(t);
        float shift = impact;
        if (t > 0.6f) {
            float rb = (t - 0.6f) / 0.4f;
            shift -= clamp01(rb) * 0.25f;
        }
        return shift;
    }

    /**
     * Compress effect to the front part of each shake window:
     * fast hit, quick pull-back, minimal trailing tail.
     */
    private static float snappyDisplacement(float p) {
        float t = clamp01(p);
        final float activeEnd = 0.68f;
        if (t >= activeEnd) {
            return 0f;
        }
        float tp = t / activeEnd;
        return hitShakeShiftCurve(tp);
    }

    /**
     * Energy also uses compressed window + faster release,
     * so blur/stretch won't feel拖沓.
     */
    private static float snappyStrength(float p) {
        float t = clamp01(p);
        final float activeEnd = 0.68f;
        if (t >= activeEnd) {
            return 0f;
        }
        float tp = t / activeEnd;
        float impact = hitShakeImpactCurve(tp);
        float release = 1.0f - smoothStep(0.52f, 1.0f, tp);
        return impact * release;
    }

    private static float smoothStep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / Math.max(1e-6f, e1 - e0));
        return t * t * (3f - 2f * t);
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

    private float resolveLoopedTimeMs(float tMs) {
        if (!loopEnabled) {
            return tMs;
        }
        float cycle = loopDurationMs > 0f ? loopDurationMs : computeAutoLoopDurationMs();
        if (cycle <= 1f) {
            return tMs;
        }
        float m = tMs % cycle;
        return m < 0f ? (m + cycle) : m;
    }

    private float computeAutoLoopDurationMs() {
        float maxEnd = 0f;
        for (MosaicKeyframe k : mosaicKeyframes) {
            maxEnd = Math.max(maxEnd, k.timeMs);
        }
        for (ZoomEvent e : zoomEvents) {
            maxEnd = Math.max(maxEnd, e.endMs);
        }
        for (ShakeEvent e : shakeEvents) {
            maxEnd = Math.max(maxEnd, e.endMs);
        }
        for (BlackFadeEvent e : blackFadeEvents) {
            maxEnd = Math.max(maxEnd, e.endMs);
        }
        return Math.max(1f, maxEnd);
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

    private static class BlackFadeEvent {
        final float startMs;
        final float endMs;
        final float from;
        final float to;
        final int ease;

        BlackFadeEvent(float startMs, float endMs, float from, float to, int ease) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.from = from;
            this.to = to;
            this.ease = ease;
        }
    }
}
