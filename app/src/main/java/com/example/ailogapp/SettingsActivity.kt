package com.example.ailogapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.ailogapp.databinding.ActivitySettingsBinding
import com.example.ailogapp.fragment.SettingsFragment

/**
 * 独立设置页 Activity（容器）。
 *
 * 直接加载 SettingsFragment，保持与底部导航"设置"tab 完全一致的功能，
 * 避免维护两份布局和逻辑。仅额外提供顶部返回栏。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 顶部返回按钮
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.title_settings)

        // 加载 SettingsFragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_fragment_container, SettingsFragment())
                .commit()
        }
    }
}
