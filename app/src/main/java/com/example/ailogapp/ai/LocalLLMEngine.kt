package com.example.ailogapp.ai

import android.content.Context
import android.util.Log
import com.example.ailogapp.data.entities.AnalysisEntity
import com.example.ailogapp.data.entities.TranscriptEntity
import com.google.gson.Gson
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧大模型离线推理引擎（基于 Google MediaPipe LLM Inference API）。
 *
 * 使用 Gemma-2 2B int8 量化模型，完全本地运行，无需网络。
 * 支持中文理解和生成，适合日记分析、情绪分析等任务。
 *
 * 模型文件：
 * - 格式：MediaPipe 兼容的 .bin 格式
 * - 大小：约 1.8 GB（int8 量化）
 * - 存放位置：context.filesDir/llm/gemma-2b-it.bin
 *
 * 模型不打包进 APK，由用户通过设置界面下载到 filesDir，版本更新时不被覆盖。
 * MediaPipe LlmInference API 需要文件路径（不支持直接读 assets）。
 */
object LocalLLMEngine {

    private const val TAG = "LocalLLMEngine"
    private const val MODEL_DIR = "llm"
    private const val MODEL_FILE = "gemma-2b-it.bin"

    @Volatile
    private var llmInference: LlmInference? = null

    /** 初始化失败的时间戳，0 表示未失败。允许在一定时间后重试，避免永久锁定。 */
    @Volatile
    private var initFailedTime: Long = 0L

    /** 初始化失败后重试间隔（5 分钟） */
    private const val INIT_RETRY_INTERVAL_MS = 5 * 60 * 1000L

    /** 推理互斥锁：MediaPipe LlmInference.generateResponse 不保证线程安全，并发调用会导致 native 崩溃。 */
    private val inferenceMutex = Mutex()

    /** 单次分析超时时间（毫秒），超时后放弃等待结果 */
    private const val ANALYSIS_TIMEOUT_MS = 120_000L  // 2 分钟

    /** 是否正在分析中（用于外部判断状态） */
    private val isAnalyzing = AtomicBoolean(false)

    /** 模型文件运行时路径（filesDir） */
    fun modelPath(context: Context): String {
        return File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").absolutePath
    }

    /**
     * 模型文件是否存在且完整。
     * 模型不打包进 APK，仅检查 filesDir 中用户下载的文件。
     */
    fun isModelAvailable(context: Context): Boolean {
        val file = File(modelPath(context))
        return file.exists() && file.length() > 100_000_000
    }

    /** 获取模型文件大小（MB），不存在返回 0。实时读取文件实际大小。 */
    fun modelSizeMB(context: Context): Long {
        val file = File(modelPath(context))
        return if (file.exists()) file.length() / 1_048_576 else 0
    }

