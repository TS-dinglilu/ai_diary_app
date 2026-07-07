package com.example.ailogapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.ailogapp.databinding.ActivityRecordManagementBinding
import com.example.ailogapp.fragment.AnalysisFragment

/**
 * 录音管理页。
 *
 * 托管 [AnalysisFragment]，提供录音条目、转写内容、AI 分析的浏览、删除、导出和导入功能。
 * 从「我」页面的「录音管理」入口进入。
 */
class RecordManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordManagementBinding
    private var analysisFragment: AnalysisFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // 导入录音按钮
        binding.btnImport.setOnClickListener {
            analysisFragment?.triggerImport()
        }

        if (savedInstanceState == null) {
            val fragment = AnalysisFragment.newInstance(
                showDeleteRecord = true,
                showExport = true
            )
            analysisFragment = fragment
            supportFragmentManager.commit {
                replace(binding.fragmentContainer.id, fragment)
            }
        } else {
            analysisFragment = supportFragmentManager.findFragmentById(binding.fragmentContainer.id) as? AnalysisFragment
        }
    }
}
