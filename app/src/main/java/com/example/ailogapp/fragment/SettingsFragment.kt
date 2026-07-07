package com.example.ailogapp.fragment

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.ailogapp.App
import com.example.ailogapp.LogActivity
import com.example.ailogapp.R
import com.example.ailogapp.ai.ASRModelDownloader
import com.example.ailogapp.ai.LLMModelDownloader
import com.example.ailogapp.ai.LocalASREngine
import com.example.ailogapp.ai.LocalLLMEngine
import com.example.ailogapp.databinding.FragmentSettingsBinding
import com.example.ailogapp.dialog.DeleteRecordsDialog
import com.example.ailogapp.service.RecordingService
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.LogUtils
import com.example.ailogapp.util.PrefsManager
import com.example.ailogapp.util.ScheduleRecordingHelper
import com.example.ailogapp.util.WebDavBackupManager
import com.example.ailogapp.util.LocalBackupManager
import com.example.ailogapp.worker.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 Fragment，嵌入底部导航。
 *
 * 提供应用设置界面，包括 AI 接口配置、转写模式、分析模式等。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startRecording()
    }

    /** SAF 选择备份文件夹 */
    private val backupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 将 SAF Uri 转换为可写文件路径
            try {
                val ctx = requireContext()
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // 将文件写入到 SAF 目录：先写到 cacheDir 再复制到 SAF
                doLocalBackupToSaf(uri)
            } catch (e: Exception) {
                showTaskStatus("无法访问所选文件夹: ${e.message}")
            }
        }
    }

    /** SAF 选择恢复文件 */
    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // 将 ZIP 从 SAF 复制到 cacheDir 再恢复
                val ctx = requireContext()
                val tempZip = java.io.File(ctx.cacheDir, "restore_temp.zip")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tempZip.outputStream().use { input.copyTo(it) }
                }
                doLocalRestore(tempZip)
                tempZip.deleteOnExit()
            } catch (e: Exception) {
                showTaskStatus("无法读取所选文件: ${e.message}")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = (requireActivity().application as App).prefs

        setupButtons()
        setupModeChips()
        setupSwitches()
        loadConfig()
        updateRecordingUI()
    }

    private fun setupButtons() {
        binding.btnToggle.setOnClickListener {
            if (prefs.recordingEnabled) stopRecording()
            else ensurePermissionsThenStart()
        }

        binding.btnTranscribe.setOnClickListener {
            showTaskStatus(getString(R.string.msg_triggered_transcribe))
            WorkScheduler.enqueueTranscriptionManual(requireContext())
        }

        binding.btnAnalyze.setOnClickListener {
            // 使用内置（本地离线）模型时，先检查模型是否存在
            if (prefs.analyzeMode == PrefsManager.ANALYZE_LOCAL &&
                !LocalLLMEngine.isModelAvailable(requireContext())
            ) {
                Toast.makeText(requireContext(), R.string.llm_model_not_downloaded, Toast.LENGTH_LONG).show()
                binding.layoutLLMModel.visibility = View.VISIBLE
                binding.layoutLLMModel.requestFocus()
                return@setOnClickListener
            }
            showTaskStatus(getString(R.string.msg_triggered_analyze))
            WorkScheduler.enqueueAnalysisManual(requireContext())
        }

        binding.btnSaveConfig.setOnClickListener {
            prefs.aiApiUrl = binding.etApiUrl.text.toString().trim()
            prefs.aiApiKey = binding.etApiKey.text.toString().trim()
            prefs.aiModel = binding.etModel.text.toString().trim()
            prefs.transcribeUrl = binding.etTranscribeUrl.text.toString().trim()
            prefs.transcribeModel = binding.etTranscribeModel.text.toString().trim()

            // 保存模型下载链接
            prefs.asrModelUrl = binding.etAsrModelUrl.text.toString().trim()
            prefs.llmModelUrl = binding.etLlmModelUrl.text.toString().trim()

            // 保存自动删除天数
            val daysStr = binding.etAutoDeleteDays.text.toString().trim()
            if (daysStr.isNotEmpty()) {
                val days = daysStr.toIntOrNull() ?: 7
                prefs.autoDeleteDays = days.coerceAtLeast(1)
            }

            showTaskStatus(getString(R.string.msg_config_saved))
        }

        binding.btnDeleteRecords.setOnClickListener {
            DeleteRecordsDialog { count ->
                showTaskStatus(getString(R.string.msg_deleted_records, count))
            }.show(childFragmentManager, DeleteRecordsDialog.TAG)
        }

        binding.btnRetranscribeAll.setOnClickListener {
            retranscribeAll()
        }

        // 定时录音
        binding.switchScheduleRecording.setOnCheckedChangeListener { _, isChecked ->
            prefs.scheduleRecordingEnabled = isChecked
            if (isChecked) {
                ScheduleRecordingHelper.scheduleAll(
                    requireContext(),
                    prefs.scheduleRecordStartTime,
                    prefs.scheduleRecordStopTime
                )
                showTaskStatus(getString(R.string.msg_schedule_enabled))
            } else {
                ScheduleRecordingHelper.cancelAll(requireContext())
                showTaskStatus(getString(R.string.msg_schedule_disabled))
            }
        }

        binding.layoutScheduleStart.setOnClickListener {
            showTimePicker(prefs.scheduleRecordStartTime) { newTime ->
                prefs.scheduleRecordStartTime = newTime
                binding.tvScheduleStart.text = newTime
                if (prefs.scheduleRecordingEnabled) {
                    ScheduleRecordingHelper.scheduleAll(
                        requireContext(),
                        prefs.scheduleRecordStartTime,
                        prefs.scheduleRecordStopTime
                    )
                }
            }
        }

        binding.layoutScheduleStop.setOnClickListener {
            showTimePicker(prefs.scheduleRecordStopTime) { newTime ->
                prefs.scheduleRecordStopTime = newTime
                binding.tvScheduleStop.text = newTime
                if (prefs.scheduleRecordingEnabled) {
                    ScheduleRecordingHelper.scheduleAll(
                        requireContext(),
                        prefs.scheduleRecordStartTime,
                        prefs.scheduleRecordStopTime
                    )
                }
            }
        }

        // 数据备份与恢复
        binding.btnBackupCloud.setOnClickListener { backupToCloud() }
        binding.btnBackupLocal.setOnClickListener { backupToLocal() }
        binding.btnRestoreCloud.setOnClickListener { restoreFromCloud() }
        binding.btnRestoreLocal.setOnClickListener { restoreFromLocal() }
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            prefs.webdavAutoBackup = isChecked
        }
        binding.switchAutoBackupOnStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoBackupOnAppStart = isChecked
        }

        // 「其他」分类已迁移至「我」页面，此处不再绑定
    }

    /** 时间选择器对话框 */
    private fun showTimePicker(currentTime: String, onSelected: (String) -> Unit) {
        val parts = currentTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 30
        android.app.TimePickerDialog(
            requireContext(),
            { _, h, m -> onSelected(String.format("%02d:%02d", h, m)) },
            hour, minute, true
        ).show()
    }

    /** 显示备份状态文本 */
    private fun setBackupStatus(text: String, visible: Boolean = true) {
        binding.tvBackupStatus.text = text
        binding.tvBackupStatus.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 备份到坚果云 */
    private fun backupToCloud() {
        saveWebdavConfig()
        val ctx = requireContext()
        setBackupStatus("${getString(R.string.backup_in_progress)} 0%")
        viewLifecycleOwner.lifecycleScope.launch {
            WebDavBackupManager.backup(ctx).collect { state ->
                when (state) {
                    is WebDavBackupManager.BackupState.BackingUp -> {
                        setBackupStatus("${getString(R.string.backup_in_progress)} ${state.progress}%")
                    }
                    is WebDavBackupManager.BackupState.Success -> {
                        setBackupStatus(getString(R.string.backup_success, state.summary))
                        showTaskStatus(getString(R.string.backup_success, state.summary))
                    }
                    is WebDavBackupManager.BackupState.Error -> {
                        setBackupStatus(getString(R.string.backup_failed, state.message))
                        showTaskStatus(getString(R.string.backup_failed, state.message))
                    }
                }
            }
        }
    }

    /** 备份到本地（ZIP） */
    private fun backupToLocal() {
        // 使用 SAF 选择文件夹
        backupFolderLauncher.launch(null)
    }

    /** 从坚果云恢复 */
    private fun restoreFromCloud() {
        saveWebdavConfig()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_msg)
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.btn_restore_cloud) { _, _ ->
                val ctx = requireContext()
                setBackupStatus("${getString(R.string.restore_in_progress)} 0%")
                viewLifecycleOwner.lifecycleScope.launch {
                    WebDavBackupManager.restore(ctx).collect { state ->
                        when (state) {
                            is WebDavBackupManager.RestoreState.Restoring -> {
                                setBackupStatus("${getString(R.string.restore_in_progress)} ${state.progress}%")
                            }
                            is WebDavBackupManager.RestoreState.Success -> {
                                setBackupStatus(getString(R.string.restore_success, state.summary))
                                showTaskStatus(getString(R.string.restore_success, state.summary))
                            }
                            is WebDavBackupManager.RestoreState.Error -> {
                                setBackupStatus(getString(R.string.restore_failed, state.message))
                                showTaskStatus(getString(R.string.restore_failed, state.message))
                            }
                        }
                    }
                }
            }
            .show()
    }

    /** 从本地恢复（ZIP） */
    private fun restoreFromLocal() {
        restoreFileLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    /** 保存 WebDAV 配置到 PrefsManager */
    private fun saveWebdavConfig() {
        prefs.webdavUrl = binding.etWebdavUrl.text?.toString()?.trim() ?: ""
        prefs.webdavEmail = binding.etWebdavEmail.text?.toString()?.trim() ?: ""
        prefs.webdavPassword = binding.etWebdavPassword.text?.toString()?.trim() ?: ""
    }

    /** 执行本地备份到 SAF 目录 */
    private fun doLocalBackupToSaf(folderUri: Uri) {
        val ctx = requireContext()
        setBackupStatus("${getString(R.string.backup_in_progress)} 0%")
        viewLifecycleOwner.lifecycleScope.launch {
            // 先备份到 cacheDir，再复制到 SAF 目录
            val tempDir = java.io.File(ctx.cacheDir, "backup_temp").apply { mkdirs() }
            LocalBackupManager.backup(ctx, tempDir).collect { state ->
                when (state) {
                    is LocalBackupManager.BackupState.BackingUp -> {
                        setBackupStatus("${getString(R.string.backup_in_progress)} ${state.progress}%")
                    }
                    is LocalBackupManager.BackupState.Success -> {
                        // 找到生成的 ZIP 文件，复制到 SAF 目录
                        val zipFiles = tempDir.listFiles { f -> f.extension.equals("zip", ignoreCase = true) }
                        if (zipFiles != null && zipFiles.isNotEmpty()) {
                            try {
                                val zipFile = zipFiles.first()
                                val fileName = zipFile.name
                                // 用 ContentResolver 在 SAF 目录下创建文件
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                                }
                                // 直接用 SAF 的 createDocument 替代 DocumentFile
                                val targetUri = android.provider.DocumentsContract.createDocument(
                                    ctx.contentResolver, folderUri, "application/zip", fileName
                                )
                                if (targetUri != null) {
                                    ctx.contentResolver.openOutputStream(targetUri)?.use { output ->
                                        zipFile.inputStream().use { input -> input.copyTo(output) }
                                    }
                                    setBackupStatus(getString(R.string.backup_success, "已保存: $fileName"))
                                    showTaskStatus(getString(R.string.backup_success, fileName))
                                } else {
                                    // 回退：复制到 externalFilesDir
                                    val fallback = java.io.File(ctx.getExternalFilesDir(null), fileName)
                                    zipFile.copyTo(fallback, overwrite = true)
                                    setBackupStatus(getString(R.string.backup_success, "已保存到 Download 目录: $fileName"))
                                    showTaskStatus(getString(R.string.backup_success, fileName))
                                }
                            } catch (e: Exception) {
                                setBackupStatus(getString(R.string.backup_failed, e.message ?: "写入失败"))
                            }
                        }
                        tempDir.deleteRecursively()
                    }
                    is LocalBackupManager.BackupState.Error -> {
                        setBackupStatus(getString(R.string.backup_failed, state.message))
                        showTaskStatus(getString(R.string.backup_failed, state.message))
                        tempDir.deleteRecursively()
                    }
                }
            }
        }
    }

    /** 执行从本地 ZIP 恢复 */
    private fun doLocalRestore(zipFile: java.io.File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_msg)
            .setNegativeButton(R.string.delete_cancel, null)
            .setPositiveButton(R.string.btn_restore_local) { _, _ ->
                val ctx = requireContext()
                setBackupStatus("${getString(R.string.restore_in_progress)} 0%")
                viewLifecycleOwner.lifecycleScope.launch {
                    LocalBackupManager.restore(ctx, zipFile).collect { state ->
                        when (state) {
                            is LocalBackupManager.RestoreState.Restoring -> {
                                setBackupStatus("${getString(R.string.restore_in_progress)} ${state.progress}%")
                            }
                            is LocalBackupManager.RestoreState.Success -> {
                                setBackupStatus(getString(R.string.restore_success, state.summary))
                                showTaskStatus(getString(R.string.restore_success, state.summary))
                            }
                            is LocalBackupManager.RestoreState.Error -> {
                                setBackupStatus(getString(R.string.restore_failed, state.message))
                                showTaskStatus(getString(R.string.restore_failed, state.message))
                            }
                        }
                    }
                }
            }
            .show()
    }

    /** 重新转写所有录音 */
    private fun retranscribeAll() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.journal_retranscribe_all)
            .setMessage(R.string.journal_retranscribe_all_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val db = (requireActivity().application as App).database
                val ctx = requireContext()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 获取所有已转写的录音
                        val allRecords = db.audioRecordDao().getByStatus(2) + db.audioRecordDao().getByStatus(3)
                        LogUtils.i(ctx, "Settings", "重新转写所有录音，共 ${allRecords.size} 条")

                        // 删除所有转写文件
                        for (record in allRecords) {
                            val transcript = db.transcriptDao().getByAudioId(record.id)
                            if (transcript != null) {
                                runCatching { File(transcript.txtPath ?: "").takeIf { it.exists() }?.delete() }
                                db.transcriptDao().deleteById(transcript.id)
                            }
                        }
                        // 重置所有转写状态
                        db.audioRecordDao().resetAllTranscribed()
                    }
                    // 触发转写任务
                    WorkScheduler.enqueueTranscriptionManual(requireContext())
                    showTaskStatus(getString(R.string.journal_retranscribe_started))
                }
            }
            .show()
    }

    private fun setupModeChips() {
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val mode = when (checkedIds[0]) {
                binding.chipLocal.id -> PrefsManager.MODE_LOCAL
                binding.chipWhisper.id -> PrefsManager.MODE_WHISPER
                binding.chipAi.id -> PrefsManager.MODE_AI
                else -> PrefsManager.MODE_OFF
            }
            // 从本地离线模式切换到其他模式时，释放 ASR native 引擎（约 937MB）
            if (prefs.transcribeMode == PrefsManager.MODE_LOCAL && mode != PrefsManager.MODE_LOCAL) {
                LocalASREngine.release()
            }
            prefs.transcribeMode = mode
            // 选择本地模式时显示模型下载区域
            binding.layoutAsrModel.visibility =
                if (mode == PrefsManager.MODE_LOCAL) View.VISIBLE else View.GONE
            updateAsrModelStatus()
            // 切换到本地模式时，若模型未下载，提醒用户先下载
            if (mode == PrefsManager.MODE_LOCAL &&
                !LocalASREngine.isModelAvailable(requireContext())
            ) {
                Toast.makeText(requireContext(), R.string.asr_model_not_downloaded, Toast.LENGTH_LONG).show()
            }
        }

        // AI 分析模式
        binding.chipGroupAnalyzeMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val mode = if (checkedIds[0] == binding.chipAnalyzeLocal.id) {
                PrefsManager.ANALYZE_LOCAL
            } else {
                PrefsManager.ANALYZE_CLOUD
            }
            // 从本地离线分析切换到云端时，释放 LLM native 引擎（约 1.8GB）
            if (prefs.analyzeMode == PrefsManager.ANALYZE_LOCAL && mode != PrefsManager.ANALYZE_LOCAL) {
                LocalLLMEngine.release()
            }
            prefs.analyzeMode = mode
            // 选择本地模式时显示模型下载区域
            binding.layoutLLMModel.visibility =
                if (mode == PrefsManager.ANALYZE_LOCAL) View.VISIBLE else View.GONE
            updateLLMModelStatus()
            // 切换到本地模式时，若模型未下载，提醒用户先下载
            if (mode == PrefsManager.ANALYZE_LOCAL &&
                !LocalLLMEngine.isModelAvailable(requireContext())
            ) {
                Toast.makeText(requireContext(), R.string.llm_model_not_downloaded, Toast.LENGTH_LONG).show()
            }
        }

        // 模型下载按钮 - LLM（已下载时为“删除重下”，未下载时为“下载”）
        binding.btnDownloadLLM.setOnClickListener {
            if (LocalLLMEngine.isModelAvailable(requireContext())) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.llm_download)
                    .setMessage(R.string.model_redownload_confirm)
                    .setNegativeButton(R.string.delete_cancel, null)
                    .setPositiveButton(R.string.model_redownload) { _, _ ->
                        LLMModelDownloader.deleteModel(requireContext())
                        LocalLLMEngine.release()
                        updateLLMModelStatus()
                        startLLMDownload()
                    }
                    .show()
            } else {
                startLLMDownload()
            }
        }

        // 模型删除按钮 - LLM
        binding.btnDeleteLLM.setOnClickListener {
            if (!LocalLLMEngine.isModelAvailable(requireContext())) {
                Toast.makeText(requireContext(), R.string.model_not_downloaded_cant_delete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.model_delete)
                .setMessage(R.string.model_delete_confirm)
                .setNegativeButton(R.string.delete_cancel, null)
                .setPositiveButton(R.string.model_delete) { _, _ ->
                    LLMModelDownloader.deleteModel(requireContext())
                    LocalLLMEngine.release()
                    updateLLMModelStatus()
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // 模型下载按钮 - ASR（已下载时为“删除重下”，未下载时为“下载”）
        binding.btnDownloadAsr.setOnClickListener {
            if (LocalASREngine.isModelAvailable(requireContext())) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.llm_download)
                    .setMessage(R.string.model_redownload_confirm)
                    .setNegativeButton(R.string.delete_cancel, null)
                    .setPositiveButton(R.string.model_redownload) { _, _ ->
                        ASRModelDownloader.deleteModel(requireContext())
                        LocalASREngine.release()
                        updateAsrModelStatus()
                        startAsrDownload()
                    }
                    .show()
            } else {
                startAsrDownload()
            }
        }

        // 模型删除按钮 - ASR
        binding.btnDeleteAsr.setOnClickListener {
            if (!LocalASREngine.isModelAvailable(requireContext())) {
                Toast.makeText(requireContext(), R.string.model_not_downloaded_cant_delete, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.model_delete)
                .setMessage(R.string.model_delete_confirm)
                .setNegativeButton(R.string.delete_cancel, null)
                .setPositiveButton(R.string.model_delete) { _, _ ->
                    ASRModelDownloader.deleteModel(requireContext())
                    LocalASREngine.release()
                    updateAsrModelStatus()
                    Toast.makeText(requireContext(), R.string.model_delete_success, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    /** 更新 ASR 模型状态显示（实时读取文件大小，与转写可用性判断一致） */
    private fun updateAsrModelStatus() {
        if (LocalASREngine.isModelAvailable(requireContext())) {
            val sizeMB = LocalASREngine.modelSizeMB(requireContext())
            binding.tvAsrModelStatus.text = getString(R.string.asr_model_downloaded, sizeMB)
            binding.btnDownloadAsr.text = getString(R.string.llm_redownload)
            binding.btnDeleteAsr.visibility = View.VISIBLE
        } else {
            // 未下载时显示已缓存的临时文件大小（实时），无缓存则不显示固定数字
            val tmpMB = ASRModelDownloader.tempSizeMB(requireContext())
            binding.tvAsrModelStatus.text = if (tmpMB > 0)
                getString(R.string.asr_model_not_downloaded_partial, tmpMB)
            else
                getString(R.string.asr_model_not_downloaded)
            binding.btnDownloadAsr.text = getString(R.string.llm_download)
            binding.btnDeleteAsr.visibility = View.GONE
        }
    }

    /** 开始下载 ASR 模型 */
    private fun startAsrDownload() {
        val url = prefs.asrModelUrl
        if (url.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.msg_fill_download_url), Toast.LENGTH_LONG).show()
            return
        }
        binding.btnDownloadAsr.isEnabled = false
        binding.btnDeleteAsr.visibility = View.GONE
        binding.progressAsr.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            ASRModelDownloader.download(requireContext(), url).collect { state ->
                when (state) {
                    is ASRModelDownloader.DownloadState.Downloading -> {
                        binding.progressAsr.progress = state.progress
                        binding.btnDownloadAsr.text = getString(R.string.asr_downloading, state.progress)
                    }
                    is ASRModelDownloader.DownloadState.Success -> {
                        binding.btnDownloadAsr.isEnabled = true
                        binding.progressAsr.visibility = View.GONE
                        updateAsrModelStatus()
                        Toast.makeText(requireContext(), R.string.asr_download_success, Toast.LENGTH_SHORT).show()
                    }
                    is ASRModelDownloader.DownloadState.Error -> {
                        binding.btnDownloadAsr.isEnabled = true
                        binding.progressAsr.visibility = View.GONE
                        // 刷新状态：显示已缓存大小、恢复按钮文案与删除按钮可见性
                        updateAsrModelStatus()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.asr_download_failed, state.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /** 更新本地 LLM 模型状态显示（实时读取文件大小，与分析可用性判断一致） */
    private fun updateLLMModelStatus() {
        if (LocalLLMEngine.isModelAvailable(requireContext())) {
            val sizeMB = LocalLLMEngine.modelSizeMB(requireContext())
            binding.tvLLMModelStatus.text = getString(R.string.llm_model_downloaded, sizeMB)
            binding.btnDownloadLLM.text = getString(R.string.llm_redownload)
            binding.btnDeleteLLM.visibility = View.VISIBLE
        } else {
            // 未下载时显示已缓存的临时文件大小（实时），无缓存则不显示固定数字
            val tmpMB = LLMModelDownloader.tempSizeMB(requireContext())
            binding.tvLLMModelStatus.text = if (tmpMB > 0)
                getString(R.string.llm_model_not_downloaded_partial, tmpMB)
            else
                getString(R.string.llm_model_not_downloaded)
            binding.btnDownloadLLM.text = getString(R.string.llm_download)
            binding.btnDeleteLLM.visibility = View.GONE
        }
    }

    /** 开始下载 LLM 模型 */
    private fun startLLMDownload() {
        val url = prefs.llmModelUrl
        if (url.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.msg_fill_download_url), Toast.LENGTH_LONG).show()
            return
        }
        binding.btnDownloadLLM.isEnabled = false
        binding.btnDeleteLLM.visibility = View.GONE
        binding.progressLLM.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            LLMModelDownloader.download(requireContext(), url).collect { state ->
                when (state) {
                    is LLMModelDownloader.DownloadState.Downloading -> {
                        binding.progressLLM.progress = state.progress
                        binding.btnDownloadLLM.text = getString(R.string.llm_downloading, state.progress)
                    }
                    is LLMModelDownloader.DownloadState.Success -> {
                        binding.btnDownloadLLM.isEnabled = true
                        binding.progressLLM.visibility = View.GONE
                        updateLLMModelStatus()
                        Toast.makeText(requireContext(), R.string.llm_download_success, Toast.LENGTH_SHORT).show()
                    }
                    is LLMModelDownloader.DownloadState.Error -> {
                        binding.btnDownloadLLM.isEnabled = true
                        binding.progressLLM.visibility = View.GONE
                        // 刷新状态：显示已缓存大小、恢复按钮文案与删除按钮可见性
                        updateLLMModelStatus()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.llm_download_failed, state.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupSwitches() {
        binding.switchAutoTranscribe.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoTranscribeOnCharging = isChecked
        }
        binding.switchAutoAnalyze.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoAnalyzeOnCharging = isChecked
        }

        // 自动删除录音
        binding.switchAutoDelete.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoDeleteEnabled = isChecked
            binding.layoutAutoDeleteDays.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 界面设置：AI 分析页默认折叠录音
        binding.switchDefaultCollapse.setOnCheckedChangeListener { _, isChecked ->
            prefs.defaultCollapseRecords = isChecked
        }

        // 界面设置：深色模式选择
        binding.layoutThemeMode.setOnClickListener { showThemeModeDialog() }
    }

    /** 深色模式选择对话框 */
    private fun showThemeModeDialog() {
        val modes = listOf(
            PrefsManager.THEME_SYSTEM to R.string.theme_mode_system,
            PrefsManager.THEME_LIGHT to R.string.theme_mode_light,
            PrefsManager.THEME_DARK to R.string.theme_mode_dark
        )
        val labels = modes.map { getString(it.second) }.toTypedArray()
        val current = prefs.themeMode
        val checked = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.theme_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = modes[which].first
                if (selected != current) {
                    prefs.themeMode = selected
                    updateThemeModeText()
                    // 立即应用主题（Activity 会被 recreate 以重建为新模式）
                    val nightMode = when (selected) {
                        PrefsManager.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        PrefsManager.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    /** 更新深色模式右侧显示文案 */
    private fun updateThemeModeText() {
        binding.tvThemeMode.text = when (prefs.themeMode) {
            PrefsManager.THEME_LIGHT -> getString(R.string.theme_mode_light)
            PrefsManager.THEME_DARK -> getString(R.string.theme_mode_dark)
            else -> getString(R.string.theme_mode_system)
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isEmpty()) startRecording()
        else permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun startRecording() {
        requestIgnoreBatteryOptimizations()
        val intent = Intent(requireContext(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireActivity().startForegroundService(intent)
        else requireActivity().startService(intent)
        prefs.recordingEnabled = true
        updateRecordingUI()
    }

    private fun stopRecording() {
        val intent = Intent(requireContext(), RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
        requireActivity().startService(intent)
        prefs.recordingEnabled = false
        updateRecordingUI()
    }

    private fun updateRecordingUI() {
        if (prefs.recordingEnabled) {
            binding.btnToggle.text = getString(R.string.btn_stop)
            binding.tvStatus.text = getString(R.string.status_recording)
            // 录音中按钮变为深色（停止操作），计时器保持正常颜色
            binding.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.text_primary, null)
            )
            startTimer()
        } else {
            binding.btnToggle.text = getString(R.string.btn_start)
            binding.tvStatus.text = getString(R.string.status_stopped)
            binding.tvDuration.text = "00:00:00"
            binding.btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.wechat_green, null)
            )
            stopTimer()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val start = prefs.recordingStartTime
            if (prefs.recordingEnabled && start > 0) {
                val elapsed = (System.currentTimeMillis() - start) / 1000
                binding.tvDuration.text = FileUtils.formatClockSeconds(elapsed)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun startTimer() {
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
    }

    private fun loadConfig() {
        binding.etApiUrl.setText(prefs.aiApiUrl)
        binding.etApiKey.setText(prefs.aiApiKey)
        binding.etModel.setText(prefs.aiModel)
        binding.etTranscribeUrl.setText(prefs.transcribeUrl)
        binding.etTranscribeModel.setText(prefs.transcribeModel)
        when (prefs.transcribeMode) {
            PrefsManager.MODE_LOCAL -> binding.chipLocal.isChecked = true
            PrefsManager.MODE_WHISPER -> binding.chipWhisper.isChecked = true
            PrefsManager.MODE_AI -> binding.chipAi.isChecked = true
            else -> binding.chipOff.isChecked = true
        }
        // ASR 模型区域显示/隐藏
        binding.layoutAsrModel.visibility =
            if (prefs.transcribeMode == PrefsManager.MODE_LOCAL) View.VISIBLE else View.GONE
        // AI 分析模式
        if (prefs.analyzeMode == PrefsManager.ANALYZE_LOCAL) {
            binding.chipAnalyzeLocal.isChecked = true
            binding.layoutLLMModel.visibility = View.VISIBLE
        } else {
            binding.chipAnalyzeCloud.isChecked = true
            binding.layoutLLMModel.visibility = View.GONE
        }
        updateAsrModelStatus()
        updateLLMModelStatus()
        binding.switchAutoTranscribe.isChecked = prefs.autoTranscribeOnCharging
        binding.switchAutoAnalyze.isChecked = prefs.autoAnalyzeOnCharging

        // 定时录音
        binding.switchScheduleRecording.isChecked = prefs.scheduleRecordingEnabled
        binding.tvScheduleStart.text = prefs.scheduleRecordStartTime
        binding.tvScheduleStop.text = prefs.scheduleRecordStopTime

        // 云备份
        binding.switchAutoBackup.isChecked = prefs.webdavAutoBackup
        binding.switchAutoBackupOnStart.isChecked = prefs.autoBackupOnAppStart
        binding.etWebdavUrl.setText(prefs.webdavUrl)
        binding.etWebdavEmail.setText(prefs.webdavEmail)
        binding.etWebdavPassword.setText(prefs.webdavPassword)

        // 自动删除录音
        binding.switchAutoDelete.isChecked = prefs.autoDeleteEnabled
        binding.layoutAutoDeleteDays.visibility =
            if (prefs.autoDeleteEnabled) View.VISIBLE else View.GONE
        binding.etAutoDeleteDays.setText(prefs.autoDeleteDays.toString())

        // 模型下载链接
        binding.etAsrModelUrl.setText(prefs.asrModelUrl)
        binding.etLlmModelUrl.setText(prefs.llmModelUrl)

        // 界面设置：默认折叠
        binding.switchDefaultCollapse.isChecked = prefs.defaultCollapseRecords

        // 界面设置：深色模式
        updateThemeModeText()
    }

    /** 延迟隐藏任务状态文案的 Runnable，作为成员持有以便 onDestroy 时移除，避免泄漏视图 */
    private val hideStatusRunnable = Runnable {
        if (_binding != null) {
            binding.tvTaskStatus.visibility = View.GONE
        }
    }

    private fun showTaskStatus(msg: String) {
        binding.tvTaskStatus.text = msg
        binding.tvTaskStatus.visibility = View.VISIBLE
        // 先移除上一次未执行的隐藏回调，避免快速连续调用时前一个回调提前隐藏新文案
        handler.removeCallbacks(hideStatusRunnable)
        handler.postDelayed(hideStatusRunnable, 3000)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = requireActivity().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(requireActivity().packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${requireActivity().packageName}")
                    startActivity(intent)
                } catch (_: Exception) { }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRecordingUI()
        // 每次回到设置界面都实时刷新模型状态与文件大小
        updateAsrModelStatus()
        updateLLMModelStatus()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时保存 WebDAV 配置，避免用户填写后未点备份就离开导致配置丢失
        saveWebdavConfig()
    }

    override fun onDestroyView() {
        // 移除延迟隐藏回调，避免 Fragment 销毁后仍持有视图引用导致泄漏
        handler.removeCallbacks(hideStatusRunnable)
        stopTimer()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "SettingsFragment"
    }
}
