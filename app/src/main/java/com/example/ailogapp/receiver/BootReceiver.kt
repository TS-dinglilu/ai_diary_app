package com.example.ailogapp.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ailogapp.service.RecordingService
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.util.ScheduleRecordingHelper
import com.example.ailogapp.receiver.DailyCheckReceiver

/**
 * 开机自启：若用户之前开启了录音，重启后恢复录音服务。
 *
 * 支持两种开机广播：
 * - LOCKED_BOOT_COMPLETED：加密设备用户解锁前发送（direct boot）
 * - BOOT_COMPLETED：用户解锁后发送
 *
 * 注意：
 * 1. Android 3.1+ 应用安装后处于 STOPPED 状态，必须用户手动启动一次才能收广播
 * 2. Android 8.0+ 必须用 startForegroundService 启动前台服务
 * 3. Android 14+ microphone 前台服务需要 RECORD_AUDIO 权限已授予，否则 startForeground 崩溃
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "收到开机广播: $action")
        LogUtils.i(context, TAG, "收到开机广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                startRecordingService(context)
                // 设置每日定时检查
                DailyCheckReceiver.scheduleNextCheck(context)
                // 恢复定时录音闹钟（设备重启后 AlarmManager 闹钟会被清除）
                val prefs = PrefsManager(context)
                if (prefs.scheduleRecordingEnabled) {
                    ScheduleRecordingHelper.scheduleAll(
                        context,
                        prefs.scheduleRecordStartTime,
                        prefs.scheduleRecordStopTime
                    )
                    LogUtils.i(context, TAG, "定时录音闹钟已恢复: ${prefs.scheduleRecordStartTime} - ${prefs.scheduleRecordStopTime}")
                }
            }
        }
    }

    private fun startRecordingService(context: Context) {
        val prefs = PrefsManager(context)
        if (!prefs.recordingEnabled) {
            Log.i(TAG, "录音未开启，跳过自启动")
            LogUtils.i(context, TAG, "录音未开启，跳过自启动")
            return
        }

        // Android 14+: microphone 前台服务需要录音权限已授予，否则 startForeground 会崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "RECORD_AUDIO 权限未授予，无法自启动录音服务")
                LogUtils.w(context, TAG, "RECORD_AUDIO 权限未授予，无法自启动录音服务")
                return
            }
        }

        val serviceIntent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "录音服务自启动请求已发送")
            LogUtils.i(context, TAG, "录音服务自启动请求已发送")
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务可能抛 ForegroundServiceStartNotAllowedException
            Log.e(TAG, "自启动录音服务失败: ${e.message}", e)
            LogUtils.e(context, TAG, "自启动录音服务失败: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
