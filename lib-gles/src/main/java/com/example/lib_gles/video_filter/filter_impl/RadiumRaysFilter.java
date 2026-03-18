package com.example.lib_gles.video_filter.filter_impl;

import android.graphics.Color;
import android.opengl.GLES20;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Draw one top horizontal ray and one mirrored bottom ray.
 */
public class RadiumRaysFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec3 uColor1;\n"
            + "uniform float uY1;\n"
            + "uniform float uThickness;\n"
            + "uniform float uFeather;\n"
            + "uniform float uGlowWidth;\n"
            + "uniform float uGlowIntensity;\n"
            + "uniform float uOpacity;\n"
            + "uniform float uBrightness;\n"
            + "uniform float uCoreWhiteAlpha;\n"
            + "uniform float uTime;\n"
            + "uniform float uPulseStrength;\n"
            + "uniform float uAnimEnabled;\n"
            + "\n"
            + "float lineCoreMask(float y, float center, float coreHalf, float feather) {\n"
            + "    float d = abs(y - center);\n"
            + "    float core = 1.0 - smoothstep(coreHalf, coreHalf + feather, d);\n"
            + "    return clamp(core * 0.92, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "float lineGlowMask(float y, float center, float coreHalf, float feather, float glowW, float glowIntensity) {\n"
            + "    float d = abs(y - center);\n"
            + "    float core = 1.0 - smoothstep(coreHalf, coreHalf + feather, d);\n"
            + "    float outer = 1.0 - smoothstep(coreHalf + feather, coreHalf + feather + glowW, d);\n"
            + "    float glow = max(outer - core, 0.0);\n"
            + "    return clamp(glow * glowIntensity * 0.55, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec4 src = texture2D(sTexture, uv);\n"
            + "\n"
            + "    float coreHalf = max(0.0005, uThickness * 0.5);\n"
            + "    float feather = max(0.0001, uFeather);\n"
            + "    float glowW = max(0.0, uGlowWidth);\n"
            + "\n"
            + "    float cTop = lineCoreMask(uv.y, uY1, coreHalf, feather);\n"
            + "    float gTop = lineGlowMask(uv.y, uY1, coreHalf, feather, glowW, clamp(uGlowIntensity, 0.0, 4.0));\n"
            + "    float yBottom = 1.0 - uY1;\n"
            + "    float cBottom = lineCoreMask(uv.y, yBottom, coreHalf, feather);\n"
            + "    float gBottom = lineGlowMask(uv.y, yBottom, coreHalf, feather, glowW, clamp(uGlowIntensity, 0.0, 4.0));\n"
            + "\n"
            + "    float op = clamp(uOpacity, 0.0, 1.0);\n"
            + "    float whiteA = clamp(uCoreWhiteAlpha, 0.0, 1.0);\n"
            + "    vec3 rayCoreCol = mix(uColor1, vec3(1.0), whiteA);\n"
            + "    float gain = clamp(uBrightness, 0.0, 8.0);\n"
            + "    vec3 rays = rayCoreCol * (cTop * op) + uColor1 * (gTop * op)\n"
            + "              + rayCoreCol * (cBottom * op) + uColor1 * (gBottom * op);\n"
            + "    vec3 outRgb = clamp(src.rgb + rays * gain, 0.0, 1.0);\n"
            + "    gl_FragColor = vec4(outRgb, src.a);\n"
            + "}\n";

    private int color1Handle = -1;
    private int y1Handle = -1;
    private int thicknessHandle = -1;
    private int featherHandle = -1;
    private int glowWidthHandle = -1;
    private int glowIntensityHandle = -1;
    private int opacityHandle = -1;
    private int brightnessHandle = -1;
    private int coreWhiteAlphaHandle = -1;
    private int timeHandle = -1;
    private int pulseStrengthHandle = -1;
    private int animEnabledHandle = -1;

    private float color1R = 0.20f;
    private float color1G = 0.95f;
    private float color1B = 1.00f;
    private float color2R = 0.95f;
    private float color2G = 0.25f;
    private float color2B = 1.00f;

    // In UV space (0 bottom, 1 top).
    private float y1 = 0.92f;
    private float thickness = 0.010f;
    private float feather = 0.004f;
    private float glowWidth = 0.026f;
    private float glowIntensity = 1.15f;
    private float opacity = 1f;
    private float brightness = 1.20f;
    private float coreWhiteAlpha = 1.0f;
    private boolean animEnabled = true;
    private float pulseStrength = 0.12f;

    public RadiumRaysFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        color1Handle = GLES20.glGetUniformLocation(mProgramHandle, "uColor1");
        y1Handle = GLES20.glGetUniformLocation(mProgramHandle, "uY1");
        thicknessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uThickness");
        featherHandle = GLES20.glGetUniformLocation(mProgramHandle, "uFeather");
        glowWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowWidth");
        glowIntensityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowIntensity");
        opacityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOpacity");
        brightnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBrightness");
        coreWhiteAlphaHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCoreWhiteAlpha");
        timeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTime");
        pulseStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPulseStrength");
        animEnabledHandle = GLES20.glGetUniformLocation(mProgramHandle, "uAnimEnabled");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        float timeSec;
        if (presentationTimeUs > 0L) {
            timeSec = presentationTimeUs / 1_000_000f;
        } else {
            timeSec = (SystemClock.uptimeMillis() % 600000L) / 1000f;
        }
        // Force unified color for all rays.
        color2R = color1R;
        color2G = color1G;
        color2B = color1B;
        GLES20.glUniform3f(color1Handle, clamp01(color1R), clamp01(color1G), clamp01(color1B));
        GLES20.glUniform1f(y1Handle, y1);
        GLES20.glUniform1f(thicknessHandle, clamp(thickness, 0.0005f, 0.20f));
        GLES20.glUniform1f(featherHandle, clamp(feather, 0.0001f, 0.20f));
        GLES20.glUniform1f(glowWidthHandle, clamp(glowWidth, 0.0f, 0.40f));
        GLES20.glUniform1f(glowIntensityHandle, clamp(glowIntensity, 0.0f, 4.0f));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
        GLES20.glUniform1f(brightnessHandle, clamp(brightness, 0.0f, 8.0f));
        GLES20.glUniform1f(coreWhiteAlphaHandle, clamp(coreWhiteAlpha, 0.0f, 1.0f));
        GLES20.glUniform1f(timeHandle, Math.max(0.0f, timeSec));
        GLES20.glUniform1f(pulseStrengthHandle, clamp(pulseStrength, 0.0f, 1.0f));
        GLES20.glUniform1f(animEnabledHandle, animEnabled ? 1.0f : 0.0f);
    }

    public RadiumRaysFilter setRayColor(int color1) {
        this.color1R = Color.red(color1) / 255f;
        this.color1G = Color.green(color1) / 255f;
        this.color1B = Color.blue(color1) / 255f;
        this.color2R = this.color1R;
        this.color2G = this.color1G;
        this.color2B = this.color1B;
        return this;
    }

    public RadiumRaysFilter setRay1Color(int color) {
        this.color1R = Color.red(color) / 255f;
        this.color1G = Color.green(color) / 255f;
        this.color1B = Color.blue(color) / 255f;
        this.color2R = this.color1R;
        this.color2G = this.color1G;
        this.color2B = this.color1B;
        return this;
    }

    public RadiumRaysFilter setRay2Color(int color) {
        // Unified color mode: ray2 color maps to the shared color too.
        this.color1R = Color.red(color) / 255f;
        this.color1G = Color.green(color) / 255f;
        this.color1B = Color.blue(color) / 255f;
        this.color2R = this.color1R;
        this.color2G = this.color1G;
        this.color2B = this.color1B;
        return this;
    }

    public RadiumRaysFilter setRayPosition(float y1) {
        this.y1 = y1;
        return this;
    }

    /**
     * Set distance from top in UV ratio [0..1], easier for "top area" positioning.
     * Example: topOffset=0.08 means y=0.92.
     */
    public RadiumRaysFilter setRayTopOffset(float topOffset1) {
        this.y1 = 1.0f - topOffset1;
        return this;
    }

    public RadiumRaysFilter setThickness(float thickness) {
        this.thickness = thickness;
        return this;
    }

    public RadiumRaysFilter setFeather(float feather) {
        this.feather = feather;
        return this;
    }

    public RadiumRaysFilter setGlowWidth(float glowWidth) {
        this.glowWidth = glowWidth;
        return this;
    }

    public RadiumRaysFilter setGlowIntensity(float glowIntensity) {
        this.glowIntensity = glowIntensity;
        return this;
    }

    public RadiumRaysFilter setOpacity(float opacity) {
        this.opacity = opacity;
        return this;
    }

    public RadiumRaysFilter setBrightness(float brightness) {
        this.brightness = brightness;
        return this;
    }

    /**
     * White core layer alpha: 0 = no white overlay, 1 = fully white core.
     */
    public RadiumRaysFilter setCoreWhiteAlpha(float coreWhiteAlpha) {
        this.coreWhiteAlpha = coreWhiteAlpha;
        return this;
    }

    public RadiumRaysFilter setAnimEnabled(boolean animEnabled) {
        this.animEnabled = animEnabled;
        return this;
    }

    public RadiumRaysFilter setPulseStrength(float pulseStrength) {
        this.pulseStrength = pulseStrength;
        return this;
    }

    public RadiumRaysFilter setFlickerStrength(float flickerStrength) {
        // Flicker effect is removed. Keep this API for compatibility.
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }
}
