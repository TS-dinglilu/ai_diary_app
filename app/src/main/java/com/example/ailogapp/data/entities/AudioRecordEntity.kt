package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 录音文件记录。
 * 按设定时长（默认 3 分钟）切分为一个文件，对应一条记录。
 */
@Entity(
    tableName = "audio_records",
    indices = [
        Index("dateStr"),
        Index("transcribeStatus"),
        Index("startTime")
    ]
)
data class AudioRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 录音文件绝对路径 */
    val filePath: String,
    val fileName: String,
    /** 开始时间戳(ms) */
    val startTime: Long,
    /** 结束时间戳(ms)，若正在录制则为 0 */
    val endTime: Long = 0,
    /** 时长(ms) */
    val durationMs: Long = 0,
    /** 归属日期 yyyy-MM-dd，用于按天聚合分析 */
    val dateStr: String,
    /** 文件大小(字节) */
    val fileSizeBytes: Long = 0,
    /**
     * 转写状态：0 未转写，1 转写中，2 已转写，3 失败
     */
    val transcribeStatus: Int = 0,
    /** 是否已纳入当天 AI 分析 */
    val analyzedInDay: Boolean = false,
    /** 是否已软删除（录音文件已删除但保留转写和数据库记录） */
    val isDeleted: Boolean = false
)
