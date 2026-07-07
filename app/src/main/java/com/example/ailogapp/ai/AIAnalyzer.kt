package com.example.ailogapp.ai

import android.content.Context
import android.util.Log
import com.example.ailogapp.data.AppDatabase
import com.example.ailogapp.data.entities.AnalysisEntity
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.data.entities.TranscriptEntity
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.PrefsManager
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * AI 分析器：替用户写日记 + 情绪分析。
 *
 * 核心增量逻辑：
 * - 当天素材少时，分析也会基于已有转写生成（哪怕只有一段）；
 * - 当存在历史分析时，把最近 N 天的摘要拼进 prompt，让 AI 结合趋势判断情绪；
 * - 若当天已有分析结果，则"增量更新"——重新结合历史生成更完整的当日结果。
 *
 * 返回结构化 JSON：{ diary, emotion, emotionScore(-1~1), summary, fullAnalysis }
 */
class AIAnalyzer(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: PrefsManager
) {

    private val gson = Gson()

    /**
     * 对指定日期生成（或更新）AI 分析结果。
     * @param dateStr yyyy-MM-dd
     * @return 是否成功
     */
    suspend fun analyze(dateStr: String): Boolean {
        val transcripts = db.transcriptDao().getByDate(dateStr)
        if (transcripts.isEmpty()) {
            Log.i(TAG, "$dateStr 无转写内容，跳过分析")
            return false
        }

        // 根据模式选择本地或云端分析
        return if (prefs.analyzeMode == PrefsManager.ANALYZE_LOCAL) {
            analyzeLocal(dateStr, transcripts)
        } else {
            analyzeCloud(dateStr, transcripts)
        }
    }

    /** 本地离线 LLM 分析（端侧大模型） */
    private suspend fun analyzeLocal(
        dateStr: String,
        transcripts: List<TranscriptEntity>
    ): Boolean {
        Log.i(TAG, "使用本地 LLM 引擎分析 $dateStr")

        // 检查模型是否可用
        if (!LocalLLMEngine.isModelAvailable(context)) {
            Log.w(TAG, "本地 LLM 模型未下载，跳过分析")
            return false
        }

        return try {
            val history = db.analysisDao().getRecentBefore(dateStr, prefs.analysisHistoryDays)
            val existing = db.analysisDao().getByDate(dateStr)

            val json = LocalLLMEngine.analyze(context, dateStr, transcripts, history)
            if (json.isEmpty()) {
                Log.e(TAG, "本地 LLM 分析返回空结果")
                return false
            }
            val parsed = parseResult(json)
            saveResult(dateStr, transcripts, existing, parsed)
            Log.i(TAG, "$dateStr 本地 LLM 分析完成：${parsed.emotion}(${parsed.emotionScore})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "本地 LLM 分析失败", e)
            false
        }
    }

    /** 云端大模型分析 */
    private suspend fun analyzeCloud(
        dateStr: String,
        transcripts: List<TranscriptEntity>
    ): Boolean {
        val apiKey = prefs.aiApiKey.trim()
        val apiUrl = prefs.aiApiUrl.trim()
        if (apiKey.isEmpty() || apiUrl.isEmpty()) {
            Log.w(TAG, "未配置 AI 接口，跳过云端分析")
            return false
        }

        // 取最近 N 天历史分析作为上下文
        val history = db.analysisDao().getRecentBefore(dateStr, prefs.analysisHistoryDays)
        val existing = db.analysisDao().getByDate(dateStr)

        // 查询录音记录以获取实际开始时间
        val audioIds = transcripts.map { it.audioId }.distinct()
        val audioRecords = if (audioIds.isEmpty()) {
            emptyMap()
        } else {
            db.audioRecordDao().getByIds(audioIds).associateBy { it.id }
        }

        val prompt = buildPrompt(dateStr, transcripts, history, existing, audioRecords)
        val messages = listOf(
            ChatMessage("system", SYSTEM_PROMPT),
            ChatMessage("user", prompt)
        )
        val request = ChatRequest(
            model = prefs.aiModel,
            messages = messages,
            temperature = 0.8,
            responseFormat = mapOf("type" to "json_object")
        )

        return try {
            val resp = AIClient.service.chatCompletions(
                apiUrl, AIClient.bearer(apiKey), request
            )
            if (!resp.isSuccessful) {
                Log.w(TAG, "AI 接口返回 ${resp.code()}: ${resp.errorBody()?.string()}")
                return false
            }
            val content = resp.body()?.firstContent ?: ""
            val parsed = parseResult(content)
            saveResult(dateStr, transcripts, existing, parsed)
            Log.i(TAG, "$dateStr 云端分析完成：${parsed.emotion}(${parsed.emotionScore})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "云端分析失败", e)
            false
        }
    }

    /**
     * 公共结果保存逻辑：analyzeLocal 与 analyzeCloud 共用。
     *
     * coveredDurationMs 取自 transcripts 对应 audioRecords 的实际 durationMs 之和，
     * 而非按"段数 * 30 分钟"硬编码估算，避免时长统计失真（每段实际时长可能不同）。
     */
    private suspend fun saveResult(
        dateStr: String,
        transcripts: List<TranscriptEntity>,
        existing: AnalysisEntity?,
        parsed: ParsedResult
    ) {
        val audioIds = transcripts.map { it.audioId }.distinct()
        val totalDuration = if (audioIds.isEmpty()) {
            0L
        } else {
            db.audioRecordDao().getByIds(audioIds).sumOf { it.durationMs }
        }

        val entity = (existing ?: AnalysisEntity(dateStr = dateStr)).copy(
            diaryContent = parsed.diary,
            emotion = parsed.emotion,
            emotionScore = parsed.emotionScore,
            summary = parsed.summary,
            fullAnalysis = parsed.full,
            transcriptRefs = transcripts.joinToString(",") { it.id.toString() },
            coveredDurationMs = totalDuration,
            updatedAt = System.currentTimeMillis()
        )
        db.analysisDao().upsert(entity)

        // 标记对应录音已纳入当天分析
        if (audioIds.isNotEmpty()) {
            db.audioRecordDao().markAnalyzed(audioIds, true)
        }
    }

    private fun buildPrompt(
        dateStr: String,
        transcripts: List<TranscriptEntity>,
        history: List<AnalysisEntity>,
        existing: AnalysisEntity?,
        audioRecords: Map<Long, AudioRecordEntity> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("今天是 $dateStr。下面是我今天各时段的语音转文字内容（按时段排列）：")
        sb.appendLine("----------")
        transcripts.forEachIndexed { idx, t ->
            val record = audioRecords[t.audioId]
            val time = if (record != null) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(record.startTime))
            } else {
                FileUtils.nowStr().substring(11)
            }
            sb.appendLine("【第${idx + 1}段 $time】来源=${t.source}")
            sb.appendLine(if (t.text.isBlank()) "（无有效语音内容）" else t.text)
            sb.appendLine()
        }
        sb.appendLine("----------")

        if (history.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("以下是最近 ${history.size} 天的日记与情绪记录，请结合趋势分析我今天的情绪变化：")
            history.forEach { h ->
                sb.appendLine("- ${h.dateStr}：情绪=${h.emotion}(${h.emotionScore})，摘要=${h.summary}")
            }
        }

        existing?.let {
            sb.appendLine()
            sb.appendLine("今天此前已有初步分析（摘要：${it.summary}），请在此基础上结合新增内容更新完善。")
        }

        sb.appendLine()
        sb.appendLine("请以第一人称替我写一篇当天的日记，并分析我的情绪。")
        sb.appendLine("严格输出 JSON，字段如下：")
        sb.appendLine("{\"diary\":\"日记正文\",")
        sb.appendLine("\"emotion\":\"主要情绪标签(如 开心/焦虑/平静/疲惫)\",")
        sb.appendLine("\"emotionScore\":-1到1之间的数字(消极到积极),")
        sb.appendLine("\"summary\":\"一句话总结\",")
        sb.appendLine("\"fullAnalysis\":\"包含情绪趋势、关键词、建议的详细分析\"}")
        return sb.toString()
    }

    private fun parseResult(content: String): ParsedResult {
        return try {
            val obj = JsonParser.parseString(content).asJsonObject
            ParsedResult(
                diary = obj.get("diary")?.asString ?: "",
                emotion = obj.get("emotion")?.asString ?: "未知",
                emotionScore = parseFloatSafe(obj.get("emotionScore")),
                summary = obj.get("summary")?.asString ?: "",
                // 优先取 fullAnalysis 字段，避免 fullAnalysis 存储整个 JSON 串导致 UI 展示冗余
                full = obj.get("fullAnalysis")?.asString ?: content
            )
        } catch (e: Exception) {
            // 模型未返回合法 JSON，退化为整体文本
            ParsedResult(
                diary = content,
                emotion = "未知",
                emotionScore = 0f,
                summary = content.take(50),
                full = content
            )
        }
    }

    private fun parseFloatSafe(element: com.google.gson.JsonElement?): Float {
        return try {
            when {
                element == null || element.isJsonNull -> 0f
                else -> element.toString().replace("\"", "").toFloatOrNull() ?: 0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    private data class ParsedResult(
        val diary: String,
        val emotion: String,
        val emotionScore: Float,
        val summary: String,
        val full: String
    )

    companion object {
        private const val TAG = "AIAnalyzer"

        private const val SYSTEM_PROMPT = """你是一个贴心的个人日记助手。你会根据用户一天中各时段的语音转文字内容，替用户用第一人称写一篇自然流畅的日记，并分析用户的情绪状态。当有历史记录时，请结合近期情绪趋势，给出有连续性的判断和建议。语气温暖、真诚、有洞察力。必须严格按照要求的 JSON 格式输出，不要输出多余内容。"""
    }
}
