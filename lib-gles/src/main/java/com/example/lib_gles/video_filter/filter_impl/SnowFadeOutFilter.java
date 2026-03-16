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
 * SnowFadeOutFilter:
 * Particle burst diffusion from center area.
 * - No snowfall trajectory.
 * - Particles emit near center, move radially outward, grow in size, then fade.
 */
public class SnowFadeOutFilter extends GlFilter {

    private static final int MAX_PARTICLES = 1000;
    private static final int FLOATS_PER_PARTICLE = 4; // clipX, clipY, life, sizePx

    private static final String PARTICLE_VERTEX_SHADER = ""
            + "attribute vec2 aPosition;\n"
            + "attribute float aLife;\n"
            + "attribute float aSize;\n"
            + "uniform float uEdgeBlurStart;\n"
            + "uniform float uEdgeBlurStrength;\n"
            + "varying float vLife;\n"
            + "varying float vBlur;\n"
            + "void main() {\n"
            + "    vLife = clamp(aLife, 0.0, 1.0);\n"
            + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "    float edge = abs(aPosition.x);\n"
            + "    vBlur = smoothstep(clamp(uEdgeBlurStart, 0.0, 0.99), 1.0, edge);\n"
            + "    gl_PointSize = max(1.0, aSize) * (1.0 + vBlur * max(0.0, uEdgeBlurStrength));\n"
            + "}\n";

