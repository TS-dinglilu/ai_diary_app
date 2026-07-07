package com.example.ailogapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ailogapp.App
import com.example.ailogapp.ai.TranscriptionProvider
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.LogUtils
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 语音转文字 Worker。
 *
 * 职责单一：只负责把待转写录音转为文字并入库。
 * 不再自动触发 AI 分析——分析由独立的 [AnalysisWorker] 调度，
 * 二者各有"充电自动"和"手动按钮"两条路径。
 *
 * 即使转写模式为关闭（off），也会写入占位 txt（空），便于后续补处理。
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val db = app.database
        val provider = TranscriptionProvider(applicationContext)

        LogUtils.i(applicationContext, TAG, "========== 转写任务开始 ==========")

        val pending = db.audioRecordDao().getByStatus(0) + db.audioRecordDao().getByStatus(3)
        if (pending.isEmpty()) {
            LogUtils.i(applicationContext, TAG, "无待转写录音，任务结束")
            return Result.success()
        }
        LogUtils.i(applicationContext, TAG, "待转写录音 ${pending.size} 段")

        var successCount = 0
        var failCount = 0

        // 单条录音转写超时（5 分钟），防止 native ASR 卡死导致整个任务阻塞
        val perRecordTimeoutMs = 300_000L

        for ((index, record) in pending.withIndex()) {
            // 检查任务是否被取消
            if (isStopped) {
                LogUtils.i(applicationContext, TAG, "任务被停止，已完成 $successCount 个，失败 $failCount 个")
                break
            }

            LogUtils.d(applicationContext, TAG, "处理第 ${index + 1}/${pending.size} 条: ${record.fileName} (ID: ${record.id})")

            val file = File(record.filePath)
            if (!file.exists()) {
                LogUtils.w(applicationContext, TAG, "文件不存在: ${record.filePath}")
                db.audioRecordDao().updateTranscribeStatus(record.id, 3)
                failCount++
                continue
            }

            LogUtils.d(applicationContext, TAG, "文件大小: ${file.length()} 字节, 时长: ${record.durationMs}ms")
            db.audioRecordDao().updateTranscribeStatus(record.id, 1)

            try {
                val startTime = System.currentTimeMillis()
                // 用 withTimeoutOrNull 防止 native ASR 调用卡死
                val result = withTimeoutOrNull(perRecordTimeoutMs) {
                    provider.transcribe(file)
                }

                if (result == null) {
                    LogUtils.w(applicationContext, TAG, "转写超时（${perRecordTimeoutMs / 1000}s）: ${record.fileName}")
                    db.audioRecordDao().updateTranscribeStatus(record.id, 3)
                    failCount++
                    continue
                }

                val elapsed = System.currentTimeMillis() - startTime

                // 保存 txt（无论是否有内容，都生成占位文件）
                val txtFile = FileUtils.newTranscriptFile(applicationContext, record.fileName)
                txtFile.writeText(result.text)

                db.transcriptDao().insert(
                    com.example.ailogapp.data.entities.TranscriptEntity(
                        audioId = record.id,
                        dateStr = record.dateStr,
                        text = result.text,
                        txtPath = txtFile.absolutePath,
                        source = result.source,
                        speakerId = result.speakerId
                    )
                )
                db.audioRecordDao().updateTranscribeStatus(record.id, 2)

                LogUtils.i(applicationContext, TAG, "转写完成: ${record.fileName} (${result.source}), 耗时 ${elapsed}ms, 文本长度 ${result.text.length}")
                successCount++
            } catch (e: Throwable) {
                LogUtils.e(applicationContext, TAG, "转写失败: ${record.fileName}", e)
                db.audioRecordDao().updateTranscribeStatus(record.id, 3)
                failCount++
            }
        }

        LogUtils.i(applicationContext, TAG, "========== 转写任务结束: 成功 $successCount, 失败 $failCount ==========")
        return Result.success()
    }

    companion object {
        const val TAG = "TranscriptionWorker"
        const val UNIQUE_NAME = "transcription_work"
    }
}
