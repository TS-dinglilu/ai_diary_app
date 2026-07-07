package com.example.ailogapp.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.ailogapp.receiver.ScheduleRecordingReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 定时录音闹钟管理工具。
 *
 * 通过 AlarmManager 设置两个精确闹钟：
 * - 启动录音闹钟（[ScheduleRecordingReceiver.ACTION_START_RECORDING]）
 * - 停止录音闹钟（[ScheduleRecordingReceiver.ACTION_STOP_RECORDING]）
 *
 * 采用 setExactAndAllowWhileIdle + 触发后在 receiver 中重新设置次日闹钟的方式，
 * 比 setRepeating 更可靠（不受系统对重复闹钟的不精确性影响）。
 *
 * 时间格式：HH:mm（24 小时制），用 [SimpleDateFormat] 解析。
 */
object ScheduleRecordingHelper {

    private const val TAG = "ScheduleRecordingHelper"
    private const val REQ_CODE_START = 4001
    private const val REQ_CODE_STOP = 4002

    /**
     * 设置启停两个定时闹钟。
     *
     * @param startTime 启动录音时间，格式 HH:mm
     * @param stopTime  停止录音时间，格式 HH:mm
     */
    fun scheduleAll(context: Context, startTime: String, stopTime: String) {
        LogUtils.i(context, TAG, "设置定时录音：启动=$startTime, 停止=$stopTime")
        scheduleStart(context, startTime)
        scheduleStop(context, stopTime)
    }

    /** 设置启动录音闹钟（下一次触发 [startTime] 的时间点）。 */
    fun scheduleStart(context: Context, startTime: String) {
        val triggerAt = nextTriggerTime(context, startTime, "启动录音") ?: return
        setAlarm(
            context,
            ScheduleRecordingReceiver.ACTION_START_RECORDING,
            REQ_CODE_START,
            triggerAt,
            "启动录音",
            startTime
        )
    }

    /** 设置停止录音闹钟（下一次触发 [stopTime] 的时间点）。 */
    fun scheduleStop(context: Context, stopTime: String) {
        val triggerAt = nextTriggerTime(context, stopTime, "停止录音") ?: return
        setAlarm(
            context,
            ScheduleRecordingReceiver.ACTION_STOP_RECORDING,
            REQ_CODE_STOP,
            triggerAt,
            "停止录音",
            stopTime
        )
    }

    /** 取消所有定时录音闹钟。 */
    fun cancelAll(context: Context) {
        cancelAlarm(context, ScheduleRecordingReceiver.ACTION_START_RECORDING, REQ_CODE_START)
        cancelAlarm(context, ScheduleRecordingReceiver.ACTION_STOP_RECORDING, REQ_CODE_STOP)
        LogUtils.i(context, TAG, "已取消所有定时录音闹钟")
    }

    /**
     * 解析 HH:mm 并计算下一次触发时间戳：
     * 今天该时间点未到则取今天，已过则取明天。
     * 解析失败返回 null。
     */
    private fun nextTriggerTime(context: Context, timeStr: String, desc: String): Long? {
        val (hour, minute) = parseHourMinute(timeStr) ?: run {
            LogUtils.w(context, TAG, "$desc 时间格式无效: $timeStr（应为 HH:mm），跳过设置闹钟")
            return null
        }
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 如果当前已过该时间点，则设为明天
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return calendar.timeInMillis
    }

    /** 解析 HH:mm 字符串为 (hour, minute)，失败返回 null。 */
    private fun parseHourMinute(timeStr: String): Pair<Int, Int>? {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.isLenient = false
            val date = sdf.parse(timeStr) ?: return null
            val cal = Calendar.getInstance()
            cal.time = date
            Pair(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 设置精确闹钟。
     *
     * - API 23+：使用 setExactAndAllowWhileIdle
     * - Android 12+：若无 SCHEDULE_EXACT_ALARM 权限（canScheduleExactAlarms == false），
     *  退化为 setAndAllowWhileIdle 避免崩溃
     * - 低版本：使用 setExact
     */
    private fun setAlarm(
        context: Context,
        action: String,
        requestCode: Int,
        triggerAt: Long,
        desc: String,
        timeStr: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleRecordingReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }

        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(triggerAt)
        LogUtils.i(context, TAG, "已设置${desc}闹钟：$timeStr → 下次触发 $timeText")
    }

    /** 取消指定 action / requestCode 对应的闹钟。 */
    private fun cancelAlarm(context: Context, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleRecordingReceiver::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager.cancel(pendingIntent)
    }
}
