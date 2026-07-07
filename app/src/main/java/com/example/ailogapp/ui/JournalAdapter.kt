package com.example.ailogapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ailogapp.R
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.data.entities.NoteEntity
import com.example.ailogapp.data.entities.TranscriptEntity
import com.example.ailogapp.databinding.ItemJournalAnalysisBinding
import com.example.ailogapp.databinding.ItemJournalHeaderBinding
import com.example.ailogapp.databinding.ItemJournalNoteBinding
import com.example.ailogapp.databinding.ItemJournalRecordBinding
import com.example.ailogapp.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JournalAdapter(
    private val onDeleteRecord: (AudioRecordEntity) -> Unit,
    private val onDeleteTranscript: (TranscriptEntity) -> Unit,
    private val onDeleteAnalysis: (String) -> Unit,
    private val onPlayRecord: (AudioRecordEntity) -> Unit = {},
    /** 是否显示"删除录音"按钮。AI 分析页隐藏，录音管理页显示 */
    private val showDeleteRecord: Boolean = true,
    /** 是否显示导出按钮。AI 分析页隐藏，录音管理页显示 */
    private val showExport: Boolean = false,
    /** 导出录音文件 */
    private val onExportRecord: (AudioRecordEntity) -> Unit = {},
    /** 导出转写文本 */
    private val onExportTranscript: (TranscriptEntity) -> Unit = {},
    /** 导出 AI 分析结果 */
    private val onExportAnalysis: (com.example.ailogapp.data.entities.AnalysisEntity) -> Unit = {},
    /** 重新转写按钮回调 */
    private val onRetranscribe: (AudioRecordEntity) -> Unit = {},
    /** 是否显示重新转写按钮 */
    private val showRetranscribe: Boolean = true,
    /** 点击转写文本，进入详情页查看完整内容 */
    private val onTranscriptClick: (AudioRecordEntity, TranscriptEntity?) -> Unit = { _, _ -> },
    /** 点击 AI 分析内容，进入详情页查看完整内容 */
    private val onAnalysisClick: (String) -> Unit = {},
    /** 日期级重新转写按钮回调 */
    private val onRetranscribeDate: (String) -> Unit = {},
    /** 是否显示日期级重新转写按钮 */
    private val showRetranscribeDate: Boolean = true,
    /** 日期级继续转写按钮回调（转写未转写/失败的录音） */
    private val onContinueTranscribeDate: (String) -> Unit = {},
    /** 是否显示日期级继续转写按钮 */
    private val showContinueTranscribeDate: Boolean = true,
    /** 日期头部点击回调（用于折叠/展开录音） */
    private val onHeaderClick: (String) -> Unit = {},
    /** 已折叠的日期集合 */
    private val collapsedDates: Set<String> = emptySet()
) : ListAdapter<JournalItem, RecyclerView.ViewHolder>(DIFF) {

    /** 当前正在播放的录音 id，-1 表示未播放（实例变量，避免跨页面静态共享导致状态错乱） */
    private var currentPlayingId: Long = -1L

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RECORD = 1
        private const val TYPE_ANALYSIS = 2
        private const val TYPE_NOTE = 3

        /** 录音时间格式化器，复用避免重复创建（仅主线程绑定使用） */
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<JournalItem>() {
            override fun areItemsTheSame(a: JournalItem, b: JournalItem): Boolean = when {
                a is JournalItem.Header && b is JournalItem.Header -> a.dateStr == b.dateStr
                a is JournalItem.RecordItem && b is JournalItem.RecordItem -> a.record.id == b.record.id
                a is JournalItem.AnalysisItem && b is JournalItem.AnalysisItem ->
                    a.analysis.dateStr == b.analysis.dateStr
                a is JournalItem.NoteItem && b is JournalItem.NoteItem -> a.note.id == b.note.id
                a is JournalItem.Empty && b is JournalItem.Empty -> true
                else -> false
            }
            override fun areContentsTheSame(a: JournalItem, b: JournalItem): Boolean = a == b
        }
    }

    class HeaderVH(val binding: ItemJournalHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class RecordVH(val binding: ItemJournalRecordBinding) : RecyclerView.ViewHolder(binding.root)
    class AnalysisVH(val binding: ItemJournalAnalysisBinding) : RecyclerView.ViewHolder(binding.root)
    class NoteVH(val binding: ItemJournalNoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is JournalItem.Header -> TYPE_HEADER
        is JournalItem.RecordItem -> TYPE_RECORD
        is JournalItem.AnalysisItem -> TYPE_ANALYSIS
        is JournalItem.NoteItem -> TYPE_NOTE
        else -> TYPE_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemJournalHeaderBinding.inflate(inflater, parent, false))
            TYPE_RECORD -> RecordVH(ItemJournalRecordBinding.inflate(inflater, parent, false))
            TYPE_ANALYSIS -> AnalysisVH(ItemJournalAnalysisBinding.inflate(inflater, parent, false))
            TYPE_NOTE -> NoteVH(ItemJournalNoteBinding.inflate(inflater, parent, false))
            else -> HeaderVH(ItemJournalHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is JournalItem.Header -> bindHeader(holder as HeaderVH, item)
            is JournalItem.RecordItem -> bindRecord(holder as RecordVH, item)
            is JournalItem.AnalysisItem -> bindAnalysis(holder as AnalysisVH, item)
            is JournalItem.NoteItem -> bindNote(holder as NoteVH, item)
            else -> {}
        }
    }

    /** 更新播放状态，仅刷新新旧播放条目 */
    fun updatePlayingState(playingId: Long) {
        val oldId = currentPlayingId
        currentPlayingId = playingId
        // 仅刷新旧播放条目和新播放条目，避免全量刷新
        if (oldId != playingId) {
            findPositionByRecordId(oldId)?.let { notifyItemChanged(it) }
            findPositionByRecordId(playingId)?.let { notifyItemChanged(it) }
        }
    }

    /** 根据录音 id 查找在列表中的位置 */
    private fun findPositionByRecordId(recordId: Long): Int? {
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (item is JournalItem.RecordItem && item.record.id == recordId) return i
        }
        return null
    }

    private fun bindHeader(h: HeaderVH, item: JournalItem.Header) {
        h.binding.tvDate.text = item.dateStr
        h.binding.tvRecordCount.text = "${item.recordCount} 段录音"

        // 折叠状态指示
        val isCollapsed = collapsedDates.contains(item.dateStr)
        h.binding.ivArrow.rotation = if (isCollapsed) 0f else 180f

        // 日期级重新转写按钮（红色）：有已转写/转写失败的录音时显示
        h.binding.btnRetranscribeDate.visibility = if (showRetranscribeDate && item.hasTranscribed) {
            View.VISIBLE
        } else {
            View.GONE
        }
        h.binding.btnRetranscribeDate.setOnClickListener {
            onRetranscribeDate(item.dateStr)
        }

        // 日期级继续转写按钮（绿色）：有未转写/转写失败的录音时显示
        h.binding.btnContinueTranscribeDate.visibility =
            if (showContinueTranscribeDate && item.hasUntranscribed) {
                View.VISIBLE
            } else {
                View.GONE
            }
        h.binding.btnContinueTranscribeDate.setOnClickListener {
            onContinueTranscribeDate(item.dateStr)
        }

        // 第二行：转写状态
        val ctx = h.itemView.context
        if (item.isTranscribing) {
            // 正在转写 —— 红色凸显
            h.binding.tvTranscribeStatus.text = ctx.getString(R.string.journal_transcribing)
            h.binding.tvTranscribeStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.accent_record)
            )
        } else if (item.allTranscribed) {
            // 全部转写完成 —— 绿色
            h.binding.tvTranscribeStatus.text = ctx.getString(R.string.journal_all_transcribed)
            h.binding.tvTranscribeStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.wechat_green)
            )
        } else {
            // 部分转写 —— 显示已转写条数
            h.binding.tvTranscribeStatus.text = ctx.getString(
                R.string.journal_partially_transcribed, item.transcribedCount, item.recordCount
            )
            h.binding.tvTranscribeStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_tertiary)
            )
        }

        // 点击头部切换折叠状态
        h.binding.root.setOnClickListener {
            onHeaderClick(item.dateStr)
        }
    }

    private fun bindRecord(h: RecordVH, item: JournalItem.RecordItem) {
        val r = item.record
        val time = "${TIME_FORMAT.format(Date(r.startTime))} · ${FileUtils.formatDuration(r.durationMs)}"
        val sizeStr = String.format(Locale.getDefault(), "%.1fMB", r.fileSizeBytes / 1048576.0)
        h.binding.tvTime.text = "$time · $sizeStr"

        // 已删除状态
        val isDeleted = r.isDeleted
        h.binding.tvDeleted.visibility = if (isDeleted) View.VISIBLE else View.GONE

        h.binding.tvStatus.text = when (r.transcribeStatus) {
            0 -> "未转写"; 1 -> "转写中"; 2 -> "已转写"; else -> "转写失败"
        }

        // 重新转写按钮：已转写或转写失败时显示，已删除的隐藏
        h.binding.btnRetranscribe.visibility = if (!isDeleted && showRetranscribe && (r.transcribeStatus == 2 || r.transcribeStatus == 3)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        h.binding.btnRetranscribe.setOnClickListener { onRetranscribe(r) }

        // 播放按钮：已删除的隐藏
        h.binding.btnPlayRecord.visibility = if (isDeleted) View.GONE else View.VISIBLE
        val isPlaying = r.id == currentPlayingId
        h.binding.btnPlayRecord.setImageResource(
            if (isPlaying) com.example.ailogapp.R.drawable.ic_pause
            else com.example.ailogapp.R.drawable.ic_play
        )
        h.binding.btnPlayRecord.setOnClickListener { onPlayRecord(r) }

        // 导出录音按钮：仅录音管理页显示，已删除的隐藏
        h.binding.btnExportRecord.visibility = if (showExport && !isDeleted) View.VISIBLE else View.GONE
        h.binding.btnExportRecord.setOnClickListener { onExportRecord(r) }

        // 删除录音按钮：AI 分析页隐藏，录音管理页显示，已删除的隐藏
        h.binding.btnDeleteRecord.visibility = if (showDeleteRecord && !isDeleted) View.VISIBLE else View.GONE
        h.binding.btnDeleteRecord.setOnClickListener { onDeleteRecord(r) }

        val transcriptText = item.transcript?.text
        if (transcriptText.isNullOrBlank()) {
            h.binding.tvTranscript.text = "暂无转写内容"
            h.binding.tvTranscript.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.context, com.example.ailogapp.R.color.text_tertiary))
            h.binding.btnDeleteTranscript.visibility = View.GONE
            h.binding.btnExportTranscript.visibility = View.GONE
            h.binding.tvSpeaker.visibility = View.GONE
        } else {
            // else 分支说明 transcriptText 非空，即 item.transcript 必然非空（编译器智能推断）
            val transcript = item.transcript
            h.binding.tvTranscript.text = transcriptText
            h.binding.tvTranscript.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.context, com.example.ailogapp.R.color.text_secondary))
            h.binding.btnDeleteTranscript.visibility = if (isDeleted) View.GONE else View.VISIBLE
            h.binding.btnDeleteTranscript.setOnClickListener {
                onDeleteTranscript(transcript)
            }
            // 导出转写按钮
            h.binding.btnExportTranscript.visibility = if (showExport && !isDeleted) View.VISIBLE else View.GONE
            h.binding.btnExportTranscript.setOnClickListener { onExportTranscript(transcript) }

            // 说话人标签
            val speakerId = transcript.speakerId
            if (speakerId > 0) {
                h.binding.tvSpeaker.text = "说话人$speakerId"
                h.binding.tvSpeaker.visibility = View.VISIBLE
            } else {
                h.binding.tvSpeaker.visibility = View.GONE
            }
        }

        // 点击转写文本进入详情页查看完整内容（有转写内容时才可点击）
        if (!transcriptText.isNullOrBlank()) {
            h.binding.tvTranscript.setOnClickListener { onTranscriptClick(r, item.transcript) }
            // 提示可点击：背景轻微可点击反馈
            h.binding.tvTranscript.isClickable = true
        } else {
            h.binding.tvTranscript.isClickable = false
        }
    }

    private fun bindAnalysis(h: AnalysisVH, item: JournalItem.AnalysisItem) {
        val a = item.analysis
        h.binding.tvEmotion.text = "${a.emotion} (${a.emotionScore})"
        h.binding.tvSummary.text = a.summary.ifBlank { "暂无摘要" }
        h.binding.tvDiary.text = a.diaryContent.ifBlank { "暂无日记内容" }
        h.binding.btnDeleteAnalysis.setOnClickListener { onDeleteAnalysis(a.dateStr) }
        // 导出分析结果
        h.binding.btnExportAnalysis.visibility = if (showExport) View.VISIBLE else View.GONE
        h.binding.btnExportAnalysis.setOnClickListener { onExportAnalysis(a) }

        // 点击分析内容进入详情页查看完整结果
        val clickListener = View.OnClickListener { onAnalysisClick(a.dateStr) }
        h.binding.tvSummary.setOnClickListener(clickListener)
        h.binding.tvDiary.setOnClickListener(clickListener)
    }

    private fun bindNote(h: NoteVH, item: JournalItem.NoteItem) {
        val note = item.note
        h.binding.tvNoteContent.text = note.content
        h.binding.tvNoteSource.text = note.sourceLabel.ifBlank {
            h.itemView.context.getString(
                if (note.sourceType == "transcript")
                    R.string.journal_note_from_transcript
                else
                    R.string.journal_note_from_analysis
            )
        }
    }
}
