package com.example.ailogapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ailogapp.databinding.ItemChatListBinding

/**
 * 聊天列表展示用的数据模型。
 * [timeStr] 已在外层格式化好，适配器只负责展示。
 */
data class ChatListItem(
    val sessionId: Long,
    val title: String,
    val preview: String,
    val timeStr: String
)

/**
 * 微信风格聊天列表适配器。
 * 点击条目时通过 [onItemClick] 回调返回 sessionId。
 */
class ChatListAdapter(
    private val onItemClick: (Long) -> Unit
) : ListAdapter<ChatListItem, ChatListAdapter.ChatListViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatListItem>() {
            override fun areItemsTheSame(
                oldItem: ChatListItem, newItem: ChatListItem
            ): Boolean = oldItem.sessionId == newItem.sessionId

            override fun areContentsTheSame(
                oldItem: ChatListItem, newItem: ChatListItem
            ): Boolean = oldItem == newItem
        }
    }

    inner class ChatListViewHolder(val binding: ItemChatListBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatListViewHolder {
        val binding = ItemChatListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatListViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatListViewHolder, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            tvTitle.text = item.title
            tvPreview.text = item.preview
            tvTime.text = item.timeStr
            root.setOnClickListener { onItemClick(item.sessionId) }
        }
    }
}
