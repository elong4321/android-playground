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
public class MeteorFilter extends GlFilter {
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
            + "\n"
            + "    float dL = x;\n"
            + "    float dR = w - x;\n"
            + "    float dB = y;\n"
            + "    float dT = h - y;\n"
            + "    float dEdge = min(min(dL, dR), min(dB, dT));\n"
            + "\n"
            + "    float s;\n"
            + "    if (dT <= dR && dT <= dB && dT <= dL) {\n"
            + "        s = x;\n"
            + "    } else if (dR <= dT && dR <= dB && dR <= dL) {\n"
            + "        s = w + (h - y);\n"
            + "    } else if (dB <= dT && dB <= dR && dB <= dL) {\n"
            + "        s = w + h + (w - x);\n"
            + "    } else {\n"
            + "        s = w + h + w + y;\n"
            + "    }\n"
            + "\n"
            + "    float perimeter = 2.0 * (w + h);\n"
            + "    float headPos = mod(uTimeSec * max(uSpeedRps, 0.0) * perimeter, perimeter);\n"
            + "    vec3 meteorColor = pickColorByHeadPos(headPos, w, h);\n"
            + "    float delta = mod(headPos - s + perimeter, perimeter);\n"
            + "    float signedDelta = delta;\n"
            + "    if (signedDelta > 0.5 * perimeter) {\n"
            + "        signedDelta -= perimeter;\n"
            + "    }\n"
            + "\n"
            + "    float tailLen = max(uTailLengthPx, 1.0);\n"
            + "    // Warm-up from left-top start: no wrap-around tail before head has moved enough.\n"
            + "    float inTail;\n"
            + "    float tailT;\n"
            + "    if (headPos < tailLen) {\n"
            + "        float tailLenNow = max(headPos, 0.0001);\n"
            + "        float dNoWrap = headPos - s;\n"
            + "        inTail = step(0.0, dNoWrap) * (1.0 - step(tailLenNow, dNoWrap));\n"
            + "        tailT = clamp(1.0 - dNoWrap / tailLenNow, 0.0, 1.0);\n"
            + "    } else {\n"
            + "        // Normal running: tail length measured on full perimeter (delta: 0..perimeter).\n"
            + "        inTail = 1.0 - step(tailLen, delta);\n"
            + "        tailT = clamp(1.0 - delta / tailLen, 0.0, 1.0);\n"
            + "    }\n"
            + "    float tailLongitudinal = pow(tailT, 1.35) * inTail;\n"
            + "\n"
            + "    float headW = max(uHeadWidthPx, 0.5);\n"
            + "    float tailW = max(uTailWidthPx, 0.5);\n"
            + "    float localWidthTail = mix(tailW, headW, tailT) * inTail;\n"
            + "\n"
            + "    float capLen = max(headW * (2.0 * max(uHeadCapScale, 0.001)), 0.5);\n"
            + "    float inCap = step(0.0, -signedDelta) * (1.0 - step(capLen, -signedDelta));\n"
            + "    float capT = clamp((-signedDelta) / capLen, 0.0, 1.0);\n"
            + "    float capShrink = 1.0 - capT;\n"
            + "\n"
            + "    float soft = max(uInnerSoftnessPx, 0.0001);\n"
            + "    float blurR = max(uBlurRadiusPx, 0.0);\n"
            + "\n"
            + "    float coreTail = 1.0 - smoothstep(localWidthTail, localWidthTail + soft, dEdge);\n"
            + "    float hazeTail = 1.0 - smoothstep(localWidthTail + soft, localWidthTail + soft + blurR, dEdge);\n"
            + "    float tailMask = clamp(coreTail + (hazeTail - coreTail) * 0.55, 0.0, 1.0) * tailLongitudinal;\n"
            + "\n"
            + "    float capCoreWidth = max(0.0, headW * capShrink);\n"
            + "    float capHazeWidthRaw = max(0.0, (headW + soft + blurR) * capShrink);\n"
            + "    float capHazeWidth = max(capCoreWidth + soft + 0.0001, capHazeWidthRaw);\n"
            + "    float capFade = pow(capShrink, 1.15);\n"
            + "    float coreCap = (1.0 - smoothstep(capCoreWidth, capCoreWidth + soft, dEdge)) * inCap * capFade;\n"
            + "    float hazeCap = (1.0 - smoothstep(capCoreWidth + soft, capHazeWidth, dEdge)) * inCap * capFade;\n"
            + "    float capMask = clamp(coreCap + (hazeCap - coreCap) * 0.55, 0.0, 1.0);\n"
            + "\n"
            + "    float meteorAlpha = max(tailMask, capMask) * clamp(uOpacity, 0.0, 1.0);\n"
            + "\n"
            + "    vec3 meteor = meteorColor * clamp(uBrightness, 0.0, 8.0) * meteorAlpha;\n"
            + "    vec3 outRgb = clamp(base.rgb + meteor, 0.0, 1.0);\n"
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
    private long lastPresentationUs = -1L;
    private long lastRealtimeMs = -1L;
    private float accumulatedTimeSec = 0f;
    private float lastHeadPosPx = -1f;
    private OnCornerColorChangeListener onCornerColorChangeListener;

