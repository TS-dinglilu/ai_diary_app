package com.example.ailogapp.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ailogapp.App
import com.example.ailogapp.util.PrefsManager

/**
 * 守护广播接收器。
 *
 * 接收以下事件来尝试重启录音服务：
 * 1. AlarmManager 定时闹钟（每 5 分钟检查一次）
 * 2. 系统启动完成（BOOT_COMPLETED）
 * 3. 屏幕亮起（SCREEN_ON）
 * 4. 系统时间变化（TIME_TICK）
 *
 * 只要 prefs.recordingEnabled == true，就会尝试启动 RecordingService。
 * 即使用户主动杀掉 App，AlarmManager 仍然会在 5 分钟后触发重启。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "收到广播: ${intent.action}")

        val prefs = (context.applicationContext as App).prefs
        if (!prefs.recordingEnabled) {
            Log.i(TAG, "录音未开启，不重启服务")
            return
        }

        // Android 14+: microphone 前台服务需要录音权限已授予，否则 startForeground 崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO 权限未授予，跳过重启")
            scheduleNextCheck(context)
            return
        }

        // 尝试启动录音服务
        val serviceIntent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "录音服务重启请求已发送")
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务可能抛 ForegroundServiceStartNotAllowedException
            Log.e(TAG, "重启录音服务失败: ${e.message}", e)
        }

        // 重新设置下一次定时检查
        scheduleNextCheck(context)
    }

    companion object {
        private const val TAG = "KeepAliveReceiver"
        private const val REQUEST_CODE = 2001
        /** 检查间隔：5 分钟（更省电） */
        private const val CHECK_INTERVAL_MS = 300_000L

        /**
         * 设置定时检查：5 分钟后触发 KeepAliveReceiver。
         * 使用 AlarmManager.ELAPSED_REALTIME_WAKEUP，即使设备休眠也能唤醒。
         */
        fun scheduleNextCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = "com.example.ailogapp.KEEP_ALIVE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Android 12+ 需要权限才能设置精确闹钟，用 inexact 替代
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                    pendingIntent
                )
            }
            Log.d(TAG, "下次检查已设置，${CHECK_INTERVAL_MS / 60000}分钟后")
        }

        /** 取消定时检查 */
        fun cancelCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = "com.example.ailogapp.KEEP_ALIVE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pendingIntent)
            Log.i(TAG, "定时检查已取消")
        }
    }
}
