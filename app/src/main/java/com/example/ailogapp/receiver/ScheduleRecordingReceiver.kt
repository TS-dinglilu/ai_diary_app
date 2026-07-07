package com.example.ailogapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.ailogapp.service.RecordingService
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.util.ScheduleRecordingHelper

/**
 * 定时录音广播接收器。
 *
 * 接收两个由 [ScheduleRecordingHelper] 设置的定时闹钟广播：
 * - [ACTION_START_RECORDING]：启动录音服务（前台服务），并标记 recordingEnabled = true
 * - [ACTION_STOP_RECORDING]：停止录音服务，并标记 recordingEnabled = false
 *
 * 触发后会重新设置次日的闹钟，保证每天循环执行（比 setRepeating 更可靠，
 * 不受系统对重复闹钟的不精确性影响）。
 */
class ScheduleRecordingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        LogUtils.i(context, TAG, "收到定时录音广播: $action")

        when (action) {
            ACTION_START_RECORDING -> handleStart(context)
            ACTION_STOP_RECORDING -> handleStop(context)
        }
    }

    /**
     * 启动录音：
     * 1. 标记 recordingEnabled = true
     * 2. 启动 RecordingService（前台服务）
     * 3. 重新设置次日的启动闹钟
     */
    private fun handleStart(context: Context) {
        val prefs = PrefsManager(context)
        prefs.recordingEnabled = true

        val serviceIntent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            LogUtils.i(context, TAG, "定时启动录音服务已请求")
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务可能抛 ForegroundServiceStartNotAllowedException
            LogUtils.e(context, TAG, "定时启动录音服务失败: ${e.message}", e)
        }

        // 重新设置明天的启动闹钟
        ScheduleRecordingHelper.scheduleStart(context, prefs.scheduleRecordStartTime)
    }

    /**
     * 停止录音：
     * 1. 标记 recordingEnabled = false
     * 2. 停止 RecordingService
     * 3. 重新设置次日的停止闹钟
     */
    private fun handleStop(context: Context) {
        val prefs = PrefsManager(context)
        prefs.recordingEnabled = false

        val serviceIntent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
        try {
            context.startService(serviceIntent)
            LogUtils.i(context, TAG, "定时停止录音服务已请求")
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "定时停止录音服务失败: ${e.message}", e)
        }

        // 重新设置明天的停止闹钟
        ScheduleRecordingHelper.scheduleStop(context, prefs.scheduleRecordStopTime)
    }

    companion object {
        private const val TAG = "ScheduleRecordingReceiver"

        /** 定时启动录音广播 action */
        const val ACTION_START_RECORDING = "com.example.ailogapp.SCHEDULE_START_RECORDING"

        /** 定时停止录音广播 action */
        const val ACTION_STOP_RECORDING = "com.example.ailogapp.SCHEDULE_STOP_RECORDING"
    }
}
