package com.example.ailogapp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ailogapp.App
import com.example.ailogapp.MainActivity
import com.example.ailogapp.R
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.recorder.AudioRecorderManager
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.worker.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 持续录音前台服务。
 *
 * 核心能力：
 * 1) 前台服务 + WakeLock，保持进程存活
 * 2) START_STICKY：进程被杀后系统自动重启服务
 * 3) 服务重启时：恢复孤儿录音 → 自动继续录音
 * 4) 电话检测：来电/通话时停止录音，挂断后自动恢复
 * 5) 麦克风冲突检测：AudioRecord.read() 返回负值时停止录音
 * 6) 通知栏：录音中="身心健康"，仅运行="身心悲伤"
 * 7) 通知常驻：每秒刷新通知 + DeleteIntent 立即恢复被划掉的通知（类似华为运动健康）
 */
class RecordingService : Service() {

    private lateinit var recorder: AudioRecorderManager
    private lateinit var prefs: PrefsManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var telephonyManager: TelephonyManager

    /** 充电状态广播接收器（动态注册，更可靠） */
    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                LogUtils.i(this@RecordingService, TAG, "检测到电源连接（动态接收器）")
                if (prefs.autoTranscribeOnCharging) {
                    LogUtils.i(this@RecordingService, TAG, "自动转写已开启，触发转写任务")
                    WorkScheduler.enqueueTranscription(context)
                }
                if (prefs.autoAnalyzeOnCharging) {
                    LogUtils.i(this@RecordingService, TAG, "自动AI分析已开启，触发分析任务")
                    WorkScheduler.enqueueAnalysis(context)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var currentSegmentName: String = ""
    private var isInCall = false
    private var isMicConflict = false

    /** 最近一次录制的已用秒数（用于通知刷新及通知被划掉后重建） */
    @Volatile private var lastElapsedSeconds: Long = 0

    /** 最近一次录音启动/运行错误（用于通知栏诊断显示） */
    @Volatile private var lastError: String = ""

    /** 录音启动失败后的累计重试次数（用于通知栏诊断显示） */
    @Volatile private var startRetryCount: Int = 0

    /**
     * 通知持续刷新定时器：每秒重新发布通知，确保：
     * 1) 通知始终停留在通知栏（即使被系统或用户清除也能立刻恢复）
     * 2) 录制时长持续更新（类似华为运动健康的常驻通知）
     */
    private val notifHandler = Handler(Looper.getMainLooper())
    private val notifRefreshRunnable = object : Runnable {
        override fun run() {
            updateNotification(lastElapsedSeconds)
            notifHandler.postDelayed(this, NOTIF_REFRESH_INTERVAL_MS)
        }
    }

    /** 麦克风冲突后定时重试的 Handler */
    private val retryHandler = Handler(Looper.getMainLooper())
    private var lastConflictTime: Long = 0  // 上次冲突时间（elapsedRealtime）

    /** 麦克风冲突后每 10 秒尝试恢复录音 */
    private val micConflictRetryRunnable = object : Runnable {
        override fun run() {
            if (isMicConflict && !isInCall && prefs.recordingEnabled) {
                Log.i(TAG, "尝试恢复录音（麦克风冲突已过 ${SystemClock.elapsedRealtime() - lastConflictTime}ms）")
                tryResumeRecording()
                if (isMicConflict) {
                    // 仍然冲突，10 秒后再试
                    retryHandler.postDelayed(this, 10_000)
                }
            }
        }
    }

    /**
     * 录音启动失败后的定时重试任务。
     * 与 [micConflictRetryRunnable] 互斥：仅当未处于麦克风冲突、未通话、
     * 且录音确实未运行时才触发，避免服务陷入"运行中但不录音"的死锁。
     *
     * 触发场景：App 被关闭后服务由 START_STICKY / KeepAlive 重启，
     * 此时 [AudioRecorderManager.startRecording] 可能在 init 阶段失败
     * （AudioRecord 初始化失败、麦克风被上一实例短暂占用、ROM 后台限制等），
     * 录音线程未启动故不会回调 onMicConflict，原代码无任何重试，
     * 导致通知栏永久卡在"服务运行中 · 00:00:00"。
     */
    private val startRetryRunnable = object : Runnable {
        override fun run() {
            if (!recorder.isRecording() && !isInCall && !isMicConflict && prefs.recordingEnabled) {
                Log.i(TAG, "录音未运行，尝试启动录音（启动失败重试）")
                ensureRecording()
                if (!recorder.isRecording()) {
                    // 仍然失败，10 秒后再试
                    retryHandler.postDelayed(this, START_RETRY_INTERVAL_MS)
                }
            }
        }
    }

    /** 电话状态监听器 */
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.i(TAG, "电话状态变化: $state，停止录音")
                    isInCall = true
                    if (recorder.isRecording()) {
                        pauseRecordingForConflict("电话")
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (isInCall) {
                        Log.i(TAG, "电话结束，尝试恢复录音")
                        isInCall = false
                        tryResumeRecording()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = (application as App).prefs
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIDiaryApp::Record")
        wakeLock.setReferenceCounted(false)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        // READ_PHONE_STATE 是 dangerous 权限，未授予时 listen() 会抛 SecurityException。
        // 此处降级处理：权限缺失时仅不监听通话状态，不阻塞服务创建。
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "缺少 READ_PHONE_STATE 权限，无法监听通话状态", e)
        } catch (e: Exception) {
            Log.w(TAG, "注册电话状态监听失败", e)
        }

        recorder = AudioRecorderManager(
            context = this,
            prefs = prefs,
            object : AudioRecorderManager.Callback {
                override suspend fun onSegmentFinished(record: AudioRecordEntity) {
                    LogUtils.d(this@RecordingService, TAG, "录音片段完成: ${record.fileName}, 时长 ${record.durationMs}ms, 大小 ${record.fileSizeBytes}字节")
                    val dao = (application as App).database.audioRecordDao()
                    dao.insert(record)
                }

                override fun onTick(elapsedSeconds: Long) {
                    // 仅更新变量，通知刷新由 notifRefreshRunnable 统一负责（避免每秒双重更新）
                    lastElapsedSeconds = elapsedSeconds
                }

                override fun onSegmentStarted(fileName: String) {
                    LogUtils.d(this@RecordingService, TAG, "开始新录音片段: $fileName")
                    currentSegmentName = fileName
                }

                override fun onError(msg: String) {
                    Log.e(TAG, "录音错误: $msg")
                    LogUtils.e(this@RecordingService, TAG, "录音错误: $msg")
                    lastError = msg
                    // 注意：startRecording() 的 catch 块在 isRecording=false 之前回调 onError，
                    // 此刻 recorder.isRecording() 仍为 true，直接检查会漏掉重试调度。
                    // 用 post 延迟到下一轮消息循环，确保 isRecording 已被置回 false。
                    retryHandler.post {
                        updateNotification(0)
                        if (!recorder.isRecording() && !isInCall && !isMicConflict && prefs.recordingEnabled) {
                            LogUtils.w(this@RecordingService, TAG, "录音失败，计划 ${START_RETRY_INTERVAL_MS/1000}秒后重试")
                            retryHandler.removeCallbacks(startRetryRunnable)
                            retryHandler.postDelayed(startRetryRunnable, START_RETRY_INTERVAL_MS)
                        }
                    }
                }

                override fun onMicConflict() {
                    Log.w(TAG, "麦克风冲突！停止录音，10秒后自动重试")
                    LogUtils.w(this@RecordingService, TAG, "麦克风冲突，停止录音，10秒后自动重试")
                    isMicConflict = true
                    lastConflictTime = SystemClock.elapsedRealtime()
                    pauseRecordingForConflict("麦克风冲突")
                    // 10 秒后开始定时重试
                    retryHandler.postDelayed(micConflictRetryRunnable, 10_000)
                }
            }
        )

        // 动态注册充电状态广播接收器（比静态注册更可靠）
        val powerFilter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        registerReceiver(powerConnectionReceiver, powerFilter)

        // 服务创建时立即设置保活定时检查
        KeepAliveReceiver.scheduleNextCheck(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            // 用户主动停止
            LogUtils.i(this@RecordingService, TAG, "用户主动停止录音")
            if (recorder.isRecording()) {
                val last = recorder.stopRecording()
                finalizeAndFlush(last)
            } else {
                recoverOrphanedRecording()
            }
            prefs.recordingEnabled = false
            // 用户主动停止，取消定时自启动
            KeepAliveReceiver.cancelCheck(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_REFRESH_NOTIFICATION) {
            // 通知被用户划掉后立即重新显示（类似华为运动健康的常驻通知）
            Log.i(TAG, "通知被划掉，立即重新显示")
            try {
                startForeground(NOTIF_ID, buildNotification(lastElapsedSeconds))
            } catch (e: Exception) {
                Log.w(TAG, "重新显示通知失败: ${e.message}")
            }
            // 确保通知刷新定时器仍在运行
            notifHandler.removeCallbacks(notifRefreshRunnable)
            notifHandler.post(notifRefreshRunnable)
            return START_STICKY
        }

        // ACTION_START 或 null（系统重启）
        // Android 12+ 后台启动前台服务可能抛 ForegroundServiceStartNotAllowedException
        // Android 14+ microphone 类型需 RECORD_AUDIO 权限已授予，否则抛 SecurityException
        try {
            startForeground(NOTIF_ID, buildNotification(0))
            LogUtils.i(this@RecordingService, TAG, "录音服务启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}", e)
            LogUtils.e(this@RecordingService, TAG, "startForeground 失败: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        // 启动通知持续刷新定时器（每秒更新通知，确保常驻并持续显示录制时长）
        notifHandler.removeCallbacks(notifRefreshRunnable)
        notifHandler.post(notifRefreshRunnable)

        if (intent == null) {
            // 系统重启 Service：先恢复孤儿录音，再自动继续录音
            Log.i(TAG, "Service 被系统重启，恢复录音")
            LogUtils.i(this, TAG, "Service 被系统重启，恢复录音")
            recoverOrphanedRecording()
            if (prefs.recordingEnabled) {
                startFlushLoop()
                // 使用 ensureRecording：startRecording 在重启瞬间可能失败，
                // 失败时由 startRetryRunnable 定时重试，避免卡在"运行中但不录音"
                ensureRecording()
            }
        } else {
            // 用户主动启动（包括打开 App 自动启动 / KeepAlive 定时触发）
            if (recorder.isRecording()) {
                // 已在录音中，忽略重复的 START 请求。
                Log.i(TAG, "已在录音中，忽略重复的 START 请求")
                // 续期 WakeLock：长时间录音（>6h）后 WakeLock 可能已过期，
                // KeepAlive 定时触发时需重新获取，避免设备休眠导致录音中断
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    Log.i(TAG, "WakeLock 已续期")
                }
                KeepAliveReceiver.scheduleNextCheck(this)
                return START_STICKY
            }
            // 不在录音，检查是否有孤儿文件需要恢复
            recoverOrphanedRecording()
            startFlushLoop()
            // 先置位 recordingEnabled，ensureRecording 内部会检查该开关
            prefs.recordingEnabled = true
            prefs.recordingStartTime = System.currentTimeMillis()
            LogUtils.i(this, TAG, "开始启动录音")
            ensureRecording()
        }

        // 设置定时自启动检查（1分钟后），确保被杀后能自动恢复
        KeepAliveReceiver.scheduleNextCheck(this)

        return START_STICKY
    }

    /** 暂停录音（电话/麦克风冲突），保存当前段 */
    private fun pauseRecordingForConflict(reason: String) {
        if (!recorder.isRecording()) return
        Log.i(TAG, "因 $reason 暂停录音，保存当前段")
        val last = recorder.stopRecording()
        finalizeAndFlush(last)
        updateNotification(0)
    }

    /** 尝试恢复录音（电话结束/冲突解除后） */
    private fun tryResumeRecording() {
        if (isInCall) return
        if (!prefs.recordingEnabled) return
        if (recorder.isRecording()) {
            // 已经在录音了，清除冲突状态
            if (isMicConflict) {
                isMicConflict = false
                retryHandler.removeCallbacks(micConflictRetryRunnable)
                Log.i(TAG, "麦克风冲突已解除，录音已恢复")
                updateNotification(0)
            }
            return
        }
        Log.i(TAG, "尝试恢复录音...")
        try {
            recorder.startRecording()
            if (recorder.isRecording()) {
                // 恢复成功
                isMicConflict = false
                retryHandler.removeCallbacks(micConflictRetryRunnable)
                Log.i(TAG, "录音已恢复")
                updateNotification(0)
            }
            // 如果 startRecording 失败（麦克风仍被占用），保持 isMicConflict=true
            // 定时器会继续重试
        } catch (e: Exception) {
            Log.w(TAG, "恢复录音失败，将继续重试: ${e.message}")
            // 保持 isMicConflict=true，定时器会继续重试
        }
    }

    /**
     * 尝试启动录音；若启动失败（AudioRecord 初始化失败、麦克风暂时不可用、
     * ROM 后台限制等），调度 [startRetryRunnable] 定时重试。
     *
     * 与 [tryResumeRecording] 的区别：本方法用于"从未进入录音"的初始启动场景，
     * 不涉及 isMicConflict 状态；后者用于"录音中遭遇冲突后恢复"。
     */
    private fun ensureRecording() {
        if (recorder.isRecording()) return
        if (!prefs.recordingEnabled) return
        try {
            recorder.startRecording()
        } catch (e: Throwable) {
            // startRecording 内部已捕获异常并回调 onError，此处防御性兜底
            Log.w(TAG, "启动录音异常: ${e.message}")
            lastError = "异常: ${e.message}"
        }
        if (recorder.isRecording()) {
            retryHandler.removeCallbacks(startRetryRunnable)
            startRetryCount = 0
            lastError = ""
            Log.i(TAG, "录音已启动")
            updateNotification(0)
        } else {
            startRetryCount++
            Log.w(TAG, "录音启动失败(第${startRetryCount}次)，${START_RETRY_INTERVAL_MS / 1000}秒后重试")
            retryHandler.removeCallbacks(startRetryRunnable)
            retryHandler.postDelayed(startRetryRunnable, START_RETRY_INTERVAL_MS)
        }
    }

    /**
     * 恢复进程被杀前未正确关闭的录音文件。
     * WAV 格式的文件即使 DataSize 不正确也可播放，
     * 这里修复头部使其更规范。
     */
    private fun recoverOrphanedRecording() {
        val path = prefs.currentRecordingPath
        val startMs = prefs.currentRecordingStartMs
        if (path.isEmpty() || startMs == 0L) return

        val file = java.io.File(path)
        if (!file.exists() || file.length() <= 44) {
            Log.w(TAG, "孤儿录音文件不存在或太小: $path")
            prefs.currentRecordingPath = ""
            prefs.currentRecordingStartMs = 0L
            return
        }

        Log.i(TAG, "恢复未保存的录音: ${file.name} (${file.length()} bytes)")
        FileUtils.fixWavHeader(file)

        // 从文件实际数据大小计算真实音频时长（不是时间戳差值）
        val dataBytes = file.length() - 44
        val actualDurationMs = dataBytes * 1000 / (16000 * 1 * 2)  // 16kHz, mono, 16bit

        if (actualDurationMs < 1000) {
            // 实际录音不到 1 秒，说明进程被杀后没有录音数据，丢弃
            Log.w(TAG, "孤儿录音实际时长仅 ${actualDurationMs}ms，丢弃: ${file.name}")
            file.delete()
            prefs.currentRecordingPath = ""
            prefs.currentRecordingStartMs = 0L
            return
        }

        val record = AudioRecordEntity(
            filePath = file.absolutePath,
            fileName = file.name,
            startTime = startMs,
            endTime = startMs + actualDurationMs,  // endTime = startTime + 实际时长
            durationMs = actualDurationMs,
            dateStr = FileUtils.dateStr(startMs),
            fileSizeBytes = file.length(),
            transcribeStatus = 0
        )
        finalizeAndFlush(record)
        prefs.currentRecordingPath = ""
        prefs.currentRecordingStartMs = 0L
        Log.i(TAG, "孤儿录音已恢复入库: ${file.name} (实际时长 ${actualDurationMs}ms)")
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(2000)
                recorder.flushPendingFinished()
            }
        }
    }

