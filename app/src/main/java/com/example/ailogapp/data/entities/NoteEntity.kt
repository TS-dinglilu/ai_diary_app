package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户笔记，可在详情页（转写/分析）中编写。
 *
 * 笔记按日期关联，在 AI 分析界面独立展示，不受日期折叠影响。
 * 一天可有多条笔记，每条笔记关联一个来源（转写条目或当日 AI 分析）。
 */
@Entity(tableName = "notes", indices = [Index("dateStr"), Index("sourceId")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 笔记归属日期 yyyy-MM-dd */
    val dateStr: String,
    /** 笔记正文 */
    val content: String,
    /** 来源类型：transcript=转写条目，analysis=AI 分析 */
    val sourceType: String,
    /** 来源 id：transcript.id 或 analysis.id */
    val sourceId: Long,
    /** 来源展示标签，如 "08:30 转写" 或 "AI 分析"，避免列表显示时再反查来源 */
    val sourceLabel: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
