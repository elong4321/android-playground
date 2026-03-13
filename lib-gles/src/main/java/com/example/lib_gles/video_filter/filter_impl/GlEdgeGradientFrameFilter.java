package com.example.lib_gles.video_filter.filter_impl;

import android.graphics.Color;
import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * 4 边铺满的颜色深浅渐变边框：
 * 靠近画面边缘更深，靠近中心一侧更浅。
 */
public class GlEdgeGradientFrameFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform float uWidthRatio;\n"
            + "uniform vec3 uBaseColor;\n"
            + "uniform float uDarkness;\n"
            + "uniform float uLightness;\n"
            + "uniform float uOpacity;\n"
            + "uniform float uFeather;\n"
            + "uniform float uGlowWidth;\n"
            + "uniform float uGlowIntensity;\n"
            + "float sideMask(float distToEdge, float width, float feather) {\n"
            + "    return 1.0 - smoothstep(width - feather, width + feather, distToEdge);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec4 src = texture2D(sTexture, uv);\n"
            + "    float width = clamp(uWidthRatio, 0.0, 0.5);\n"
            + "    float feather = clamp(uFeather, 0.0005, 0.5);\n"
            + "    float glowW = clamp(uGlowWidth, 0.0, 0.5);\n"
            + "    float d = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));\n"
            + "\n"
            + "    float l = sideMask(uv.x, width, feather);\n"
            + "    float r = sideMask(1.0 - uv.x, width, feather);\n"
            + "    float tSide = sideMask(1.0 - uv.y, width, feather);\n"
            + "    float b = sideMask(uv.y, width, feather);\n"
            + "\n"
            + "    float sideSum = l + r + tSide + b;\n"
            + "    float coreMask = clamp(sideSum, 0.0, 1.0);\n"
            + "\n"
            + "    float tl = clamp(uv.x / max(width + glowW, 0.0001), 0.0, 1.0);\n"
            + "    float tr = clamp((1.0 - uv.x) / max(width + glowW, 0.0001), 0.0, 1.0);\n"
            + "    float tt = clamp((1.0 - uv.y) / max(width + glowW, 0.0001), 0.0, 1.0);\n"
            + "    float tb = clamp(uv.y / max(width + glowW, 0.0001), 0.0, 1.0);\n"
            + "    float gradT = (l * tl + r * tr + tSide * tt + b * tb) / max(sideSum, 0.0001);\n"
            + "    gradT = smoothstep(0.0, 1.0, gradT);\n"
            + "\n"
            + "    vec3 darkColor = uBaseColor * (1.0 - clamp(uDarkness, 0.0, 1.0) * 0.35);\n"
            + "    vec3 lightColor = mix(uBaseColor, vec3(1.0), clamp(uLightness, 0.0, 1.0));\n"
            + "    vec3 gradColor = mix(darkColor, lightColor, gradT);\n"
            + "\n"
            + "    float glowMask = 0.0;\n"
            + "    if (glowW > 0.0001) {\n"
            + "        float glow = 1.0 - smoothstep(width, width + glowW, d);\n"
            + "        glowMask = glow * (1.0 - coreMask);\n"
            + "    }\n"
            + "\n"
            + "    float totalMask = clamp(coreMask * 0.70 + glowMask * clamp(uGlowIntensity, 0.0, 2.0) * 0.45, 0.0, 1.0);\n"
            + "    totalMask *= clamp(uOpacity, 0.0, 1.0);\n"
            + "    vec3 screenLike = 1.0 - (1.0 - src.rgb) * (1.0 - gradColor * totalMask);\n"
            + "    vec3 outRgb = mix(src.rgb, screenLike, totalMask * 0.80);\n"
            + "    outRgb = clamp(outRgb, 0.0, 1.0);\n"
            + "    gl_FragColor = vec4(outRgb, src.a);\n"
            + "}\n";

    private int widthRatioHandle = -1;
    private int baseColorHandle = -1;
    private int darknessHandle = -1;
    private int lightnessHandle = -1;
    private int opacityHandle = -1;
    private int featherHandle = -1;
    private int glowWidthHandle = -1;
    private int glowIntensityHandle = -1;

    private float widthRatio = 0.09f;
    private float colorR = 0.90f;
    private float colorG = 0.42f;
    private float colorB = 0.86f;
    private float darkness = 0.22f;
    private float lightness = 0.36f;
    private float opacity = 0.50f;
    private float feather = 0.085f;
    private float glowWidth = 0.065f;
    private float glowIntensity = 0.35f;

    public GlEdgeGradientFrameFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        widthRatioHandle = GLES20.glGetUniformLocation(mProgramHandle, "uWidthRatio");
        baseColorHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBaseColor");
        darknessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uDarkness");
        lightnessHandle = GLES20.glGetUniformLocation(mProgramHandle, "uLightness");
        opacityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOpacity");
        featherHandle = GLES20.glGetUniformLocation(mProgramHandle, "uFeather");
        glowWidthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowWidth");
        glowIntensityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlowIntensity");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        GLES20.glUniform1f(widthRatioHandle, clamp(widthRatio, 0.0f, 0.5f));
        GLES20.glUniform3f(baseColorHandle, clamp01(colorR), clamp01(colorG), clamp01(colorB));
        GLES20.glUniform1f(darknessHandle, clamp(darkness, 0.0f, 1.0f));
        GLES20.glUniform1f(lightnessHandle, clamp(lightness, 0.0f, 1.0f));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
        GLES20.glUniform1f(featherHandle, clamp(feather, 0.0005f, 0.5f));
        GLES20.glUniform1f(glowWidthHandle, clamp(glowWidth, 0.0f, 0.5f));
        GLES20.glUniform1f(glowIntensityHandle, clamp(glowIntensity, 0.0f, 2.0f));
    }

    /**
     * 渐变宽度占画面短边的一半比例，范围 [0, 0.5]。
     */
    public GlEdgeGradientFrameFilter setWidthRatio(float widthRatio) {
        this.widthRatio = clamp(widthRatio, 0.0f, 0.5f);
        return this;
    }

    public GlEdgeGradientFrameFilter setColor(float r, float g, float b) {
        this.colorR = clamp01(r);
        this.colorG = clamp01(g);
        this.colorB = clamp01(b);
        return this;
    }

    public GlEdgeGradientFrameFilter setColor(int color) {
        this.colorR = Color.red(color) / 255f;
        this.colorG = Color.green(color) / 255f;
        this.colorB = Color.blue(color) / 255f;
        return this;
    }

    /**
     * 靠边缘颜色加深程度，0=不加深，1=最深。
     */
    public GlEdgeGradientFrameFilter setDarkness(float darkness) {
        this.darkness = clamp(darkness, 0.0f, 1.0f);
        return this;
    }

    /**
     * 靠中心一侧颜色变浅程度，0=不变浅，1=最浅(接近白色)。
     */
    public GlEdgeGradientFrameFilter setLightness(float lightness) {
        this.lightness = clamp(lightness, 0.0f, 1.0f);
        return this;
    }

    public GlEdgeGradientFrameFilter setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0.0f, 1.0f);
        return this;
    }

    /**
     * 过渡羽化宽度（UV 比例）。值越大边界越模糊、角点越不明显。
     */
    public GlEdgeGradientFrameFilter setFeather(float feather) {
        this.feather = clamp(feather, 0.0005f, 0.5f);
        return this;
    }

    /**
     * 外扩光晕宽度（UV 比例）。
     */
    public GlEdgeGradientFrameFilter setGlowWidth(float glowWidth) {
        this.glowWidth = clamp(glowWidth, 0.0f, 0.5f);
        return this;
    }

    /**
     * 外扩光晕强度。
     */
    public GlEdgeGradientFrameFilter setGlowIntensity(float glowIntensity) {
        this.glowIntensity = clamp(glowIntensity, 0.0f, 2.0f);
        return this;
    }

    public GlEdgeGradientFrameFilter setCornerRadius(float cornerRadius) {
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }
}
