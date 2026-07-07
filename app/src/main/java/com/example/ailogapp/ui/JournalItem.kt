package com.example.ailogapp.ui

import com.example.ailogapp.data.entities.AnalysisEntity
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.data.entities.NoteEntity
import com.example.ailogapp.data.entities.TranscriptEntity

/**
 * 按日期聚合的日志数据，用于 AI 分析页面的分组展示。
 */
data class JournalDay(
    val dateStr: String,
    val records: List<AudioRecordEntity>,
    val transcripts: List<TranscriptEntity>,
    val analysis: AnalysisEntity?
)

/**
 * RecyclerView 用的扁平化条目，按日期分组排列。
 */
sealed class JournalItem {
    data class Header(
        val dateStr: String,
        val recordCount: Int,
        val hasTranscribed: Boolean = false,
        /** 是否有未转写的录音（状态 0 未转写 或 3 转写失败），用于显示"继续转写"按钮 */
        val hasUntranscribed: Boolean = false,
        /** 是否正在转写中（存在状态 1 的录音），用于第二行状态显示 */
        val isTranscribing: Boolean = false,
        /** 是否全部转写完成（所有录音状态均为 2），用于第二行状态显示 */
        val allTranscribed: Boolean = false,
        /** 已转写条数，用于第二行"X/Y 已转写"显示 */
        val transcribedCount: Int = 0
    ) : JournalItem()
    data class RecordItem(val record: AudioRecordEntity, val transcript: TranscriptEntity?) : JournalItem()
    data class AnalysisItem(val analysis: AnalysisEntity) : JournalItem()
    /** 用户笔记，独立展示，不受日期折叠影响 */
    data class NoteItem(val note: NoteEntity) : JournalItem()
    object Empty : JournalItem()
}
