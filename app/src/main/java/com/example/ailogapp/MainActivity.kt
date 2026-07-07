package com.example.ailogapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ailogapp.databinding.ActivityMainBinding
import com.example.ailogapp.fragment.AnalysisFragment
import com.example.ailogapp.fragment.ChatListFragment
import com.example.ailogapp.fragment.MeFragment
import com.example.ailogapp.fragment.SettingsFragment
import com.example.ailogapp.service.RecordingService
import com.example.ailogapp.util.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    /** 录音权限申请回调 */
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 权限授予后，检查电池优化白名单，再启动录音
            checkBatteryOptimization()
            autoStartRecordingIfNeeded()
        } else {
            Toast.makeText(this, "需要录音权限才能后台录音", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = (application as App).prefs

        // 启动时修复卡住的转写状态（转写中→未转写）
        resetStuckTranscribing()

        // 权限与后台保活：先确保录音权限，再申请电池优化白名单，最后启动录音
        ensurePermissionsAndStartRecording()

        if (savedInstanceState == null) {
            switchTo(R.id.nav_chat)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }
    }

    /**
     * 确保录音权限已授予，并申请电池优化白名单，然后启动录音服务。
     * 这是后台持续录音的关键前提：
     * 1. RECORD_AUDIO 运行时权限
     * 2. 电池优化白名单（小米"省电策略无限制" ≠ 电池优化白名单）
     */
    private fun ensurePermissionsAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            checkBatteryOptimization()
            autoStartRecordingIfNeeded()
        }
    }

    /**
     * 检查是否已加入电池优化白名单，未加入则弹窗引导用户加入。
     * 小米等国产 ROM 上，"省电策略无限制"与"电池优化白名单"是两套独立机制，
     * 必须同时开启才能保证后台服务不被杀。
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // 某些 ROM 不支持该 intent，回退到电池优化设置页
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "请在设置中将本应用加入电池优化白名单", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 启动时将所有"转写中"(status=1)的录音重置为"未转写"(status=0)。
     * 修复 App 退出时转写中断导致的状态卡住问题。
     */
    private fun resetStuckTranscribing() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = (application as App).database
                db.audioRecordDao().resetStuckTranscribing()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "重置卡住的转写失败", e)
            }
        }
    }

    /**
     * 如果录音功能已开启（prefs.recordingEnabled == true），
     * 打开 App 时自动启动录音服务。
     * 这确保用户每次打开 App 都会恢复录音。
     */
    private fun autoStartRecordingIfNeeded() {
        if (prefs.recordingEnabled) {
            val intent = Intent(this, RecordingService::class.java)
            intent.action = RecordingService.ACTION_START
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "启动录音服务失败", e)
            }
        }
    }

    private fun switchTo(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_chat -> "chat"
            R.id.nav_analysis -> "analysis"
            R.id.nav_settings -> "settings"
            R.id.nav_me -> "me"
            else -> "chat"
        }
        val fragment = getOrCreateFragment(R.id.fragmentContainer, tag) {
            when (tag) {
                "chat" -> ChatListFragment()
                "analysis" -> AnalysisFragment.newInstance(showDeleteRecord = false)
                "settings" -> SettingsFragment()
                "me" -> MeFragment()
                else -> ChatListFragment()
            }
        }
        supportFragmentManager.beginTransaction().apply {
            // hide all, show selected
            supportFragmentManager.fragments.forEach { hide(it) }
            show(fragment)
        }.commit()
    }

    /**
     * 通过 FragmentManager 查找已存在的 Fragment，避免成员变量持有导致的状态泄漏。
     * 不存在时按需创建并 add 到容器，使用 tag 标识。
     */
    private fun getOrCreateFragment(
        containerId: Int,
        tag: String,
        create: () -> Fragment
    ): Fragment {
        return supportFragmentManager.findFragmentByTag(tag) ?: create().also {
            supportFragmentManager.beginTransaction().add(containerId, it, tag).commit()
        }
    }
}
