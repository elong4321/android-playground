package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical pulse scale effect:
 * - Scale Y from 1.0 to targetScaleY during shrinkDurationMs.
 * - Scale Y from targetScaleY back to 1.0 during expandDurationMs.
 * - Wait intervalMs, then repeat.
 */
public class GlPulseVerticalScaleFilter extends GlFilter {
    private static class PulseSegment {
        final float startMs;
        final float splitMs;
        final float endMs;
        final float targetScaleY;

        PulseSegment(float startMs, float splitMs, float endMs, float targetScaleY) {
            this.startMs = startMs;
            this.splitMs = splitMs;
            this.endMs = endMs;
            this.targetScaleY = targetScaleY;
        }
    }

    public interface OnPulseProgressListener {
        void onShrinkProgress(long cycleIndex, float progress, float scaleY);
        void onExpandProgress(long cycleIndex, float progress, float scaleY);
    }

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoord;\n"
            + "varying highp vec2 vTextureCoord;\n"
            + "void main() {\n"
            + "    gl_Position = aPosition;\n"
            + "    vTextureCoord = aTextureCoord.xy;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 vTextureCoord;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform float uScaleY;\n"
            + "float mirror01(float v) {\n"
            + "    float t = mod(v, 2.0);\n"
            + "    return (t <= 1.0) ? t : (2.0 - t);\n"
            + "}\n"
            + "void main() {\n"
            + "    float sy = max(uScaleY, 0.0001);\n"
            + "    // Center-anchored vertical shrink/expand remap.\n"
            + "    float srcY = (vTextureCoord.y - 0.5) / sy + 0.5;\n"
            + "    // Fill outside area by mirrored reflection of top/bottom content.\n"
            + "    srcY = mirror01(srcY);\n"
            + "    gl_FragColor = texture2D(sTexture, vec2(vTextureCoord.x, srcY));\n"
            + "}\n";

    private int scaleYHandle = -1;

    private float targetScaleY = 0.7f;
    private float shrinkDurationMs = 200f;
    private float expandDurationMs = 200f;
    private float intervalMs = 1000f;
    private long firstPresentationMs = -1L;
    private OnPulseProgressListener onPulseProgressListener;
    private final List<PulseSegment> timelineSegments = new ArrayList<>();
    private float timelineLoopDurationMs = -1f;

    public GlPulseVerticalScaleFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    public GlPulseVerticalScaleFilter(float targetScaleY) {
        this();
        this.targetScaleY = clamp(targetScaleY, 0.01f, 1.0f);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        scaleYHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScaleY");
    }

    @Override
    public void onDraw(long presentationTimeUs) {
        // Keep one time base. presentationTimeUs is fed in nanoseconds in this pipeline.
        long nowMs = presentationTimeUs >= 0
                ? presentationTimeUs / 1_000_000L
                : SystemClock.uptimeMillis();
        if (firstPresentationMs < 0L || nowMs < firstPresentationMs) {
            firstPresentationMs = nowMs;
        }

        float elapsedMs = nowMs - firstPresentationMs;
        if (!timelineSegments.isEmpty()) {
            drawTimeline(elapsedMs);
            return;
        }
        float effectMs = Math.max(1f, shrinkDurationMs + expandDurationMs);
        float cycleMs = Math.max(1f, effectMs + Math.max(0f, intervalMs));
        long cycleIndex = (long) Math.floor(elapsedMs / cycleMs);
        float phaseMs = elapsedMs % cycleMs;
        if (phaseMs >= effectMs) {
            GLES20.glUniform1f(scaleYHandle, 1.0f);
            return;
        }

        float scaleY;
        if (phaseMs < shrinkDurationMs) {
            float p = phaseMs / Math.max(1f, shrinkDurationMs); // 0..1
            scaleY = 1.0f + (targetScaleY - 1.0f) * p;
            if (onPulseProgressListener != null) {
                onPulseProgressListener.onShrinkProgress(cycleIndex, clamp(p, 0f, 1f), scaleY);
            }
        } else {
            float p = (phaseMs - shrinkDurationMs) / Math.max(1f, expandDurationMs); // 0..1
            scaleY = targetScaleY + (1.0f - targetScaleY) * p;
            if (onPulseProgressListener != null) {
                onPulseProgressListener.onExpandProgress(cycleIndex, clamp(p, 0f, 1f), scaleY);
            }
        }
        GLES20.glUniform1f(scaleYHandle, scaleY);
    }

