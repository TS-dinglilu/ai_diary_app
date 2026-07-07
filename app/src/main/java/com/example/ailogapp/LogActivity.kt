package com.example.ailogapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.ailogapp.databinding.ActivityLogBinding
import com.example.ailogapp.util.LogUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志查看页面
 *
 * 功能：
 * 1. 查看今日运行日志
 * 2. 导出所有日志为 txt 文件
 * 3. 清空日志
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 自动刷新日志的 Runnable */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadLogContent()
            handler.postDelayed(this, 3000)  // 每3秒刷新一次
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 顶部返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 导出按钮
        binding.btnExport.setOnClickListener { exportLogs() }

        // 清空按钮
        binding.btnClear.setOnClickListener { clearLogs() }

        // 加载日志
        loadLogContent()
        updateLogInfo()
    }

    override fun onResume() {
        super.onResume()
        // 页面可见时开始自动刷新
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时停止自动刷新
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        super.onDestroy()
    }

    /**
     * 加载日志内容
     */
    private fun loadLogContent() {
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                LogUtils.readTodayLog(this@LogActivity)
            }
            if (content.isEmpty()) {
                binding.tvLogContent.text = getString(R.string.log_empty)
            } else {
                binding.tvLogContent.text = content
            }
        }
    }

    /**
     * 更新日志信息（文件大小等）
     */
    private fun updateLogInfo() {
        val size = LogUtils.getTotalLogSize(this)
        val fileCount = LogUtils.getAllLogFiles(this).size
        val sizeStr = LogUtils.formatFileSize(size)
        binding.tvLogInfo.text = getString(R.string.log_info, fileCount, sizeStr)
    }

    /**
     * 导出日志
     */
    private fun exportLogs() {
        lifecycleScope.launch {
            // 生成导出文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val exportFileName = "AI日记_日志_$timeStamp.txt"
            val exportFile = File(getExternalFilesDir(null), exportFileName)

            val success = withContext(Dispatchers.IO) {
                LogUtils.exportAllLogs(this@LogActivity, exportFile)
            }

            if (success) {
                showExportSuccessDialog(exportFile)
            } else {
                Toast.makeText(this@LogActivity, R.string.log_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 显示导出成功对话框
     */
    private fun showExportSuccessDialog(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_export_success)
            .setMessage(getString(R.string.log_export_path, file.absolutePath))
            .setPositiveButton(R.string.log_share) { _, _ ->
                shareLogFile(file)
            }
            .setNegativeButton(R.string.log_open) { _, _ ->
                openLogFile(file)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 分享日志文件
     */
    private fun shareLogFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开日志文件
     */
    private fun openLogFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清空日志
     */
    private fun clearLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_clear_confirm_title)
            .setMessage(R.string.log_clear_confirm_msg)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                LogUtils.clearAllLogs(this)
                loadLogContent()
                updateLogInfo()
                Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
