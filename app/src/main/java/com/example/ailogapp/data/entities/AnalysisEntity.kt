package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * AI 分析结果，按天聚合，每天一条。
 * 最开始当天只有少量素材，后续会结合当天录音与历史分析结果一起生成。
 */
@Entity(tableName = "analyses", indices = [Index(value = ["dateStr"], unique = true)])
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 分析归属日期 yyyy-MM-dd */
    val dateStr: String,
    /** AI 替写的日记正文 */
    val diaryContent: String = "",
    /** 主要情绪标签，如 开心/焦虑/平静 */
    val emotion: String = "",
    /** 情绪分数 -1.0(消极) ~ 1.0(积极) */
    val emotionScore: Float = 0f,
    /** 简短总结 */
    val summary: String = "",
    /** 完整分析（含建议、关键词等）的 JSON */
    val fullAnalysis: String = "",
    /** 本次分析引用的录音记录 id 列表（逗号分隔） */
    val transcriptRefs: String = "",
    /** 本次分析覆盖的录音时长(ms) */
    val coveredDurationMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
