package com.example.ailogapp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager

/**
 * 任务调度入口。
 *
 * 转写和分析各有两条触发路径，逻辑完全对称：
 * - 充电时自动触发（charging + network 约束）
 * - 用户手动触发（仅 network 约束，不要求充电）
 *
 * 转写和分析是独立任务，互不依赖，可各自单独调度。
 */
object WorkScheduler {

    // ============ 转写 ============

    /** 充电时自动触发转写 */
    fun enqueueTranscription(context: Context) {
        LogUtils.i(context, "WorkScheduler", "调度转写任务（充电自动触发）")
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(transcribeNetworkType(context))
            .build()

        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptionWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** 用户手动触发转写（不要求充电，不要求网络——内置模型离线运行） */
    fun enqueueTranscriptionManual(context: Context) {
        LogUtils.i(context, "WorkScheduler", "调度转写任务（手动触发）")
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptionWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    // ============ AI 分析 ============

    /** 充电时自动触发 AI 分析（分析当天） */
    fun enqueueAnalysis(context: Context) {
        enqueueAnalysis(context, null)
    }

    /** 用户手动触发 AI 分析（不要求充电） */
    fun enqueueAnalysisManual(context: Context) {
        enqueueAnalysisManual(context, null)
    }

    /** 充电时自动分析指定日期 */
    fun enqueueAnalysis(context: Context, date: String?) {
        LogUtils.i(context, "WorkScheduler", "调度AI分析任务（充电自动触发）, 日期: ${date ?: "今天"}")
        // 本地分析模式离线运行，不要求网络；云端模式才需要联网
        val networkType = analyzeNetworkType(context)
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(AnalysisWorker.KEY_DATE to (date ?: "")))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AnalysisWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** 用户手动触发 AI 分析指定日期（不要求充电） */
    fun enqueueAnalysisManual(context: Context, date: String?) {
        LogUtils.i(context, "WorkScheduler", "调度AI分析任务（手动触发）, 日期: ${date ?: "今天"}")
        // 本地分析模式离线运行，不要求网络；云端模式才需要联网
        val networkType = analyzeNetworkType(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(AnalysisWorker.KEY_DATE to (date ?: "")))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            AnalysisWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * 根据 AI 分析模式决定 WorkManager 的网络约束：
     * - 本地离线分析（ANALYZE_LOCAL）：NetworkType.NOT_REQUIRED，避免无谓地等待网络
     * - 云端分析（ANALYZE_CLOUD）：NetworkType.CONNECTED，必须有网才能调用大模型
     */
    private fun analyzeNetworkType(context: Context): NetworkType {
        val prefs = PrefsManager(context)
        return if (prefs.analyzeMode == PrefsManager.ANALYZE_LOCAL) {
            NetworkType.NOT_REQUIRED
        } else {
            NetworkType.CONNECTED
        }
    }

    /**
     * 根据转写模式决定 WorkManager 的网络约束：
     * - 内置模型（MODE_LOCAL）/ 关闭（MODE_OFF）：离线运行，NetworkType.NOT_REQUIRED
     * - Whisper / AI 大模型（MODE_WHISPER / MODE_AI）：需联网，NetworkType.CONNECTED
     */
    private fun transcribeNetworkType(context: Context): NetworkType {
        val prefs = PrefsManager(context)
        return when (prefs.transcribeMode) {
            PrefsManager.MODE_LOCAL, PrefsManager.MODE_OFF -> NetworkType.NOT_REQUIRED
            else -> NetworkType.CONNECTED
        }
    }
}
