package com.example.lib_gles.video_filter.filter_impl;

import android.graphics.Color;
import android.opengl.GLES20;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Meteor around frame border:
 * - Clockwise loop on 4 edges
 * - Head wide, tail narrow
 * - Tail has inner-side fog transition
 * - Edge side remains sharp (no blur at the exact frame edge)
 */
public class MeteorFilter2 extends GlFilter {
    public interface OnCornerColorChangeListener {
        /**
         * Called when meteor head crosses a corner and color is about to switch.
         * cornerIndex: 0(top-right), 1(bottom-right), 2(bottom-left), 3(top-left)
         * nextColorIndex: 0(top), 1(right), 2(bottom), 3(left)
         * nextColor: packed RGB int (0xFFRRGGBB)
         */
        void onCornerColorChange(int cornerIndex, int nextColorIndex, int nextColor);
    }

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform vec3 uColor0;\n"
            + "uniform vec3 uColor1;\n"
            + "uniform vec3 uColor2;\n"
            + "uniform vec3 uColor3;\n"
            + "uniform float uOpacity;\n"
            + "uniform float uBrightness;\n"
            + "uniform float uHeadWidthPx;\n"
            + "uniform float uTailWidthPx;\n"
            + "uniform float uTailLengthPx;\n"
            + "uniform float uInnerSoftnessPx;\n"
            + "uniform float uBlurRadiusPx;\n"
            + "uniform float uHeadCapScale;\n"
            + "uniform float uSpeedRps;\n"
            + "uniform float uTimeSec;\n"
            + "uniform float uBlendStart;\n"
            + "uniform float uBlendGamma;\n"
            + "uniform float uCornerBlendLen;\n"
            + "uniform float uMidRatio;\n"
            + "uniform float uContentScaleY;\n"
            + "uniform float uHeadAlpha;\n"
            + "uniform float uTailAlpha;\n"
            + "\n"
            + "vec3 edgeBlend(vec3 curr, vec3 next, float tOnEdge) {\n"
            + "    float t = clamp(tOnEdge, 0.0, 1.0);\n"
            + "    float start = clamp(uBlendStart, 0.0, 0.98);\n"
            + "    float base = smoothstep(start, 1.0, t);\n"
            + "    float mixT = pow(base, max(0.25, uBlendGamma));\n"
            + "    return mix(curr, next, mixT);\n"
            + "}\n"
            + "\n"
            + "vec3 edgeBlendTwoStage(vec3 curr, vec3 next, float tOnEdge) {\n"
            + "    float t = clamp(tOnEdge, 0.0, 1.0);\n"
            + "    float start = clamp(uBlendStart, 0.0, 0.98);\n"
            + "    float base = smoothstep(start, 1.0, t);\n"
            + "    float k = pow(base, max(0.25, uBlendGamma));\n"
            + "    vec3 mid = mix(curr, next, clamp(uMidRatio, 0.1, 0.9));\n"
            + "    return mix(curr, mid, k);\n"
            + "}\n"
            + "\n"
            + "vec3 applyCornerContinuation(vec3 edgeColor, vec3 curr, vec3 next, float tOnEdge) {\n"
            + "    float len = clamp(uCornerBlendLen, 0.0, 0.8);\n"
            + "    if (len <= 0.0001) {\n"
            + "        return edgeColor;\n"
            + "    }\n"
            + "    float t = clamp(tOnEdge, 0.0, 1.0);\n"
            + "    vec3 mid = mix(curr, next, clamp(uMidRatio, 0.1, 0.9));\n"
            + "    float k = smoothstep(0.0, len, t);\n"
            + "    vec3 cont = mix(mid, next, pow(k, max(0.25, uBlendGamma)));\n"
            + "    // Only take effect near corner exit (next edge beginning)\n"
            + "    float useCont = 1.0 - smoothstep(len, len + 0.05, t);\n"
            + "    return mix(edgeColor, cont, useCont);\n"
            + "}\n"
            + "\n"
            + "vec3 pickColorByHeadPos(float headPos, float w, float h) {\n"
            + "    // Ensure initial frame starts from topColor.\n"
            + "    if (headPos <= 0.0001) {\n"
            + "        return uColor0;\n"
            + "    }\n"
            + "    float p0 = w;\n"
            + "    float p1 = w + h;\n"
            + "    float p2 = w + h + w;\n"
            + "    if (headPos < p0) {\n"
            + "        float t = headPos / max(w, 0.0001);\n"
            + "        vec3 c = edgeBlendTwoStage(uColor0, uColor1, t);\n"
            + "        return applyCornerContinuation(c, uColor3, uColor0, t);\n"
            + "    }\n"
            + "    if (headPos < p1) {\n"
            + "        float t = (headPos - p0) / max(h, 0.0001);\n"
            + "        vec3 c = edgeBlendTwoStage(uColor1, uColor2, t);\n"
            + "        return applyCornerContinuation(c, uColor0, uColor1, t);\n"
            + "    }\n"
            + "    if (headPos < p2) {\n"
            + "        float t = (headPos - p1) / max(w, 0.0001);\n"
            + "        vec3 c = edgeBlendTwoStage(uColor2, uColor3, t);\n"
            + "        return applyCornerContinuation(c, uColor1, uColor2, t);\n"
            + "    }\n"
            + "    float t = (headPos - p2) / max(h, 0.0001);\n"
            + "    vec3 c = edgeBlendTwoStage(uColor3, uColor0, t);\n"
            + "    return applyCornerContinuation(c, uColor2, uColor3, t);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec4 base = texture2D(sTexture, uv);\n"
            + "\n"
            + "    float w = max(uResolution.x, 1.0);\n"
            + "    float h = max(uResolution.y, 1.0);\n"
            + "    float x = uv.x * w;\n"
            + "    float y = uv.y * h;\n"
            + "    float scaleY = clamp(uContentScaleY, 0.01, 1.0);\n"
            + "    float insetY = (1.0 - scaleY) * 0.5 * h;\n"
            + "    float yMin = insetY;\n"
            + "    float yMax = h - insetY;\n"
            + "    float hInner = max(yMax - yMin, 1.0);\n"
            + "    float yc = clamp(y, yMin, yMax);\n"
            + "\n"
            + "    float dL = x;\n"
            + "    float dR = w - x;\n"
            + "    float dB = abs(y - yMin);\n"
            + "    float dT = abs(y - yMax);\n"
            + "    float dEdge = min(min(dL, dR), min(dB, dT));\n"
            + "\n"
            + "    float s;\n"
            + "    if (dT <= dR && dT <= dB && dT <= dL) {\n"
            + "        s = x;\n"
            + "    } else if (dR <= dT && dR <= dB && dR <= dL) {\n"
            + "        s = w + (yMax - yc);\n"
            + "    } else if (dB <= dT && dB <= dR && dB <= dL) {\n"
            + "        s = w + hInner + (w - x);\n"
            + "    } else {\n"
            + "        s = w + hInner + w + (yc - yMin);\n"
            + "    }\n"
            + "\n"
            + "    float perimeter = 2.0 * (w + hInner);\n"
            + "    float headPos = mod(uTimeSec * max(uSpeedRps, 0.0) * perimeter, perimeter);\n"
            + "    vec3 meteorColor = pickColorByHeadPos(headPos, w, hInner);\n"
            + "    float delta = mod(headPos - s + perimeter, perimeter);\n"
            + "    float signedDelta = delta;\n"
            + "    if (signedDelta > 0.5 * perimeter) {\n"
            + "        signedDelta -= perimeter;\n"
            + "    }\n"
            + "\n"
            + "    float tailLen = max(uTailLengthPx, 1.0);\n"
            + "    // Tail length measured on full perimeter (delta: 0..perimeter).\n"
            + "    float inTail = 1.0 - step(tailLen, delta);\n"
            + "    float tailT = clamp(1.0 - delta / tailLen, 0.0, 1.0);\n"
            + "    float tailLongitudinal = pow(tailT, 1.35) * inTail;\n"
            + "\n"
            + "    float headW = max(uHeadWidthPx, 0.5);\n"
            + "    float tailW = max(uTailWidthPx, 0.5);\n"
            + "    float localWidthTail = mix(tailW, headW, tailT) * inTail;\n"
            + "    float soft = max(uInnerSoftnessPx, 0.0001);\n"
            + "    float blurR = max(uBlurRadiusPx, 0.0);\n"
            + "\n"
            + "    float capLen = max(headW * (2.0 * max(uHeadCapScale, 0.001)), 0.5);\n"
            + "    float capForward = max(0.0, -signedDelta);\n"
            + "    // Smooth cap gate removes hard cut at head root/tip.\n"
            + "    float capEnter = 1.0 - smoothstep(0.0, soft * 1.6, signedDelta);\n"
            + "    float capExit = 1.0 - smoothstep(capLen - soft * 1.2, capLen + soft * 1.2, capForward);\n"
            + "    float inCap = clamp(capEnter * capExit, 0.0, 1.0);\n"
            + "    float capT = clamp(capForward / max(capLen, 0.0001), 0.0, 1.0);\n"
            + "    // Slightly rounded taper curve for a softer front profile.\n"
            + "    float capShrink = pow(max(1.0 - capT, 0.0), 0.78);\n"
            + "\n"
            + "    // Longitudinal alpha profile: strongest at middle, weaker at head/tail.\n"
            + "    float aHead = clamp(uHeadAlpha, 0.0, 1.0);\n"
            + "    float aTail = clamp(uTailAlpha, 0.0, 1.0);\n"
            + "    float totalLen = max(capLen + tailLen, 0.0001);\n"
            + "    float distFromHeadTip = 0.0;\n"
            + "    if (inCap > 0.0) {\n"
            + "        distFromHeadTip = capLen - capForward;\n"
            + "    } else {\n"
            + "        distFromHeadTip = capLen + clamp(delta, 0.0, tailLen);\n"
            + "    }\n"
            + "    float axialT = clamp(distFromHeadTip / totalLen, 0.0, 1.0);\n"
            + "    float alphaProfile;\n"
            + "    if (axialT <= 0.5) {\n"
            + "        float k = axialT / 0.5;\n"
            + "        alphaProfile = mix(aHead, 1.0, k);\n"
            + "    } else {\n"
            + "        float k = (axialT - 0.5) / 0.5;\n"
            + "        alphaProfile = mix(1.0, aTail, k);\n"
            + "    }\n"
            + "\n"
            + "    float coreTail = 1.0 - smoothstep(localWidthTail, localWidthTail + soft, dEdge);\n"
            + "    float hazeTail = 1.0 - smoothstep(localWidthTail + soft, localWidthTail + soft + blurR, dEdge);\n"
            + "    float tailCoreMask = clamp(coreTail, 0.0, 1.0) * tailLongitudinal;\n"
            + "    float tailGlowMask = max(hazeTail - coreTail, 0.0) * tailLongitudinal;\n"
            + "\n"
            + "    float capCoreWidth = max(0.0, headW * capShrink);\n"
            + "    float capHazeWidthRaw = max(0.0, (headW + soft + blurR) * capShrink);\n"
            + "    float capHazeWidth = max(capCoreWidth + soft + 0.0001, capHazeWidthRaw);\n"
            + "    float capFade = pow(capShrink, 0.95);\n"
            + "    float coreCap = (1.0 - smoothstep(capCoreWidth, capCoreWidth + soft, dEdge)) * inCap * capFade;\n"
            + "    float hazeCap = (1.0 - smoothstep(capCoreWidth + soft, capHazeWidth, dEdge)) * inCap * capFade;\n"
            + "    float capCoreMask = clamp(coreCap, 0.0, 1.0);\n"
            + "    float capGlowMask = max(hazeCap - coreCap, 0.0);\n"
            + "\n"
            + "    float coreMask = max(tailCoreMask, capCoreMask);\n"
            + "    float glowMask = max(tailGlowMask, capGlowMask);\n"
            + "    float op = clamp(uOpacity, 0.0, 1.0);\n"
            + "    float coreAlpha = coreMask * op * alphaProfile;\n"
            + "    float glowAlpha = glowMask * op * alphaProfile;\n"
            + "\n"
            + "    // Corner boost: corners are slightly brighter and wider like overlay references.\n"
            + "    float cd0 = length(vec2(x, abs(y - yMax)));\n"
            + "    float cd1 = length(vec2(w - x, abs(y - yMax)));\n"
            + "    float cd2 = length(vec2(w - x, abs(y - yMin)));\n"
            + "    float cd3 = length(vec2(x, abs(y - yMin)));\n"
            + "    float cornerDist = min(min(cd0, cd1), min(cd2, cd3));\n"
            + "    float cornerBoost = 1.0 - smoothstep(headW * 1.2, headW * 4.5 + blurR, cornerDist);\n"
            + "\n"
            + "    float bright = clamp(uBrightness, 0.0, 8.0);\n"
            + "    vec3 coreCol = meteorColor * bright * (0.95 + cornerBoost * 0.35);\n"
            + "    vec3 glowCol = meteorColor * bright * (0.70 + cornerBoost * 0.55);\n"
            + "\n"
            + "    vec3 outRgb = base.rgb;\n"
            + "    outRgb = clamp(outRgb + coreCol * coreAlpha, 0.0, 1.0);\n"
            + "    vec3 screenGlow = 1.0 - (1.0 - outRgb) * (1.0 - glowCol * glowAlpha);\n"
            + "    outRgb = mix(outRgb, screenGlow, clamp(glowAlpha * 0.90, 0.0, 1.0));\n"
            + "    gl_FragColor = vec4(outRgb, base.a);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int color0Handle = -1;
    private int color1Handle = -1;
    private int color2Handle = -1;
    private int color3Handle = -1;
    private int opacityHandle = -1;
    private int brightnessHandle = -1;
    private int headWidthHandle = -1;
    private int tailWidthHandle = -1;
    private int tailLengthHandle = -1;
    private int innerSoftnessHandle = -1;
    private int blurRadiusHandle = -1;
    private int headCapScaleHandle = -1;
    private int speedHandle = -1;
    private int timeHandle = -1;
    private int blendStartHandle = -1;
    private int blendGammaHandle = -1;
    private int cornerBlendLenHandle = -1;
    private int midRatioHandle = -1;
    private int contentScaleYHandle = -1;
    private int headAlphaHandle = -1;
    private int tailAlphaHandle = -1;

