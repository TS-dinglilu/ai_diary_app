package com.example.ailogapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.example.ailogapp.ai.SpeakerManager
import com.example.ailogapp.data.AppDatabase
import com.example.ailogapp.receiver.DailyCheckReceiver
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.util.WebDavBackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 应用入口：创建通知渠道、初始化数据库单例与偏好配置。
 */
class App : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val prefs: PrefsManager by lazy { PrefsManager(this) }

    /** 应用级协程 scope，随进程生命周期存在（SupervisorJob 确保子协程异常不互相影响） */
    private val appScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogUtils.i(this, "App", "应用启动")
        // 在 Activity 创建前应用主题模式，避免界面闪烁
        applyThemeMode(prefs.themeMode)
        createNotificationChannels()
        // 设置每日定时检查（晚上8点）
        DailyCheckReceiver.scheduleNextCheck(this)

        // 初始化说话人管理器
        appScope.launch {
            SpeakerManager.init(this@App)
        }

        // 启动时自动备份到坚果云（后台执行，不阻塞 UI）
        maybeAutoBackup()
    }

    /** 启动时自动备份：检查条件后后台执行坚果云备份 */
    private fun maybeAutoBackup() {
        if (!prefs.autoBackupOnAppStart) return
        // WebDAV 凭据未配置则跳过
        if (prefs.webdavEmail.isBlank() || prefs.webdavPassword.isBlank()) return
        // 节流：距上次备份尝试不足 30 分钟则跳过（无论成功失败，避免频繁重试）
        val now = System.currentTimeMillis()
        if (now - prefs.lastAutoBackupTime < 30 * 60 * 1000L) {
            LogUtils.i(this, "App", "距上次备份尝试不足 30 分钟，跳过启动备份")
            return
        }

        appScope.launch {
            LogUtils.i(this@App, "App", "启动自动备份到坚果云…")
            // 先记录尝试时间，避免失败后每次启动都重试
            prefs.lastAutoBackupTime = now
            try {
                WebDavBackupManager.backup(this@App).collect { state ->
                    when (state) {
                        is WebDavBackupManager.BackupState.Success -> {
                            LogUtils.i(this@App, "App", "启动自动备份完成: ${state.summary}")
                        }
                        is WebDavBackupManager.BackupState.Error -> {
                            LogUtils.w(this@App, "App", "启动自动备份失败: ${state.message}")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                LogUtils.w(this@App, "App", "启动自动备份异常: ${e.message}")
            }
        }
    }

    /** 将主题偏好映射为 AppCompatDelegate 夜间模式并应用 */
    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            PrefsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            PrefsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_recording_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(recordingChannel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording_channel"
        lateinit var instance: App
            private set
    }
}