    private void drawTimeline(float elapsedMs) {
        float loopMs = timelineLoopDurationMs > 0f ? timelineLoopDurationMs : inferTimelineDuration();
        if (loopMs <= 0f) {
            GLES20.glUniform1f(scaleYHandle, 1.0f);
            return;
        }
        float t = elapsedMs % loopMs;
        long cycleIndex = (long) Math.floor(elapsedMs / loopMs);
        for (int i = 0; i < timelineSegments.size(); i++) {
            PulseSegment seg = timelineSegments.get(i);
            if (t >= seg.startMs && t < seg.endMs) {
                float scaleY;
                if (t < seg.splitMs) {
                    float p = (t - seg.startMs) / Math.max(1f, seg.splitMs - seg.startMs);
                    scaleY = 1.0f + (seg.targetScaleY - 1.0f) * p;
                    if (onPulseProgressListener != null) {
                        onPulseProgressListener.onShrinkProgress(cycleIndex, clamp(p, 0f, 1f), scaleY);
                    }
                } else {
                    float p = (t - seg.splitMs) / Math.max(1f, seg.endMs - seg.splitMs);
                    scaleY = seg.targetScaleY + (1.0f - seg.targetScaleY) * p;
                    if (onPulseProgressListener != null) {
                        onPulseProgressListener.onExpandProgress(cycleIndex, clamp(p, 0f, 1f), scaleY);
                    }
                }
                GLES20.glUniform1f(scaleYHandle, scaleY);
                return;
            }
        }
        GLES20.glUniform1f(scaleYHandle, 1.0f);
    }

    public GlPulseVerticalScaleFilter setTargetScaleY(float targetScaleY) {
        this.targetScaleY = clamp(targetScaleY, 0.01f, 1.0f);
        return this;
    }

    public GlPulseVerticalScaleFilter setShrinkDurationMs(float shrinkDurationMs) {
        this.shrinkDurationMs = Math.max(1f, shrinkDurationMs);
        return this;
    }

    public GlPulseVerticalScaleFilter setExpandDurationMs(float expandDurationMs) {
        this.expandDurationMs = Math.max(1f, expandDurationMs);
        return this;
    }

    public GlPulseVerticalScaleFilter setIntervalMs(float intervalMs) {
        this.intervalMs = Math.max(0f, intervalMs);
        return this;
    }

    public GlPulseVerticalScaleFilter setOnPulseProgressListener(OnPulseProgressListener listener) {
        this.onPulseProgressListener = listener;
        return this;
    }

    public GlPulseVerticalScaleFilter clearTimelineSegments() {
        timelineSegments.clear();
        timelineLoopDurationMs = -1f;
        return this;
    }

    public GlPulseVerticalScaleFilter addTimelineSegment(
            float startMs, float splitMs, float endMs, float targetScaleY) {
        float s = Math.max(0f, startMs);
        float m = Math.max(s + 1f, splitMs);
        float e = Math.max(m + 1f, endMs);
        timelineSegments.add(new PulseSegment(s, m, e, clamp(targetScaleY, 0.01f, 1.0f)));
        timelineLoopDurationMs = Math.max(timelineLoopDurationMs, e);
        return this;
    }

    public GlPulseVerticalScaleFilter setTimelineLoopDurationMs(float timelineLoopDurationMs) {
        this.timelineLoopDurationMs = Math.max(1f, timelineLoopDurationMs);
        return this;
    }

    private float inferTimelineDuration() {
        float end = 0f;
        for (int i = 0; i < timelineSegments.size(); i++) {
            end = Math.max(end, timelineSegments.get(i).endMs);
        }
        return end;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
