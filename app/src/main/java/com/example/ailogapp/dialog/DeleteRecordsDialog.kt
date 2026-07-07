package com.example.ailogapp.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ailogapp.App
import com.example.ailogapp.R
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.databinding.DialogDeleteRecordsBinding
import com.example.ailogapp.ui.DeleteRecordsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 删除录音对话框。
 *
 * 功能：
 * - 按日期筛选（下拉选择"全部日期"或某个具体日期）
 * - 按转写状态筛选（全部 / 未转写 / 已转写）
 * - 多选录音条目，支持全选/取消全选
 * - 确认后删除选中的录音文件和数据库记录（关联的 transcripts 通过外键 CASCADE 自动清理）
 */
class DeleteRecordsDialog(
    private val onDeleted: (Int) -> Unit = {}
) : DialogFragment() {

    private var _binding: DialogDeleteRecordsBinding? = null
    private val binding get() = _binding!!
    private val adapter = DeleteRecordsAdapter { updateSelectedCount() }

    private val allDates = mutableListOf<String>()
    private var currentDateFilter = ""
    private var currentStatusFilter = STATUS_ALL

    // 删除类型：0=只删录音，1=只删文字，2=都删
    private var deleteType = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDeleteRecordsBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        setupUI()
        loadData()
        return dialog
    }

    private fun setupUI() {
        binding.rvDeleteList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDeleteList.adapter = adapter

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirmDelete.setOnClickListener { confirmDelete() }
        binding.btnSelectAll.setOnClickListener {
            if (adapter.selectedCount() == adapter.itemCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        // 全选当天
        binding.btnSelectAllDate.setOnClickListener {
            selectAllCurrentDate()
        }

        // 删除类型选择
        binding.chipGroupDeleteType.setOnCheckedStateChangeListener { _, checkedIds ->
            deleteType = when (checkedIds.firstOrNull()) {
                binding.chipDeleteAudio.id -> DELETE_TYPE_AUDIO
                binding.chipDeleteTranscript.id -> DELETE_TYPE_TRANSCRIPT
                binding.chipDeleteBoth.id -> DELETE_TYPE_BOTH
                else -> DELETE_TYPE_AUDIO
            }
        }
    }

    /** 全选当前筛选日期下的所有录音 */
    private fun selectAllCurrentDate() {
        if (currentDateFilter.isEmpty()) {
            // 如果筛选的是全部日期，则全选所有
            adapter.selectAll()
        } else {
            // 只全选当前日期的
            adapter.selectAllByDate(currentDateFilter)
        }
        updateSelectedCount()
    }

    private fun loadData() {
        val db = (requireActivity().application as App).database
        val ctx = requireContext()
        lifecycleScope.launch {
            // 加载所有日期
            allDates.clear()
            allDates.add(ctx.getString(R.string.filter_all_dates))
            allDates.addAll(withContext(Dispatchers.IO) { db.audioRecordDao().getAllDates() })

            // 协程恢复后 dialog 可能已被 dismiss（_binding 为空），需检查避免崩溃
            if (_binding == null) return@launch
            setupDateSpinner()
            setupStatusSpinner()
            applyFilter()
        }
    }

    private fun setupDateSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, allDates)
        binding.spDateFilter.setAdapter(adapter)
        binding.spDateFilter.setText(allDates.first(), false)
        binding.spDateFilter.setOnItemClickListener { _, _, position, _ ->
            currentDateFilter = if (position == 0) "" else allDates[position]
            applyFilter()
        }
    }

    private fun setupStatusSpinner() {
        val statuses = listOf(
            getString(R.string.filter_all_status),
            getString(R.string.filter_untranscribed),
            getString(R.string.filter_transcribed)
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, statuses)
        binding.spStatusFilter.setAdapter(adapter)
        binding.spStatusFilter.setText(statuses.first(), false)
        binding.spStatusFilter.setOnItemClickListener { _, _, position, _ ->
            currentStatusFilter = position
            applyFilter()
        }
    }

    private fun applyFilter() {
        val db = (requireActivity().application as App).database
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                val dates = if (currentDateFilter.isEmpty()) {
                    db.audioRecordDao().getAllDates()
                } else {
                    listOf(currentDateFilter)
                }
                when (currentStatusFilter) {
                    STATUS_UNTRANSCRIBED -> db.audioRecordDao().filter(dates, listOf(0, 3))
                    STATUS_TRANSCRIBED -> db.audioRecordDao().filter(dates, listOf(2))
                    else -> db.audioRecordDao().filterByDates(dates)
                }
            }
            // 协程恢复后 dialog 可能已被 dismiss，需检查避免崩溃
            if (_binding == null) return@launch
            adapter.submitList(records)
            updateEmptyState(records.isEmpty())
            updateSelectedCount()
        }
    }

    private fun updateEmptyState(empty: Boolean) {
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvDeleteList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateSelectedCount() {
        val count = adapter.selectedCount()
        binding.tvSelectedCount.text = getString(R.string.delete_selected_count, count)
        binding.btnSelectAll.text = if (count == adapter.itemCount && count > 0) {
            getString(R.string.deselect_all)
        } else {
            getString(R.string.select_all)
        }
    }

    private fun confirmDelete() {
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(), R.string.delete_none_selected, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setMessage(getString(when (deleteType) {
                DELETE_TYPE_AUDIO -> R.string.delete_confirm_msg_audio
                DELETE_TYPE_TRANSCRIPT -> R.string.delete_confirm_msg_transcript
                else -> R.string.delete_confirm_msg
            }, ids.size))
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                performDelete(ids.toList())
            }
            .setNegativeButton(R.string.delete_cancel, null)
            .show()
    }

    private fun performDelete(ids: List<Long>) {
        val db = (requireActivity().application as App).database
        val ctx = requireContext()
        lifecycleScope.launch {
            val count = ids.size
            withContext(Dispatchers.IO) {
                // 先查出文件路径，删除物理文件
                val records = db.audioRecordDao().getByIds(ids)
                // 批量查询转写记录，避免逐条 N+1 查询
                val transcriptMap = db.transcriptDao().getByAudioIds(ids).associateBy { it.audioId }

                when (deleteType) {
                    DELETE_TYPE_AUDIO -> {
                        // 只删录音文件，保留转写文字（软删除）
                        records.forEach { r ->
                            runCatching {
                                val file = File(r.filePath)
                                if (file.exists()) file.delete()
                            }
                        }
                        // 标记为已删除（软删除）
                        db.audioRecordDao().markAsDeleted(ids)
                    }
                    DELETE_TYPE_TRANSCRIPT -> {
                        // 只删转写文字，保留录音文件
                        records.forEach { r ->
                            transcriptMap[r.id]?.txtPath?.let { txtPath ->
                                runCatching {
                                    val txtFile = File(txtPath)
                                    if (txtFile.exists()) txtFile.delete()
                                }
                            }
                        }
                        // 删除转写数据库记录
                        val transcriptIds = transcriptMap.values.map { it.id }
                        if (transcriptIds.isNotEmpty()) {
                            db.transcriptDao().deleteByIds(transcriptIds)
                        }
                        // 重置录音的转写状态为未转写
                        db.audioRecordDao().resetTranscribeStatus(ids)
                    }
                    DELETE_TYPE_BOTH -> {
                        // 都删（硬删除）
                        records.forEach { r ->
                            runCatching {
                                val file = File(r.filePath)
                                if (file.exists()) file.delete()
                            }
                            // 删除转写 txt 文件
                            transcriptMap[r.id]?.txtPath?.let { txtPath ->
                                runCatching {
                                    val txtFile = File(txtPath)
                                    if (txtFile.exists()) txtFile.delete()
                                }
                            }
                        }
                        // 删除数据库记录（transcripts 表会 CASCADE 自动删除）
                        db.audioRecordDao().deleteByIds(ids)
                    }
                }
            }
            val deleteTypeStr = when (deleteType) {
                DELETE_TYPE_AUDIO -> "录音文件"
                DELETE_TYPE_TRANSCRIPT -> "转写文字"
                else -> "录音和文字"
            }
            android.widget.Toast.makeText(
                ctx,
                "已删除 $count 条$deleteTypeStr",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            onDeleted(count)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val STATUS_ALL = 0
        private const val STATUS_UNTRANSCRIBED = 1
        private const val STATUS_TRANSCRIBED = 2

        // 删除类型：0=只删录音，1=只删文字，2=都删
        const val DELETE_TYPE_AUDIO = 0
        const val DELETE_TYPE_TRANSCRIPT = 1
        const val DELETE_TYPE_BOTH = 2

        const val TAG = "DeleteRecordsDialog"
    }
}
