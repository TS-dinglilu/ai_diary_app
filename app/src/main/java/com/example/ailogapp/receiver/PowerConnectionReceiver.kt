package com.example.ailogapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.worker.WorkScheduler

/**
 * 监听接入充电：根据用户的设置决定是否触发转写和分析。
 * 两个开关独立控制，互不影响。
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        LogUtils.i(context, "PowerConnectionReceiver", "检测到电源连接")

        val prefs = PrefsManager(context)
        if (prefs.autoTranscribeOnCharging) {
            LogUtils.i(context, "PowerConnectionReceiver", "自动转写已开启，触发转写任务")
            WorkScheduler.enqueueTranscription(context)
        } else {
            LogUtils.d(context, "PowerConnectionReceiver", "自动转写未开启，跳过")
        }
        if (prefs.autoAnalyzeOnCharging) {
            LogUtils.i(context, "PowerConnectionReceiver", "自动AI分析已开启，触发分析任务")
            WorkScheduler.enqueueAnalysis(context)
        } else {
            LogUtils.d(context, "PowerConnectionReceiver", "自动AI分析未开启，跳过")
        }
    }
}