    private float color0R = 0.98f;
    private float color0G = 0.80f;
    private float color0B = 0.30f;
    private float color1R = 1.00f;
    private float color1G = 0.30f;
    private float color1B = 0.62f;
    private float color2R = 0.26f;
    private float color2G = 0.86f;
    private float color2B = 1.00f;
    private float color3R = 0.78f;
    private float color3G = 0.38f;
    private float color3B = 1.00f;
    private float opacity = 0.95f;
    private float brightness = 1.35f;
    private float headWidthPx = 30f;
    private float tailWidthPx = 9f;
    private float tailLengthPx = 320f;
    // If >= 0, tail length is resolved by perimeter * ratio.
    // If < 0, use tailLengthPx.
    private float tailLengthRatio = -1f;
    private float innerSoftnessPx = 28f;
    private float blurRadiusPx = 22f;
    private float headCapScale = 0.5f;
    private float speedRps = 0.18f;
    private float blendStart = 0.30f;
    private float blendGamma = 0.65f;
    private float cornerBlendLen = 0.22f;
    private float midRatio = 0.5f;
    private float contentScaleY = 1.0f;
    private float headAlpha = 0.25f;
    private float tailAlpha = 0.25f;
    private long lastPresentationUs = -1L;
    private long lastRealtimeMs = -1L;
    private float accumulatedTimeSec = 0f;
    private float lastHeadPosPx = -1f;
    private OnCornerColorChangeListener onCornerColorChangeListener;