    /**
     * 异步入库最后一段录音并刷入待处理段记录。
     * 使用 scope.launch（IO 派发）替代 runBlocking，避免阻塞调用线程。
     * 注意：onDestroy 因 scope 即将 cancel，仍使用 runBlocking 同步入库。
     */
    private fun finalizeAndFlush(last: AudioRecordEntity?) {
        scope.launch {
            val dao = (application as App).database.audioRecordDao()
            last?.let { dao.insert(it) }
            recorder.flushPendingFinished()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogUtils.i(this, TAG, "用户从最近任务中移除应用")
        // 如果录音仍然开启，设置定时自启动
        if (prefs.recordingEnabled) {
            LogUtils.i(this, TAG, "录音已开启，设置 1 分钟后自启动")
            KeepAliveReceiver.scheduleNextCheck(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        notifHandler.removeCallbacks(notifRefreshRunnable)
        retryHandler.removeCallbacks(micConflictRetryRunnable)
        retryHandler.removeCallbacks(startRetryRunnable)
        flushJob?.cancel()
        // stopRecording 已同步 join 录音线程（见 AudioRecorderManager），此处线程已结束。
        // onDestroy 中 scope 即将 cancel，故仍用 runBlocking 同步完成纯 DB 插入，避免丢失。
        val last = recorder.stopRecording()
        val dao = (application as App).database.audioRecordDao()
        kotlinx.coroutines.runBlocking {
            last?.let { dao.insert(it) }
            recorder.flushPendingFinished()
        }
        scope.cancel()
        try { telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {}
        // 注销充电状态广播接收器
        try { unregisterReceiver(powerConnectionReceiver) } catch (_: Exception) {}
        if (wakeLock.isHeld) wakeLock.release()
        prefs.currentRecordingPath = ""
        prefs.currentRecordingStartMs = 0L

        // 如果录音仍然开启（非用户主动停止），设置定时自启动
        if (prefs.recordingEnabled) {
            Log.i(TAG, "Service 被销毁，设置 1 分钟后自启动")
            KeepAliveReceiver.scheduleNextCheck(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(elapsedSeconds: Long) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(elapsedSeconds))
    }

    private fun buildNotification(elapsedSeconds: Long): Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // DeleteIntent：用户划掉通知后立即触发服务重新显示通知（类似华为运动健康常驻通知）
        val deleteIntent = Intent(this, RecordingService::class.java)
            .setAction(ACTION_REFRESH_NOTIFICATION)
        val deletePi = PendingIntent.getService(
            this, 2, deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: String
        val timeStr = if (recorder.isRecording()) {
            FileUtils.formatClockSeconds(elapsedSeconds)
        } else {
            FileUtils.formatClockSeconds(lastElapsedSeconds)
        }

        when {
            recorder.isRecording() -> {
                title = "身心健康_$timeStr"
            }
            else -> {
                title = "身心悲伤_$timeStr"
            }
        }

        return NotificationCompat.Builder(this, App.CHANNEL_RECORDING)
            .setContentTitle(title)
            .setContentText("")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(pi)
            .setDeleteIntent(deletePi)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.setCategory(NotificationCompat.CATEGORY_SERVICE)
                }
            }
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.ailogapp.START"
        const val ACTION_STOP = "com.example.ailogapp.STOP"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.ailogapp.REFRESH_NOTIF"
        /** 通知刷新间隔：1 秒 */
        private const val NOTIF_REFRESH_INTERVAL_MS = 1000L
        /** WakeLock 超时兜底：6 小时，避免进程被强杀后 WakeLock 永不释放持续耗电 */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        /** 录音启动失败后的重试间隔：5 秒（更频繁的重试，确保快速恢复） */
        private const val START_RETRY_INTERVAL_MS = 5_000L
    }
}
