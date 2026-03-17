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
            + "precision mediump float;\n"
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
            + "float softBand(float y, float start, float len, float soft) {\n"
            + "    return smoothstep(start - soft, start, y)\n"
            + "         * (1.0 - smoothstep(start + len, start + len + soft, y));\n"
            + "}\n"
            + "\n"
            + "float halfEllipseBand(float xFromEdge, float y, float start, float len, float stripW, float soft) {\n"
            + "    // Ellipse center is on screen edge, so only half ellipse is visible in-frame.\n"
            + "    // Make it slimmer: reduce horizontal radius while keeping vertical length.\n"
            + "    float rx = max(stripW * 0.58, 1e-4);\n"
            + "    float ry = max(len * 0.5, 1e-4);\n"
            + "    float cx = 0.0;\n"
            + "    float cy = start + ry;\n"
            + "    float nx = (xFromEdge - cx) / rx;\n"
            + "    float ny = (y - cy) / ry;\n"
            + "    float n = nx * nx + ny * ny;\n"
            + "    float edge = n - 1.0;\n"
            + "    float softN = clamp(soft / max(min(rx, ry), 1e-4), 0.001, 1.2);\n"
            + "    return 1.0 - smoothstep(0.0, softN, edge);\n"
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
            + "    float moveRange = 1.0 + trainLen;\n"
            + "    \n"
            + "    // Ping-pong: complete full forward pass, then full backward pass\n"
            + "    float phase, yStartLeft, yStartRight;\n"
            + "    if (uPingPongMode) {\n"
            + "        float cycle = uTime * uSpeed;\n"
            + "        float period = 2.0;\n"
            + "        float t = fract(cycle / period);\n"
            + "        float passPhase = t * 2.0; // 0->2 over one cycle\n"
            + "        if (passPhase < 1.0) {\n"
            + "            // Forward: left bottom->top, right top->bottom\n"
            + "            yStartLeft = -trainLen + moveRange * passPhase;\n"
            + "            yStartRight = 1.0 - moveRange * passPhase;\n"
            + "        } else {\n"
            + "            // Backward: left top->bottom, right bottom->top\n"
            + "            float backT = passPhase - 1.0; // 0->1 in backward pass\n"
            + "            yStartLeft = 1.0 - moveRange * backT;\n"
            + "            yStartRight = -trainLen + moveRange * backT;\n"
            + "        }\n"
            + "    } else {\n"
            + "        // Legacy mode: always forward\n"
            + "        phase = fract(uTime * uSpeed);\n"
            + "        yStartLeft = -trainLen + moveRange * phase;\n"
            + "        yStartRight = 1.0 - moveRange * phase;\n"
            + "    }\n"
            + "    \n"
            + "    // Bar positions (color0 always leads)\n"
            + "    float b0s = 0.0;\n"
            + "    float b1s = slot;\n"
            + "    float b2s = slot * 2.0;\n"
            + "    float b3s = slot * 3.0;\n"
            + "\n"
            + "    float xLeft = uv.x;\n"
            + "    // Keep edge-side intact (no fade at x=0), only blur/fade toward inner side.\n"
            + "    float leftXMask = 1.0 - smoothstep(uStripWidth - uEdgeSoftness, uStripWidth + uBlurRadius, xLeft);\n"
            + "    float leftYMask = smoothstep(yStartLeft - uBandSoftness, yStartLeft, uv.y)\n"
            + "                    * (1.0 - smoothstep(yStartLeft + trainLen, yStartLeft + trainLen + uBandSoftness, uv.y));\n"
            + "    float yLocalLeft = uv.y - yStartLeft;\n"
            + "    float xLocalLeft = xLeft;\n"
            + "    float leftAlphaSeg = 0.0;\n"
            + "    vec3 leftColor = vec3(0.0);\n"
            + "    float a0 = halfEllipseBand(xLocalLeft, yLocalLeft, b0s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float a1 = halfEllipseBand(xLocalLeft, yLocalLeft, b1s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float a2 = halfEllipseBand(xLocalLeft, yLocalLeft, b2s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float a3 = halfEllipseBand(xLocalLeft, yLocalLeft, b3s, barLen, uStripWidth, uBandSoftness);\n"
            + "    leftAlphaSeg = max(max(a0, a1), max(a2, a3));\n"
            + "    if (a0 >= a1 && a0 >= a2 && a0 >= a3) leftColor = pickColor(0);\n"
            + "    else if (a1 >= a0 && a1 >= a2 && a1 >= a3) leftColor = pickColor(1);\n"
            + "    else if (a2 >= a0 && a2 >= a1 && a2 >= a3) leftColor = pickColor(2);\n"
            + "    else leftColor = pickColor(3);\n"
            + "    float leftAlpha = leftXMask * leftYMask * leftAlphaSeg * uOpacity;\n"
            + "\n"
            + "    float xRight = 1.0 - uv.x;\n"
            + "    // Keep edge-side intact (no fade at x=1), only blur/fade toward inner side.\n"
            + "    float rightXMask = 1.0 - smoothstep(uStripWidth - uEdgeSoftness, uStripWidth + uBlurRadius, xRight);\n"
            + "    float rightYMask = smoothstep(yStartRight - uBandSoftness, yStartRight, uv.y)\n"
            + "                     * (1.0 - smoothstep(yStartRight + trainLen, yStartRight + trainLen + uBandSoftness, uv.y));\n"
            + "    float yLocalRight = (yStartRight + trainLen) - uv.y;\n"
            + "    float xLocalRight = xRight;\n"
            + "    float rightAlphaSeg = 0.0;\n"
            + "    vec3 rightColor = vec3(0.0);\n"
            + "    float ra0 = halfEllipseBand(xLocalRight, yLocalRight, b0s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float ra1 = halfEllipseBand(xLocalRight, yLocalRight, b1s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float ra2 = halfEllipseBand(xLocalRight, yLocalRight, b2s, barLen, uStripWidth, uBandSoftness);\n"
            + "    float ra3 = halfEllipseBand(xLocalRight, yLocalRight, b3s, barLen, uStripWidth, uBandSoftness);\n"
            + "    rightAlphaSeg = max(max(ra0, ra1), max(ra2, ra3));\n"
            + "    if (ra0 >= ra1 && ra0 >= ra2 && ra0 >= ra3) rightColor = pickColor(0);\n"
            + "    else if (ra1 >= ra0 && ra1 >= ra2 && ra1 >= ra3) rightColor = pickColor(1);\n"
            + "    else if (ra2 >= ra0 && ra2 >= ra1 && ra2 >= ra3) rightColor = pickColor(2);\n"
            + "    else rightColor = pickColor(3);\n"
            + "    float rightAlpha = rightXMask * rightYMask * rightAlphaSeg * uOpacity;\n"
            + "\n"
            + "    vec3 color = base.rgb;\n"
            + "    color = mix(color, leftColor, leftAlpha);\n"
            + "    color = mix(color, rightColor, rightAlpha);\n"
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
    private int pingPongModeHandle = -1;
    private int color0Handle = -1;
    private int color1Handle = -1;
    private int color2Handle = -1;
    private int color3Handle = -1;

    private float stripWidthPx;
    private float edgeSoftnessPx;
    private float blurRadiusPx;
    private float barLength = 0.22f;
    private float barGap = 0.20f;
    // If explicit bar sizing is enabled, legacy setTrainLength() won't override barLength/barGap.
    private boolean explicitBarSizing = false;
    private float bandSoftness = 0.06f;
    private float speed = 0.50f;
    private float opacity = 0.95f;
    private boolean pingPongMode = true;

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
        // Keep time in a bounded range: avoids precision loss in shader, and still moves even if filter is recreated.
        float timeSec = (SystemClock.uptimeMillis() % 600000L) / 1000f;
        float widthNorm = clamp(stripWidthPx / mWidth, 0.001f, 0.35f);
        float edgeSoftNorm = clamp(edgeSoftnessPx / mWidth, 0.0005f, widthNorm * 0.95f);
        float blurNorm = clamp(blurRadiusPx / mWidth, 0.0005f, 0.40f);

        GLES20.glUniform1f(timeHandle, timeSec);
        GLES20.glUniform1f(stripWidthHandle, widthNorm);
        GLES20.glUniform1f(edgeSoftnessHandle, edgeSoftNorm);
        GLES20.glUniform1f(blurRadiusHandle, blurNorm);
        GLES20.glUniform1f(barLengthHandle, clamp(barLength, 0.01f, 2.0f));
        GLES20.glUniform1f(barGapHandle, clamp(barGap, 0.0f, 2.0f));
        GLES20.glUniform1f(bandSoftnessHandle, clamp(bandSoftness, 0.01f, 0.45f));
        GLES20.glUniform1f(speedHandle, Math.max(0.001f, speed));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
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