    /** 初始化 LLM 推理器（线程安全，仅初始化一次） */
    @Synchronized
    fun init(context: Context): Boolean {
        llmInference?.let { return true }
        // 允许在 INIT_RETRY_INTERVAL_MS 后重试，避免瞬时失败导致永久不可用
        if (initFailedTime > 0 && System.currentTimeMillis() - initFailedTime < INIT_RETRY_INTERVAL_MS) {
            Log.w(TAG, "LLM 引擎近期初始化失败，${(INIT_RETRY_INTERVAL_MS - (System.currentTimeMillis() - initFailedTime)) / 1000}s 后可重试")
            return false
        }
        // 模型由用户下载到 filesDir，不打包进 APK
        val file = File(modelPath(context))
        if (!file.exists() || file.length() <= 100_000_000) {
            Log.w(TAG, "模型文件不存在（filesDir 无），无法初始化")
            return false
        }
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath(context))
                .setMaxTokens(1024)
                .setTopK(40)
                .setTemperature(0.8f)
                .setRandomSeed(101)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe LLM 引擎初始化成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "MediaPipe LLM 引擎初始化失败", e)
            initFailedTime = System.currentTimeMillis()
            false
        }
    }

    /**
     * 本地 LLM 分析转写文本，生成 JSON 格式结果。
     *
     * 安全设计：
     * 1. native generateResponse 不可中断，用独立协程执行，通过 CompletableDeferred 获取结果。
     * 2. 超时/取消后调用方直接返回空，不调用 release()——避免在 native 推理进行中 close 引擎导致段错误。
     * 3. native 调用最终完成后（无论成功/异常），独立协程的 finally 块安全释放引擎。
     * 4. 分析后自动释放引擎，避免常驻内存。
     *
     * @param dateStr 日期
     * @param transcripts 当天转写内容
     * @param history 最近几天分析历史
     * @return JSON 字符串，包含 diary/emotion/emotionScore/summary/fullAnalysis
     */
    suspend fun analyze(
        context: Context,
        dateStr: String,
        transcripts: List<TranscriptEntity>,
        history: List<AnalysisEntity>
    ): String {
        if (!init(context)) {
            Log.e(TAG, "LLM 引擎未初始化，无法分析")
            return ""
        }
        val inference = llmInference ?: return ""

        val prompt = buildPrompt(dateStr, transcripts, history)
        Log.i(TAG, "开始本地 LLM 分析，prompt 长度: ${prompt.length}")

        isAnalyzing.set(true)

        // 用 CompletableDeferred 在独立协程中执行 native 调用，
        // 超时/取消后 native 调用仍会在后台完成并安全释放引擎。
        val resultDeferred = CompletableDeferred<String>()
        val nativeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        nativeScope.launch {
            try {
                inferenceMutex.withLock {
                    val inf = llmInference
                    if (inf != null) {
                        val result = inf.generateResponse(prompt)
                        resultDeferred.complete(result)
                    } else {
                        resultDeferred.complete("")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "native 推理异常", e)
                resultDeferred.complete("")
            } finally {
                // native 调用完成后（无论成功/异常/超时后自然结束），安全释放引擎
                release()
                isAnalyzing.set(false)
                Log.i(TAG, "LLM 分析结束，引擎已自动释放")
            }
        }

        // 等待结果，超时返回空（不调用 release，由 native 协程自行处理）
        val response = withTimeoutOrNull(ANALYSIS_TIMEOUT_MS) {
            resultDeferred.await()
        }

        if (response == null) {
            Log.e(TAG, "LLM 分析超时（${ANALYSIS_TIMEOUT_MS / 1000}s），等待 native 完成后自动释放")
            return ""
        }

        Log.i(TAG, "LLM 分析完成，响应长度: ${response.length}")
        return extractJsonFromResponse(response)
    }

    /** 是否正在分析中 */
    fun isAnalyzing(): Boolean = isAnalyzing.get()

    /** 构建分析 prompt */
    private fun buildPrompt(
        dateStr: String,
        transcripts: List<TranscriptEntity>,
        history: List<AnalysisEntity>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("你是一个贴心的个人日记助手。请分析以下录音转写内容，生成一份当日日记和情绪分析。")
        sb.appendLine()
        sb.appendLine("日期：$dateStr")
        sb.appendLine()

        // 转写内容
        sb.appendLine("=== 今日录音转写内容 ===")
        transcripts.forEachIndexed { idx, t ->
            val preview = if (t.text.length > 500) t.text.take(500) + "..." else t.text
            sb.appendLine("【第${idx + 1}段】$preview")
        }
        sb.appendLine()

        // 历史上下文
        if (history.isNotEmpty()) {
            sb.appendLine("=== 最近分析历史 ===")
            history.takeLast(3).forEach { a ->
                sb.appendLine("${a.dateStr}: 情绪=${a.emotion}(${a.emotionScore}), 摘要=${a.summary}")
            }
            sb.appendLine()
        }

        // 输出要求
        sb.appendLine("=== 请按以下 JSON 格式输出（不要输出其他内容）===")
        sb.appendLine("""{"diary": "用第一人称写的日记，200-400字，自然流畅", "emotion": "主要情绪(开心/平静/疲惫/焦虑/悲伤)", "emotionScore": -1.0到1.0之间的浮点数, "summary": "一句话总结", "fullAnalysis": "详细分析，包括情绪分析、关键词、趋势、建议"}""")

        return sb.toString()
    }

    /** 从 LLM 响应中提取 JSON */
    private fun extractJsonFromResponse(response: String): String {
        // 尝试找到 JSON 部分
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1)
        }
        // 如果没有找到 JSON，构建一个默认结果
        return Gson().toJson(mapOf(
            "diary" to response.take(500),
            "emotion" to "平静",
            "emotionScore" to 0.0,
            "summary" to "本地分析",
            "fullAnalysis" to response
        ))
    }

    /** 释放资源 */
    @Synchronized
    fun release() {
        try { llmInference?.close() } catch (_: Throwable) {}
        llmInference = null
        initFailedTime = 0L
    }
}