    private static final String PARTICLE_FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying float vLife;\n"
            + "varying float vBlur;\n"
            + "uniform vec3 uTint;\n"
            + "uniform float uOpacity;\n"
            + "void main() {\n"
            + "    vec2 coord = gl_PointCoord - vec2(0.5, 0.5);\n"
            + "    float dist = length(coord);\n"
            + "    if (dist > 0.54) discard;\n"
            + "    float sigma = mix(0.19, 0.33, clamp(vBlur, 0.0, 1.0));\n"
            + "    float gaussian = exp(-(dist * dist) / max(2.0 * sigma * sigma, 1e-4));\n"
            + "    float edgeSoft = 1.0 - smoothstep(0.40, mix(0.53, 0.56, vBlur), dist);\n"
            + "    float alpha = gaussian * edgeSoft * vLife * clamp(uOpacity, 0.0, 1.0);\n"
            + "    alpha *= mix(1.0, 0.72, vBlur);\n"
            + "    vec3 lifeCol = mix(vec3(1.0), vec3(0.85, 0.92, 1.0), 1.0 - vLife);\n"
            + "    gl_FragColor = vec4(lifeCol * uTint, alpha);\n"
            + "}\n";

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float elapsed;
        float maxLife;
        float baseSize;
        float growth;
        float swirl;
    }

    private final Particle[] particles = new Particle[MAX_PARTICLES];
    private final Random random = new Random(19L);
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
    private int uTintHandle = -1;
    private int uOpacityHandle = -1;
    private int uEdgeBlurStartHandle = -1;
    private int uEdgeBlurStrengthHandle = -1;

    private long firstPresentationUs = Long.MIN_VALUE;
    private long lastPresentationUs = Long.MIN_VALUE;

    private float tintR = 1.0f;
    private float tintG = 1.0f;
    private float tintB = 1.0f;
    private float opacity = 0.82f;
    private float intensity = 1.0f;
    private int particleCount = 180;

    private float pointSize = 10.0f;
    private float sizeMin = 0.6f;
    private float sizeMax = 1.4f;
    private float growthMin = 0.35f;
    private float growthMax = 1.2f;
    private float speedMin = 0.08f;
    private float speedMax = 0.28f;
    private float lifeMin = 0.9f;
    private float lifeMax = 1.8f;
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    // Spawn from a dispersed middle zone (not a tiny center point).
    private float spawnSpreadX = 0.42f;
    private float spawnSpreadY = 0.32f;
    private float edgeBlurStart = 0.72f;
    private float edgeBlurStrength = 0.7f;

    public SnowFadeOutFilter() {
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
        uTintHandle = GLES20.glGetUniformLocation(particleProgram, "uTint");
        uOpacityHandle = GLES20.glGetUniformLocation(particleProgram, "uOpacity");
        uEdgeBlurStartHandle = GLES20.glGetUniformLocation(particleProgram, "uEdgeBlurStart");
        uEdgeBlurStrengthHandle = GLES20.glGetUniformLocation(particleProgram, "uEdgeBlurStrength");

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

    private void initParticles() {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            resetParticle(particles[i]);
        }
    }

    private void resetParticle(Particle p) {
        float sx = centerX + (random.nextFloat() - 0.5f) * spawnSpreadX;
        float sy = centerY + (random.nextFloat() - 0.5f) * spawnSpreadY;

        p.x = clamp(sx, 0f, 1f);
        p.y = clamp(sy, 0f, 1f);

        // Radial outward direction from center with slight random deviation.
        float dx = p.x - centerX;
        float dy = p.y - centerY;
        float baseDir = (float) Math.atan2(dy, dx);
        if (Math.abs(dx) + Math.abs(dy) < 0.02f) {
            baseDir = random.nextFloat() * 6.2831853f;
        }
        float dir = baseDir + (random.nextFloat() - 0.5f) * 0.70f;
        float speed = lerp(speedMin, speedMax, random.nextFloat());
        p.vx = (float) Math.cos(dir) * speed;
        p.vy = (float) Math.sin(dir) * speed;

        p.life = 1.0f;
        p.elapsed = 0.0f;
        p.maxLife = lerp(lifeMin, lifeMax, random.nextFloat());
        p.baseSize = lerp(sizeMin, sizeMax, random.nextFloat());
        p.growth = lerp(growthMin, growthMax, random.nextFloat());
        p.swirl = (random.nextFloat() - 0.5f) * 1.2f;
    }

    private void updateParticles(float dtSec, float timeSec) {
        final int count = (int) clamp(particleCount, 1, MAX_PARTICLES);
        final float speedScale = Math.max(0f, intensity);

        for (int i = 0; i < count; i++) {
            Particle p = particles[i];

            p.elapsed += dtSec;
            p.life = clamp(1.0f - p.elapsed / Math.max(0.1f, p.maxLife), 0f, 1f);

            float age = 1.0f - p.life;
            float swirlX = (float) Math.sin(timeSec * 1.8f + i * 0.31f) * 0.01f * p.swirl;
            float swirlY = (float) Math.cos(timeSec * 1.5f + i * 0.27f) * 0.01f * p.swirl;

            p.x += (p.vx + swirlX) * speedScale * dtSec;
            p.y += (p.vy + swirlY) * speedScale * dtSec;

            // Keep particle visible until it leaves the screen with margin, then respawn.
            if (p.x < -0.20f || p.x > 1.20f || p.y < -0.20f || p.y > 1.20f) {
                resetParticle(p);
            }
        }
    }

    private void buildParticleBuffer() {
        final int count = (int) clamp(particleCount, 1, MAX_PARTICLES);
        int index = 0;
        float sizePx = Math.max(1f, pointSize);

        for (int i = 0; i < count; i++) {
            Particle p = particles[i];
            float age = clamp(p.elapsed / Math.max(0.1f, p.maxLife), 0f, 1f);
            float grow = 1.0f + p.growth * age;
            float clipX = p.x * 2f - 1f;
            float clipY = p.y * 2f - 1f;

            // Fade-in quickly, then keep visible (no end fade-out before leaving screen).
            float fadeIn = smooth01(age / 0.18f);
            float lifeAlpha = clamp(0.35f + 0.65f * fadeIn, 0f, 1f);

            particleData[index++] = clipX;
            particleData[index++] = clipY;
            particleData[index++] = lifeAlpha;
            particleData[index++] = sizePx * Math.max(0.1f, p.baseSize) * grow;
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
        GLES20.glUniform3f(uTintHandle, clamp01(tintR), clamp01(tintG), clamp01(tintB));
        GLES20.glUniform1f(uOpacityHandle, clamp(opacity, 0f, 1f));
        GLES20.glUniform1f(uEdgeBlurStartHandle, clamp(edgeBlurStart, 0f, 0.99f));
        GLES20.glUniform1f(uEdgeBlurStrengthHandle, Math.max(0f, edgeBlurStrength));

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

    public SnowFadeOutFilter setTint(int colorInt) {
        this.tintR = Color.red(colorInt) / 255f;
        this.tintG = Color.green(colorInt) / 255f;
        this.tintB = Color.blue(colorInt) / 255f;
        return this;
    }

    public SnowFadeOutFilter setTint(float r, float g, float b) {
        this.tintR = clamp01(r);
        this.tintG = clamp01(g);
        this.tintB = clamp01(b);
        return this;
    }

    public SnowFadeOutFilter setOpacity(float opacity) {
        this.opacity = clamp(opacity, 0f, 1f);
        return this;
    }

    public SnowFadeOutFilter setIntensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
        return this;
    }

    public SnowFadeOutFilter setParticleCount(int particleCount) {
        this.particleCount = (int) clamp(particleCount, 1, MAX_PARTICLES);
        return this;
    }

    public SnowFadeOutFilter setPointSize(float pointSize) {
        this.pointSize = Math.max(1f, pointSize);
        return this;
    }

    public SnowFadeOutFilter setSizeRange(float min, float max) {
        this.sizeMin = min;
        this.sizeMax = max;
        return this;
    }

    public SnowFadeOutFilter setGrowthRange(float min, float max) {
        this.growthMin = min;
        this.growthMax = max;
        return this;
    }

    public SnowFadeOutFilter setSpeedRange(float min, float max) {
        this.speedMin = min;
        this.speedMax = max;
        return this;
    }

    public SnowFadeOutFilter setLifeRange(float minSec, float maxSec) {
        this.lifeMin = minSec;
        this.lifeMax = maxSec;
        return this;
    }

    public SnowFadeOutFilter setCenter(float x, float y) {
        this.centerX = clamp(x, 0f, 1f);
        this.centerY = clamp(y, 0f, 1f);
        return this;
    }

    public SnowFadeOutFilter setSpawnRadius(float radius) {
        float r = clamp(radius, 0.001f, 0.45f);
        this.spawnSpreadX = clamp(r * 2.0f, 0.02f, 0.9f);
        this.spawnSpreadY = clamp(r * 2.0f, 0.02f, 0.9f);
        return this;
    }

    public SnowFadeOutFilter setSpawnSpread(float spreadX, float spreadY) {
        this.spawnSpreadX = clamp(spreadX, 0.02f, 0.9f);
        this.spawnSpreadY = clamp(spreadY, 0.02f, 0.9f);
        return this;
    }

    /**
     * Near left/right edges, particles become out-of-focus gradually.
     * start is based on |clipX|, 0=center, 1=edge. Typical 0.68~0.82.
     */
    public SnowFadeOutFilter setEdgeBlur(float start, float strength) {
        this.edgeBlurStart = clamp(start, 0f, 0.99f);
        this.edgeBlurStrength = Math.max(0f, strength);
        return this;
    }

    // Compatibility for old call sites.
    public SnowFadeOutFilter setSnowColor(int colorInt) {
        return setTint(colorInt);
    }

    // Compatibility for old call sites.
    public SnowFadeOutFilter setSnowColor(float r, float g, float b) {
        return setTint(r, g, b);
    }

    private static float smooth01(float x) {
        float t = clamp(x, 0f, 1f);
        return t * t * (3f - 2f * t);
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
