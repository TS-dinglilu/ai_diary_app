package com.example.ailogapp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.databinding.ItemDeleteRecordBinding
import com.example.ailogapp.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除录音列表的多选适配器。
 * 点击整行或 CheckBox 均可切换选中状态。
 */
class DeleteRecordsAdapter(
    private val onSelectChanged: () -> Unit
) : RecyclerView.Adapter<DeleteRecordsAdapter.VH>() {

    private val items = mutableListOf<AudioRecordEntity>()
    private val selectedIds = mutableSetOf<Long>()

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    inner class VH(val binding: ItemDeleteRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                toggleSelection(bindingAdapterPosition)
            }
            // cbSelect 的监听在 onBindViewHolder 中统一设置，此处设置会在首次绑定时被覆盖（死代码），已移除
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDeleteRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.binding.tvDate.text = TIME_FORMAT.format(Date(r.startTime))
        holder.binding.tvDur.text = FileUtils.formatDuration(r.durationMs)

        val statusText = when (r.transcribeStatus) {
            0 -> "未转写"
            1 -> "转写中"
            2 -> "已转写"
            else -> "转写失败"
        }
        val sizeStr = String.format(Locale.getDefault(), "%.1f MB", r.fileSizeBytes / 1048576.0)
        holder.binding.tvStatus.text = "$statusText · $sizeStr"

        // 避免在 recycle 时触发回调
        holder.binding.cbSelect.setOnCheckedChangeListener(null)
        holder.binding.cbSelect.isChecked = selectedIds.contains(r.id)
        holder.binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            val pos = holder.bindingAdapterPosition
            if (pos < 0 || pos >= items.size) return@setOnCheckedChangeListener
            val id = items[pos].id
            if (isChecked) selectedIds.add(id) else selectedIds.remove(id)
            onSelectChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(list: List<AudioRecordEntity>) {
        items.clear()
        items.addAll(list)
        selectedIds.clear()
        notifyDataSetChanged()
    }

    private fun toggleSelection(position: Int) {
        if (position < 0 || position >= items.size) return
        val id = items[position].id
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        notifyItemChanged(position)
        onSelectChanged()
    }

    fun selectAll() {
        items.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectChanged()
    }

    /** 全选指定日期的所有录音 */
    fun selectAllByDate(dateStr: String) {
        items.filter { it.dateStr == dateStr }.forEach { selectedIds.add(it.id) }
        notifyDataSetChanged()
        onSelectChanged()
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectChanged()
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun selectedCount(): Int = selectedIds.size
}
