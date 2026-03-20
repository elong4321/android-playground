package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.EFramebufferObject;
import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.Map;

/**
 * FlurFilter:
 * 1) downsample image by ratio (via bigger texel stride)
 * 2) separable gaussian blur (horizontal + vertical)
 * 3) render back at original frame size
 */
public class BlurFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision highp float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uDownsampleScale;\n"
            + "uniform float uBlurRadiusPx;\n"
            + "uniform float uMix;\n"
            + "uniform vec2 uDirection;\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec2 texel = vec2(1.0) / max(uResolution, vec2(1.0));\n"
            + "    float ds = clamp(uDownsampleScale, 0.05, 1.0);\n"
            + "    float radius = max(0.0, uBlurRadiusPx) * clamp(uMix, 0.0, 1.0);\n"
            + "    // Prevent sparse sampling grid artifacts by keeping blur step bounded.\n"
            + "    float dsBoost = mix(1.45, 1.0, smoothstep(0.10, 0.40, ds));\n"
            + "    float stepScale = clamp(radius * 0.13 * dsBoost, 0.30, 6.5);\n"
            + "    vec2 stepUv = texel * uDirection * stepScale;\n"
            + "\n"
            + "    // 9-tap separable gaussian\n"
            + "    vec4 c = texture2D(sTexture, clamp(uv, 0.0, 1.0)) * 0.227027;\n"
            + "    c += texture2D(sTexture, clamp(uv + stepUv * 1.0, 0.0, 1.0)) * 0.1945946;\n"
            + "    c += texture2D(sTexture, clamp(uv - stepUv * 1.0, 0.0, 1.0)) * 0.1945946;\n"
            + "    c += texture2D(sTexture, clamp(uv + stepUv * 2.0, 0.0, 1.0)) * 0.1216216;\n"
            + "    c += texture2D(sTexture, clamp(uv - stepUv * 2.0, 0.0, 1.0)) * 0.1216216;\n"
            + "    c += texture2D(sTexture, clamp(uv + stepUv * 3.0, 0.0, 1.0)) * 0.054054;\n"
            + "    c += texture2D(sTexture, clamp(uv - stepUv * 3.0, 0.0, 1.0)) * 0.054054;\n"
            + "    c += texture2D(sTexture, clamp(uv + stepUv * 4.0, 0.0, 1.0)) * 0.016216;\n"
            + "    c += texture2D(sTexture, clamp(uv - stepUv * 4.0, 0.0, 1.0)) * 0.016216;\n"
            + "\n"
            + "    gl_FragColor = c;\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int downsampleScaleHandle = -1;
    private int blurRadiusHandle = -1;
    private int mixHandle = -1;
    private int directionHandle = -1;

    private final EFramebufferObject tempFbo = new EFramebufferObject();
    private int tempW = -1;
    private int tempH = -1;

    private float downsampleScale = 1f;
    private float blurRadiusPx = 22f;
    private float mix = 1.0f;

    public BlurFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        downsampleScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uDownsampleScale");
        blurRadiusHandle = GLES20.glGetUniformLocation(mProgramHandle, "uBlurRadiusPx");
        mixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMix");
        directionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uDirection");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        // Multi-pass mode uses overridden draw().
    }

    @Override
    public int draw(int sourceTextId, EFramebufferObject fbo, long presentationTimeUs, Map<String, Integer> extraTextureIds) {
        checkSetUp();
        if (mWidth <= 0 || mHeight <= 0) {
            return fbo != null ? fbo.getTexName() : -1;
        }
        ensureTempFbo();

        // Stage A: horizontal + vertical
        drawPass(sourceTextId, tempFbo, presentationTimeUs, 1.0f, 0.0f, 0.85f);
        drawPass(tempFbo.getTexName(), fbo, presentationTimeUs, 0.0f, 1.0f, 0.85f);

        // Stage B: horizontal + vertical (accumulate thickness without sparse-grid feel)
        if (fbo != null) {
            drawPass(fbo.getTexName(), tempFbo, presentationTimeUs, 1.0f, 0.0f, 0.65f);
            drawPass(tempFbo.getTexName(), fbo, presentationTimeUs, 0.0f, 1.0f, 0.65f);
        }

        return fbo != null ? fbo.getTexName() : -1;
    }

    private void drawPass(int inputTex, EFramebufferObject targetFbo, long presentationTimeUs, float dirX, float dirY, float radiusFactor) {
        if (targetFbo != null) {
            targetFbo.enable();
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        useProgram();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, getVertexBufferName());
        GLES20.glEnableVertexAttribArray(getHandle("aPosition"));
        GLES20.glVertexAttribPointer(getHandle("aPosition"), VERTICES_DATA_POS_SIZE, GLES20.GL_FLOAT, false, VERTICES_DATA_STRIDE_BYTES, VERTICES_DATA_POS_OFFSET);
        GLES20.glEnableVertexAttribArray(getHandle("aTextureCoord"));
        GLES20.glVertexAttribPointer(getHandle("aTextureCoord"), VERTICES_DATA_UV_SIZE, GLES20.GL_FLOAT, false, VERTICES_DATA_STRIDE_BYTES, VERTICES_DATA_UV_OFFSET);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex);
        GLES20.glUniform1i(getHandle("sTexture"), 0);

        onDrawFrameBegin(presentationTimeUs);
        GLES20.glUniform2f(resolutionHandle, Math.max(1, mWidth), Math.max(1, mHeight));
        GLES20.glUniform1f(downsampleScaleHandle, clamp(downsampleScale, 0.05f, 1.0f));
        GLES20.glUniform1f(blurRadiusHandle, Math.max(0.0f, blurRadiusPx) * clamp(radiusFactor, 0.0f, 1.0f));
        GLES20.glUniform1f(mixHandle, clamp(mix, 0.0f, 1.0f));
        GLES20.glUniform2f(directionHandle, dirX, dirY);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(getHandle("aPosition"));
        GLES20.glDisableVertexAttribArray(getHandle("aTextureCoord"));
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void ensureTempFbo() {
        if (tempW == mWidth && tempH == mHeight) {
            return;
        }
        tempFbo.setup(mWidth, mHeight);
        tempW = mWidth;
        tempH = mHeight;
    }

    @Override
    public void release() {
        tempFbo.release();
        tempW = -1;
        tempH = -1;
        super.release();
    }

    /**
     * 1.0 = no downsample, 0.25 = quarter resolution.
     */
    public BlurFilter setDownsampleScale(float downsampleScale) {
        this.downsampleScale = clamp(downsampleScale, 0.05f, 1.0f);
        return this;
    }

    public BlurFilter setBlurRadiusPx(float blurRadiusPx) {
        this.blurRadiusPx = Math.max(0.0f, blurRadiusPx);
        return this;
    }

    /**
     * Used as blur amount factor in this dual-pass implementation.
     * 0 = no blur radius, 1 = full blur radius.
     */
    public BlurFilter setMix(float mix) {
        this.mix = clamp(mix, 0.0f, 1.0f);
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
