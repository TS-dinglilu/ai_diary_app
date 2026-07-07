package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 语音转文字结果，与录音记录一一对应。
 */
@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = AudioRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("audioId"), Index("dateStr")]
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联的录音记录 id */
    val audioId: Long,
    val dateStr: String,
    /** 转写得到的文本内容 */
    val text: String,
    /** 转写文本文件路径（保存为 txt） */
    val txtPath: String? = null,
    /** 转写来源：local / openai-whisper / aliyun 等 */
    val source: String = "local",
    /** 说话人ID（0表示未区分，1、2、3...表示不同说话人） */
    val speakerId: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
