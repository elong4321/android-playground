package com.example.lib_gles.video_filter.filter_impl;

import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

/**
 * Motion-blur transform filter adapted from the MultiInputRight motionBlur shaders.
 *
 * Time-driven motion blur transform filter adapted from the MultiInputRight motionBlur shaders.
 *
 * It automatically interpolates transform parameters from a start state to an end state and
 * internally uses the previous frame transform to generate the blur vector.
 */
public class MotionBlurFilter extends GlFilter {

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying highp vec2 textureCoordinate;\n"
            + "uniform lowp sampler2D sTexture;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uRatio;\n"
            + "uniform float uRadian;\n"
            + "uniform vec2 uPosition;\n"
            + "uniform vec2 uAnchorPosition;\n"
            + "uniform float uScale;\n"
            + "uniform float uPrevRadian;\n"
            + "uniform vec2 uPrevPosition;\n"
            + "uniform vec2 uPrevAnchorPosition;\n"
            + "uniform float uPrevScale;\n"
            + "uniform float uMotionBlurSize;\n"
            + "uniform float uOpacity;\n"
            + "uniform bool uRepeatTile;\n"
            + "uniform bool uMotionBlur;\n"
            + "vec2 transformUv(vec2 uv, float radian, vec2 position, vec2 anchor, float scale) {\n"
            + "    vec2 t = uv - position;\n"
            + "    t /= vec2(1.0, uRatio);\n"
            + "    t /= max(scale, 0.0001);\n"
            + "    float c = cos(radian);\n"
            + "    float s = sin(radian);\n"
            + "    vec2 rotated = vec2(c * t.x - s * t.y, s * t.x + c * t.y);\n"
            + "    return rotated * vec2(1.0, uRatio) + anchor;\n"
            + "}\n"
            + "vec4 sampleSource(vec2 uv) {\n"
            + "    vec2 curUv = uv;\n"
            + "    if (uRepeatTile) {\n"
            + "        if (curUv.x < 0.0 || curUv.x >= 1.0) {\n"
            + "            curUv.x = mod(1.0 - curUv.x, 1.0);\n"
            + "        }\n"
            + "        if (curUv.y < 0.0 || curUv.y >= 1.0) {\n"
            + "            curUv.y = mod(1.0 - curUv.y, 1.0);\n"
            + "        }\n"
            + "    } else if (curUv.x < 0.0 || curUv.x >= 1.0 || curUv.y < 0.0 || curUv.y >= 1.0) {\n"
            + "        return vec4(0.0);\n"
            + "    }\n"
            + "    return texture2D(sTexture, clamp(curUv, 0.0, 1.0));\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 uv = textureCoordinate;\n"
            + "    vec2 currentUv = transformUv(uv, uRadian, uPosition, uAnchorPosition, uScale);\n"
            + "    vec2 prevUv = transformUv(uv, uPrevRadian, uPrevPosition, uPrevAnchorPosition, uPrevScale);\n"
            + "    vec2 motionVector = (currentUv - prevUv) * uMotionBlurSize;\n"
            + "    vec4 color = sampleSource(currentUv);\n"
            + "    if (uMotionBlur) {\n"
            + "        const int STEPS = 32;\n"
            + "        vec4 accum = vec4(0.0);\n"
            + "        float totalWeight = 0.0;\n"
            + "        for (int i = 0; i < STEPS; i++) {\n"
            + "            float fi = float(i);\n"
            + "            float weight = float(STEPS - i) / float(STEPS);\n"
            + "            vec2 sampleUv = currentUv + fi * motionVector / float(STEPS);\n"
            + "            accum += sampleSource(sampleUv) * weight;\n"
            + "            totalWeight += weight;\n"
            + "        }\n"
            + "        color = accum / max(totalWeight, 0.0001);\n"
            + "    }\n"
            + "    gl_FragColor = vec4(color.rgb, color.a * uOpacity);\n"
            + "}\n";

    private int resolutionHandle = -1;
    private int ratioHandle = -1;
    private int radianHandle = -1;
    private int positionHandle = -1;
    private int anchorPositionHandle = -1;
    private int scaleHandle = -1;
    private int prevRadianHandle = -1;
    private int prevPositionHandle = -1;
    private int prevAnchorPositionHandle = -1;
    private int prevScaleHandle = -1;
    private int motionBlurSizeHandle = -1;
    private int opacityHandle = -1;
    private int repeatTileHandle = -1;
    private int motionBlurHandle = -1;

    private float radian = 0f;
    private float positionX = 0.5f;
    private float positionY = 0.5f;
    private float anchorX = 0.5f;
    private float anchorY = 0.5f;
    private float scale = 1.0f;

    private float prevRadian = 0f;
    private float prevPositionX = 0.5f;
    private float prevPositionY = 0.5f;
    private float prevAnchorX = 0.5f;
    private float prevAnchorY = 0.5f;
    private float prevScale = 1.0f;

    private float startRadian = 0f;
    private float startPositionX = 0.5f;
    private float startPositionY = 0.5f;
    private float startAnchorX = 0.5f;
    private float startAnchorY = 0.5f;
    private float startScale = 1.0f;

