package com.example.lib_gles.video_filter.filter_impl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.SystemClock;

import com.example.lib_gles.video_filter.core.filter.GlFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Random;

/**
 * Multi-heart version of HeartPopFlashFilter.
 * Hearts are scattered in 4 quadrants with simple spacing constraints.
 */
public class MultiHeartPopFlashFilter extends GlFilter {

    private static final String DEFAULT_ASSET_PATH = "MultiInputHeartUp/heart_up_2/heart_up_2_0001.png";
    private static final int MAX_HEARTS = 16;

    private static final String FRAGMENT_SHADER = ""
            + "precision mediump float;\n"
            + "varying vec2 textureCoordinate;\n"
            + "uniform sampler2D sTexture;\n"
            + "uniform sampler2D uHeartTexture;\n"
            + "uniform int uHeartCount;\n"
            + "uniform vec2 uHeartOrigin[" + MAX_HEARTS + "];\n"
            + "uniform vec2 uHeartSize[" + MAX_HEARTS + "];\n"
            + "uniform float uHeartAlpha[" + MAX_HEARTS + "];\n"
            + "void main() {\n"
            + "    vec4 outColor = texture2D(sTexture, textureCoordinate);\n"
            + "    for (int i = 0; i < " + MAX_HEARTS + "; i++) {\n"
            + "        if (i >= uHeartCount) {\n"
            + "            continue;\n"
            + "        }\n"
            + "        vec2 localCoord = (textureCoordinate - uHeartOrigin[i]) / uHeartSize[i];\n"
            + "        float inX = step(0.0, localCoord.x) * step(localCoord.x, 1.0);\n"
            + "        float inY = step(0.0, localCoord.y) * step(localCoord.y, 1.0);\n"
            + "        float inRect = inX * inY;\n"
            + "        vec2 heartCoord = vec2(localCoord.x, 1.0 - localCoord.y);\n"
            + "        vec4 heart = texture2D(uHeartTexture, heartCoord);\n"
            + "        float luma = dot(heart.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "        float alphaMask = smoothstep(0.02, 0.15, heart.a);\n"
            + "        float brightMask = smoothstep(0.22, 0.62, luma) * 0.85;\n"
            + "        float mask = max(alphaMask, brightMask);\n"
            + "        float a = mask * uHeartAlpha[i] * inRect;\n"
            + "        outColor = mix(outColor, vec4(heart.rgb, 1.0), a);\n"
            + "    }\n"
            + "    gl_FragColor = outColor;\n"
            + "}\n";

    private final Context context;
    private final String assetPath;
    private final Random random = new Random(42L);

    private int heartTextureId = 0;
    private Bitmap heartBitmap;
    private int heartWidth = 0;
    private int heartHeight = 0;

    private int heartSamplerHandle = -1;
    private int heartCountHandle = -1;
    private int heartOriginHandle = -1; // uHeartOrigin[0]
    private int heartSizeHandle = -1;   // uHeartSize[0]
    private int heartAlphaHandle = -1;  // uHeartAlpha[0]

    private long firstPresentationUs = Long.MIN_VALUE;
    private long startTimeMs = -1L;
    private long lastLayoutCycle = Long.MIN_VALUE;
    // 1_000 for microseconds->ms, 1_000_000 for nanoseconds->ms.
    private float timelineToMsDivisor = 1000f;
    private boolean useWallClockFallback = true;

    private int heartsPerQuadrant = 2; // total = 8 by default
    private float minHeartSpacing = 0.10f;
    private float centerPadding = 0.08f;

    // 正常缩小阶段时长：心形从正常大小缩到最小大小的时间

    // 渐显放大阶段时长：心形从初始大小放大到正常大小的时间
    private float fadeInShrinkDurationMs = 300f;
    // 渐显放大阶段的初始缩放比例
    private float fadeInStartScale = 0.7f;
    // 渐显放大阶段的初始透明度
    private float fadeInStartAlpha = 0.2f;
    // 渐显放大阶段的结束透明度
    private float fadeInEndAlpha = 0.65f;
    private float normalShrinkDurationMs = 1100f;
    // 突然放大后缩小阶段时长：在“瞬间回到正常大小”后再缩小到0并淡出的时间
    private float flashShrinkDurationMs = 320f;
    // 多颗心形之间的最大错峰延迟时间（毫秒）
    private float maxStaggerMs = 280f;
    // 单颗心形的循环周期：每隔多久重复一轮（含错峰后单独计算）
    private float repeatIntervalMs = 2200f;

