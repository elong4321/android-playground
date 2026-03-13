package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Voronoi-based crystallization effect.
 * cellSize is expressed in pixels.
 */
public class CrystallizationFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uCellSize;\n"
            + "\n"
            + "vec2 hash22(vec2 p) {\n"
            + "    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));\n"
            + "    p3 += dot(p3, p3.yzx + 33.33);\n"
            + "    return fract((p3.xx + p3.yz) * p3.zy);\n"
            + "}\n"
            + "\n"
            + "vec2 voronoiSampleUv(vec2 uv, float cellSizePx) {\n"
            + "    vec2 cellCount = max(vec2(1.0), uResolution / max(cellSizePx, 1.0));\n"
            + "    vec2 p = uv * cellCount;\n"
            + "    vec2 baseCell = floor(p);\n"
            + "    float bestDist = 1e9;\n"
            + "    vec2 bestPoint = p;\n"
            + "    for (int j = -1; j <= 1; ++j) {\n"
            + "        for (int i = -1; i <= 1; ++i) {\n"
            + "            vec2 neighbor = baseCell + vec2(float(i), float(j));\n"
            + "            vec2 jitter = hash22(neighbor) - 0.5;\n"
            + "            vec2 point = neighbor + 0.5 + jitter * 0.9;\n"
            + "            vec2 delta = point - p;\n"
            + "            float dist2 = dot(delta, delta);\n"
            + "            if (dist2 < bestDist) {\n"
            + "                bestDist = dist2;\n"
            + "                bestPoint = point;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    return clamp(bestPoint / cellCount, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 sampleUv = voronoiSampleUv(textureCoordinate, uCellSize);\n"
            + "    gl_FragColor = texture2D(sTexture, sampleUv);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int cellSizeHandle = -1;

    private float cellSizePx = 36f;

    public CrystallizationFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        cellSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uCellSize");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(cellSizeHandle, Math.max(1.0f, cellSizePx));
    }

    public CrystallizationFilter setCellSizePx(float cellSizePx) {
        this.cellSizePx = Math.max(1.0f, cellSizePx);
        return this;
    }
}