    private float endRadian = 0f;
    private float endPositionX = 0.7f;
    private float endPositionY = 0.5f;
    private float endAnchorX = 0.5f;
    private float endAnchorY = 0.5f;
    private float endScale = 1.0f;

    private boolean hasPrevTransform = false;
    private boolean repeatTile = false;
    private boolean motionBlur = true;
    private boolean autoAnimate = true;
    private boolean repeat = false;
    private float motionBlurSize = 1.0f;
    private float opacity = 1.0f;
    private float durationMs = 600f;
    private float shutterWindowMs = 33.0f;
    private float firstPresentationMs = -1f;

    public MotionBlurFilter() {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        resolutionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uResolution");
        ratioHandle = GLES20.glGetUniformLocation(mProgramHandle, "uRatio");
        radianHandle = GLES20.glGetUniformLocation(mProgramHandle, "uRadian");
        positionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPosition");
        anchorPositionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uAnchorPosition");
        scaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uScale");
        prevRadianHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPrevRadian");
        prevPositionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPrevPosition");
        prevAnchorPositionHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPrevAnchorPosition");
        prevScaleHandle = GLES20.glGetUniformLocation(mProgramHandle, "uPrevScale");
        motionBlurSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMotionBlurSize");
        opacityHandle = GLES20.glGetUniformLocation(mProgramHandle, "uOpacity");
        repeatTileHandle = GLES20.glGetUniformLocation(mProgramHandle, "uRepeatTile");
        motionBlurHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMotionBlur");
    }

    @Override
    protected void onDraw(long presentationTimeUs) {
        float ratio = mWidth > 0 ? (float) mHeight / (float) mWidth : 1.0f;
        if (autoAnimate) {
            updateAnimatedTransforms(presentationTimeUs);
        } else if (!hasPrevTransform) {
            syncPreviousToCurrent();
        }
        GLES20.glUniform2f(resolutionHandle, mWidth, mHeight);
        GLES20.glUniform1f(ratioHandle, ratio);
        GLES20.glUniform1f(radianHandle, radian);
        GLES20.glUniform2f(positionHandle, positionX, positionY);
        GLES20.glUniform2f(anchorPositionHandle, anchorX, anchorY);
        GLES20.glUniform1f(scaleHandle, Math.max(0.0001f, scale));
        GLES20.glUniform1f(prevRadianHandle, prevRadian);
        GLES20.glUniform2f(prevPositionHandle, prevPositionX, prevPositionY);
        GLES20.glUniform2f(prevAnchorPositionHandle, prevAnchorX, prevAnchorY);
        GLES20.glUniform1f(prevScaleHandle, Math.max(0.0001f, prevScale));
        GLES20.glUniform1f(motionBlurSizeHandle, Math.max(0.0f, motionBlurSize));
        GLES20.glUniform1f(opacityHandle, clamp(opacity, 0.0f, 1.0f));
        GLES20.glUniform1i(repeatTileHandle, repeatTile ? 1 : 0);
        boolean enableBlur = motionBlur && hasMotionDelta();
        GLES20.glUniform1i(motionBlurHandle, enableBlur ? 1 : 0);
        if (!autoAnimate) {
            syncPreviousToCurrent();
        }
    }

    @Override
    public void setup() {
        hasPrevTransform = false;
        firstPresentationMs = -1f;
        super.setup();
    }

    @Override
    public void release() {
        hasPrevTransform = false;
        firstPresentationMs = -1f;
        super.release();
    }

    public MotionBlurFilter setPosition(float x, float y) {
        autoAnimate = false;
        this.positionX = x;
        this.positionY = y;
        return this;
    }

    public MotionBlurFilter setAnchorPosition(float x, float y) {
        autoAnimate = false;
        this.anchorX = x;
        this.anchorY = y;
        return this;
    }

    public MotionBlurFilter setScale(float scale) {
        autoAnimate = false;
        this.scale = Math.max(0.0001f, scale);
        return this;
    }

    public MotionBlurFilter setRotationDegrees(float degrees) {
        autoAnimate = false;
        this.radian = (float) (-degrees / 180.0 * Math.PI);
        return this;
    }

    public MotionBlurFilter setRotationRadians(float radians) {
        autoAnimate = false;
        this.radian = radians;
        return this;
    }

    public MotionBlurFilter setDurationMs(float durationMs) {
        this.durationMs = Math.max(1f, durationMs);
        return this;
    }

    public MotionBlurFilter setRepeat(boolean repeat) {
        this.repeat = repeat;
        return this;
    }

    public MotionBlurFilter setShutterWindowMs(float shutterWindowMs) {
        this.shutterWindowMs = Math.max(1f, shutterWindowMs);
        return this;
    }

    public MotionBlurFilter setAutoAnimate(boolean autoAnimate) {
        this.autoAnimate = autoAnimate;
        return this;
    }

    public MotionBlurFilter setStartPosition(float x, float y) {
        this.startPositionX = x;
        this.startPositionY = y;
        return this;
    }

