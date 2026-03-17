package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Simple light/brightness boost filter.
 */
public class LightFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform float uLight;\n"
            + "void main() {\n"
            + "    vec4 src = texture2D(sTexture, textureCoordinate);\n"
            + "    vec3 outRgb = clamp(src.rgb * max(uLight, 0.0), 0.0, 1.0);\n"
            + "    gl_FragColor = vec4(outRgb, src.a);\n"
            + "}\n";

    private int lightHandle = -1;
    private float light = 1.2f;

    public LightFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        lightHandle = GLES20.glGetUniformLocation(mProgramHandle, "uLight");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        GLES20.glUniform1f(lightHandle, clamp(light, 0.0f, 8.0f));
    }

    /**
     * 1.0 means no change, >1.0 brighter, <1.0 darker.
     */
    public LightFilter setLight(float light) {
        this.light = light;
        return this;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
