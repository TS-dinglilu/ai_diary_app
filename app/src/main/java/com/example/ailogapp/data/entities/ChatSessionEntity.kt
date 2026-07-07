package com.example.ailogapp.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 聊天会话，类似微信的"一个对话"。
 * 每次新建聊天创建一条记录，支持多个独立对话。
 */
@Entity(tableName = "chat_sessions", indices = [Index("updatedAt")])
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对话标题（默认"新对话"，首条消息后可自动更新） */
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 最后一条消息预览 */
    val lastMessage: String = "",
    /** 最后一条消息时间戳 */
    val lastMessageAt: Long = System.currentTimeMillis()
)