    public MotionBlurFilter setEndPosition(float x, float y) {
        this.endPositionX = x;
        this.endPositionY = y;
        return this;
    }

    public MotionBlurFilter setStartAnchorPosition(float x, float y) {
        this.startAnchorX = x;
        this.startAnchorY = y;
        return this;
    }

    public MotionBlurFilter setEndAnchorPosition(float x, float y) {
        this.endAnchorX = x;
        this.endAnchorY = y;
        return this;
    }

    public MotionBlurFilter setStartScale(float scale) {
        this.startScale = Math.max(0.0001f, scale);
        return this;
    }

    public MotionBlurFilter setEndScale(float scale) {
        this.endScale = Math.max(0.0001f, scale);
        return this;
    }

    public MotionBlurFilter setStartRotationDegrees(float degrees) {
        this.startRadian = (float) (-degrees / 180.0 * Math.PI);
        return this;
    }

    public MotionBlurFilter setEndRotationDegrees(float degrees) {
        this.endRadian = (float) (-degrees / 180.0 * Math.PI);
        return this;
    }

    public MotionBlurFilter setStartRotationRadians(float radians) {
        this.startRadian = radians;
        return this;
    }

    public MotionBlurFilter setEndRotationRadians(float radians) {
        this.endRadian = radians;
        return this;
    }

    public MotionBlurFilter setTransformRange(
            float fromX, float fromY,
            float toX, float toY,
            float fromScale, float toScale) {
        this.startPositionX = fromX;
        this.startPositionY = fromY;
        this.endPositionX = toX;
        this.endPositionY = toY;
        this.startScale = Math.max(0.0001f, fromScale);
        this.endScale = Math.max(0.0001f, toScale);
        return this;
    }

    public MotionBlurFilter setMotionBlurSize(float motionBlurSize) {
        this.motionBlurSize = Math.max(0.0f, motionBlurSize);
        return this;
    }

    public MotionBlurFilter setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0.0f, 1.0f);
        return this;
    }

    public MotionBlurFilter setRepeatTile(boolean repeatTile) {
        this.repeatTile = repeatTile;
        return this;
    }

    public MotionBlurFilter setMotionBlurEnabled(boolean motionBlur) {
        this.motionBlur = motionBlur;
        return this;
    }

    public MotionBlurFilter resetPreviousTransform() {
        hasPrevTransform = false;
        return this;
    }

    public MotionBlurFilter restartAnimation() {
        firstPresentationMs = -1f;
        hasPrevTransform = false;
        return this;
    }

    private boolean hasMotionDelta() {
        return Math.abs(radian - prevRadian) > 0.0001f
                || Math.abs(positionX - prevPositionX) > 0.0001f
                || Math.abs(positionY - prevPositionY) > 0.0001f
                || Math.abs(anchorX - prevAnchorX) > 0.0001f
                || Math.abs(anchorY - prevAnchorY) > 0.0001f
                || Math.abs(scale - prevScale) > 0.0001f;
    }

    private void syncPreviousToCurrent() {
        prevRadian = radian;
        prevPositionX = positionX;
        prevPositionY = positionY;
        prevAnchorX = anchorX;
        prevAnchorY = anchorY;
        prevScale = scale;
        hasPrevTransform = true;
    }

    private void updateAnimatedTransforms(long presentationTimeUs) {
        float nowMs = presentationTimeUs / 1_000_000f;
        if (firstPresentationMs < 0f) {
            firstPresentationMs = nowMs;
        }
        float elapsedMs = Math.max(0f, nowMs - firstPresentationMs);
        float prevElapsedMs = Math.max(0f, elapsedMs - shutterWindowMs);
        applyTransformAt(elapsedMs, false);
        applyTransformAt(prevElapsedMs, true);
        hasPrevTransform = true;
    }

    private void applyTransformAt(float elapsedMs, boolean previous) {
        float progress;
        if (repeat) {
            progress = (elapsedMs % durationMs) / durationMs;
        } else {
            progress = clamp(elapsedMs / durationMs, 0f, 1f);
        }
        float eased = easeInOut(progress);
        float outRadian = lerp(startRadian, endRadian, eased);
        float outPositionX = lerp(startPositionX, endPositionX, eased);
        float outPositionY = lerp(startPositionY, endPositionY, eased);
        float outAnchorX = lerp(startAnchorX, endAnchorX, eased);
        float outAnchorY = lerp(startAnchorY, endAnchorY, eased);
        float outScale = lerp(startScale, endScale, eased);
        if (previous) {
            prevRadian = outRadian;
            prevPositionX = outPositionX;
            prevPositionY = outPositionY;
            prevAnchorX = outAnchorX;
            prevAnchorY = outAnchorY;
            prevScale = outScale;
        } else {
            radian = outRadian;
            positionX = outPositionX;
            positionY = outPositionY;
            anchorX = outAnchorX;
            anchorY = outAnchorY;
            scale = outScale;
        }
    }

    private static float easeInOut(float p) {
        float t = clamp(p, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * clamp(t, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
