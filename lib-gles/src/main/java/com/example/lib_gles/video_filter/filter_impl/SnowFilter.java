package com.example.lib_gles.video_filter.filter_impl;

import android.graphics.Color;
import android.opengl.GLES20;

import com.example.lib_gles.video_filter.core.EFramebufferObject;
import com.example.lib_gles.video_filter.core.filter.GlFilter;
import com.example.lib_gles.video_filter.utils.EglUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Random;

/**
 * SnowFilter:
 * - Based on SnowFilter7 particle-system pipeline.
 * - Lower density by default.
 * - Snow appears only near left/right side bands with configurable width.
 */
public class SnowFilter extends GlFilter {

    private static final int MAX_PARTICLES = 1000;
    private static final int FLOATS_PER_PARTICLE = 4; // clipX, clipY, life, sizePx

    private static final String PARTICLE_VERTEX_SHADER = ""
            + "attribute vec2 aPosition;\n"
            + "attribute float aLife;\n"
            + "attribute float aSize;\n"
            + "varying float vLife;\n"
            + "void main() {\n"
            + "    vLife = clamp(aLife, 0.0, 1.0);\n"
            + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "    gl_PointSize = max(1.0, aSize) * (0.5 + 0.5 * vLife);\n"
            + "}\n";

    private static final String PARTICLE_FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying float vLife;\n"
            + "uniform vec3 uSnowTint;\n"
            + "uniform float uOpacity;\n"
            + "void main() {\n"
            + "    vec2 coord = gl_PointCoord - vec2(0.5, 0.5);\n"
            + "    float dist = length(coord);\n"
            + "    if (dist > 0.5) discard;\n"
            + "    float edge = 1.0 - smoothstep(0.38, 0.5, dist);\n"
            + "    float alpha = edge * vLife * clamp(uOpacity, 0.0, 1.0);\n"
            + "    vec3 lifeCol = mix(vec3(1.0, 1.0, 1.0), vec3(0.8, 0.9, 1.0), 1.0 - vLife);\n"
            + "    gl_FragColor = vec4(lifeCol * uSnowTint, alpha);\n"
            + "}\n";

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float maxLife;
        float size;
        float flutterPhase;
        float flutterFreq;
        float flutterAmp;
        float sideFlag; // 0: left side band, 1: right side band
    }

    private final Particle[] particles = new Particle[MAX_PARTICLES];
    private final Random random = new Random(11L);
    private final float[] particleData = new float[MAX_PARTICLES * FLOATS_PER_PARTICLE];
    private final FloatBuffer particleBuffer = ByteBuffer
            .allocateDirect(MAX_PARTICLES * FLOATS_PER_PARTICLE * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();

    private int particleProgram = 0;
    private int particleVbo = 0;

    private int aPositionHandle = -1;
    private int aLifeHandle = -1;
    private int aSizeHandle = -1;
    private int uSnowTintHandle = -1;
    private int uOpacityHandle = -1;

    private long firstPresentationUs = Long.MIN_VALUE;
    private long lastPresentationUs = Long.MIN_VALUE;

    private float snowR = 1.0f;
    private float snowG = 1.0f;
    private float snowB = 1.0f;
    private float opacity = 0.88f;
    private float intensity = 1.0f;
    private float pointSize = 14.0f;
    private float wind = 0.02f;
    private int particleCount = 260; // lower default density
    private float sizeMin = 0.7f;
    private float sizeMax = 1.25f;
    private float speedMin = 2.2f;
    private float speedMax = 5.4f;
    private float lifeMin = 2.2f;
    private float lifeMax = 5.0f;
    // width ratio for each side band in normalized [0,1] screen width.
    private float sideBandWidthRatio = 0.18f;

    public SnowFilter() {
        super();
        for (int i = 0; i < MAX_PARTICLES; i++) {
            particles[i] = new Particle();
        }
    }

    @Override
    public void setup() {
        super.setup();
        initParticleProgram();
        initParticles();
        firstPresentationUs = Long.MIN_VALUE;
        lastPresentationUs = Long.MIN_VALUE;
    }

    private void initParticleProgram() {
        releaseParticleProgram();
        int vs = EglUtil.loadShader(PARTICLE_VERTEX_SHADER, GLES20.GL_VERTEX_SHADER);
        int fs = EglUtil.loadShader(PARTICLE_FRAGMENT_SHADER, GLES20.GL_FRAGMENT_SHADER);
        particleProgram = EglUtil.createProgram(vs, fs);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

        aPositionHandle = GLES20.glGetAttribLocation(particleProgram, "aPosition");
        aLifeHandle = GLES20.glGetAttribLocation(particleProgram, "aLife");
        aSizeHandle = GLES20.glGetAttribLocation(particleProgram, "aSize");
        uSnowTintHandle = GLES20.glGetUniformLocation(particleProgram, "uSnowTint");
        uOpacityHandle = GLES20.glGetUniformLocation(particleProgram, "uOpacity");

        int[] buffers = new int[1];
        GLES20.glGenBuffers(1, buffers, 0);
        particleVbo = buffers[0];
    }

    private void releaseParticleProgram() {
        if (particleVbo != 0) {
            GLES20.glDeleteBuffers(1, new int[]{particleVbo}, 0);
            particleVbo = 0;
        }
        if (particleProgram != 0) {
            GLES20.glDeleteProgram(particleProgram);
            particleProgram = 0;
        }
    }

    private float sideWidth() {
        return clamp(sideBandWidthRatio, 0.02f, 0.48f);
    }

    private void initParticles() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            resetParticle(particles[i], true);
        }
    }

    private void resetParticle(Particle p, boolean randomY) {
        float band = sideWidth();
        p.sideFlag = random.nextBoolean() ? 0f : 1f;
        float localX = random.nextFloat() * band;
        p.x = p.sideFlag < 0.5f ? localX : (1.0f - localX);

        p.y = randomY ? random.nextFloat() : (1.0f + random.nextFloat() * 0.15f);

        p.vx = (random.nextFloat() - 0.5f) * 0.016f;
        p.vy = (0.10f + random.nextFloat() * 0.20f);

        p.life = 1.0f;
        p.maxLife = lerp(lifeMin, lifeMax, random.nextFloat());
        p.size = lerp(sizeMin, sizeMax, random.nextFloat());

        p.flutterPhase = random.nextFloat() * 6.2831853f;
        p.flutterFreq = lerp(0.8f, 2.2f, random.nextFloat());
        p.flutterAmp = lerp(0.001f, 0.008f, random.nextFloat());
    }

    private void keepInSideBand(Particle p) {
        float band = sideWidth();
        float leftMin = 0f;
        float leftMax = band;
        float rightMin = 1f - band;
        float rightMax = 1f;

        if (p.sideFlag < 0.5f) {
            if (p.x < leftMin) {
                p.x = leftMin + (leftMin - p.x) * 0.25f;
                p.vx = Math.abs(p.vx) * 0.35f;
            } else if (p.x > leftMax) {
                p.x = leftMax - (p.x - leftMax) * 0.25f;
                p.vx = -Math.abs(p.vx) * 0.35f;
            }
            p.x = clamp(p.x, leftMin, leftMax);
        } else {
            if (p.x < rightMin) {
                p.x = rightMin + (rightMin - p.x) * 0.25f;
                p.vx = Math.abs(p.vx) * 0.35f;
            } else if (p.x > rightMax) {
                p.x = rightMax - (p.x - rightMax) * 0.25f;
                p.vx = -Math.abs(p.vx) * 0.35f;
            }
            p.x = clamp(p.x, rightMin, rightMax);
        }
    }

    private void updateParticles(float dtSec, float timeSec) {
        final int count = (int) clamp(particleCount, 1, MAX_PARTICLES);
        final float speedScale = Math.max(0f, intensity);
        final float vMin = Math.max(0.1f, Math.min(speedMin, speedMax));
        final float vMax = Math.max(vMin, Math.max(speedMin, speedMax));
        final float gustGlobal = (float) (
                Math.sin(timeSec * 0.45f) * 0.6f
                        + Math.sin(timeSec * 0.93f + 1.7f) * 0.4f
        );

        for (int i = 0; i < count; i++) {
            Particle p = particles[i];

            p.life -= dtSec / Math.max(0.1f, p.maxLife);
            if (p.life <= 0f) {
                resetParticle(p, true);
                continue;
            }

            float vyNow = p.vy * lerp(vMin, vMax, 0.5f) / 4.0f;
            p.y += vyNow * speedScale * dtSec;

            float flutter = (float) Math.sin(timeSec * p.flutterFreq + p.flutterPhase) * p.flutterAmp;
            float gustLocal = (float) Math.sin(timeSec * (0.55f + p.flutterFreq * 0.15f) + p.flutterPhase * 0.7f);
            float windNow = wind * 0.02f * (1.0f + 0.30f * gustGlobal + 0.20f * gustLocal);
            p.x += (p.vx + windNow + flutter) * dtSec;

            keepInSideBand(p);

            if (p.y > 1.1f) {
                resetParticle(p, true);
            }
        }
    }

    private void buildParticleBuffer() {
        final int count = (int) clamp(particleCount, 1, MAX_PARTICLES);
        int index = 0;
        float sizePx = Math.max(1f, pointSize);
        for (int i = 0; i < count; i++) {
            Particle p = particles[i];
            float clipX = p.x * 2f - 1f;
            float clipY = p.y * 2f - 1f;

            particleData[index++] = clipX;
            particleData[index++] = clipY;
            particleData[index++] = clamp(p.life, 0f, 1f);
            particleData[index++] = sizePx * Math.max(0.1f, p.size);
        }

        particleBuffer.clear();
        particleBuffer.put(particleData, 0, count * FLOATS_PER_PARTICLE);
        particleBuffer.position(0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo);
        GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                count * FLOATS_PER_PARTICLE * 4,
                particleBuffer,
                GLES20.GL_DYNAMIC_DRAW
        );
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void drawParticles() {
        if (particleProgram == 0 || particleVbo == 0) {
            return;
        }

        final int count = (int) clamp(particleCount, 1, MAX_PARTICLES);

        GLES20.glUseProgram(particleProgram);
        GLES20.glUniform3f(uSnowTintHandle, clamp01(snowR), clamp01(snowG), clamp01(snowB));
        GLES20.glUniform1f(uOpacityHandle, clamp(opacity, 0f, 1f));

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo);
        int stride = FLOATS_PER_PARTICLE * 4;

        GLES20.glEnableVertexAttribArray(aPositionHandle);
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, stride, 0);

        GLES20.glEnableVertexAttribArray(aLifeHandle);
        GLES20.glVertexAttribPointer(aLifeHandle, 1, GLES20.GL_FLOAT, false, stride, 2 * 4);

        GLES20.glEnableVertexAttribArray(aSizeHandle);
        GLES20.glVertexAttribPointer(aSizeHandle, 1, GLES20.GL_FLOAT, false, stride, 3 * 4);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);

        GLES20.glDisableVertexAttribArray(aPositionHandle);
        GLES20.glDisableVertexAttribArray(aLifeHandle);
        GLES20.glDisableVertexAttribArray(aSizeHandle);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    @Override
    public int draw(int sourceTextId, EFramebufferObject fbo, long presentationTimeUs, Map<String, Integer> extraTextureIds) {
        int outTex = super.draw(sourceTextId, fbo, presentationTimeUs, extraTextureIds);

        if (firstPresentationUs == Long.MIN_VALUE) {
            firstPresentationUs = Math.max(0L, presentationTimeUs);
        }
        if (lastPresentationUs == Long.MIN_VALUE) {
            lastPresentationUs = presentationTimeUs;
        }

        long relUs = Math.max(0L, presentationTimeUs - firstPresentationUs);
        long deltaUs = Math.max(0L, presentationTimeUs - lastPresentationUs);
        lastPresentationUs = presentationTimeUs;

        float dt = deltaUs > 0 ? (deltaUs / 1_000_000f) : (1f / 30f);
        dt = clamp(dt, 0f, 1f / 12f);
        float tSec = relUs / 1_000_000f;

        updateParticles(dt, tSec);
        buildParticleBuffer();
        drawParticles();

        return outTex;
    }

    @Override
    public void release() {
        releaseParticleProgram();
        firstPresentationUs = Long.MIN_VALUE;
        lastPresentationUs = Long.MIN_VALUE;
        super.release();
    }

    public SnowFilter setSnowColor(int colorInt) {
        this.snowR = Color.red(colorInt) / 255f;
        this.snowG = Color.green(colorInt) / 255f;
        this.snowB = Color.blue(colorInt) / 255f;
        return this;
    }

    public SnowFilter setSnowColor(float r, float g, float b) {
        this.snowR = clamp01(r);
        this.snowG = clamp01(g);
        this.snowB = clamp01(b);
        return this;
    }

    public SnowFilter setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0f, 1f);
        return this;
    }

    public SnowFilter setIntensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public SnowFilter setPointSize(float pointSize) {
        this.pointSize = Math.max(1f, pointSize);
        return this;
    }

    public SnowFilter setWind(float wind) {
        this.wind = wind;
        return this;
    }

    public SnowFilter setParticleCount(int particleCount) {
        this.particleCount = (int) clamp(particleCount, 1, MAX_PARTICLES);
        return this;
    }

    public SnowFilter setSizeRange(float min, float max) {
        this.sizeMin = min;
        this.sizeMax = max;
        return this;
    }

    public SnowFilter setSpeedRange(float min, float max) {
        this.speedMin = min;
        this.speedMax = max;
        return this;
    }

    public SnowFilter setLifeRange(float minSec, float maxSec) {
        this.lifeMin = minSec;
        this.lifeMax = maxSec;
        return this;
    }

    /**
     * Width ratio of each side band, range [0.02, 0.48].
     * Example: 0.18 means left 18% + right 18% area can emit snow.
     */
    public SnowFilter setSideBandWidthRatio(float widthRatio) {
        this.sideBandWidthRatio = clamp(widthRatio, 0.02f, 0.48f);
        return this;
    }

    // Compatibility with previous API style.
    public SnowFilter setDrift(float drift) {
        return this;
    }

    // Compatibility with previous API style.
    public SnowFilter setSoftness(float softness) {
        return this;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }
}