    public MeteorFilter2() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        color0Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor0");
        color1Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor1");
        color2Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor2");
        color3Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor3");
        opacityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOpacity");
        brightnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBrightness");
        headWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeadWidthPx");
        tailWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTailWidthPx");
        tailLengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTailLengthPx");
        innerSoftnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uInnerSoftnessPx");
        blurRadiusHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurRadiusPx");
        headCapScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeadCapScale");
        speedHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSpeedRps");
        timeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTimeSec");
        blendStartHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlendStart");
        blendGammaHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlendGamma");
        cornerBlendLenHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCornerBlendLen");
        midRatioHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMidRatio");
        contentScaleYHandle = GLES20.glGetUniformLocation(mProgramHandle, "uContentScaleY");
        headAlphaHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeadAlpha");
        tailAlphaHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTailAlpha");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        if (lastRealtimeMs < 0L) {
            lastRealtimeMs = nowMs;
        }
        float fallbackDeltaSec = Math.max(0f, (nowMs - lastRealtimeMs) / 1000f);

        float deltaSec;
        if (presentationTimeUs > 0L && lastPresentationUs > 0L && presentationTimeUs > lastPresentationUs) {
            deltaSec = (presentationTimeUs - lastPresentationUs) / 1_000_000f;
            deltaSec = Math.max(0f, Math.min(deltaSec, 0.1f));
        } else {
            deltaSec = Math.max(0f, Math.min(fallbackDeltaSec, 0.1f));
        }
        accumulatedTimeSec += deltaSec;
        lastPresentationUs = presentationTimeUs > 0L ? presentationTimeUs : lastPresentationUs;
        lastRealtimeMs = nowMs;
        float timeSec = accumulatedTimeSec;
        float perimeterPx = 2f * (mWidth + mHeight);
        float innerHeightPx = Math.max(1.0f, mHeight * clamp(contentScaleY, 0.01f, 1.0f));
        float dynamicPerimeterPx = 2f * (mWidth + innerHeightPx);
        float resolvedTailLengthPx = tailLengthRatio >= 0f
                ? Math.max(1.0f, dynamicPerimeterPx * clamp(tailLengthRatio, 0.0f, 1.0f))
                : Math.max(1.0f, tailLengthPx);
        float headPosPx = dynamicPerimeterPx > 0f
                ? (timeSec * Math.max(0.0f, speedRps) * dynamicPerimeterPx) % dynamicPerimeterPx
                : 0f;
        dispatchCornerEvents(headPosPx, dynamicPerimeterPx, mWidth, innerHeightPx);
        lastHeadPosPx = headPosPx;
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform3f(color0Handle, clamp01(color0R), clamp01(color0G), clamp01(color0B));
        GLES20.glUniform3f(color1Handle, clamp01(color1R), clamp01(color1G), clamp01(color1B));
        GLES20.glUniform3f(color2Handle, clamp01(color2R), clamp01(color2G), clamp01(color2B));
        GLES20.glUniform3f(color3Handle, clamp01(color3R), clamp01(color3G), clamp01(color3B));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
        GLES20.glUniform1f(brightnessHandle, clamp(brightness, 0.0f, 8.0f));
        GLES20.glUniform1f(headWidthHandle, Math.max(0.5f, headWidthPx));
        GLES20.glUniform1f(tailWidthHandle, Math.max(0.5f, tailWidthPx));
        GLES20.glUniform1f(tailLengthHandle, resolvedTailLengthPx);
        GLES20.glUniform1f(innerSoftnessHandle, Math.max(0.0f, innerSoftnessPx));
        GLES20.glUniform1f(blurRadiusHandle, Math.max(0.0f, blurRadiusPx));
        GLES20.glUniform1f(headCapScaleHandle, Math.max(0.001f, headCapScale));
        GLES20.glUniform1f(speedHandle, Math.max(0.0f, speedRps));
        GLES20.glUniform1f(timeHandle, Math.max(0.0f, timeSec));
        GLES20.glUniform1f(blendStartHandle, clamp(blendStart, 0.0f, 0.98f));
        GLES20.glUniform1f(blendGammaHandle, Math.max(0.25f, blendGamma));
        GLES20.glUniform1f(cornerBlendLenHandle, clamp(cornerBlendLen, 0.0f, 0.8f));
        GLES20.glUniform1f(midRatioHandle, clamp(midRatio, 0.1f, 0.9f));
        GLES20.glUniform1f(contentScaleYHandle, clamp(contentScaleY, 0.01f, 1.0f));
        GLES20.glUniform1f(headAlphaHandle, clamp(headAlpha, 0.0f, 1.0f));
        GLES20.glUniform1f(tailAlphaHandle, clamp(tailAlpha, 0.0f, 1.0f));
    }

    @Override
    public void setup() {
        lastPresentationUs = -1L;
        lastRealtimeMs = -1L;
        accumulatedTimeSec = 0f;
        lastHeadPosPx = -1f;
        super.setup();
    }

    @Override
    public void release() {
        lastPresentationUs = -1L;
        lastRealtimeMs = -1L;
        accumulatedTimeSec = 0f;
        lastHeadPosPx = -1f;
        super.release();
    }

    public MeteorFilter2 setColor(float r, float g, float b) {
        float rr = clamp01(r);
        float gg = clamp01(g);
        float bb = clamp01(b);
        this.color0R = rr;
        this.color0G = gg;
        this.color0B = bb;
        this.color1R = rr;
        this.color1G = gg;
        this.color1B = bb;
        this.color2R = rr;
        this.color2G = gg;
        this.color2B = bb;
        this.color3R = rr;
        this.color3G = gg;
        this.color3B = bb;
        return this;
    }

    public MeteorFilter2 setColor(int color) {
        return setColor(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f
        );
    }

    /**
     * 4-edge color cycle in clockwise order:
     * top -> right -> bottom -> left.
     */
    public MeteorFilter2 setCornerColors(
            int topColor,
            int rightColor,
            int bottomColor,
            int leftColor) {
        this.color0R = Color.red(topColor) / 255f;
        this.color0G = Color.green(topColor) / 255f;
        this.color0B = Color.blue(topColor) / 255f;
        this.color1R = Color.red(rightColor) / 255f;
        this.color1G = Color.green(rightColor) / 255f;
        this.color1B = Color.blue(rightColor) / 255f;
        this.color2R = Color.red(bottomColor) / 255f;
        this.color2G = Color.green(bottomColor) / 255f;
        this.color2B = Color.blue(bottomColor) / 255f;
        this.color3R = Color.red(leftColor) / 255f;
        this.color3G = Color.green(leftColor) / 255f;
        this.color3B = Color.blue(leftColor) / 255f;
        return this;
    }

    public MeteorFilter2 setOnCornerColorChangeListener(OnCornerColorChangeListener listener) {
        this.onCornerColorChangeListener = listener;
        return this;
    }

    public MeteorFilter2 setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0.0f, 1.0f);
        return this;
    }

    public MeteorFilter2 setBrightness(float brightness) {
        this.brightness = clamp(brightness, 0.0f, 8.0f);
        return this;
    }

    public MeteorFilter2 setHeadWidthPx(float headWidthPx) {
        this.headWidthPx = Math.max(0.5f, headWidthPx);
        return this;
    }

    public MeteorFilter2 setTailWidthPx(float tailWidthPx) {
        this.tailWidthPx = Math.max(0.5f, tailWidthPx);
        return this;
    }

    public MeteorFilter2 setTailLengthPx(float tailLengthPx) {
        this.tailLengthPx = Math.max(1.0f, tailLengthPx);
        this.tailLengthRatio = -1f;
        return this;
    }

    /**
     * Tail length as ratio of frame perimeter [0,1].
     * Example: 0.25 means quarter of the whole border loop length.
     * Setting this enables ratio mode and overrides pixel length.
     */
    public MeteorFilter2 setTailLengthRatio(float tailLengthRatio) {
        this.tailLengthRatio = clamp(tailLengthRatio, 0.0f, 1.0f);
        return this;
    }

    /**
     * Tail fog transition width toward the inner image side.
     * Near frame edge remains sharp by design.
     */
    public MeteorFilter2 setInnerSoftnessPx(float innerSoftnessPx) {
        this.innerSoftnessPx = Math.max(0.0f, innerSoftnessPx);
        return this;
    }

    /**
     * Extra fog/blur radius toward inner image side (pixels).
     * Keeps frame-edge side sharp.
     */
    public MeteorFilter2 setBlurRadiusPx(float blurRadiusPx) {
        this.blurRadiusPx = Math.max(0.0f, blurRadiusPx);
        return this;
    }

    /**
     * Head cap length scale relative to head width.
     * 0.5 = semicircle; >0.5 = stretched semi-ellipse.
     */
    public MeteorFilter2 setHeadCapScale(float headCapScale) {
        this.headCapScale = Math.max(0.001f, headCapScale);
        return this;
    }

    /**
     * Backward-compatible API.
     * Converts absolute cap length(px) to scale where 0.5 is semicircle.
     */
    public MeteorFilter2 setHeadCapPx(float headCapPx) {
        float safeHeadW = Math.max(0.5f, headWidthPx);
        this.headCapScale = Math.max(0.001f, headCapPx / (2.0f * safeHeadW));
        return this;
    }

    /**
     * Loops per second around the full frame perimeter.
     */
    public MeteorFilter2 setSpeedRps(float speedRps) {
        this.speedRps = Math.max(0.0f, speedRps);
        return this;
    }

    /**
     * Color blending start position on each edge [0..1].
     * Lower value means earlier/longer transition.
     */
    public MeteorFilter2 setColorBlendStart(float blendStart) {
        this.blendStart = clamp(blendStart, 0.0f, 0.98f);
        return this;
    }

    /**
     * Color blend curve gamma.
     * <1 makes transition more obvious; >1 makes it softer.
     */
    public MeteorFilter2 setColorBlendGamma(float blendGamma) {
        this.blendGamma = Math.max(0.25f, blendGamma);
        return this;
    }

    /**
     * Blend length ratio on the next edge after crossing corner [0..0.8].
     */
    public MeteorFilter2 setCornerBlendLen(float cornerBlendLen) {
        this.cornerBlendLen = clamp(cornerBlendLen, 0.0f, 0.8f);
        return this;
    }

    /**
     * Intermediate color ratio between current and next [0.1..0.9].
     * 0.5 means exact middle color.
     */
    public MeteorFilter2 setColorMidRatio(float midRatio) {
        this.midRatio = clamp(midRatio, 0.1f, 0.9f);
        return this;
    }

    /**
     * Follow vertical content scale (same meaning as GlPulseVerticalScaleFilter uScaleY).
     * 1.0 = no vertical shrink; smaller value moves border toward center vertically.
     */
    public MeteorFilter2 setContentScaleY(float contentScaleY) {
        this.contentScaleY = clamp(contentScaleY, 0.01f, 1.0f);
        return this;
    }

    /**
     * Alpha strength on the head side of meteor [0..1].
     * 1 = same strength as middle, 0 = fully faded.
     */
    public MeteorFilter2 setHeadAlpha(float headAlpha) {
        this.headAlpha = clamp(headAlpha, 0.0f, 1.0f);
        return this;
    }

    /**
     * Alpha strength on the tail side of meteor [0..1].
     * 1 = same strength as middle, 0 = fully faded.
     */
    public MeteorFilter2 setTailAlpha(float tailAlpha) {
        this.tailAlpha = clamp(tailAlpha, 0.0f, 1.0f);
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private void dispatchCornerEvents(float headPosPx, float perimeterPx, float width, float height) {
        if (onCornerColorChangeListener == null || perimeterPx <= 0f || lastHeadPosPx < 0f) {
            return;
        }
        float p0 = width;
        float p1 = width + height;
        float p2 = width + height + width;
        float p3 = perimeterPx;
        float[] boundaries = new float[]{p0, p1, p2, p3};
        int[] cornerIndices = new int[]{0, 1, 2, 3};
        int[] nextColorIndices = new int[]{1, 2, 3, 0};

        if (lastHeadPosPx <= headPosPx) {
            for (int i = 0; i < boundaries.length; i++) {
                if (lastHeadPosPx < boundaries[i] && boundaries[i] <= headPosPx) {
                    notifyCorner(cornerIndices[i], nextColorIndices[i]);
                }
            }
        } else {
            for (int i = 0; i < boundaries.length; i++) {
                if (lastHeadPosPx < boundaries[i] && boundaries[i] <= perimeterPx) {
                    notifyCorner(cornerIndices[i], nextColorIndices[i]);
                }
            }
            for (int i = 0; i < boundaries.length; i++) {
                if (0f <= boundaries[i] && boundaries[i] <= headPosPx) {
                    notifyCorner(cornerIndices[i], nextColorIndices[i]);
                }
            }
        }
    }

    private void notifyCorner(int cornerIndex, int nextColorIndex) {
        int nextColor = getColorInt(nextColorIndex);
        onCornerColorChangeListener.onCornerColorChange(cornerIndex, nextColorIndex, nextColor);
    }

    private int getColorInt(int colorIndex) {
        switch (colorIndex) {
            case 0:
                return packColor(color0R, color0G, color0B);
            case 1:
                return packColor(color1R, color1G, color1B);
            case 2:
                return packColor(color2R, color2G, color2B);
            default:
                return packColor(color3R, color3G, color3B);
        }
    }

    private static int packColor(float r, float g, float b) {
        int rr = (int) (clamp01(r) * 255f + 0.5f);
        int gg = (int) (clamp01(g) * 255f + 0.5f);
        int bb = (int) (clamp01(b) * 255f + 0.5f);
        return Color.rgb(rr, gg, bb);
    }
}
