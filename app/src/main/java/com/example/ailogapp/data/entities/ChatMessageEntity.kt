package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天消息记录，属于某个会话（ChatSession）。
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index("createdAt"), Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属会话 id */
    val sessionId: Long,
    /** 角色：user / assistant */
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
