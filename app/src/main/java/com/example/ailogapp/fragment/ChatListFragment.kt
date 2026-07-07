package com.example.ailogapp.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ailogapp.App
import com.example.ailogapp.ChatDetailActivity
import com.example.ailogapp.data.entities.ChatSessionEntity
import com.example.ailogapp.databinding.FragmentChatListBinding
import com.example.ailogapp.ui.ChatListAdapter
import com.example.ailogapp.ui.ChatListItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatListFragment : Fragment() {

    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ChatListAdapter

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChatListAdapter(::onSessionClicked)
        binding.rvChatList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatList.adapter = adapter

        binding.btnAdd.setOnClickListener { createNewSession() }

        observeSessions()
    }

    /** 点击会话条目：从当前列表快照中取出标题，跳转到详情页。 */
    private fun onSessionClicked(sessionId: Long) {
        val title = adapter.currentList.firstOrNull { it.sessionId == sessionId }?.title
        openChatDetail(sessionId, title)
    }

    /** 观察会话列表，实时刷新 UI。 */
    private fun observeSessions() {
        val db = (requireActivity().application as App).database
        viewLifecycleOwner.lifecycleScope.launch {
            db.chatSessionDao().observeAll().collect { sessions ->
                val items = sessions.map { it.toChatListItem() }
                adapter.submitList(items)
                val empty = items.isEmpty()
                binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.rvChatList.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    /** 点击 + 新建一个会话，并跳转到详情页。 */
    private fun createNewSession() {
        val db = (requireActivity().application as App).database
        viewLifecycleOwner.lifecycleScope.launch {
            val session = ChatSessionEntity() // 标题默认"新对话"
            val newId = db.chatSessionDao().insert(session)
            openChatDetail(newId, session.title)
        }
    }

    /** 跳转到聊天详情页，传递 sessionId 与标题。 */
    private fun openChatDetail(sessionId: Long, title: String?) {
        val intent = Intent(requireContext(), ChatDetailActivity::class.java).apply {
            putExtra(ChatDetailActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(ChatDetailActivity.EXTRA_SESSION_TITLE, title ?: "")
        }
        startActivity(intent)
    }

    /** 将数据库实体映射为列表展示项，并按今天/历史格式化时间。 */
    private fun ChatSessionEntity.toChatListItem(): ChatListItem {
        val timeStr = if (isToday(lastMessageAt)) {
            timeFormat.format(Date(lastMessageAt))
        } else {
            dateFormat.format(Date(lastMessageAt))
        }
        return ChatListItem(
            sessionId = id,
            title = title,
            preview = lastMessage,
            timeStr = timeStr
        )
    }

    private fun isToday(timestamp: Long): Boolean {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = timestamp }
        return now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
