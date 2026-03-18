package com.example.wangduwei.demos.gles.media

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.example.lib_gles.video_filter.composer.Mp4Composer
import com.example.lib_gles.video_filter.core.filter.GlFilter
import com.example.lib_gles.video_filter.core.filter.GlFilterGroup
import com.example.lib_gles.video_filter.core.filter.GlFilterList
import com.example.lib_gles.video_filter.core.filter.GlFilterPeriod
import com.example.lib_gles.video_filter.core.filter.TimeScaleFilter
import com.example.lib_gles.video_filter.filter_impl.GlDualSideMarqueeFilter
import com.example.lib_gles.video_filter.filter_impl.GlDynamicMosaicFilter
import com.example.lib_gles.video_filter.filter_impl.GlEdgeGradientFrameFilter
import com.example.lib_gles.video_filter.filter_impl.GlMosaicShiftCascadeFilter
import com.example.lib_gles.video_filter.filter_impl.GlPulseVerticalScaleFilter
import com.example.lib_gles.video_filter.filter_impl.GlPulseZoomFilter
import com.example.lib_gles.video_filter.filter_impl.GlRadialSpreadColorFilter
import com.example.lib_gles.video_filter.filter_impl.GlSoulOutFilter
import com.example.lib_gles.video_filter.filter_impl.GlWatermarkFilter
import com.example.lib_gles.video_filter.filter_impl.LightFilter
import com.example.lib_gles.video_filter.filter_impl.MeteorFilter
import com.example.lib_gles.video_filter.filter_impl.MeteorFilter2
import com.example.lib_gles.video_filter.filter_impl.MultiHeartPopFlashFilter
import com.example.lib_gles.video_filter.filter_impl.PrismaticFilter2
import com.example.lib_gles.video_filter.filter_impl.RadiumRaysFilter
import com.example.lib_gles.video_filter.filter_impl.ScaleBlurFilter
import com.example.lib_gles.video_filter.filter_impl.SnowFadeOutFilter
import com.example.lib_gles.video_filter.filter_impl.SnowFilter
import com.example.lib_processor.PageInfo
import com.example.wangduwei.demos.R
import com.example.wangduwei.demos.main.BaseSupportFragment
import java.io.File


@PageInfo(
    description = "视频编辑输出",
    navigationId = R.id.fragment_gl_export,
    title = "OpenGl视频特效",
    preview = R.drawable.media_codec
)
class MediaEditFragment: BaseSupportFragment() {

