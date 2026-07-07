package com.example.ailogapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ailogapp.App
import com.example.ailogapp.ai.AIAnalyzer
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.LogUtils
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 分析 Worker：对所有需要分析的日期按时间顺序执行 AI 分析。
 *
 * 分析策略：
 * - 遍历所有有转写的日期
 * - 对每个日期判断是否需要分析：
 *   - 未分析过 → 需要分析
 *   - 已分析过但转写内容有新增（transcript id 集合变化）→ 需要分析
 *   - 已分析过且转写内容无变化 → 跳过，避免重复分析
 * - 按日期从早到晚依次分析
 * - 后续日期的分析结合当天录音和所有历史分析结果
 */
class AnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val rawDate = inputData.getString(KEY_DATE) ?: ""
        val targetDate = if (rawDate.isBlank()) null else rawDate

        LogUtils.i(applicationContext, TAG, "========== AI分析任务开始 ==========")

        val startTime = System.currentTimeMillis()
        val analyzer = AIAnalyzer(applicationContext, app.database, app.prefs)

        // 如果指定了日期，判断是否需要分析后决定是否执行
        if (targetDate != null) {
            if (!needsAnalysis(app.database, targetDate)) {
                LogUtils.i(applicationContext, TAG, "$targetDate 已分析且无新增转写，跳过")
                LogUtils.i(applicationContext, TAG, "========== AI分析任务结束 ==========")
                return Result.success()
            }
            val ok = analyzer.analyze(targetDate)
            val elapsed = System.currentTimeMillis() - startTime
            LogUtils.i(applicationContext, TAG, "分析 $targetDate => $ok, 耗时 ${elapsed}ms")
            LogUtils.i(applicationContext, TAG, "========== AI分析任务结束 ==========")
            return if (ok) Result.success() else Result.retry()
        }

        // 否则分析所有需要分析的日期
        try {
            // 获取所有有转写的日期
            val allTranscriptDates = app.database.transcriptDao().getAllDates()

            // 找出需要分析的日期（未分析 或 已分析但有新增转写）
            val datesToAnalyze = mutableListOf<String>()
            for (date in allTranscriptDates.sorted()) {  // 按日期从早到晚排序
                if (needsAnalysis(app.database, date)) {
                    datesToAnalyze.add(date)
                }
            }

            if (datesToAnalyze.isEmpty()) {
                LogUtils.i(applicationContext, TAG, "没有需要分析的日期，任务结束")
                LogUtils.i(applicationContext, TAG, "========== AI分析任务结束 ==========")
                return Result.success()
            }

            LogUtils.i(applicationContext, TAG, "待分析日期共 ${datesToAnalyze.size} 个: $datesToAnalyze")

            var successCount = 0
            var failCount = 0

            // 单日分析超时（3 分钟），防止 native 推理卡死导致整个任务阻塞
            val perDateTimeoutMs = 180_000L

            // 按日期从早到晚依次分析
            for (date in datesToAnalyze) {
                if (isStopped) {
                    LogUtils.i(applicationContext, TAG, "任务被停止，已完成 $successCount 个，失败 $failCount 个")
                    break
                }

                val dateStartTime = System.currentTimeMillis()
                // 用 withTimeoutOrNull 防止单日分析卡死，超时则跳过该日期
                val ok = withTimeoutOrNull(perDateTimeoutMs) {
                    analyzer.analyze(date)
                } ?: false
                val dateElapsed = System.currentTimeMillis() - dateStartTime

                if (ok) {
                    successCount++
                    LogUtils.i(applicationContext, TAG, "✓ $date 分析成功，耗时 ${dateElapsed}ms")
                } else {
                    failCount++
                    LogUtils.i(applicationContext, TAG, "✗ $date 分析失败或超时，耗时 ${dateElapsed}ms")
                }
            }

            val totalElapsed = System.currentTimeMillis() - startTime
            LogUtils.i(
                applicationContext, TAG,
                "分析完成：成功 $successCount 个，失败 $failCount 个，总耗时 ${totalElapsed}ms"
            )
            LogUtils.i(applicationContext, TAG, "========== AI分析任务结束 ==========")

            // 只要有成功的就返回成功
            return if (successCount > 0) Result.success() else Result.retry()

        } catch (e: Exception) {
            LogUtils.e(applicationContext, TAG, "AI分析任务异常", e)
            return Result.retry()
        }
    }

    /**
     * 判断某日期是否需要 AI 分析。
     *
     * - 没有分析记录 → 需要分析
     * - 有分析记录，但当前转写 id 集合与 [AnalysisEntity.transcriptRefs] 不同 → 需要分析（有新增/删除的转写）
     * - 有分析记录且转写 id 集合完全相同 → 不需要分析（无变化）
     */
    private suspend fun needsAnalysis(db: com.example.ailogapp.data.AppDatabase, dateStr: String): Boolean {
        val existing = db.analysisDao().getByDate(dateStr) ?: return true
        val currentTranscriptIds = db.transcriptDao().getByDate(dateStr).map { it.id }.toSet()
        val analyzedIds = existing.transcriptRefs
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        // 转写 id 集合不同（有新增或删除）则需要重新分析
        return currentTranscriptIds != analyzedIds
    }

    companion object {
        const val TAG = "AnalysisWorker"
        const val UNIQUE_NAME = "analysis_work"
        const val KEY_DATE = "target_date"
    }
}