    private float normalScale = 0.62f;
    private float minScaleFactor = 0.20f;
    private float maxDisplayWidthRatio = 0.22f;
    private float maxDisplayHeightRatio = 0.22f;

    private final float[] centersX = new float[MAX_HEARTS];
    private final float[] centersY = new float[MAX_HEARTS];
    private final float[] delayMs = new float[MAX_HEARTS];
    private final float[] scaleMul = new float[MAX_HEARTS];

    private final float[] origins = new float[MAX_HEARTS * 2];
    private final float[] sizes = new float[MAX_HEARTS * 2];
    private final float[] alphas = new float[MAX_HEARTS];

    public MultiHeartPopFlashFilter(Context context) {
        this(context, DEFAULT_ASSET_PATH);
    }

    public MultiHeartPopFlashFilter(Context context, String assetPath) {
        super(VERTEX_SHADER, FRAGMENT_SHADER);
        this.context = context.getApplicationContext();
        this.assetPath = assetPath;
    }

    @Override
    public void initProgramHandle() {
        super.initProgramHandle();
        heartSamplerHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeartTexture");
        heartCountHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeartCount");
        heartOriginHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeartOrigin[0]");
        heartSizeHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeartSize[0]");
        heartAlphaHandle = GLES20.glGetUniformLocation(mProgramHandle, "uHeartAlpha[0]");
        uploadHeartTextureIfNeed();
        regenerateLayout();
    }

    @Override
    public void onDraw(long presentationTimeUs) {
        if (mWidth <= 0 || mHeight <= 0) {
            return;
        }
        uploadHeartTextureIfNeed();
        if (heartTextureId == 0 || heartWidth <= 0 || heartHeight <= 0) {
            return;
        }

        if (firstPresentationUs == Long.MIN_VALUE && presentationTimeUs > 0L) {
            firstPresentationUs = presentationTimeUs;
            startTimeMs = 0L;
        }
        float elapsedMs;
        if (presentationTimeUs > 0L && firstPresentationUs != Long.MIN_VALUE) {
            long delta = Math.max(0L, presentationTimeUs - firstPresentationUs);
            // Auto-detect timestamp unit once (us vs ns). Typical frame delta:
            // us: ~16_000/33_000 ; ns: ~16_000_000/33_000_000
            if (delta > 1_000_000L) {
                timelineToMsDivisor = 1_000_000f; // likely ns
            } else {
                timelineToMsDivisor = 1000f; // likely us
            }
            elapsedMs = delta / Math.max(1f, timelineToMsDivisor);
        } else {
            if (!useWallClockFallback) {
                elapsedMs = 0f;
            } else {
                if (startTimeMs < 0L) {
                    startTimeMs = SystemClock.uptimeMillis();
                }
                elapsedMs = SystemClock.uptimeMillis() - startTimeMs;
            }
        }

        // Refresh layout each round so hearts do not repeat fixed positions forever.
        float cycleLenForLayout = Math.max(1f, repeatIntervalMs);
        long layoutCycle = (long) Math.floor(elapsedMs / cycleLenForLayout);
        if (layoutCycle != lastLayoutCycle) {
            regenerateLayout();
            lastLayoutCycle = layoutCycle;
        }

        float scaleCap = computeSafeScaleCap();
        int heartCount = Math.min(MAX_HEARTS, Math.max(1, heartsPerQuadrant * 4));

        for (int i = 0; i < heartCount; i++) {
            float cycleLen = Math.max(1f, repeatIntervalMs);
            float localElapsed = (elapsedMs - delayMs[i]) % cycleLen;
            if (localElapsed < 0f) {
                localElapsed += cycleLen;
            }
            float scale = 0f;
            float alpha = 0f;
            float minScale = normalScale * minScaleFactor;

            if (localElapsed >= 0f) {
                // 阶段 1: 渐显放大阶段 (0 ~ fadeInShrinkDurationMs)
                // 从 fadeInStartScale 放大到 normalScale，透明度从 fadeInStartAlpha 到 fadeInEndAlpha
                float t0 = fadeInShrinkDurationMs;
                // 阶段 2: 正常缩小阶段 (t0 ~ t0 + normalShrinkDurationMs)
                float t1 = t0 + normalShrinkDurationMs;
                // 阶段 3: 突然放大后缩小阶段 (t1 ~ t1 + flashShrinkDurationMs)
                float t2 = t1 + flashShrinkDurationMs;
                
                if (localElapsed < t0) {
                    // 渐显放大阶段
                    float p = localElapsed / Math.max(1f, fadeInShrinkDurationMs);
                    scale = lerp(fadeInStartScale, normalScale, p);
                    alpha = lerp(fadeInStartAlpha, fadeInEndAlpha, p);
                } else if (localElapsed < t1) {
                    // 正常缩小阶段
                    float p = (localElapsed - t0) / Math.max(1f, normalShrinkDurationMs);
                    scale = lerp(normalScale, minScale, p);
                    alpha = fadeInEndAlpha;
                } else if (localElapsed < t2) {
                    // 突然放大后缩小阶段
                    float p = (localElapsed - t1) / Math.max(1f, flashShrinkDurationMs);
                    scale = lerp(normalScale, 0f, p);
                    alpha = fadeInEndAlpha * (1.0f - p);
                }
            }

            float safeScale = Math.min(scale, scaleCap) * scaleMul[i];
            safeScale = Math.max(0f, safeScale);
            float sizeX = (heartWidth * safeScale) / mWidth;
            float sizeY = (heartHeight * safeScale) / mHeight;
            float originX = centersX[i] - sizeX * 0.5f;
            float originY = centersY[i] - sizeY * 0.5f;

            int p2 = i * 2;
            origins[p2] = originX;
            origins[p2 + 1] = originY;
            sizes[p2] = sizeX;
            sizes[p2 + 1] = sizeY;
            alphas[i] = alpha;
        }

        for (int i = heartCount; i < MAX_HEARTS; i++) {
            int p2 = i * 2;
            origins[p2] = 0f;
            origins[p2 + 1] = 0f;
            sizes[p2] = 0f;
            sizes[p2 + 1] = 0f;
            alphas[i] = 0f;
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, heartTextureId);
        GLES20.glUniform1i(heartSamplerHandle, 1);
        GLES20.glUniform1i(heartCountHandle, heartCount);
        GLES20.glUniform2fv(heartOriginHandle, MAX_HEARTS, FloatBuffer.wrap(origins));
        GLES20.glUniform2fv(heartSizeHandle, MAX_HEARTS, FloatBuffer.wrap(sizes));
        GLES20.glUniform1fv(heartAlphaHandle, MAX_HEARTS, FloatBuffer.wrap(alphas));
    }

