package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Approximate "1.jpg -> first processed preview" chain:
 * 1) scale up
 * 2) stretch horizontally
 * 3) shift left/right
 * 4) coarse sample and blend back
 * 5) horizontal smear blur
 * 6) light gaussian soften
 */
public class ShakeBlurFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uScale;\n"
            + "uniform float uStretchX;\n"
            + "uniform float uOffsetX;\n"
            + "uniform float uSampleScale;\n"
            + "uniform float uSampleMix;\n"
            + "uniform float uSmearStrength;\n"
            + "uniform float uSoftBlurStrength;\n"
            + "uniform float uContrast;\n"
            + "uniform float uSaturation;\n"
            + "uniform float uSharpnessMix;\n"
            + "\n"
            + "vec3 adjustSaturation(vec3 color, float saturation) {\n"
            + "    float luma = dot(color, vec3(0.299, 0.587, 0.114));\n"
            + "    return mix(vec3(luma), color, saturation);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    float scale = max(uScale, 1.0);\n"
            + "    float stretchX = max(uStretchX, 1.0);\n"
            + "    vec2 centered = textureCoordinate - vec2(0.5);\n"
            + "    vec2 scaledUv = centered / scale + vec2(0.5);\n"
            + "    vec2 stretchedUv = vec2(0.5 + (scaledUv.x - 0.5) / stretchX, scaledUv.y);\n"
            + "    stretchedUv.x -= uOffsetX;\n"
            + "    stretchedUv = clamp(stretchedUv, 0.0, 1.0);\n"
            + "\n"
            + "    vec2 sampledUv = stretchedUv;\n"
            + "    if (uSampleScale > 1.001) {\n"
            + "        vec2 samplePixel = floor((stretchedUv * uResolution) / uSampleScale) * uSampleScale + vec2(uSampleScale * 0.5);\n"
            + "        vec2 coarseUv = clamp(samplePixel / uResolution, 0.0, 1.0);\n"
            + "        sampledUv = mix(stretchedUv, coarseUv, clamp(uSampleMix, 0.0, 1.0));\n"
            + "    }\n"
            + "\n"
            + "    vec4 base = texture2D(sTexture, sampledUv);\n"
            + "    vec2 texel = vec2(1.0) / uResolution;\n"
            + "\n"
            + "    vec2 smearStep = vec2(texel.x * uSmearStrength * 56.0, 0.0);\n"
            + "    vec4 smear = vec4(0.0);\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -6.0, 0.0, 1.0)) * 0.05;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -5.0, 0.0, 1.0)) * 0.07;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -4.0, 0.0, 1.0)) * 0.10;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -3.0, 0.0, 1.0)) * 0.13;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -2.0, 0.0, 1.0)) * 0.15;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * -1.0, 0.0, 1.0)) * 0.16;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.14;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 1.0, 0.0, 1.0)) * 0.10;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 2.0, 0.0, 1.0)) * 0.06;\n"
            + "    smear += texture2D(sTexture, clamp(sampledUv + smearStep * 3.0, 0.0, 1.0)) * 0.04;\n"
            + "\n"
            + "    vec2 blurStep = texel * (uSoftBlurStrength * 220.0);\n"
            + "    vec4 soft = vec4(0.0);\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 1.5, -blurStep.y), 0.0, 1.0)) * 0.0625;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(0.0, -blurStep.y), 0.0, 1.0)) * 0.1250;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 1.5, -blurStep.y), 0.0, 1.0)) * 0.0625;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 1.5, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv, 0.0, 1.0)) * 0.2500;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 1.5, 0.0), 0.0, 1.0)) * 0.1250;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(-blurStep.x * 1.5, blurStep.y), 0.0, 1.0)) * 0.0625;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(0.0, blurStep.y), 0.0, 1.0)) * 0.1250;\n"
            + "    soft += texture2D(sTexture, clamp(sampledUv + vec2(blurStep.x * 1.5, blurStep.y), 0.0, 1.0)) * 0.0625;\n"
            + "\n"
            + "    vec3 thickBase = mix(base.rgb, smear.rgb, clamp(uSmearStrength * 0.72, 0.0, 1.0));\n"
            + "    vec3 color = mix(thickBase, soft.rgb, clamp(uSoftBlurStrength * 3.8, 0.0, 1.0));\n"
            + "\n"
            + "    vec3 lowContrast = (color - 0.5) * uContrast + 0.5;\n"
            + "    color = adjustSaturation(lowContrast, uSaturation);\n"
            + "    color = mix(color, soft.rgb, clamp(uSharpnessMix, 0.0, 1.0));\n"
            + "\n"
            + "    gl_FragColor = vec4(color, base.a);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int scaleHandle = -1;
    private int stretchXHandle = -1;
    private int offsetXHandle = -1;
    private int sampleScaleHandle = -1;
    private int sampleMixHandle = -1;
    private int smearStrengthHandle = -1;
    private int softBlurStrengthHandle = -1;
    private int contrastHandle = -1;
    private int saturationHandle = -1;
    private int sharpnessMixHandle = -1;

    private float scale = 1.2f;
    private float stretchX = 1.35f;
    private float offsetX = -0.06f;
    private float sampleScale = 6.0f;
    private float sampleMix = 0.55f;
    private float smearStrength = 1.15f;
    private float softBlurStrength = 0.03f;
    private float contrast = 0.90f;
    private float saturation = 0.95f;
    private float sharpnessMix = 0.25f;

    public ShakeBlurFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        stretchXHandle = GLES20.glGetUniformLocation(mProgramHandle, "uStretchX");
        offsetXHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOffsetX");
        sampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleScale");
        sampleMixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSampleMix");
        smearStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSmearStrength");
        softBlurStrengthHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSoftBlurStrength");
        contrastHandle = GLES20.glGetUniformLocation(mProgramHandle, "uContrast");
        saturationHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSaturation");
        sharpnessMixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uSharpnessMix");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(scaleHandle, Math.max(1.0f, scale));
        GLES20.glUniform1f(stretchXHandle, Math.max(1.0f, stretchX));
        GLES20.glUniform1f(offsetXHandle, offsetX);
        GLES20.glUniform1f(sampleScaleHandle, Math.max(1.0f, sampleScale));
        GLES20.glUniform1f(sampleMixHandle, clamp(sampleMix, 0.0f, 1.0f));
        GLES20.glUniform1f(smearStrengthHandle, Math.max(0.0f, smearStrength));
        GLES20.glUniform1f(softBlurStrengthHandle, clamp(softBlurStrength, 0.0f, 0.12f));
        GLES20.glUniform1f(contrastHandle, clamp(contrast, 0.5f, 1.5f));
        GLES20.glUniform1f(saturationHandle, clamp(saturation, 0.0f, 1.5f));
        GLES20.glUniform1f(sharpnessMixHandle, clamp(sharpnessMix, 0.0f, 1.0f));
    }

    public ShakeBlurFilter setScale(float scale) {
        this.scale = Math.max(1.0f, scale);
        return this;
    }

    public ShakeBlurFilter setStretchX(float stretchX) {
        this.stretchX = Math.max(1.0f, stretchX);
        return this;
    }

    public ShakeBlurFilter setOffsetX(float offsetX) {
        this.offsetX = offsetX;
        return this;
    }

    public ShakeBlurFilter setSampleScale(float sampleScale) {
        this.sampleScale = Math.max(1.0f, sampleScale);
        return this;
    }

    public ShakeBlurFilter setSampleMix(float sampleMix) {
        this.sampleMix = clamp(sampleMix, 0.0f, 1.0f);
        return this;
    }

    public ShakeBlurFilter setSmearStrength(float smearStrength) {
        this.smearStrength = Math.max(0.0f, smearStrength);
        return this;
    }

    public ShakeBlurFilter setSoftBlurStrength(float softBlurStrength) {
        this.softBlurStrength = clamp(softBlurStrength, 0.0f, 0.12f);
        return this;
    }

    public ShakeBlurFilter setContrast(float contrast) {
        this.contrast = clamp(contrast, 0.5f, 1.5f);
        return this;
    }

    public ShakeBlurFilter setSaturation(float saturation) {
        this.saturation = clamp(saturation, 0.0f, 1.5f);
        return this;
    }

    public ShakeBlurFilter setSharpnessMix(float sharpnessMix) {
        this.sharpnessMix = clamp(sharpnessMix, 0.0f, 1.0f);
        return this;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
