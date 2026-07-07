package com.example.ailogapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ailogapp.App
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.worker.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 每日定时检查广播接收器。
 *
 * 每天晚上8点触发，检查：
 * 1. 是否有未转写的录音 → 如果有，触发转写任务
 * 2. 当天录音是否有AI分析 → 如果没有，触发分析任务
 */
class DailyCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        LogUtils.i(context, TAG, "每日定时检查触发")

        val app = context.applicationContext as App
        val db = app.database

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 检查是否有未转写的录音
                val untranscribedCount = db.audioRecordDao().countUntranscribed()
                LogUtils.i(context, TAG, "未转写录音数量: $untranscribedCount")

                if (untranscribedCount > 0) {
                    LogUtils.i(context, TAG, "发现未转写的录音，触发转写任务")
                    WorkScheduler.enqueueTranscriptionManual(context)
                }

                // 检查当天是否有AI分析
                val today = FileUtils.todayStr()
                val todayAnalysis = db.analysisDao().getByDate(today)
                val hasTodayRecords = db.audioRecordDao().hasRecordsForDate(today)

                LogUtils.i(context, TAG, "当天日期: $today, 当天是否有录音: $hasTodayRecords, 是否有分析: ${todayAnalysis != null}")

                if (hasTodayRecords && todayAnalysis == null) {
                    LogUtils.i(context, TAG, "当天有录音但没有AI分析，触发分析任务")
                    WorkScheduler.enqueueAnalysisManual(context, today)
                }

                // 自动删除旧录音（只删录音文件，保留转写文字）
                val prefs = app.prefs
                if (prefs.autoDeleteEnabled) {
                    autoDeleteOldRecordings(context, prefs.autoDeleteDays)
                }

                // 设置下一次检查
                scheduleNextCheck(context)
            } catch (e: Exception) {
                LogUtils.e(context, TAG, "每日检查失败: ${e.message}", e)
                // 设置下一次检查
                scheduleNextCheck(context)
            }
        }
    }

    /**
     * 自动删除超过指定天数的录音文件（只删录音文件，保留转写文字和数据库记录）。
     */
    private suspend fun autoDeleteOldRecordings(context: Context, days: Int) {
        try {
            val app = context.applicationContext as App
            val db = app.database

            // 计算N天前的日期
            val calendar = java.util.Calendar.getInstance()
            calendar.add(java.util.Calendar.DAY_OF_MONTH, -days)
            val cutoffDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(calendar.time)

            LogUtils.i(context, TAG, "自动删除已转写旧录音：删除 $cutoffDate 之前已转写的录音文件，保留转写文字")

            // 获取所有日期
            val allDates = db.audioRecordDao().getAllDates()
            // 筛选出需要删除的日期（早于cutoffDate的日期）
            val datesToDelete = allDates.filter { it < cutoffDate }

            if (datesToDelete.isEmpty()) {
                LogUtils.i(context, TAG, "没有需要自动删除的旧录音")
                return
            }

            LogUtils.i(context, TAG, "需要自动删除的日期共 ${datesToDelete.size} 个: $datesToDelete")

            var deletedCount = 0

            for (date in datesToDelete) {
                // 获取该日期下所有未删除且已转写（transcribeStatus==2）的录音
                // 未转写的录音不删除，避免数据丢失
                val records = db.audioRecordDao().getByDate(date)
                    .filter { !it.isDeleted && it.transcribeStatus == 2 }

                if (records.isEmpty()) continue

                val idsToDelete = records.map { it.id }

                // 删除物理录音文件
                for (record in records) {
                    runCatching {
                        val file = java.io.File(record.filePath)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }

                // 标记为已删除（软删除）
                db.audioRecordDao().markAsDeleted(idsToDelete)
                deletedCount += records.size
            }

            LogUtils.i(context, TAG, "自动删除完成：共删除 $deletedCount 个录音文件")

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "自动删除录音失败: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "DailyCheckReceiver"
        private const val REQUEST_CODE = 3001
        private const val ACTION_DAILY_CHECK = "com.example.ailogapp.DAILY_CHECK"

        /**
         * 设置每天晚上8点的定时检查。
         */
        fun scheduleNextCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, DailyCheckReceiver::class.java).apply {
                action = ACTION_DAILY_CHECK
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 计算今天晚上8点的时间，如果已经过了就设置为明天晚上8点
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 20)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }

            // 如果现在已经过了晚上8点，就设置为明天
            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }

            val triggerTime = calendar.timeInMillis
            LogUtils.i(context, TAG, "下次每日检查时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(triggerTime)}")

            // Android 12+ 需要权限才能设置精确闹钟，用 inexact 替代
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        }

        /** 取消定时检查 */
        fun cancelCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, DailyCheckReceiver::class.java).apply {
                action = ACTION_DAILY_CHECK
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pendingIntent)
            LogUtils.i(context, TAG, "每日定时检查已取消")
        }
    }
}