    @Override
    public void setup() {
        firstPresentationUs = Long.MIN_VALUE;
        startTimeMs = -1L;
        lastLayoutCycle = Long.MIN_VALUE;
        timelineToMsDivisor = 1000f;
        super.setup();
    }

    @Override
    public void release() {
        if (heartTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{heartTextureId}, 0);
            heartTextureId = 0;
        }
        if (heartBitmap != null && !heartBitmap.isRecycled()) {
            heartBitmap.recycle();
            heartBitmap = null;
        }
        firstPresentationUs = Long.MIN_VALUE;
        startTimeMs = -1L;
        lastLayoutCycle = Long.MIN_VALUE;
        timelineToMsDivisor = 1000f;
        super.release();
    }

    private float computeSafeScaleCap() {
        float widthAtScale1 = heartWidth / Math.max(1f, (float) mWidth);
        float heightAtScale1 = heartHeight / Math.max(1f, (float) mHeight);
        float cap = normalScale;
        if (widthAtScale1 > 0f) {
            cap = Math.min(cap, maxDisplayWidthRatio / widthAtScale1);
        }
        if (heightAtScale1 > 0f) {
            cap = Math.min(cap, maxDisplayHeightRatio / heightAtScale1);
        }
        return Math.max(0f, cap);
    }

    private void regenerateLayout() {
        int idx = 0;
        for (int q = 0; q < 4; q++) {
            float minX = (q % 2 == 0) ? centerPadding : 0.5f + centerPadding;
            float maxX = (q % 2 == 0) ? 0.5f - centerPadding : 1.0f - centerPadding;
            float minY = (q < 2) ? 0.5f + centerPadding : centerPadding;
            float maxY = (q < 2) ? 1.0f - centerPadding : 0.5f - centerPadding;

            for (int i = 0; i < heartsPerQuadrant && idx < MAX_HEARTS; i++, idx++) {
                boolean placed = false;
                for (int attempt = 0; attempt < 24 && !placed; attempt++) {
                    float cx = lerp(minX, maxX, random.nextFloat());
                    float cy = lerp(minY, maxY, random.nextFloat());
                    if (isFarEnough(cx, cy, idx)) {
                        centersX[idx] = cx;
                        centersY[idx] = cy;
                        placed = true;
                    }
                }
                if (!placed) {
                    centersX[idx] = lerp(minX, maxX, 0.5f + (random.nextFloat() - 0.5f) * 0.4f);
                    centersY[idx] = lerp(minY, maxY, 0.5f + (random.nextFloat() - 0.5f) * 0.4f);
                }
                delayMs[idx] = random.nextFloat() * maxStaggerMs;
                scaleMul[idx] = lerp(0.4f, 0.8f, random.nextFloat());
            }
        }
    }

    private boolean isFarEnough(float x, float y, int countSoFar) {
        float minDist2 = minHeartSpacing * minHeartSpacing;
        for (int i = 0; i < countSoFar; i++) {
            float dx = centersX[i] - x;
            float dy = centersY[i] - y;
            if (dx * dx + dy * dy < minDist2) {
                return false;
            }
        }
        return true;
    }

    private void uploadHeartTextureIfNeed() {
        if (heartTextureId != 0) {
            return;
        }
        if (heartBitmap == null || heartBitmap.isRecycled()) {
            heartBitmap = loadBitmapFromAssets(assetPath);
            if (heartBitmap == null) {
                return;
            }
            heartWidth = heartBitmap.getWidth();
            heartHeight = heartBitmap.getHeight();
        }

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        heartTextureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, heartTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, heartBitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        heartBitmap.recycle();
        heartBitmap = null;
    }

    private Bitmap loadBitmapFromAssets(String path) {
        InputStream inputStream = null;
        try {
            inputStream = context.getAssets().open(path);
            return BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load heart bitmap from assets: " + path, e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public MultiHeartPopFlashFilter setHeartsPerQuadrant(int count) {
        this.heartsPerQuadrant = (int) clamp(count, 1, 4);
        regenerateLayout();
        return this;
    }

    public MultiHeartPopFlashFilter setMinHeartSpacing(float spacing) {
        this.minHeartSpacing = Math.max(0f, spacing);
        regenerateLayout();
        return this;
    }

    public MultiHeartPopFlashFilter setCenterPadding(float padding) {
        this.centerPadding = clamp(padding, 0.02f, 0.24f);
        regenerateLayout();
        return this;
    }

    public MultiHeartPopFlashFilter setFadeInShrinkDurationMs(float durationMs) {
        this.fadeInShrinkDurationMs = Math.max(1f, durationMs);
        return this;
    }

    public MultiHeartPopFlashFilter setFadeInStartScale(float scale) {
        this.fadeInStartScale = Math.max(0.01f, scale);
        return this;
    }

    public MultiHeartPopFlashFilter setFadeInStartAlpha(float alpha) {
        this.fadeInStartAlpha = clamp(alpha, 0f, 1f);
        return this;
    }

    public MultiHeartPopFlashFilter setFadeInEndAlpha(float alpha) {
        this.fadeInEndAlpha = clamp(alpha, 0f, 1f);
        return this;
    }

    public MultiHeartPopFlashFilter setNormalShrinkDurationMs(float durationMs) {
        this.normalShrinkDurationMs = Math.max(1f, durationMs);
        return this;
    }

    public MultiHeartPopFlashFilter setFlashShrinkDurationMs(float durationMs) {
        this.flashShrinkDurationMs = Math.max(1f, durationMs);
        return this;
    }

    // 兼容旧接口：映射到“正常缩小阶段”。
    public MultiHeartPopFlashFilter setShrinkDurationMs(float shrinkDurationMs) {
        return setNormalShrinkDurationMs(shrinkDurationMs);
    }

    public MultiHeartPopFlashFilter setNormalScale(float normalScale) {
        this.normalScale = Math.max(0.01f, normalScale);
        return this;
    }

    public MultiHeartPopFlashFilter setMinScaleFactor(float minScaleFactor) {
        this.minScaleFactor = clamp(minScaleFactor, 0f, 1f);
        return this;
    }

    public MultiHeartPopFlashFilter setMaxStaggerMs(float maxStaggerMs) {
        this.maxStaggerMs = Math.max(0f, maxStaggerMs);
        regenerateLayout();
        return this;
    }

    public MultiHeartPopFlashFilter setRepeatIntervalMs(float repeatIntervalMs) {
        this.repeatIntervalMs = Math.max(1f, repeatIntervalMs);
        return this;
    }

    public MultiHeartPopFlashFilter setUseWallClockFallback(boolean useWallClockFallback) {
        this.useWallClockFallback = useWallClockFallback;
        return this;
    }

    public MultiHeartPopFlashFilter setMaxDisplayRatio(float widthRatio, float heightRatio) {
        this.maxDisplayWidthRatio = Math.max(0.01f, widthRatio);
        this.maxDisplayHeightRatio = Math.max(0.01f, heightRatio);
        return this;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
