package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Hexagonal crystallization / honeycomb mosaic effect.
 * cellSize is expressed in pixels.
 */
public class HexCrystallizationFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uCellSize;\n"
            + "\n"
            + "vec2 nearestHexCenter(vec2 uv, float cellSizePx) {\n"
            + "    vec2 pixel = uv * uResolution;\n"
            + "    float r = max(cellSizePx, 1.0);\n"
            + "    float hexW = sqrt(3.0) * r;\n"
            + "    float hexH = 2.0 * r;\n"
            + "    float rowStep = 1.5 * r;\n"
            + "\n"
            + "    float row = floor(pixel.y / rowStep);\n"
            + "    float rowOffset = mod(row, 2.0) * (hexW * 0.5);\n"
            + "    float col = floor((pixel.x - rowOffset) / hexW);\n"
            + "\n"
            + "    vec2 bestCenter = pixel;\n"
            + "    float bestDist = 1e9;\n"
            + "    for (int j = -1; j <= 1; ++j) {\n"
            + "        for (int i = -1; i <= 1; ++i) {\n"
            + "            float testRow = row + float(j);\n"
            + "            float testOffset = mod(testRow, 2.0) * (hexW * 0.5);\n"
            + "            float testCol = col + float(i);\n"
            + "            vec2 center = vec2(testCol * hexW + testOffset + hexW * 0.5, testRow * rowStep + r);\n"
            + "            vec2 delta = center - pixel;\n"
            + "            float dist2 = dot(delta, delta);\n"
            + "            if (dist2 < bestDist) {\n"
            + "                bestDist = dist2;\n"
            + "                bestCenter = center;\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "    return clamp(bestCenter / uResolution, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 sampleUv = nearestHexCenter(textureCoordinate, uCellSize);\n"
            + "    gl_FragColor = texture2D(sTexture, sampleUv);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int cellSizeHandle = -1;

    private float cellSizePx = 24f;

    public HexCrystallizationFilter() {
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

    public HexCrystallizationFilter setCellSizePx(float cellSizePx) {
        this.cellSizePx = Math.max(1.0f, cellSizePx);
        return this;
    }
}
