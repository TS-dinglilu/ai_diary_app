package com.example.ailogapp.fragment

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ailogapp.App
import com.example.ailogapp.ContentDetailActivity
import com.example.ailogapp.R
import com.example.ailogapp.data.entities.AnalysisEntity
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.data.entities.NoteEntity
import com.example.ailogapp.data.entities.TranscriptEntity
import com.example.ailogapp.databinding.FragmentAnalysisBinding
import com.example.ailogapp.ui.JournalAdapter
import com.example.ailogapp.ui.JournalItem
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.worker.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnalysisFragment : Fragment() {

    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private var playingRecordId: Long = -1L

    /** 是否显示"删除录音"按钮。AI 分析页=false，录音管理页=true */
    private val showDeleteRecord: Boolean by lazy {
        arguments?.getBoolean(ARG_SHOW_DELETE_RECORD, false) ?: false
    }

    /** 是否显示导出按钮。AI 分析页=false，录音管理页=true */
    private val showExport: Boolean by lazy {
        arguments?.getBoolean(ARG_SHOW_EXPORT, false) ?: false
    }

    private lateinit var adapter: JournalAdapter

    /**
     * 用户手动切换过折叠状态的日期 -> 是否折叠。
     * 未在此 Map 中的日期遵循 [PrefsManager.defaultCollapseRecords] 默认设置。
     */
    private val dateCollapseOverride = mutableMapOf<String, Boolean>()

    /** 传给适配器的有效折叠日期集合，每次构建列表前同步 */
    private val collapsedDates = mutableSetOf<String>()

    /** 计算某日期的有效折叠状态：用户手动切换的优先，否则用默认设置 */
    private fun isDateCollapsed(dateStr: String): Boolean =
        dateCollapseOverride[dateStr] ?: prefs.defaultCollapseRecords

    /** PrefsManager 引用，懒加载 */
    private val prefs by lazy {
        (requireActivity().application as App).prefs
    }

    /** 最新的录音、转写、分析数据（用于折叠状态改变时重新构建列表） */
    private var latestRecords: List<AudioRecordEntity> = emptyList()
    private var latestTranscripts: List<TranscriptEntity> = emptyList()
    private var latestAnalyses: List<AnalysisEntity> = emptyList()
    private var latestNotes: List<NoteEntity> = emptyList()

    // SAF 导出/导入 launchers
    private var pendingExportRecord: AudioRecordEntity? = null
    private var pendingExportTranscript: TranscriptEntity? = null
    private var pendingExportAnalysis: AnalysisEntity? = null

    private lateinit var exportAudioLauncher: ActivityResultLauncher<String>
    private lateinit var exportTextLauncher: ActivityResultLauncher<String>
    private lateinit var importAudioLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册 SAF launchers（必须在 onCreate 中注册）
        exportAudioLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("audio/wav")
        ) { uri ->
            uri?.let { copyFileToUri(pendingExportRecord, it) }
            pendingExportRecord = null
        }

        exportTextLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri == null) {
                pendingExportTranscript = null
                pendingExportAnalysis = null
            } else {
                val transcript = pendingExportTranscript
                val analysis = pendingExportAnalysis
                if (transcript != null) {
                    writeTextToUri(transcript.text, "转写结果", uri)
                } else if (analysis != null) {
                    val content = buildString {
                        appendLine("【AI 分析结果】")
                        appendLine("日期: ${analysis.dateStr}")
                        appendLine("情绪: ${analysis.emotion} (${analysis.emotionScore})")
                        appendLine()
                        appendLine("【摘要】")
                        appendLine(analysis.summary)
                        appendLine()
                        appendLine("【日记】")
                        appendLine(analysis.diaryContent)
                    }
                    writeTextToUri(content, "分析结果", uri)
                }
                pendingExportTranscript = null
                pendingExportAnalysis = null
            }
        }

        importAudioLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { importAudioFile(it) }
        }

        adapter = JournalAdapter(
            onDeleteRecord = { record -> deleteRecord(record) },
            onDeleteTranscript = { transcript -> deleteTranscript(transcript) },
            onDeleteAnalysis = { dateStr -> deleteAnalysis(dateStr) },
            onPlayRecord = { record -> togglePlayback(record) },
            showDeleteRecord = showDeleteRecord,
            showExport = showExport,
            onExportRecord = { record -> exportRecord(record) },
            onExportTranscript = { transcript -> exportTranscript(transcript) },
            onExportAnalysis = { analysis -> exportAnalysis(analysis) },
            onRetranscribe = { record -> retranscribeRecord(record) },
            showRetranscribe = true,
            onTranscriptClick = { record, _ -> openTranscriptDetail(record) },
            onAnalysisClick = { dateStr -> openAnalysisDetail(dateStr) },
            onRetranscribeDate = { dateStr -> retranscribeDate(dateStr) },
            showRetranscribeDate = true,
            onContinueTranscribeDate = { dateStr -> continueTranscribeDate(dateStr) },
            showContinueTranscribeDate = true,
            onHeaderClick = { dateStr -> toggleCollapse(dateStr) },
            collapsedDates = collapsedDates
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvJournal.layoutManager = LinearLayoutManager(requireContext())
        binding.rvJournal.adapter = adapter

        observeJournal()
    }

    /** 触发导入录音（由 RecordManagementActivity 调用） */
    fun triggerImport() {
        importAudioLauncher.launch(arrayOf("audio/*"))
    }

    private fun observeJournal() {
        val db = (requireActivity().application as App).database

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                db.audioRecordDao().observeAll(),
                db.transcriptDao().observeAll(),
                db.analysisDao().observeAll(),
                db.noteDao().observeAll()
            ) { records, transcripts, analyses, notes ->
                latestRecords = records
                latestTranscripts = transcripts
                latestAnalyses = analyses
                latestNotes = notes
                // buildJournalItems 包含排序、过滤等 O(n log n) 操作，
                // 移到 Default 线程避免阻塞 UI
                withContext(Dispatchers.Default) {
                    buildJournalItems(records, transcripts, analyses, notes)
                }
            }.flowOn(Dispatchers.Default).collect { items ->
                adapter.submitList(items)
                binding.tvJournalEmpty.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.rvJournal.visibility =
                    if (items.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** 切换日期的折叠状态 */
    private fun toggleCollapse(dateStr: String) {
        // 翻转该日期的有效状态并存入 override
        val newCollapsed = !isDateCollapsed(dateStr)
        dateCollapseOverride[dateStr] = newCollapsed
        // 重新构建列表（在后台线程执行）
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.Default) {
                buildJournalItems(latestRecords, latestTranscripts, latestAnalyses, latestNotes)
            }
            adapter.submitList(items)
        }
    }

    private fun buildJournalItems(
        records: List<AudioRecordEntity>,
        transcripts: List<TranscriptEntity>,
        analyses: List<AnalysisEntity>,
        notes: List<NoteEntity>
    ): List<JournalItem> {
        if (records.isEmpty() && analyses.isEmpty() && notes.isEmpty()) return emptyList()

        // 日期来源：录音 + 分析 + 笔记，合并去重后倒序排列
        val allDates = (records.map { it.dateStr } + analyses.map { it.dateStr } + notes.map { it.dateStr })
            .distinct().sortedDescending()
        val transcriptMap = transcripts.associateBy { it.audioId }
        val analysisMap = analyses.associateBy { it.dateStr }
        val notesByDate = notes.groupBy { it.dateStr }

        val items = mutableListOf<JournalItem>()
        // 同步有效折叠集合（供适配器箭头方向使用）
        collapsedDates.clear()
        for (date in allDates) {
            val dayRecords = records.filter { it.dateStr == date }.sortedBy { it.startTime }
            val dayAnalysis = analysisMap[date]
            val dayNotes = notesByDate[date] ?: emptyList()
            // 没有录音、分析、笔记中任何一个则跳过（防御性）
            if (dayRecords.isEmpty() && dayAnalysis == null && dayNotes.isEmpty()) continue
            val isCollapsed = isDateCollapsed(date)
            if (isCollapsed) collapsedDates.add(date)

            val hasTranscribed = dayRecords.any { it.transcribeStatus == 2 || it.transcribeStatus == 3 }
            // 有未转写（状态 0）或转写失败（状态 3）的录音时，显示"继续转写"
            val hasUntranscribed = dayRecords.any { it.transcribeStatus == 0 || it.transcribeStatus == 3 }
            // 正在转写中（存在状态 1）
            val isTranscribing = dayRecords.any { it.transcribeStatus == 1 }
            // 全部转写完成（所有录音状态均为 2）
            val allTranscribed = dayRecords.isNotEmpty() && dayRecords.all { it.transcribeStatus == 2 }
            // 已转写条数（状态 2）
            val transcribedCount = dayRecords.count { it.transcribeStatus == 2 }
            items.add(JournalItem.Header(
                date, dayRecords.size, hasTranscribed,
                hasUntranscribed, isTranscribing, allTranscribed, transcribedCount
            ))
            // 如果不是折叠状态，才添加录音项
            if (!isCollapsed) {
                for (r in dayRecords) {
                    items.add(JournalItem.RecordItem(r, transcriptMap[r.id]))
                }
            }
            // AI分析结果始终显示，不折叠
            if (dayAnalysis != null) {
                items.add(JournalItem.AnalysisItem(dayAnalysis))
            }
            // 笔记始终显示，不折叠（和 AI 分析一样独立展示在外面）
            for (note in dayNotes) {
                items.add(JournalItem.NoteItem(note))
            }
        }
        return items
    }

    // ---- 详情页 ----

    /** 打开转写详情页，查看完整转写内容并写笔记 */
    private fun openTranscriptDetail(record: AudioRecordEntity) {
        val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.startTime))
        val sourceLabel = "${timeLabel} ${getString(R.string.journal_transcript)}"
        val intent = Intent(requireContext(), ContentDetailActivity::class.java).apply {
            putExtra(ContentDetailActivity.EXTRA_TYPE, ContentDetailActivity.TYPE_TRANSCRIPT)
            putExtra(ContentDetailActivity.EXTRA_DATE, record.dateStr)
            putExtra(ContentDetailActivity.EXTRA_SOURCE_ID, record.id)
            putExtra(ContentDetailActivity.EXTRA_SOURCE_LABEL, sourceLabel)
        }
        startActivity(intent)
    }

    /** 打开 AI 分析详情页，查看完整分析结果并写笔记 */
    private fun openAnalysisDetail(dateStr: String) {
        val intent = Intent(requireContext(), ContentDetailActivity::class.java).apply {
            putExtra(ContentDetailActivity.EXTRA_TYPE, ContentDetailActivity.TYPE_ANALYSIS)
            putExtra(ContentDetailActivity.EXTRA_DATE, dateStr)
            // 分析按日期唯一，sourceId 用该日期对应的分析记录 id（详情页内部按日期查询）
            val analysis = latestAnalyses.firstOrNull { it.dateStr == dateStr }
            putExtra(ContentDetailActivity.EXTRA_SOURCE_ID, analysis?.id ?: -1L)
            putExtra(ContentDetailActivity.EXTRA_SOURCE_LABEL, getString(R.string.journal_analysis))
        }
        startActivity(intent)
    }

    // ---- 删除 ----

    private fun deleteRecord(record: AudioRecordEntity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.journal_confirm_delete)
            .setMessage(R.string.journal_confirm_delete_record_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val db = (requireActivity().application as App).database
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { File(record.filePath).takeIf { it.exists() }?.delete() }
                        db.transcriptDao().getByAudioId(record.id)?.txtPath?.let { txtPath ->
                            runCatching { File(txtPath).takeIf { it.exists() }?.delete() }
                        }
                        db.audioRecordDao().deleteByIds(listOf(record.id))
                    }
                    toast(getString(R.string.journal_deleted))
                }
            }
            .show()
    }

    private fun deleteTranscript(transcript: TranscriptEntity) {
        val db = (requireActivity().application as App).database
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(transcript.txtPath ?: "").takeIf { it.exists() }?.delete() }
                db.transcriptDao().deleteById(transcript.id)
                db.audioRecordDao().updateTranscribeStatus(transcript.audioId, 0)
            }
            toast(getString(R.string.journal_deleted))
        }
    }

    /** 重新转写单条录音 */
    private fun retranscribeRecord(record: AudioRecordEntity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.journal_retranscribe)
            .setMessage(R.string.journal_retranscribe_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val db = (requireActivity().application as App).database
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 删除旧转写
                        val transcript = db.transcriptDao().getByAudioId(record.id)
                        if (transcript != null) {
                            runCatching { File(transcript.txtPath ?: "").takeIf { it.exists() }?.delete() }
                            db.transcriptDao().deleteById(transcript.id)
                        }
                        // 重置转写状态为未转写
                        db.audioRecordDao().updateTranscribeStatus(record.id, 0)
                    }
                    // 触发转写任务
                    WorkScheduler.enqueueTranscriptionManual(requireContext())
                    toast(getString(R.string.journal_retranscribe_started))
                }
            }
            .show()
    }

    /** 重新转写指定日期下的所有录音 */
    private fun retranscribeDate(dateStr: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("重新转写当日所有录音")
            .setMessage("确认重新转写 $dateStr 的所有录音？将删除当前转写结果并重新生成，此操作可能需要较长时间。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val db = (requireActivity().application as App).database
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 获取该日期下所有已转写或转写失败的录音
                        val records = db.audioRecordDao().getByDate(dateStr)
                            .filter { it.transcribeStatus == 2 || it.transcribeStatus == 3 }
                        
                        // 删除所有旧转写
                        for (record in records) {
                            val transcript = db.transcriptDao().getByAudioId(record.id)
                            if (transcript != null) {
                                runCatching { File(transcript.txtPath ?: "").takeIf { it.exists() }?.delete() }
                                db.transcriptDao().deleteById(transcript.id)
                            }
                            // 重置转写状态为未转写
                            db.audioRecordDao().updateTranscribeStatus(record.id, 0)
                        }
                    }
                    // 触发转写任务
                    WorkScheduler.enqueueTranscriptionManual(requireContext())
                    toast(getString(R.string.journal_retranscribe_started))
                }
            }
            .show()
    }

    /** 继续转写指定日期下未转写/转写失败的录音 */
    private fun continueTranscribeDate(dateStr: String) {
        val db = (requireActivity().application as App).database
        viewLifecycleOwner.lifecycleScope.launch {
            // 先查出需要转写的录音条数，用于确认对话框
            val pendingCount = withContext(Dispatchers.IO) {
                db.audioRecordDao().getUntranscribedByDate(dateStr).size
            }
            if (pendingCount == 0) {
                toast("没有需要转写的录音")
                return@launch
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.journal_continue_transcribe)
                .setMessage(getString(R.string.journal_continue_transcribe_confirm, dateStr, pendingCount))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            // 将转写失败（状态 3）的录音重置为未转写（状态 0），状态 0 保持不变
                            val pending = db.audioRecordDao().getUntranscribedByDate(dateStr)
                            for (record in pending) {
                                if (record.transcribeStatus == 3) {
                                    db.audioRecordDao().updateTranscribeStatus(record.id, 0)
                                }
                            }
                        }
                        // 触发转写任务
                        WorkScheduler.enqueueTranscriptionManual(requireContext())
                        toast(getString(R.string.journal_continue_transcribe_started))
                    }
                }
                .show()
        }
    }

    private fun deleteAnalysis(dateStr: String) {
        val db = (requireActivity().application as App).database
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.analysisDao().deleteByDate(dateStr) }
            toast(getString(R.string.journal_deleted))
        }
    }

    // ---- 导出 ----

    /** 导出录音文件 */
    private fun exportRecord(record: AudioRecordEntity) {
        val file = File(record.filePath)
        if (!file.exists()) {
            toast("录音文件不存在")
            return
        }
        pendingExportRecord = record
        // 使用原始文件扩展名（录音格式为 wav），避免导出 .mp3 但实际是 WAV 导致播放器无法识别
        val ext = record.fileName.substringAfterLast(".", "wav")
        val fileName = record.fileName.substringBeforeLast(".") + ".$ext"
        exportAudioLauncher.launch(fileName)
    }

    /** 导出转写文本 */
    private fun exportTranscript(transcript: TranscriptEntity) {
        pendingExportTranscript = transcript
        val fileName = "转写_${transcript.dateStr}_${transcript.audioId}.txt"
        exportTextLauncher.launch(fileName)
    }

    /** 导出 AI 分析结果 */
    private fun exportAnalysis(analysis: AnalysisEntity) {
        pendingExportAnalysis = analysis
        val fileName = "分析_${analysis.dateStr}.txt"
        exportTextLauncher.launch(fileName)
    }

    /** 复制录音文件到用户选择的 URI */
    private fun copyFileToUri(record: AudioRecordEntity?, uri: Uri) {
        if (record == null) return
        val srcFile = File(record.filePath)
        if (!srcFile.exists()) {
            toast("录音文件不存在")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { it.copyTo(out) }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            toast(if (ok) "录音已导出" else "导出失败")
        }
    }

    /** 写入文本到用户选择的 URI */
    private fun writeTextToUri(text: String, label: String, uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            toast(if (ok) "$label 已导出" else "导出失败")
        }
    }

    // ---- 导入录音 ----

    /** 导入外部音频文件到 app 录音目录 */
    private fun importAudioFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val ctx = requireContext()
                    val now = System.currentTimeMillis()
                    val destFile = FileUtils.newRecordingFile(ctx, now)

                    // 复制文件
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: return@withContext false

                    // 获取文件时长
                    val durationMs = getAudioDuration(destFile)

                    val record = AudioRecordEntity(
                        filePath = destFile.absolutePath,
                        fileName = destFile.name,
                        startTime = now,
                        endTime = now + durationMs,
                        durationMs = durationMs,
                        dateStr = FileUtils.dateStr(now),
                        fileSizeBytes = destFile.length(),
                        transcribeStatus = 0
                    )
                    val db = (ctx.applicationContext as App).database
                    db.audioRecordDao().insert(record)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            toast(if (ok) "录音已导入" else "导入失败")
        }
    }

    /** 用 MediaPlayer 获取音频时长 */
    private fun getAudioDuration(file: File): Long {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val dur = mp.duration.toLong()
            mp.release()
            dur
        } catch (e: Exception) {
            0L
        }
    }

    // ---- 播放 ----

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback(record: AudioRecordEntity) {
        val file = File(record.filePath)
        if (!file.exists()) {
            toast("录音文件不存在")
            return
        }

        if (playingRecordId == record.id && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            playingRecordId = -1L
            adapter.updatePlayingState(-1L)
            return
        }

        stopPlayback()

        try {
            val mp = MediaPlayer().apply {
                setDataSource(record.filePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ ->
                    toast("播放失败")
                    stopPlayback()
                    true
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
            mediaPlayer = mp
            playingRecordId = record.id
            adapter.updatePlayingState(record.id)
        } catch (e: Exception) {
            toast("无法播放: ${e.message}")
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) { }
        }
        mediaPlayer = null
        playingRecordId = -1L
        adapter.updatePlayingState(-1L)
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPlayback()
        _binding = null
    }

    companion object {
        private const val ARG_SHOW_DELETE_RECORD = "show_delete_record"
        private const val ARG_SHOW_EXPORT = "show_export"

        fun newInstance(showDeleteRecord: Boolean = false, showExport: Boolean = false): AnalysisFragment {
            return AnalysisFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_SHOW_DELETE_RECORD, showDeleteRecord)
                    putBoolean(ARG_SHOW_EXPORT, showExport)
                }
            }
        }
    }
}
