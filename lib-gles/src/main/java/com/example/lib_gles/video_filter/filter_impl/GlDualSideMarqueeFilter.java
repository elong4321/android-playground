package com.example.lib_gles.video_filter.filter_impl;

import android.graphics.Color;
import android.opengl.GLES20;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Dual-side marquee effect:
 * - Left side: multiple single-color bars move from bottom to top.
 * - Right side: multiple single-color bars move from top to bottom.
 * - Supports configurable strip width, bar length/gap, and soft/blur transitions.
 */
public class GlDualSideMarqueeFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision highp float;\n"
            + "varying vec2 textureCoordinate;\n"
            + "uniform sampler2D sTexture;\n"
            + "uniform float uTime;\n"
            + "uniform float uStripWidth;\n"
            + "uniform float uEdgeSoftness;\n"
            + "uniform float uBlurRadius;\n"
            + "uniform float uBandSoftness;\n"
            + "uniform float uBarLength;\n"
            + "uniform float uBarGap;\n"
            + "uniform float uSpeed;\n"
            + "uniform float uOpacity;\n"
            + "uniform float uGlowWidth;\n"
            + "uniform float uGlowIntensity;\n"
            + "uniform float uColorIntensity;\n"
            + "uniform float uHardColorSwitch;\n"
            + "uniform bool uPingPongMode;\n"
            + "uniform vec3 uColor0;\n"
            + "uniform vec3 uColor1;\n"
            + "uniform vec3 uColor2;\n"
            + "uniform vec3 uColor3;\n"
            + "\n"
            + "vec3 pickColor(int idx) {\n"
            + "    if (idx == 0) return uColor0;\n"
            + "    if (idx == 1) return uColor1;\n"
            + "    if (idx == 2) return uColor2;\n"
            + "    return uColor3;\n"
            + "}\n"
            + "\n"
            + "vec3 pickColorDir(int idx, float dirForward) {\n"
            + "    // Keep color order invariant across ping-pong direction changes.\n"
            + "    return pickColor(idx);\n"
            + "}\n"
            + "\n"
            + "float softBand(float y, float start, float len, float soft) {\n"
            + "    return smoothstep(start - soft, start, y)\n"
            + "         * (1.0 - smoothstep(start + len, start + len + soft, y));\n"
            + "}\n"
            + "\n"
            + "float halfEllipseBand(float xFromEdge, float y, float start, float len, float stripW, float soft, float blur) {\n"
            + "    // Ellipse center is on screen edge, so only half ellipse is visible in-frame.\n"
            + "    // Make it slimmer: reduce horizontal radius while keeping vertical length.\n"
            + "    float rx = max(stripW * 0.58, 0.010);\n"
            + "    float ry = max(len * 0.5, 0.030);\n"
            + "    float cx = 0.0;\n"
            + "    float cy = start + ry;\n"
            + "    float nx = (xFromEdge - cx) / rx;\n"
            + "    float ny = (y - cy) / ry;\n"
            + "    float n = nx * nx + ny * ny;\n"
            + "    // Gradient from center (0) to edge (1): fade out towards ellipse edge\n"
            + "    float gradient = 1.0 - smoothstep(0.5, 1.0, n);\n"
            + "    // Blur: create a larger extended ellipse for glow effect\n"
            + "    float blurExtend = max(blur * 12.0, 0.010); // Amplify blur for visible effect\n"
            + "    float rxBlur = rx + blurExtend;\n"
            + "    float ryBlur = ry + blurExtend;\n"
            + "    float nxB = (xFromEdge - cx) / rxBlur;\n"
            + "    float nyB = (y - cy) / ryBlur;\n"
            + "    float nBlur = nxB * nxB + nyB * nyB;\n"
            + "    // Smooth blur fade with wider transition range\n"
            + "    float blurFade = 1.0 - smoothstep(0.3, 1.5, nBlur);\n"
            + "    gradient = max(gradient, blurFade * 0.5);\n"
            + "    // Soft edge fade for anti-aliasing\n"
            + "    if (soft > 0.001) {\n"
            + "        float softN = clamp(soft / max(min(rx, ry), 1e-4), 0.01, 1.2);\n"
            + "        float edgeFade = 1.0 - smoothstep(1.0 - softN, 1.0, n);\n"
            + "        gradient *= edgeFade;\n"
            + "    } else {\n"
            + "        gradient *= step(0.0, n - 1.0);\n"
            + "    }\n"
            + "    return gradient;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec4 base = texture2D(sTexture, uv);\n"
            + "\n"
            + "    float barLen = clamp(uBarLength, 0.01, 2.0);\n"
            + "    float gapLen = clamp(uBarGap, 0.0, 2.0);\n"
            + "    float slot = barLen + gapLen;\n"
            + "    float trainLen = slot * 4.0;\n"
            + "    float startPad = max(uBandSoftness, 0.001) * 1.4;\n"
            + "    float moveRange = 1.0 + trainLen + startPad * 2.0;\n"
            + "    \n"
            + "    // Ping-pong with triangle wave to avoid branch-switch jump at turning points.\n"
            + "    float phase;\n"
            + "    float dirForward = 1.0;\n"
            + "    if (uPingPongMode) {\n"
            + "        float t = fract(uTime * uSpeed);\n"
            + "        dirForward = 1.0 - step(0.5, t);\n"
            + "        phase = 1.0 - abs(t * 2.0 - 1.0); // 0->1->0\n"
            + "    } else {\n"
            + "        phase = fract(uTime * uSpeed);\n"
            + "    }\n"
            + "    float yStartLeft = -trainLen - startPad + moveRange * phase;\n"
            + "    float yStartRight = 1.0 + startPad - moveRange * phase;\n"
            + "    // Keep color order stable across direction changes:\n"
            + "    // color0 always sticks to the motion-leading edge.\n"
            + "    float yLocalLeft;\n"
            + "    float yLocalRight;\n"
            + "    if (dirForward > 0.5) {\n"
            + "        // Left side moves up, right side moves down.\n"
            + "        yLocalLeft = (yStartLeft + trainLen) - uv.y;\n"
            + "        yLocalRight = uv.y - yStartRight;\n"
            + "    } else {\n"
            + "        // Left side moves down, right side moves up.\n"
            + "        yLocalLeft = uv.y - yStartLeft;\n"
            + "        yLocalRight = (yStartRight + trainLen) - uv.y;\n"
            + "    }\n"
            + "    // Whole-train visibility gate to avoid first-frame flash when train is still outside.\n"
            + "    float leftTrainMask = smoothstep(yStartLeft - uBandSoftness, yStartLeft, uv.y)\n"
            + "                        * (1.0 - smoothstep(yStartLeft + trainLen, yStartLeft + trainLen + uBandSoftness, uv.y));\n"
            + "    float rightTrainMask = smoothstep(yStartRight - uBandSoftness, yStartRight, uv.y)\n"
            + "                         * (1.0 - smoothstep(yStartRight + trainLen, yStartRight + trainLen + uBandSoftness, uv.y));\n"
            + "    \n"
            + "    // Bar positions (color0 always leads)\n"
            + "    float b0s = 0.0;\n"
            + "    float b1s = slot;\n"
            + "    float b2s = slot * 2.0;\n"
            + "    float b3s = slot * 3.0;\n"
            + "\n"
            + "    float xLeft = uv.x;\n"
            + "    // Keep edge-side intact (no fade at x=0), only blur/fade toward inner side.\n"
            + "    float leftCoreX = 1.0 - smoothstep(uStripWidth - uEdgeSoftness, uStripWidth + uBlurRadius * 0.55, xLeft);\n"
            + "    float leftOuterX = 1.0 - smoothstep(uStripWidth + uBlurRadius * 0.25, uStripWidth + uBlurRadius + uGlowWidth, xLeft);\n"
            + "    float leftGlowX = max(leftOuterX - leftCoreX, 0.0);\n"
            + "    float xLocalLeft = xLeft;\n"
            + "    float leftAlphaSeg = 0.0;\n"
            + "    vec3 leftColor = vec3(0.0);\n"
            + "    float a0 = halfEllipseBand(xLocalLeft, yLocalLeft, b0s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float a1 = halfEllipseBand(xLocalLeft, yLocalLeft, b1s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float a2 = halfEllipseBand(xLocalLeft, yLocalLeft, b2s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float a3 = halfEllipseBand(xLocalLeft, yLocalLeft, b3s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float lr0 = softBand(yLocalLeft, b0s, barLen, uBandSoftness);\n"
            + "    float lr1 = softBand(yLocalLeft, b1s, barLen, uBandSoftness);\n"
            + "    float lr2 = softBand(yLocalLeft, b2s, barLen, uBandSoftness);\n"
            + "    float lr3 = softBand(yLocalLeft, b3s, barLen, uBandSoftness);\n"
            + "    float leftRectSeg = max(max(lr0, lr1), max(lr2, lr3));\n"
            + "    float leftEllipseSeg = max(max(a0, a1), max(a2, a3));\n"
            + "    // Keep half-ellipse as primary shape; rectangle only acts as weak visibility fallback.\n"
            + "    leftAlphaSeg = max(leftEllipseSeg, leftRectSeg * 0.20);\n"
            + "    float lw0 = max(a0, lr0 * 0.20);\n"
            + "    float lw1 = max(a1, lr1 * 0.20);\n"
            + "    float lw2 = max(a2, lr2 * 0.20);\n"
            + "    float lw3 = max(a3, lr3 * 0.20);\n"
            + "    if (uHardColorSwitch > 0.5) {\n"
            + "        if (lw0 >= lw1 && lw0 >= lw2 && lw0 >= lw3) leftColor = pickColorDir(0, dirForward);\n"
            + "        else if (lw1 >= lw0 && lw1 >= lw2 && lw1 >= lw3) leftColor = pickColorDir(1, dirForward);\n"
            + "        else if (lw2 >= lw0 && lw2 >= lw1 && lw2 >= lw3) leftColor = pickColorDir(2, dirForward);\n"
            + "        else leftColor = pickColorDir(3, dirForward);\n"
            + "    } else {\n"
            + "        float lws = max(lw0 + lw1 + lw2 + lw3, 1e-5);\n"
            + "        leftColor = (pickColorDir(0, dirForward) * lw0 + pickColorDir(1, dirForward) * lw1 + pickColorDir(2, dirForward) * lw2 + pickColorDir(3, dirForward) * lw3) / lws;\n"
            + "    }\n"
            + "    float leftCoreMask = leftCoreX * leftAlphaSeg * leftTrainMask;\n"
            + "    float leftGlowMask = leftGlowX * leftAlphaSeg * leftTrainMask;\n"
            + "\n"
            + "    float xRight = 1.0 - uv.x;\n"
            + "    // Keep edge-side intact (no fade at x=1), only blur/fade toward inner side.\n"
            + "    float rightCoreX = 1.0 - smoothstep(uStripWidth - uEdgeSoftness, uStripWidth + uBlurRadius * 0.55, xRight);\n"
            + "    float rightOuterX = 1.0 - smoothstep(uStripWidth + uBlurRadius * 0.25, uStripWidth + uBlurRadius + uGlowWidth, xRight);\n"
            + "    float rightGlowX = max(rightOuterX - rightCoreX, 0.0);\n"
            + "    float xLocalRight = xRight;\n"
            + "    float rightAlphaSeg = 0.0;\n"
            + "    vec3 rightColor = vec3(0.0);\n"
            + "    float ra0 = halfEllipseBand(xLocalRight, yLocalRight, b0s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float ra1 = halfEllipseBand(xLocalRight, yLocalRight, b1s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float ra2 = halfEllipseBand(xLocalRight, yLocalRight, b2s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float ra3 = halfEllipseBand(xLocalRight, yLocalRight, b3s, barLen, uStripWidth, uBandSoftness, uBlurRadius);\n"
            + "    float rr0 = softBand(yLocalRight, b0s, barLen, uBandSoftness);\n"
            + "    float rr1 = softBand(yLocalRight, b1s, barLen, uBandSoftness);\n"
            + "    float rr2 = softBand(yLocalRight, b2s, barLen, uBandSoftness);\n"
            + "    float rr3 = softBand(yLocalRight, b3s, barLen, uBandSoftness);\n"
            + "    float rightRectSeg = max(max(rr0, rr1), max(rr2, rr3));\n"
            + "    float rightEllipseSeg = max(max(ra0, ra1), max(ra2, ra3));\n"
            + "    // Keep half-ellipse as primary shape; rectangle only acts as weak visibility fallback.\n"
            + "    rightAlphaSeg = max(rightEllipseSeg, rightRectSeg * 0.20);\n"
            + "    float rw0 = max(ra0, rr0 * 0.20);\n"
            + "    float rw1 = max(ra1, rr1 * 0.20);\n"
            + "    float rw2 = max(ra2, rr2 * 0.20);\n"
            + "    float rw3 = max(ra3, rr3 * 0.20);\n"
            + "    if (uHardColorSwitch > 0.5) {\n"
            + "        if (rw0 >= rw1 && rw0 >= rw2 && rw0 >= rw3) rightColor = pickColorDir(0, dirForward);\n"
            + "        else if (rw1 >= rw0 && rw1 >= rw2 && rw1 >= rw3) rightColor = pickColorDir(1, dirForward);\n"
            + "        else if (rw2 >= rw0 && rw2 >= rw1 && rw2 >= rw3) rightColor = pickColorDir(2, dirForward);\n"
            + "        else rightColor = pickColorDir(3, dirForward);\n"
            + "    } else {\n"
            + "        float rws = max(rw0 + rw1 + rw2 + rw3, 1e-5);\n"
            + "        rightColor = (pickColorDir(0, dirForward) * rw0 + pickColorDir(1, dirForward) * rw1 + pickColorDir(2, dirForward) * rw2 + pickColorDir(3, dirForward) * rw3) / rws;\n"
            + "    }\n"
            + "    float rightCoreMask = rightCoreX * rightAlphaSeg * rightTrainMask;\n"
            + "    float rightGlowMask = rightGlowX * rightAlphaSeg * rightTrainMask;\n"
            + "\n"
            + "    vec3 color = base.rgb;\n"
            + "    float leftCoreAlpha = clamp(leftCoreMask * clamp(uOpacity, 0.0, 1.0), 0.0, 1.0);\n"
            + "    float leftGlowAlpha = clamp(leftGlowMask * clamp(uGlowIntensity, 0.0, 2.0) * clamp(uOpacity, 0.0, 1.0), 0.0, 1.0);\n"
            + "    vec3 leftTint = clamp(leftColor * uColorIntensity, 0.0, 1.0);\n"
            + "    // Core: direct tint keeps color separation strong.\n"
            + "    color = mix(color, leftTint, leftCoreAlpha * 0.96);\n"
            + "    // Glow: additive tint so halo hue follows the same core color.\n"
            + "    color += leftTint * (leftGlowAlpha * 0.42);\n"
            + "\n"
            + "    float rightCoreAlpha = clamp(rightCoreMask * clamp(uOpacity, 0.0, 1.0), 0.0, 1.0);\n"
            + "    float rightGlowAlpha = clamp(rightGlowMask * clamp(uGlowIntensity, 0.0, 2.0) * clamp(uOpacity, 0.0, 1.0), 0.0, 1.0);\n"
            + "    vec3 rightTint = clamp(rightColor * uColorIntensity, 0.0, 1.0);\n"
            + "    color = mix(color, rightTint, rightCoreAlpha * 0.96);\n"
            + "    color += rightTint * (rightGlowAlpha * 0.42);\n"
            + "    color = clamp(color, 0.0, 1.0);\n"
            + "    gl_FragColor = vec4(color, base.a);\n"
            + "}\n";

    private int timeHandle = -1;
    private int stripWidthHandle = -1;
    private int edgeSoftnessHandle = -1;
    private int blurRadiusHandle = -1;
    private int bandSoftnessHandle = -1;
    private int barLengthHandle = -1;
    private int barGapHandle = -1;
    private int speedHandle = -1;
    private int opacityHandle = -1;
    private int glowWidthHandle = -1;
    private int glowIntensityHandle = -1;
    private int colorIntensityHandle = -1;
    private int hardColorSwitchHandle = -1;
    private int pingPongModeHandle = -1;
    private int color0Handle = -1;
    private int color1Handle = -1;
    private int color2Handle = -1;
    private int color3Handle = -1;

    private float stripWidthPx;
    private float edgeSoftnessPx;
    private float blurRadiusPx;
    private float barLength = 0.22f;
    private float barGap = 0.0f;
    // If explicit bar sizing is enabled, legacy setTrainLength() won't override barLength/barGap.
    private boolean explicitBarSizing = false;
    private float bandSoftness = 0.06f;
    private float speed = 0.50f;
    private float opacity = 0.95f;
    private float glowWidthPx = 22f;
    private float glowIntensity = 1.10f;
    private float colorIntensity = 1.35f;
    private boolean hardColorSwitch = true;
    private boolean pingPongMode = true;
    private long lastPresentationTimeRaw = Long.MIN_VALUE;
    private boolean presentationTimeUnitLocked = false;
    private boolean presentationTimeInNs = false;
    private float accumulatedTimelineSec = 0f;

    private float color0R = 1.00f;
    private float color0G = 0.20f;
    private float color0B = 0.30f;
    private float color1R = 1.00f;
    private float color1G = 0.78f;
    private float color1B = 0.22f;
    private float color2R = 0.20f;
    private float color2G = 0.85f;
    private float color2B = 1.00f;
    private float color3R = 0.72f;
    private float color3G = 0.28f;
    private float color3B = 1.00f;

    public GlDualSideMarqueeFilter(float stripWidthPx) {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
        this.stripWidthPx = stripWidthPx;
        this.edgeSoftnessPx = Math.max(6f, stripWidthPx * 0.55f);
        this.blurRadiusPx = Math.max(8f, stripWidthPx * 0.90f);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        timeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTime");
        stripWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStripWidth");
        edgeSoftnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uEdgeSoftness");
        blurRadiusHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurRadius");
        bandSoftnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBandSoftness");
        barLengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBarLength");
        barGapHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBarGap");
        speedHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSpeed");
        opacityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOpacity");
        glowWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowWidth");
        glowIntensityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowIntensity");
        colorIntensityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uColorIntensity");
        hardColorSwitchHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHardColorSwitch");
        pingPongModeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPingPongMode");
        color0Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor0");
        color1Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor1");
        color2Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor2");
        color3Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor3");
    }

    @Override
    public void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        // Use frame-to-frame delta accumulation to avoid unit ambiguity (us/ns) causing flicker.
        float timeSec;
        if (presentationTimeUs > 0L) {
            if (lastPresentationTimeRaw == Long.MIN_VALUE) {
                lastPresentationTimeRaw = presentationTimeUs;
                accumulatedTimelineSec = 0f;
            } else {
                long deltaRaw = presentationTimeUs - lastPresentationTimeRaw;
                lastPresentationTimeRaw = presentationTimeUs;
                if (deltaRaw < 0L || deltaRaw > 10_000_000_000L) {
                    // Timeline reset/seek. Restart local clock to avoid visible jump.
                    accumulatedTimelineSec = 0f;
                    presentationTimeUnitLocked = false;
                } else if (deltaRaw > 0L) {
                    if (!presentationTimeUnitLocked) {
                        // Typical frame delta: us ~= 16k..50k; ns ~= 16M..50M.
                        presentationTimeInNs = deltaRaw > 2_000_000L;
                        presentationTimeUnitLocked = true;
                    }
                    float divisor = presentationTimeInNs ? 1_000_000_000f : 1_000_000f;
                    float deltaSec = deltaRaw / divisor;
                    // Clamp extreme gaps to keep motion visually stable.
                    deltaSec = clamp(deltaSec, 0f, 0.25f);
                    accumulatedTimelineSec += deltaSec;
                }
            }
            timeSec = accumulatedTimelineSec;
        } else {
            // Preview fallback when timestamp is unavailable.
            timeSec = (SystemClock.uptimeMillis() % 600000L) / 1000f;
            lastPresentationTimeRaw = Long.MIN_VALUE;
            presentationTimeUnitLocked = false;
            presentationTimeInNs = false;
            accumulatedTimelineSec = 0f;
        }
        float widthNorm = clamp(stripWidthPx / mWidth, 0.001f, 0.35f);
        float edgeSoftNorm = clamp(edgeSoftnessPx / mWidth, 0.0005f, widthNorm * 0.95f);
        float blurNorm = clamp(blurRadiusPx / mWidth, 0.0005f, 0.40f);
        float glowWidthNorm = clamp(glowWidthPx / mWidth, 0.0005f, 0.40f);

        GLES20.glUniform1f(timeHandle, timeSec);
        GLES20.glUniform1f(stripWidthHandle, widthNorm);
        GLES20.glUniform1f(edgeSoftnessHandle, edgeSoftNorm);
        GLES20.glUniform1f(blurRadiusHandle, blurNorm);
        GLES20.glUniform1f(barLengthHandle, clamp(barLength, 0.01f, 2.0f));
        GLES20.glUniform1f(barGapHandle, clamp(barGap, 0.0f, 2.0f));
        GLES20.glUniform1f(bandSoftnessHandle, clamp(bandSoftness, 0.01f, 0.45f));
        GLES20.glUniform1f(speedHandle, Math.max(0.001f, speed));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
        GLES20.glUniform1f(glowWidthHandle, glowWidthNorm);
        GLES20.glUniform1f(glowIntensityHandle, clamp(glowIntensity, 0.0f, 2.0f));
        GLES20.glUniform1f(colorIntensityHandle, clamp(colorIntensity, 0.5f, 3.0f));
        GLES20.glUniform1f(hardColorSwitchHandle, hardColorSwitch ? 1.0f : 0.0f);
        GLES20.glUniform1i(pingPongModeHandle, pingPongMode ? 1 : 0);
        GLES20.glUniform3f(color0Handle, color0R, color0G, color0B);
        GLES20.glUniform3f(color1Handle, color1R, color1G, color1B);
        GLES20.glUniform3f(color2Handle, color2R, color2G, color2B);
        GLES20.glUniform3f(color3Handle, color3R, color3G, color3B);
    }

    public GlDualSideMarqueeFilter setStripWidthPx(float stripWidthPx) {
        this.stripWidthPx = stripWidthPx;
        return this;
    }

    public GlDualSideMarqueeFilter setEdgeSoftnessPx(float edgeSoftnessPx) {
        this.edgeSoftnessPx = edgeSoftnessPx;
        return this;
    }

    public GlDualSideMarqueeFilter setBlurRadiusPx(float blurRadiusPx) {
        this.blurRadiusPx = blurRadiusPx;
        return this;
    }

    public GlDualSideMarqueeFilter setTrainLength(float trainLength) {
        if (explicitBarSizing) {
            return this;
        }
        // Keep compatibility: trainLength now maps to 4 color bars + 4 gaps (with trailing gap).
        // Derive barLength by subtracting current gaps.
        float total = Math.max(0.08f, trainLength);
        float gapTotal = barGap * 4.0f;
        this.barLength = Math.max(0.01f, (total - gapTotal) / 4.0f);
        return this;
    }

    public GlDualSideMarqueeFilter setBandSoftness(float bandSoftness) {
        this.bandSoftness = bandSoftness;
        return this;
    }

    public GlDualSideMarqueeFilter setColorBlendSpan(float colorBlendSpan) {
        // Keep compatibility: map old "blend span" concept to larger bar gap.
        explicitBarSizing = true;
        this.barGap = Math.max(0f, colorBlendSpan);
        return this;
    }

    public GlDualSideMarqueeFilter setBarLength(float barLength) {
        explicitBarSizing = true;
        this.barLength = Math.max(0.01f, barLength);
        return this;
    }

    public GlDualSideMarqueeFilter setBarGap(float barGap) {
        explicitBarSizing = true;
        this.barGap = Math.max(0f, barGap);
        return this;
    }

    public GlDualSideMarqueeFilter setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    public GlDualSideMarqueeFilter setOpacity(float opacity) {
        this.opacity = opacity;
        return this;
    }

    public GlDualSideMarqueeFilter setGlowWidthPx(float glowWidthPx) {
        this.glowWidthPx = Math.max(0f, glowWidthPx);
        return this;
    }

    public GlDualSideMarqueeFilter setGlowIntensity(float glowIntensity) {
        this.glowIntensity = Math.max(0f, glowIntensity);
        return this;
    }

    public GlDualSideMarqueeFilter setColorIntensity(float colorIntensity) {
        this.colorIntensity = colorIntensity;
        return this;
    }

    public GlDualSideMarqueeFilter setHardColorSwitch(boolean hardColorSwitch) {
        this.hardColorSwitch = hardColorSwitch;
        return this;
    }

    public GlDualSideMarqueeFilter setPingPongMode(boolean pingPongMode) {
        this.pingPongMode = pingPongMode;
        return this;
    }

    public GlDualSideMarqueeFilter setColors(int color0, int color1, int color2, int color3) {
        setColors(Color.red(color0) / 255f, Color.green(color0) / 255f, Color.blue(color0) / 255f,
                Color.red(color1) / 255f, Color.green(color1) / 255f, Color.blue(color1) / 255f,
                Color.red(color2) / 255f, Color.green(color2) / 255f, Color.blue(color2) / 255f,
                Color.red(color3) / 255f, Color.green(color3) / 255f, Color.blue(color3) / 255f);
        return this;
    }

    public GlDualSideMarqueeFilter setColors(
            float c0r, float c0g, float c0b,
            float c1r, float c1g, float c1b,
            float c2r, float c2g, float c2b,
            float c3r, float c3g, float c3b) {
        this.color0R = c0r;
        this.color0G = c0g;
        this.color0B = c0b;
        this.color1R = c1r;
        this.color1G = c1g;
        this.color1B = c1b;
        this.color2R = c2r;
        this.color2G = c2g;
        this.color2B = c2b;
        this.color3R = c3r;
        this.color3G = c3g;
        this.color3B = c3b;
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