    public MeteorFilter() {
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
        float resolvedTailLengthPx = tailLengthRatio >= 0f
                ? Math.max(1.0f, perimeterPx * clamp(tailLengthRatio, 0.0f, 1.0f))
                : Math.max(1.0f, tailLengthPx);
        float headPosPx = perimeterPx > 0f
                ? (timeSec * Math.max(0.0f, speedRps) * perimeterPx) % perimeterPx
                : 0f;
        dispatchCornerEvents(headPosPx, perimeterPx, mWidth, mHeight);
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

    public MeteorFilter setColor(float r, float g, float b) {
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

    public MeteorFilter setColor(int color) {
        float rr = Color.red(color) / 255f;
        float gg = Color.green(color) / 255f;
        float bb = Color.blue(color) / 255f;
        setColor(rr, gg, bb);
        return this;
    }

    /**
     * 4-edge color cycle in clockwise order:
     * top -> right -> bottom -> left.
     */
    public MeteorFilter setCornerColors(
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

    public MeteorFilter setOnCornerColorChangeListener(OnCornerColorChangeListener listener) {
        this.onCornerColorChangeListener = listener;
        return this;
    }

    public MeteorFilter setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0.0f, 1.0f);
        return this;
    }

    public MeteorFilter setBrightness(float brightness) {
        this.brightness = clamp(brightness, 0.0f, 8.0f);
        return this;
    }

    public MeteorFilter setHeadWidthPx(float headWidthPx) {
        this.headWidthPx = Math.max(0.5f, headWidthPx);
        return this;
    }

    public MeteorFilter setTailWidthPx(float tailWidthPx) {
        this.tailWidthPx = Math.max(0.5f, tailWidthPx);
        return this;
    }

    public MeteorFilter setTailLengthPx(float tailLengthPx) {
        this.tailLengthPx = Math.max(1.0f, tailLengthPx);
        this.tailLengthRatio = -1f;
        return this;
    }

    /**
     * Tail length as ratio of frame perimeter [0,1].
     * Example: 0.25 means quarter of the whole border loop length.
     * Setting this enables ratio mode and overrides pixel length.
     */
    public MeteorFilter setTailLengthRatio(float tailLengthRatio) {
        this.tailLengthRatio = clamp(tailLengthRatio, 0.0f, 1.0f);
        return this;
    }

    /**
     * Tail fog transition width toward the inner image side.
     * Near frame edge remains sharp by design.
     */
    public MeteorFilter setInnerSoftnessPx(float innerSoftnessPx) {
        this.innerSoftnessPx = Math.max(0.0f, innerSoftnessPx);
        return this;
    }

    /**
     * Extra fog/blur radius toward inner image side (pixels).
     * Keeps frame-edge side sharp.
     */
    public MeteorFilter setBlurRadiusPx(float blurRadiusPx) {
        this.blurRadiusPx = Math.max(0.0f, blurRadiusPx);
        return this;
    }

    /**
     * Head cap length scale relative to head width.
     * 0.5 = semicircle; >0.5 = stretched semi-ellipse.
     */
    public MeteorFilter setHeadCapScale(float headCapScale) {
        this.headCapScale = Math.max(0.001f, headCapScale);
        return this;
    }

    /**
     * Backward-compatible API.
     * Converts absolute cap length(px) to scale where 0.5 is semicircle.
     */
    public MeteorFilter setHeadCapPx(float headCapPx) {
        float safeHeadW = Math.max(0.5f, headWidthPx);
        this.headCapScale = Math.max(0.001f, headCapPx / (2.0f * safeHeadW));
        return this;
    }

    /**
     * Loops per second around the full frame perimeter.
     */
    public MeteorFilter setSpeedRps(float speedRps) {
        this.speedRps = Math.max(0.0f, speedRps);
        return this;
    }

    /**
     * Color blending start position on each edge [0..1].
     * Lower value means earlier/longer transition.
     */
    public MeteorFilter setColorBlendStart(float blendStart) {
        this.blendStart = clamp(blendStart, 0.0f, 0.98f);
        return this;
    }

    /**
     * Color blend curve gamma.
     * <1 makes transition more obvious; >1 makes it softer.
     */
    public MeteorFilter setColorBlendGamma(float blendGamma) {
        this.blendGamma = Math.max(0.25f, blendGamma);
        return this;
    }

    /**
     * Blend length ratio on the next edge after crossing corner [0..0.8].
     */
    public MeteorFilter setCornerBlendLen(float cornerBlendLen) {
        this.cornerBlendLen = clamp(cornerBlendLen, 0.0f, 0.8f);
        return this;
    }

    /**
     * Intermediate color ratio between current and next [0.1..0.9].
     * 0.5 means exact middle color.
     */
    public MeteorFilter setColorMidRatio(float midRatio) {
        this.midRatio = clamp(midRatio, 0.1f, 0.9f);
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
