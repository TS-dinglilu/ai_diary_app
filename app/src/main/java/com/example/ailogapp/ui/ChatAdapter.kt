package com.example.ailogapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ailogapp.data.entities.ChatMessageEntity
import com.example.ailogapp.databinding.ItemChatAiBinding
import com.example.ailogapp.databinding.ItemChatMeBinding

class ChatAdapter : ListAdapter<ChatMessageEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_ME = 0
        private const val TYPE_AI = 1

        val DIFF = object : DiffUtil.ItemCallback<ChatMessageEntity>() {
            override fun areItemsTheSame(a: ChatMessageEntity, b: ChatMessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessageEntity, b: ChatMessageEntity) = a == b
        }
    }

    class MeVH(val binding: ItemChatMeBinding) : RecyclerView.ViewHolder(binding.root)
    class AiVH(val binding: ItemChatAiBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).role == "user") TYPE_ME else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ME) {
            MeVH(ItemChatMeBinding.inflate(inflater, parent, false))
        } else {
            AiVH(ItemChatAiBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is MeVH -> holder.binding.tvContent.text = msg.content
            is AiVH -> holder.binding.tvContent.text = msg.content
        }
    }
}
