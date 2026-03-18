package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulse zoom effect:
 * - Zoom in from 1.0 to targetScale during zoomInDurationMs.
 * - Zoom out from targetScale back to 1.0 during zoomOutDurationMs.
 * - Repeats forever.
 */
public class GlPulseZoomFilter extends GlFilter {
    private static class ZoomSegment {
        final float startMs;
        final float endMs;
        final float fromScale;
        final float toScale;

        ZoomSegment(float startMs, float endMs, float fromScale, float toScale) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.fromScale = fromScale;
            this.toScale = toScale;
        }
    }

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTextureCoord;\n"
            + "varying highp vec2 vTextureCoord;\n"
            + "uniform float uScale;\n"
            + "void main() {\n"
            + "    gl_Position = aPosition;\n"
            + "    vec2 centered = aTextureCoord.xy - vec2(0.5, 0.5);\n"
            + "    vTextureCoord = centered / uScale + vec2(0.5, 0.5);\n"
            + "}\n";

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 vTextureCoord;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "void main() {\n"
            + "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n"
            + "}\n";

    private int scaleHandle = -1;

    private float targetScale = 1.3f;
    private float baseScale = 1.0f;
    private float zoomInDurationMs = 1000f;
    private float zoomOutDurationMs = 1000f;
    private boolean startInZoomOutPhase = false;
    private long firstPresentationMs = -1L;
    private final List<ZoomSegment> timelineSegments = new ArrayList<>();
    private float timelineLoopDurationMs = -1f;
    private float timelineIdleScale = 1.0f;

    public GlPulseZoomFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    public GlPulseZoomFilter(float targetScale) {
        this();
        this.targetScale = Math.max(1.0f, targetScale);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
    }

    @Override
    public void onDraw(long presentationTimeUs) {
        long nowMs = presentationTimeUs >= 0
                ? presentationTimeUs / 1_000_000L
                : SystemClock.uptimeMillis();
        if (firstPresentationMs < 0L || nowMs < firstPresentationMs) {
            firstPresentationMs = nowMs;
        }
        float elapsedMs = nowMs - firstPresentationMs;
        float timelineScale = evaluateTimelineScale(elapsedMs);
        if (timelineScale > 0f) {
            GLES20.glUniform1f(scaleHandle, timelineScale);
            return;
        }
        float cycle = Math.max(1f, zoomInDurationMs + zoomOutDurationMs);
        float phaseOffset = startInZoomOutPhase ? zoomInDurationMs : 0f;
        float t = (elapsedMs + phaseOffset) % cycle;

        float scale;
        if (t < zoomInDurationMs) {
            float p = t / Math.max(1f, zoomInDurationMs); // 0..1
            scale = baseScale + (targetScale - baseScale) * p;
        } else {
            float p = (t - zoomInDurationMs) / Math.max(1f, zoomOutDurationMs); // 0..1
            scale = targetScale + (baseScale - targetScale) * p;
        }
        GLES20.glUniform1f(scaleHandle, scale);
    }

    @Override
    public void setup() {
        firstPresentationMs = -1L;
        super.setup();
    }

    @Override
    public void release() {
        firstPresentationMs = -1L;
        super.release();
    }

    public GlPulseZoomFilter setTargetScale(float targetScale) {
        this.targetScale = Math.max(1.0f, targetScale);
        return this;
    }

    public GlPulseZoomFilter setBaseScale(float baseScale) {
        this.baseScale = Math.max(1.0f, baseScale);
        return this;
    }

    public GlPulseZoomFilter setZoomInDurationMs(float zoomInDurationMs) {
        this.zoomInDurationMs = Math.max(1f, zoomInDurationMs);
        return this;
    }

    public GlPulseZoomFilter setZoomOutDurationMs(float zoomOutDurationMs) {
        this.zoomOutDurationMs = Math.max(1f, zoomOutDurationMs);
        return this;
    }

    public GlPulseZoomFilter setStartInZoomOutPhase(boolean startInZoomOutPhase) {
        this.startInZoomOutPhase = startInZoomOutPhase;
        return this;
    }

    public GlPulseZoomFilter clearTimelineSegments() {
        timelineSegments.clear();
        timelineLoopDurationMs = -1f;
        timelineIdleScale = 1.0f;
        return this;
    }

    public GlPulseZoomFilter addTimelineSegment(float startMs, float endMs, float fromScale, float toScale) {
        float s = Math.max(0f, startMs);
        float e = Math.max(s + 1f, endMs);
        timelineSegments.add(new ZoomSegment(s, e, Math.max(1.0f, fromScale), Math.max(1.0f, toScale)));
        timelineLoopDurationMs = Math.max(timelineLoopDurationMs, e);
        return this;
    }

    public GlPulseZoomFilter setTimelineLoopDurationMs(float timelineLoopDurationMs) {
        this.timelineLoopDurationMs = Math.max(1f, timelineLoopDurationMs);
        return this;
    }

    public GlPulseZoomFilter setTimelineIdleScale(float timelineIdleScale) {
        this.timelineIdleScale = Math.max(1.0f, timelineIdleScale);
        return this;
    }

    private float evaluateTimelineScale(float elapsedMs) {
        if (timelineSegments.isEmpty()) {
            return -1f;
        }
        float loopMs = timelineLoopDurationMs > 0f ? timelineLoopDurationMs : inferTimelineDuration();
        if (loopMs <= 0f) {
            return timelineIdleScale;
        }
        float t = elapsedMs % loopMs;
        for (int i = 0; i < timelineSegments.size(); i++) {
            ZoomSegment seg = timelineSegments.get(i);
            if (t >= seg.startMs && t < seg.endMs) {
                float p = (t - seg.startMs) / Math.max(1f, seg.endMs - seg.startMs);
                return seg.fromScale + (seg.toScale - seg.fromScale) * p;
            }
        }
        return timelineIdleScale;
    }

    private float inferTimelineDuration() {
        float end = 0f;
        for (int i = 0; i < timelineSegments.size(); i++) {
            end = Math.max(end, timelineSegments.get(i).endMs);
        }
        return end;
    }
}
