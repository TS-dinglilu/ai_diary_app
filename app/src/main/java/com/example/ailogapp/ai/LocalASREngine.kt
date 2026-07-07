package com.example.ailogapp.ai

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.io.FileOutputStream

/**
 * 基于 sherpa-onnx 的离线语音转文字引擎（SenseVoice-Small 模型）。
 *
 * 使用阿里 FunASr 的 SenseVoice-Small 模型（经 sherpa-onnx 转换为 onnx 格式），
 * 完全本地运行，无需网络。中文准确率显著高于 Whisper tiny。
 *
 * 特点：
 * - 支持 中/英/日/韩/粤语 5 种语言，中文识别准确率极高
 * - 非自回归模型，推理速度快
 * - 支持 inverse text normalization（逆文本归一化），输出更自然
 *
 * 模型文件：
 * - sense-voice.onnx  完整模型（~937MB，非量化，准确率最高），由用户下载到 filesDir，不打包进 APK
 * - tokens.txt        词表（~300KB，体积小，保留在 assets，首次使用时复制到 filesDir）
 *
 * 重要：所有 native 调用可能抛出 [UnsatisfiedLinkError]（继承自 [Error]，
 * 不是 [Exception]），因此必须用 `catch (e: Throwable)` 捕获。
 */
object LocalASREngine {

    private const val TAG = "LocalASREngine"
    private const val MODEL_DIR = "sherpa"
    private const val MODEL_FILE = "sense-voice.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** 初始化失败的时间戳，0 表示未失败。允许在一定时间后重试。 */
    @Volatile
    private var initFailedTime: Long = 0L

    /** 初始化失败后重试间隔（5 分钟） */
    private const val INIT_RETRY_INTERVAL_MS = 5 * 60 * 1000L

    @Synchronized
    fun init(context: Context): Boolean {
        recognizer?.let { return true }
        if (initFailedTime > 0 && System.currentTimeMillis() - initFailedTime < INIT_RETRY_INTERVAL_MS) {
            Log.w(TAG, "ASR 引擎近期初始化失败，稍后可重试")
            return false
        }
        // 模型由用户下载到 filesDir，不打包进 APK
        val modelFile = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        if (!modelFile.exists() || modelFile.length() <= 100_000_000) {
            Log.w(TAG, "ASR 模型文件不存在（filesDir 无），无法初始化")
            return false
        }
        // tokens.txt 体积很小（~300KB），保留在 assets 中，首次使用时复制到 filesDir
        val tokensFile = File(context.filesDir, "$MODEL_DIR/$TOKENS_FILE")
        if (!tokensFile.exists()) {
            if (!copyTokensFromAssets(context)) {
                Log.w(TAG, "tokens.txt 复制失败，无法初始化")
                return false
            }
        }
        return try {
            val config = OfflineRecognizerConfig().apply {
                featConfig = FeatureConfig()
                modelConfig = OfflineModelConfig().apply {
                    senseVoice = OfflineSenseVoiceModelConfig().apply {
                        model = modelFile.absolutePath
                        language = "zh"  // 中文
                        useInverseTextNormalization = true  // 逆文本归一化，数字/日期等输出更自然
                    }
                    tokens = tokensFile.absolutePath
                    numThreads = 4       // 多线程加速
                    provider = "cpu"
                }
                decodingMethod = "greedy_search"
            }
            // 不传 assetManager，sherpa-onnx 走 newFromFile 从绝对路径加载模型
            recognizer = OfflineRecognizer(config = config)
            Log.i(TAG, "SenseVoice 离线引擎初始化成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "SenseVoice 引擎初始化失败", e)
            initFailedTime = System.currentTimeMillis()
            false
        }
    }

    /** 将 tokens.txt 从 assets 复制到 filesDir（体积小，保留在 assets 中作为词表来源） */
    private fun copyTokensFromAssets(context: Context): Boolean {
        return try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()
            val targetFile = File(modelDir, TOKENS_FILE)
            context.assets.open("$MODEL_DIR/$TOKENS_FILE").use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "tokens.txt 已复制到 ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "复制 tokens.txt 失败", e)
            false
        }
    }

    /**
     * 转写音频文件。
     * 使用 @Synchronized 序列化：sherpa-onnx 的 OfflineRecognizer 不保证并发安全，
     * 多处同时触发转写（如手动 + Worker）会导致 native 状态错乱或崩溃。
     */
    @Synchronized
    fun transcribe(context: Context, audioFile: File): String {
        return transcribeWithSpeaker(context, audioFile).first
    }

    /**
     * 转写音频文件并识别说话人。
     *
     * @return Pair(转写文本, 说话人ID)
     */
    @Synchronized
    fun transcribeWithSpeaker(context: Context, audioFile: File): Pair<String, Int> {
        if (!init(context)) {
            Log.e(TAG, "引擎未初始化，无法转写")
            return Pair("", 0)
        }
        val rec = recognizer ?: return Pair("", 0)
        var stream: OfflineStream? = null
        return try {
            val samples = AudioDecoder.decodeToFloatArray(audioFile.absolutePath)
            if (samples.isEmpty()) {
                Log.e(TAG, "音频解码失败: ${audioFile.absolutePath}")
                return Pair("", 0)
            }
            val durationSec = samples.size / 16000.0
            Log.d(TAG, "音频解码完成，${samples.size} 采样点 (${String.format("%.1f", durationSec)}s)")

            // 识别说话人
            val speakerId = SpeakerManager.identifySpeaker(samples)
            Log.i(TAG, "说话人识别结果: 说话人$speakerId")

            stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)

            val text = rec.getResult(stream).text.trim()
            Log.i(TAG, "离线转写完成 (${String.format("%.1f", durationSec)}s), 说话人$speakerId: ${if (text.length > 100) text.take(100) + "..." else text}")
            Pair(text, speakerId)
        } catch (e: Throwable) {
            Log.e(TAG, "离线转写失败", e)
            Pair("", 0)
        } finally {
            try { stream?.release() } catch (_: Throwable) { }
        }
    }

    /**
     * 模型文件是否存在且完整。
     * 模型不打包进 APK，仅检查 filesDir 中用户下载的文件。
     */
    fun isModelAvailable(context: Context): Boolean {
        val file = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        return file.exists() && file.length() > 100_000_000
    }

    /** 获取模型文件大小（MB），不存在返回 0。实时读取文件实际大小。 */
    fun modelSizeMB(context: Context): Long {
        val file = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        return if (file.exists()) file.length() / 1_048_576 else 0
    }

    /**
     * 释放引擎资源。
     *
     * 不使用 @Synchronized：避免在 UI 线程调用时因等待转写锁而阻塞导致 ANR。
     * 通过原子引用交换确保安全性：先将 recognizer 置 null 阻止新调用，
     * 再释放旧引用。正在进行的转写会因 native 对象被释放而返回空结果（用户主动操作）。
     */
    fun release() {
        val old = recognizer
        recognizer = null
        initFailedTime = 0L
        try { old?.release() } catch (_: Throwable) { }
    }
}