    private val videoPath = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "/Camera/PXL_20260302_053006835.mp4"
    ).absolutePath

    private val videoPath2 = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ForBiggerEscapes.mp4"
    ).absolutePath


    private val duration: Long = 10000;

    private val audioPath = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "file_example_MP3_1MG.mp3"
    ).absolutePath


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_gl_export, null)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val filterOutput = view.findViewById<TextView>(R.id.video_plus_filter)
        val audioOutput = view.findViewById<TextView>(R.id.video_plus_audio)
        val filterEffectOutput = view.findViewById<TextView>(R.id.video_plus_filter_effect)

        val videoEffect1 = view.findViewById<TextView>(R.id.video_effect1)
        val videoEffect3 = view.findViewById<TextView>(R.id.video_effect3)
        val videoEffect4 = view.findViewById<TextView>(R.id.video_effect4)


        filterOutput.setOnClickListener {
            if (hasStoragePermission().not()) {
                return@setOnClickListener
            }

            val filterList = GlFilterList()

            filterList.putGlFilter(GlFilterPeriod(0L,3 * 1000L, GlSoulOutFilter(view.context)))

            val defaultText = "视频加滤镜输出"
            filterOutput.isEnabled = false
            filterOutput.text = "处理中 0%"
            val outFile = File(requireContext().filesDir, "clip_filter_${System.currentTimeMillis()}.mp4")
            Mp4Composer(videoPath, outFile.absolutePath)
                .size(720, 1280)
                .clip(0, 3_000)
                .filterList(filterList)
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        val percent = (progress * 100).toInt().coerceIn(0, 100)
                        activity?.runOnUiThread {
                            filterOutput.text = "处理中 ${percent}%"
                        }
                    }

                    override fun onCompleted() {
                        activity?.runOnUiThread {
                            filterOutput.isEnabled = true
                            filterOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出完成: ${outFile.absolutePath}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onCanceled() {
                        activity?.runOnUiThread {
                            filterOutput.isEnabled = true
                            filterOutput.text = defaultText
                            Toast.makeText(requireContext(), "已取消", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailed(exception: java.lang.Exception, errorCode: Int) {
                        activity?.runOnUiThread {
                            filterOutput.isEnabled = true
                            filterOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                .start()
        }



        audioOutput.setOnClickListener {
            if (hasStoragePermission().not()) {
                return@setOnClickListener
            }
            val defaultText = "视频加音频输出"
            audioOutput.isEnabled = false
            audioOutput.text = "处理中 0%"
            val outFile = File(requireContext().filesDir, "mix_audio_${System.currentTimeMillis()}.mp4")
            Mp4Composer(videoPath2, outFile.absolutePath)
                .size(720, 1280)
                .audioPath(audioPath)
                .audioMode(Mp4Composer.AudioMode.MIX)
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        val percent = (progress * 100).toInt().coerceIn(0, 100)
                        activity?.runOnUiThread {
                            audioOutput.text = "处理中 ${percent}%"
                        }
                    }

                    override fun onCompleted() {
                        activity?.runOnUiThread {
                            audioOutput.isEnabled = true
                            audioOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出完成: ${outFile.absolutePath}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onCanceled() {
                        activity?.runOnUiThread {
                            audioOutput.isEnabled = true
                            audioOutput.text = defaultText
                            Toast.makeText(requireContext(), "已取消", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailed(exception: java.lang.Exception, errorCode: Int) {
                        activity?.runOnUiThread {
                            audioOutput.isEnabled = true
                            audioOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                .start()
        }

        filterEffectOutput.setOnClickListener {
            if (hasStoragePermission().not()) {
                return@setOnClickListener
            }

            if (!File(videoPath).exists()) {
                Toast.makeText(requireContext(), "视频不存在", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!File(audioPath).exists()) {
                Toast.makeText(requireContext(), "音频不存在", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val watermarkBitmap = BitmapFactory.decodeResource(resources, R.drawable.guide_enjoy_haha)
            if (watermarkBitmap == null) {
                Toast.makeText(requireContext(), "水印资源加载失败", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val filterGroup = GlFilterGroup(
                GlWatermarkFilter(watermarkBitmap,
                    24f,
                    0.8f,
                    1.0f,
                    GlWatermarkFilter.Position.RIGHT_BOTTOM),
                GlSoulOutFilter(view.context)
            )

            val defaultText = "原视频加水印加特效加音频混合输出"
            filterEffectOutput.isEnabled = false
            filterEffectOutput.text = "处理中 0%"
            val outFile = File(requireContext().filesDir, "watermark_soulout_audio_mix_${System.currentTimeMillis()}.mp4")

            val glFilterList = GlFilterList()
            glFilterList.putGlFilter(GlFilterPeriod(0L,7 * 1000L, filterGroup))
            Mp4Composer(videoPath, outFile.absolutePath)
                .size(720, 1280)
                .filterList(glFilterList)
                .audioPath(audioPath)
                .clip(0, 7_000)
                .audioMode(Mp4Composer.AudioMode.MIX)
                .listener(object : Mp4Composer.Listener {
                    override fun onProgress(progress: Double) {
                        val percent = (progress * 100).toInt().coerceIn(0, 100)
                        activity?.runOnUiThread {
                            filterEffectOutput.text = "处理中 ${percent}%"
                        }
                    }

                    override fun onCompleted() {
                        activity?.runOnUiThread {
                            filterEffectOutput.isEnabled = true
                            filterEffectOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出完成: ${outFile.absolutePath}", Toast.LENGTH_SHORT).show()

                            Log.d("wdw-gl","path = ${outFile.absolutePath}")
                        }
                    }

                    override fun onCanceled() {
                        activity?.runOnUiThread {
                            filterEffectOutput.isEnabled = true
                            filterEffectOutput.text = defaultText
                            Toast.makeText(requireContext(), "已取消", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onFailed(exception: java.lang.Exception, errorCode: Int) {
                        activity?.runOnUiThread {
                            filterEffectOutput.isEnabled = true
                            filterEffectOutput.text = defaultText
                            Toast.makeText(requireContext(), "输出失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                .start()
        }


        videoEffect1.setOnClickListener { onClickEffect1Test(videoEffect1) }
        videoEffect3.setOnClickListener { onClickEffect3(videoEffect3) }
        videoEffect4.setOnClickListener { onClickEffect4(videoEffect4) }
        videoEffectTest.setOnClickListener { testRay(videoEffectTest) }
    }

    /**
     * 00:00～00:03：视频特效-马塞克（level=1）
     * 00:02～00:03：ScaleBlur一次，结束后马赛克level降为0.85
     * 00:04～00:05：ScaleBlur一次，结束后马赛克level降为0.70
     * 00:07～00:08：ScaleBlur一次，结束后马赛克level降为0.55
     * 00:08～00:09: ScaleBlur一次，结束后马赛克level降为0.40
     * 00:09～00:10: 1秒时间内，马赛克渐降为0，同时以画面为中心渐放大到1.3倍，
     */
    private fun onClickEffect1(textView: TextView) {
//        val dynamicMosaicFilter = GlDynamicMosaicFilter()
//            .setRange(2f, 40f) // 最小/最大马赛克块大小(px)
//            .setDurationMs(1000f) // 一个变化周期
//            .setLoop(true) // 循环
//            .setPingPong(true)

        val shakeStep1 = 1060f
        val shakeStep2 = 1310f
        val shakeStep3 = 1560f
        val shakeStep4 = 1810f
        val shakeStep5 = 1160f
        val shakeStep6 = 1160f

        val shiftMosaicFilter = GlMosaicShiftCascadeFilter2()
            .setMosaicMaxBlockSize(10f)
            .setShakeScalePadding(0.04f)
            .setMaxShakeScale(1.3f)
            .setMaxShakeBlur(0.05f)
            .setMaxShakeSampleScale(8.0f)
            .clearMosaicKeyframes()
            .addMosaicLevelKeyframe(0f, 1.0f)
            .addMosaicLevelKeyframe(1000f, 1.0f)
            .addMosaicLevelKeyframe(shakeStep1, 0.85f)
            .addMosaicLevelKeyframe(shakeStep2, 0.70f)
            .addMosaicLevelKeyframe(shakeStep3, 0.55f)
            .addMosaicLevelKeyframe(shakeStep4, 0.40f)
            .addMosaicLevelKeyframe(2000f, 0.40f)
            .addMosaicLevelKeyframe(3000f, 0.0f)
            .clearShakeEvents()
            .addPulseShakeEvent(1000f, shakeStep1, -0.30f, 0f)
            .addPulseShakeEvent(1250f, shakeStep2, -0.30f, 0f)
            .addPulseShakeEvent(1500f, shakeStep3, -0.30f, 0f)
            .addPulseShakeEvent(1750f, shakeStep4, -0.30f, 0f)
            .addPulseShakeEvent(3000f, 3200f, 0f, -0.03f)
            .addPulseShakeEvent(5000f, 6000f, 0.03f, 0f)
            .addPulseShakeEvent(8000f, 9000f, 0.03f, 0f)
//            .addPulseShakeEvent(10000f, 11000f, 0.03f, -0.03f)
//            .addPulseShakeEvent(13000f, 14000f, 0.03f, 0f)

        val filterGroup = GlFilterGroup(
//            GlFilterPeriod(1000L,Long.MAX_VALUE, dynamicMosaicFilter),
            GlFilterPeriod(0,Long.MAX_VALUE, shiftMosaicFilter),
//            GlFilterPeriod(8000L,12000L, TimeScaleFilter(0.5)),
        )

        val outFile = File(requireContext().externalCacheDir, "特效一_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }


    private fun onClickEffect1Test(textView: TextView) {

        val shakeStep1 = 2000f
        val shakeStep2 = 4000f
        val shakeStep3 = 7000f
        val shakeStep4 = 9000f

        val shakeDuration = 400

        val shakeEnd1 = shakeStep1 + shakeDuration
        val shakeEnd2 = shakeStep2 + shakeDuration
        val shakeEnd3 = shakeStep3 + shakeDuration
        val shakeEnd4 = shakeStep4 + shakeDuration

        val shiftMosaicFilter = GlMosaicShiftCascadeFilter5()
            .setMosaicMaxBlockSize(10f)
            .setShakeScalePadding(0.04f)
            .setMaxShakeScale(1.3f)
            .setMaxShakeStretch(1.35f, 1.0f)
//            .setMaxShakeBlur(0.05f)
            .setMaxShakeSampleScale(8.0f)
            .clearMosaicKeyframes()
            .addMosaicLevelKeyframe(0f, 1.0f)
            .addMosaicLevelKeyframe(shakeEnd1, 0.7f)
            .addMosaicLevelKeyframe(shakeEnd2, 0.5f)
            .addMosaicLevelKeyframe(shakeEnd3, 0.4f)
            .addMosaicLevelKeyframe(shakeEnd4, 0.20f)
            .addMosaicLevelKeyframe(shakeEnd4 + 1000, 0f)
            .clearZoomEvents()
            .addZoomEvent(shakeEnd4, shakeEnd4 + 1000f, 1.0f, 1.3f, GlMosaicShiftCascadeFilter3.EASE_SMOOTH)
            .addZoomEvent(shakeEnd4 + 1000f, Float.MAX_VALUE, 1.3f, 1.3f, GlMosaicShiftCascadeFilter3.EASE_LINEAR)
            .clearShakeEvents()
            .addPulseShakeEvent(shakeStep1, shakeEnd1, -0.30f, 0f)
            .addPulseShakeEvent(shakeStep2, shakeEnd2, -0.30f, 0f)
            .addPulseShakeEvent(shakeStep3, shakeEnd3, -0.30f, 0f)
            .addPulseShakeEvent(shakeStep4, shakeEnd4, -0.30f, 0f)

        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0, Long.MAX_VALUE, shiftMosaicFilter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效一test_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun onClickEffect1Temp(textView: TextView) {

        val filter = ScaleBlurFilter()
            .setScale(1.2f)
            .setStretchX(1.35f)
            .setOffsetX(-0.05f)                  // 开始露右边缘
            .setSwitchOffsetAtMs(2000f)          // 2秒开始切换
            .setSwitchToOppositeDurationMs(200f) // 200ms到左边缘
            .setReturnToCenterDurationMs(200f)   // 再200ms回中心
            .setSampleScale(8.0f)
            .setBlurStrength(0.05f);


        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0,Long.MAX_VALUE, filter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效temp_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun onClickEffect3(textView: TextView) {

//        val radialColorFilter = GlRadialSpreadColorFilter()
//            .setCycleDurationSec(1.6f)
//            .setMaxIntensity(0.88f)
//            .setSpreadSoftness(0.10f)
//            .setColorList(arrayListOf<Int>(
//                Color.RED,
//                Color.YELLOW,
//                Color.DKGRAY,
//                Color.LTGRAY,
//            ))

        val color0 = 0xFFFAFF5A
        val color1 = 0xFFF433E3
        val color2 = 0xFF5FFFFF
        val color3 = 0xFF6BFF8E

        val lightFilter = LightFilter()
            .setLight(1f)

        val zoomFilter = GlPulseZoomFilter(2f)
            .setZoomInDurationMs(500f)
            .setZoomOutDurationMs(500f)

        val initialTopRay1 = 0.02f
//        val initialTopRay2 = -0.05f
//        val centerTopRay1 = 0.15f
//        val centerTopRay2 = 0.08f


        val rayFilter = RadiumRaysFilter()
            .setRayColor(color0.toInt())
            .setRayTopOffset(initialTopRay1)
            .setThickness(0.0035f)   // 线更细
            .setGlowWidth(0.014f)    // 光晕更窄
            .setGlowIntensity(0.85f) // 光晕更弱
            .setBrightness(1.25f)    // 整体强度（包括光晕）
            .setCoreWhiteAlpha(0.45f)// 白色层透明度
            .setPulseStrength(2f)
            .setFlickerStrength(0f)

        val meteorFilter = MeteorFilter()
            .setCornerColors(
                color0.toInt(), // top
                color1.toInt(), // right
                color2.toInt(), // bottom
                color3.toInt()  // left
            )
            .setBlurRadiusPx(100f)
            .setOpacity(1f)
            .setBrightness(1f)

            // 细核心 + 大光晕（接近参考图左侧“窄亮条+大片黄雾”）
            .setHeadWidthPx(0.8f)
            .setTailWidthPx(0.2f)
            .setHeadAlpha(0.8f)
            .setTailAlpha(0.6f)
            .setInnerSoftnessPx(50f)
            .setBlurRadiusPx(82f)

            // 尾巴长度与速度
            .setTailLengthRatio(0.42f)
            .setSpeedRps(0.085f)

            // 颜色过渡更柔和，避免硬断层
            .setColorBlendStart(0.05f)
            .setColorBlendGamma(0.30f)
            .setCornerBlendLen(0.42f)
            .setColorMidRatio(0.58f)

            // 头部圆润一点
            .setHeadCapScale(0.75f)

        meteorFilter.setOnCornerColorChangeListener{ cornerIndex, nextColorIndex, nextColor ->
            rayFilter.setRayColor(nextColor)

        }

        val verticalScaleFilter = GlPulseVerticalScaleFilter()
            .setTargetScaleY(0.7f)
            .setShrinkDurationMs(200f)
            .setExpandDurationMs(200f)
            .setIntervalMs(3000f)
            .setOnPulseProgressListener(object: GlPulseVerticalScaleFilter.OnPulseProgressListener {
                private val baseLight = 1.0f
                private val targetLight = 1.45f
                // Shrink progress reaches this threshold, then begin boosting light.
                private val shrinkLightStartProgress = 0.9f


                override fun onExpandProgress(cycleIndex: Long, progress: Float, scaleY: Float) {
                    // Expand: move rays away from center (back to top edge area).
                    val t = progress.coerceIn(0f, 1f)
                    val e = if (t < 0.5f) {
                        2f * t * t
                    } else {
                        1f - ((-2f * t + 2f) * (-2f * t + 2f)) / 2f
                    }
//                    val off1 = centerTopRay1 + (initialTopRay1 - centerTopRay1) * e
//                    val off2 = centerTopRay2 + (initialTopRay2 - centerTopRay2) * e
//                    rayFilter.setRayTopOffsets(off1, off2)
                    meteorFilter.setContentScaleY(scaleY)

                    // Expand phase: light returns from target to base.
                    val light = targetLight + (baseLight - targetLight) * e
                    lightFilter.setLight(light)
                }

                override fun onShrinkProgress(cycleIndex: Long, progress: Float, scaleY: Float) {
                    // Shrink: move rays toward center.
                    val t = progress.coerceIn(0f, 1f)
                    val e = if (t < 0.5f) {
                        2f * t * t
                    } else {
                        1f - ((-2f * t + 2f) * (-2f * t + 2f)) / 2f
                    }
//                    val off1 = initialTopRay1 + (centerTopRay1 - initialTopRay1) * e
//                    val off2 = initialTopRay2 + (centerTopRay2 - initialTopRay2) * e
//                    rayFilter.setRayTopOffsets(off1, off2)
                    meteorFilter.setContentScaleY(scaleY)

                    // Shrink phase: after threshold, light ramps to target.
                    val lt = if (t <= shrinkLightStartProgress) {
                        0f
                    } else {
                        ((t - shrinkLightStartProgress) / (1f - shrinkLightStartProgress)).coerceIn(0f, 1f)
                    }
                    val light = baseLight + (targetLight - baseLight) * lt
                    lightFilter.setLight(light)
                }
            })


//        val color: Int = 0xFF8A2BE2.toInt();
//        val edgeGradientFrameFilter = GlEdgeGradientFrameFilter()
//            .setColor(color)
//            .setWidthRatio(0.12f)
//            .setDarkness(0.35f)
//            .setLightness(0.25f)
//            .setOpacity(0.78f)
//            .setFeather(0.06f)
//            .setGlowWidth(0.05f)
//            .setGlowIntensity(0.55f)

        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0,Long.MAX_VALUE, lightFilter),
            // 先做画面几何变换，再叠加边缘层，避免 zoom 时边框被放大裁掉
            GlFilterPeriod(0,Long.MAX_VALUE, zoomFilter),
            GlFilterPeriod(0,Long.MAX_VALUE, rayFilter),
            GlFilterPeriod(0,Long.MAX_VALUE, verticalScaleFilter),
            GlFilterPeriod(0,Long.MAX_VALUE, meteorFilter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效三_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun onClickEffect4(textView: TextView) {
        val snow = SnowFilter()
            .setParticleCount(30)
            .setSideBandWidthRatio(0.16f)
            .setPointSize(10.5f)
            .setSpeedRange(0.2f, 0.4f)
            .setWind(0.02f);

        val snowFadeOut = SnowFadeOutFilter()
            .setParticleCount(30)
            .setSpawnSpread(0.46f, 0.34f)
            .setCenter(0.5f, 0.52f)
            .setPointSize(10f)
            .setSpeedRange(0.005f, 0.02f)
            .setEdgeBlur(0.72f, 0.8f)


        val color0 = 0xFFCB2BF6
        val color1 = 0xFF572CCD
        val color2 = 0xFF0B78FF
        val color3 = 0xFF00C8FF
        val sideMarqueeFilter = GlDualSideMarqueeFilter(24f)
            .setStripWidthPx(36f)
            .setBarLength(0.7f)
            .setBarGap(0.2f)
            .setBandSoftness(0.08f)
            .setEdgeSoftnessPx(12f)
            .setBlurRadiusPx(32f)
            .setGlowWidthPx(48f)
            .setGlowIntensity(1.5f)
            .setOpacity(1.0f)
            .setColorIntensity(1.8f)
            .setGlowIntensity(1.0f)
            .setSpeed(0.1f)

//            .setStripWidthPx(24f)
//            .setBlurRadiusPx(30f)
//            .setEdgeSoftnessPx(10f)
//            .setGlowWidthPx(40f)
//            .setGlowIntensity(1.3f)
//            .setOpacity(0.95f)

//            .setGlowWidthPx(36f)
//            .setGlowIntensity(1.35f)
//            .setEdgeSoftnessPx(10f)
//            .setBlurRadiusPx(24f)


//            .setEdgeSoftnessPx(60f)
//            .setBlurRadiusPx(36f)   // 继续加大虚化
//            .setColorBlendSpan(1f)
//            .setBarLength(0.8f)
//            .setBarGap(0f)
//            .setBandSoftness(0f) // 头尾模糊
//            .setSpeed(0.1f)
//            .setOpacity(0.95f)
            .setColors(
                color0.toInt(),
                color1.toInt(),
                color2.toInt(),
                color3.toInt(),
            )

        val edgeFilter = GlEdgePingPongFilter()
    .setBarLengthRatio(0.8f)
    .setSpeed(0.45f)
    .setWidthRatio(0.085f)
    .setGlowWidth(0.06f)
    .setGlowIntensity(1.0f);

        val heartFilter = MultiHeartPopFlashFilter(context, "heart.png")
            .setInitialScaleRange(0.2f, 0.4f)
            .setMaxScaleMultiplier(1.2f)
            .setNormalShrinkDurationMs(1400f)
            .setFlashShrinkDurationMs(350f)
            .setMaxStaggerMs(1200f)
            .setRepeatIntervalMs(2000f)

        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0,Long.MAX_VALUE, snow),
            GlFilterPeriod(0,Long.MAX_VALUE, snowFadeOut),
            GlFilterPeriod(0,Long.MAX_VALUE, sideMarqueeFilter),
            GlFilterPeriod(0,Long.MAX_VALUE, heartFilter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效测试_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun onClickEffectTest(textView: TextView) {
        val filter = ShakeBlurFilter()
            .setScale(1.2f)
            .setStretchX(1.35f)
            .setOffsetX(-0.06f)
            .setSampleScale(6.0f)
            .setSampleMix(0.55f)
            .setSmearStrength(1.0f)
            .setSoftBlurStrength(0.02f)
            .setContrast(0.90f)
            .setSaturation(0.95f)
            .setSharpnessMix(0.25f)

        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0,Long.MAX_VALUE, filter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效四_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun testShake(textView: TextView) {
//        val filter = ShakeFilter()
//            .setDurationMs(180f)
//            .setAttackRatio(0.12f)
//            .setPeakHoldRatio(0.10f)
//            .setMaxOffsetX(-0.16f)
//            .setMaxScale(1.15f)
//            .setMaxStretchX(1.22f)
//            .setMaxSampleScale(10.0f)
//            .setMaxSoftBlurStrength(0.08f)
//            .setMaxSmearStrength(0.75f)

        val filter = PrismaticFilter()
            .setSampleScalePx(10f)
            .setBlurStrength(0.05f)
            .setCellSizePx(42f)
            .setSpacingJitter(0.75f)
            .setWhiteThreshold(0.72f)
            .setWhiteSoftness(0.12f)
            .setEdgeHighlight(0.65f)
            .setEdgeWidth(0.18f)
            .setFacetContrast(0.8f);


//        val filter = MotionBlurFilter()
//            .setStartPosition(0.35f, 0.5f)
//            .setEndPosition(0.75f, 0.5f)
//            .setDurationMs(400f)
//            .setShutterWindowMs(60f)
//            .setMotionBlurSize(10.0f)
//            .setRepeat(true);



//            .setDurationMs(160f)
//            .setAttackRatio(0.18f)
//            .setMaxOffsetX(-0.03f)
//            .setMaxScale(1.06f)
//            .setMaxStretchX(1.08f)
//            .setMaxSampleScale(1.0f)
//            .setSampleMix(0.0f)
//            .setMaxSmearStrength(0.35f)
//            .setMaxSoftBlurStrength(0.01f);


//            .setDurationMs(220f)
//            .setAttackRatio(0.22f)
//            .setMaxOffsetX(-0.12f)
//            .setMaxScale(1.18f)
//            .setMaxStretchX(1.28f)
//            .setMaxSampleScale(5.0f)
//            .setSampleMix(0.45f)
//            .setMaxSmearStrength(1.1f)
//            .setMaxSoftBlurStrength(0.03f);


        val filterGroup = GlFilterGroup(
            GlFilterPeriod(0,Long.MAX_VALUE, filter),
        )

        val outFile = File(requireContext().externalCacheDir, "特效测试_${System.currentTimeMillis()}.mp4")
        compose(filterGroup, textView, outFile)
    }

    private fun testSnow(textView: TextView) {
        val snow = SnowFilter()
            .setParticleCount(100)
            .setSideBandWidthRatio(0.16f)
            .setPointSize(10.5f)
            .setSpeedRange(0.4f, 0.8f)
            .setWind(0.02f);

//            .setSnowColor(1.0f, 1.0f, 1.0f)
//            .setParticleCount(680)      // 画面密度
//            .setPointSize(13.5f)        // 雪花基础大小
//            .setSizeRange(0.65f, 1.45f) // 大小离散
//            .setSpeedRange(2.2f, 5.2f)  // 下落速度
//            .setLifeRange(2.4f, 4.8f)   // 生命周期
//            .setWind(0.022f)            // 轻微横风
//            .setOpacity(0.88f)
//            .setIntensity(1.0f)

        val outFile = File(requireContext().externalCacheDir, "雪花_${System.currentTimeMillis()}.mp4")
        compose(snow, textView, outFile)
    }

    private fun testSnowScale(textView: TextView) {
        val snow = SnowFadeOutFilter()
            .setParticleCount(40)
            .setSpawnSpread(0.46f, 0.34f)
            .setCenter(0.5f, 0.52f)
            .setPointSize(10f)
            .setSpeedRange(0.01f, 0.08f)
            .setEdgeBlur(0.72f, 0.8f)

//            .setSnowColor(1.0f, 1.0f, 1.0f)
//            .setParticleCount(680)      // 画面密度
//            .setPointSize(13.5f)        // 雪花基础大小
//            .setSizeRange(0.65f, 1.45f) // 大小离散
//            .setSpeedRange(2.2f, 5.2f)  // 下落速度
//            .setLifeRange(2.4f, 4.8f)   // 生命周期
//            .setWind(0.022f)            // 轻微横风
//            .setOpacity(0.88f)
//            .setIntensity(1.0f)

        val outFile = File(requireContext().externalCacheDir, "雪花_${System.currentTimeMillis()}.mp4")
        compose(snow, textView, outFile)
    }

    private fun testRay(textView: TextView) {
        val filter = RadiumRaysFilter()
        .setRayColor(0xFF34F0FF.toInt())
        .setRayTopOffsets(0.08f, 0.15f)
        .setThickness(0.005f)
        .setGlowWidth(0.026f)
        .setGlowIntensity(1.2f)
        .setOpacity(1f)
        .setBrightness(1.25f)
        .setPulseStrength(2f)
        .setFlickerStrength(0f)



        val outFile = File(requireContext().externalCacheDir, "ray_${System.currentTimeMillis()}.mp4")
        compose(filter, textView, outFile)
    }


    private fun compose(filter: GlFilter, textView: TextView, outFile: File) {
        val glFilterList = GlFilterList()
        glFilterList.putGlFilter(GlFilterPeriod(0, Long.MAX_VALUE, filter))
        val defaultText = textView.text;
        Mp4Composer(videoPath, outFile.absolutePath)
//            .size(720, 1280)
            .clip(0, duration)
            .filterList(glFilterList)
            .listener(object : Mp4Composer.Listener {
                override fun onProgress(progress: Double) {
                    val percent = (progress * 100).toInt().coerceIn(0, 100)
                    activity?.runOnUiThread {
                        textView.text = "处理中 ${percent}%"
                    }
                }

                override fun onCompleted() {
                    activity?.runOnUiThread {
                        textView.isEnabled = true
                        textView.text = defaultText
                        Toast.makeText(requireContext(), "输出完成: ${outFile.absolutePath}", Toast.LENGTH_SHORT).show()

                        Log.d("wdw-gl","path = ${outFile.absolutePath}")
                    }
                }

                override fun onCanceled() {
                    activity?.runOnUiThread {
                        textView.isEnabled = true
                        textView.text = defaultText
                        Toast.makeText(requireContext(), "已取消", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailed(exception: java.lang.Exception, errorCode: Int) {
                    activity?.runOnUiThread {
                        textView.isEnabled = true
                        textView.text = defaultText
                        Toast.makeText(requireContext(), "输出失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            .start()
    }


    private fun hasStoragePermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            Toast.makeText(
                requireContext(),
                "请开启 MANAGE_EXTERNAL_STORAGE 权限",
                Toast.LENGTH_SHORT
            ).show()
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            startActivity(intent)
            return false
        }


        return true
    }



}
