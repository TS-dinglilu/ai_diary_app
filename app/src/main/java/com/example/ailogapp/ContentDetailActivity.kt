package com.example.ailogapp

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ailogapp.data.AppDatabase
import com.example.ailogapp.data.entities.NoteEntity
import com.example.ailogapp.databinding.ActivityContentDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 内容详情页：查看完整的转写内容或 AI 分析结果，并在底部编写笔记。
 *
 * 通过 Intent extras 传入类型与来源：
 * - EXTRA_TYPE: "transcript" 或 "analysis"
 * - EXTRA_DATE: 归属日期 yyyy-MM-dd
 * - EXTRA_SOURCE_ID: 转写 id（transcript.id）或分析 id（analysis.id）
 * - EXTRA_SOURCE_LABEL: 列表展示用的来源标签，如 "08:30 转写"
 */
class ContentDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContentDetailBinding
    private lateinit var type: String
    private lateinit var dateStr: String
    private var sourceId: Long = 0L
    private lateinit var sourceLabel: String

    private val noteDao by lazy { AppDatabase.getInstance(this).noteDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_TRANSCRIPT
        dateStr = intent.getStringExtra(EXTRA_DATE) ?: return finishActivity()
        sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, -1L)
        sourceLabel = intent.getStringExtra(EXTRA_SOURCE_LABEL) ?: ""

        binding.btnBack.setOnClickListener { finish() }

        if (type == TYPE_TRANSCRIPT) {
            binding.tvTitle.text = getString(R.string.detail_transcript)
            loadTranscriptContent()
        } else {
            binding.tvTitle.text = getString(R.string.detail_analysis)
            loadAnalysisContent()
        }

        binding.btnSaveNote.setOnClickListener { saveNote() }

        observeNotes()
    }

    /** 加载并展示完整转写内容 */
    private fun loadTranscriptContent() {
        if (sourceId <= 0L) {
            binding.tvTranscriptContent.text = getString(R.string.detail_no_content)
            binding.tvTranscriptContent.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch {
            val transcript = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@ContentDetailActivity)
                    .transcriptDao().getByAudioId(sourceId)
            }
            binding.tvTranscriptContent.text = transcript?.text?.ifBlank { getString(R.string.detail_no_content) }
                ?: getString(R.string.detail_no_content)
            binding.tvTranscriptContent.visibility = View.VISIBLE
        }
    }

    /** 加载并展示完整 AI 分析内容 */
    private fun loadAnalysisContent() {
        lifecycleScope.launch {
            val analysis = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@ContentDetailActivity)
                    .analysisDao().getByDate(dateStr)
            }
            if (analysis == null) {
                binding.tvTranscriptContent.text = getString(R.string.detail_no_content)
                binding.tvTranscriptContent.visibility = View.VISIBLE
                return@launch
            }
            binding.tvAnalysisEmotion.text = "${analysis.emotion} (${analysis.emotionScore})"
            binding.tvAnalysisEmotion.visibility = View.VISIBLE

            binding.tvSummaryLabel.visibility = View.VISIBLE
            binding.tvAnalysisSummary.text = analysis.summary.ifBlank { getString(R.string.detail_no_content) }
            binding.tvAnalysisSummary.visibility = View.VISIBLE

            binding.tvDiaryLabel.visibility = View.VISIBLE
            binding.tvAnalysisDiary.text = analysis.diaryContent.ifBlank { getString(R.string.detail_no_content) }
            binding.tvAnalysisDiary.visibility = View.VISIBLE
        }
    }

    /** 观察当前来源下的已有笔记并展示 */
    private fun observeNotes() {
        lifecycleScope.launch {
            noteDao.observeBySource(type, sourceId).collectLatest { notes ->
                binding.layoutExistingNotes.visibility =
                    if (notes.isEmpty()) View.GONE else View.VISIBLE
                binding.notesContainer.removeAllViews()
                val inflater = LayoutInflater.from(this@ContentDetailActivity)
                for (note in notes) {
                    val itemView = inflater.inflate(
                        R.layout.item_detail_note, binding.notesContainer, false
                    )
                    itemView.findViewById<TextView>(R.id.tvNoteContent).text = note.content
                    val timeStr = DateFormat.format("MM-dd HH:mm", Date(note.createdAt))
                    itemView.findViewById<TextView>(R.id.tvNoteTime).text = timeStr.toString()
                    itemView.findViewById<TextView>(R.id.btnDeleteNote).setOnClickListener {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { noteDao.deleteById(note.id) }
                            Toast.makeText(
                                this@ContentDetailActivity,
                                R.string.journal_note_deleted,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    binding.notesContainer.addView(itemView)
                }
            }
        }
    }

    /** 保存笔记 */
    private fun saveNote() {
        val content = binding.etNoteContent.text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.journal_note_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val note = NoteEntity(
            dateStr = dateStr,
            content = content,
            sourceType = type,
            sourceId = sourceId,
            sourceLabel = sourceLabel.ifBlank {
                if (type == TYPE_TRANSCRIPT) getString(R.string.journal_note_from_transcript)
                else getString(R.string.journal_note_from_analysis)
            }
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { noteDao.upsert(note) }
            binding.etNoteContent.text?.clear()
            Toast.makeText(this@ContentDetailActivity, R.string.journal_note_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishActivity() {
        finish()
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_SOURCE_ID = "extra_source_id"
        const val EXTRA_SOURCE_LABEL = "extra_source_label"

        const val TYPE_TRANSCRIPT = "transcript"
        const val TYPE_ANALYSIS = "analysis"
    }
}
