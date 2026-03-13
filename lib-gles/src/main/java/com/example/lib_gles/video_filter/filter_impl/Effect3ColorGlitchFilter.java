package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * 复合效果：粉紫调色 + 顶部块状 glitch + 轻微柔化
 */
public class Effect3ColorGlitchFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uTimeSec;\n"
            + "uniform float uGradeStrength;\n"
            + "uniform float uHueShift;\n"
            + "uniform float uSaturation;\n"
            + "uniform float uContrast;\n"
            + "uniform float uSoften;\n"
            + "uniform float uGlitchStrength;\n"
            + "uniform float uGlitchTopRatio;\n"
            + "uniform float uGlitchDensity;\n"
            + "\n"
            + "float hash12(vec2 p) {\n"
            + "    p = fract(p * vec2(123.34, 456.21));\n"
            + "    p += dot(p, p + 78.233);\n"
            + "    return fract(p.x * p.y);\n"
            + "}\n"
            + "\n"
            + "vec3 rgb2hsv(vec3 c) {\n"
            + "    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n"
            + "    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n"
            + "    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n"
            + "    float d = q.x - min(q.w, q.y);\n"
            + "    float e = 1.0e-10;\n"
            + "    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n"
            + "}\n"
            + "\n"
            + "vec3 hsv2rgb(vec3 c) {\n"
            + "    vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);\n"
            + "    vec3 rgb = clamp(p - 1.0, 0.0, 1.0);\n"
            + "    return c.z * mix(vec3(1.0), rgb, c.y);\n"
            + "}\n"
            + "\n"
            + "vec3 gradePink(vec3 src) {\n"
            + "    vec3 hsv = rgb2hsv(clamp(src, 0.0, 1.0));\n"
            + "    hsv.x = fract(hsv.x + uHueShift);\n"
            + "    hsv.y = clamp(hsv.y * uSaturation, 0.0, 1.2);\n"
            + "    vec3 outc = hsv2rgb(hsv);\n"
            + "    outc.r += 0.05 * uGradeStrength;\n"
            + "    outc.b += 0.08 * uGradeStrength;\n"
            + "    outc.g *= (1.0 - 0.06 * uGradeStrength);\n"
            + "    outc = (outc - 0.5) * uContrast + 0.5;\n"
            + "    return clamp(outc, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "vec3 softBlur(vec2 uv, vec3 baseColor) {\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "    vec2 o = texel * (1.2 + 2.8 * uSoften);\n"
            + "    vec3 b = vec3(0.0);\n"
            + "    b += texture2D(sTexture, clamp(uv + vec2(-o.x, 0.0), 0.0, 1.0)).rgb * 0.20;\n"
            + "    b += texture2D(sTexture, clamp(uv + vec2(o.x, 0.0), 0.0, 1.0)).rgb * 0.20;\n"
            + "    b += texture2D(sTexture, clamp(uv + vec2(0.0, -o.y), 0.0, 1.0)).rgb * 0.20;\n"
            + "    b += texture2D(sTexture, clamp(uv + vec2(0.0, o.y), 0.0, 1.0)).rgb * 0.20;\n"
            + "    b += baseColor * 0.20;\n"
            + "    return mix(baseColor, b, clamp(uSoften, 0.0, 1.0));\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec3 src = texture2D(sTexture, uv).rgb;\n"
            + "\n"
            + "    vec3 graded = gradePink(src);\n"
            + "    vec3 color = softBlur(uv, graded);\n"
            + "\n"
            + "    float topMaskA = smoothstep(1.0 - uGlitchTopRatio, 1.0, uv.y);\n"
            + "    float topMaskB = smoothstep(1.0 - uGlitchTopRatio, 1.0, 1.0 - uv.y);\n"
            + "    float topMask = max(topMaskA, topMaskB);\n"
            + "    vec2 blockId = floor(vec2(uv.x * 28.0, uv.y * 96.0 + uTimeSec * 18.0));\n"
            + "    float n = hash12(blockId);\n"
            + "    float density = clamp(uGlitchDensity, 0.05, 0.95);\n"
            + "    float burst = 0.45 + 0.55 * step(0.52, fract(uTimeSec * 8.0));\n"
            + "    float active = step(1.0 - density, n) * topMask * burst * clamp(uGlitchStrength, 0.0, 1.0);\n"
            + "\n"
            + "    float shiftX = (0.014 + 0.042 * hash12(blockId + 3.17)) * (hash12(blockId + 9.41) * 2.0 - 1.0);\n"
            + "    vec2 offset = vec2(shiftX, 0.0) * active;\n"
            + "\n"
            + "    float split = (0.010 + 0.030 * hash12(blockId + 11.7)) * active;\n"
            + "    vec3 cr = gradePink(texture2D(sTexture, clamp(uv + offset + vec2(split, 0.0), 0.0, 1.0)).rgb);\n"
            + "    vec3 cg = gradePink(texture2D(sTexture, clamp(uv + offset, 0.0, 1.0)).rgb);\n"
            + "    vec3 cb = gradePink(texture2D(sTexture, clamp(uv + offset - vec2(split, 0.0), 0.0, 1.0)).rgb);\n"
            + "    vec3 glitchColor = vec3(cr.r, cg.g, cb.b);\n"
            + "\n"
            + "    float redStrip = step(0.72, hash12(blockId + 21.0)) * active;\n"
            + "    glitchColor = mix(glitchColor, vec3(1.0, 0.10, 0.08), redStrip * 0.90);\n"
            + "    float blueStrip = step(0.80, hash12(blockId + 33.0)) * active;\n"
            + "    glitchColor = mix(glitchColor, vec3(0.08, 0.35, 1.0), blueStrip * 0.70);\n"
            + "\n"
            + "    float scanBand = step(0.84, sin((uv.y * 420.0) + (uTimeSec * 60.0))) * topMask;\n"
            + "    float colRnd = step(0.58, hash12(vec2(floor(uv.x * 30.0) + floor(uTimeSec * 24.0), floor(uTimeSec * 7.0))));\n"
            + "    float forcedBar = scanBand * colRnd * clamp(uGlitchStrength, 0.0, 1.0);\n"
            + "    float chooseBlue = step(0.5, hash12(vec2(floor(uv.x * 14.0), floor(uTimeSec * 11.0))));\n"
            + "    vec3 barColor = mix(vec3(1.0, 0.06, 0.05), vec3(0.04, 0.30, 1.0), chooseBlue);\n"
            + "\n"
            + "    vec3 outColor = mix(color, glitchColor, active);\n"
            + "    outColor = mix(outColor, barColor, forcedBar * 0.95);\n"
            + "    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int timeHandle = -1;
    private int gradeStrengthHandle = -1;
    private int hueShiftHandle = -1;
    private int saturationHandle = -1;
    private int contrastHandle = -1;
    private int softenHandle = -1;
    private int glitchStrengthHandle = -1;
    private int glitchTopRatioHandle = -1;
    private int glitchDensityHandle = -1;

    private float gradeStrength = 1.0f;
    private float hueShift = 0.07f;
    private float saturation = 0.85f;
    private float contrast = 1.06f;
    private float soften = 0.24f;
    private float glitchStrength = 1.0f;
    private float glitchTopRatio = 0.46f;
    private float glitchDensity = 0.72f;

    public Effect3ColorGlitchFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        timeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTimeSec");
        gradeStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGradeStrength");
        hueShiftHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHueShift");
        saturationHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSaturation");
        contrastHandle = GLES20.glGetUniformLocation(mProgramHandle, "uContrast");
        softenHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSoften");
        glitchStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlitchStrength");
        glitchTopRatioHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlitchTopRatio");
        glitchDensityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uGlitchDensity");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        float t = presentationTimeUs / 1_000_000f;
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(timeHandle, t);
        GLES20.glUniform1f(gradeStrengthHandle, clamp(gradeStrength, 0.0f, 2.0f));
        GLES20.glUniform1f(hueShiftHandle, hueShift);
        GLES20.glUniform1f(saturationHandle, clamp(saturation, 0.0f, 2.0f));
        GLES20.glUniform1f(contrastHandle, clamp(contrast, 0.5f, 2.0f));
        GLES20.glUniform1f(softenHandle, clamp(soften, 0.0f, 1.0f));
        GLES20.glUniform1f(glitchStrengthHandle, clamp(glitchStrength, 0.0f, 1.0f));
        GLES20.glUniform1f(glitchTopRatioHandle, clamp(glitchTopRatio, 0.0f, 1.0f));
        GLES20.glUniform1f(glitchDensityHandle, clamp(glitchDensity, 0.05f, 0.95f));
    }

    public Effect3ColorGlitchFilter setGradeStrength(float gradeStrength) {
        this.gradeStrength = clamp(gradeStrength, 0.0f, 2.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setHueShift(float hueShift) {
        this.hueShift = hueShift;
        return this;
    }

    public Effect3ColorGlitchFilter setSaturation(float saturation) {
        this.saturation = clamp(saturation, 0.0f, 2.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setContrast(float contrast) {
        this.contrast = clamp(contrast, 0.5f, 2.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setSoften(float soften) {
        this.soften = clamp(soften, 0.0f, 1.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setGlitchStrength(float glitchStrength) {
        this.glitchStrength = clamp(glitchStrength, 0.0f, 1.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setGlitchTopRatio(float glitchTopRatio) {
        this.glitchTopRatio = clamp(glitchTopRatio, 0.0f, 1.0f);
        return this;
    }

    public Effect3ColorGlitchFilter setGlitchDensity(float glitchDensity) {
        this.glitchDensity = clamp(glitchDensity, 0.05f, 0.95f);
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
