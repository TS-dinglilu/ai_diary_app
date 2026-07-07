package com.example.ailogapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ailogapp.ai.AIClient
import com.example.ailogapp.ai.ChatMessage
import com.example.ailogapp.ai.ChatRequest
import com.example.ailogapp.ai.LocalASREngine
import com.example.ailogapp.data.entities.ChatMessageEntity
import com.example.ailogapp.databinding.ActivityChatDetailBinding
import com.example.ailogapp.ui.ChatAdapter
import com.example.ailogapp.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微信风格聊天详情页。
 *
 * 通过 Intent 携带 [EXTRA_SESSION_ID]（必传）与 [EXTRA_SESSION_TITLE]（可选）。
 * 观察该会话下的消息列表，发送消息时先存库再调用 AI 接口，收到回复后存入数据库。
 */
class ChatDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatDetailBinding
    private val adapter = ChatAdapter()
    private var isWaiting = false

    private var sessionId: Long = -1L

    // 语音录制相关
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentRecordingFile: File? = null
    private var recordingStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private val recordPermissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 必须携带 session_id，否则直接退出
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId == -1L) {
            finish()
            return
        }

        binding = ActivityChatDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置标题：优先使用 Intent 传入的标题，缺省时使用默认标题
        val title = intent.getStringExtra(EXTRA_SESSION_TITLE)
        binding.tvTitle.text = title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.chat_default_title)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 消息列表
        binding.rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChat.adapter = adapter

        // 发送
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage(); true
        }

        // 语音按钮
        binding.btnVoice.setOnClickListener { toggleVoiceRecording() }

        observeMessages()
    }

    /** 观察当前会话的消息列表，数据变化时自动刷新并滚动到底部。 */
    private fun observeMessages() {
        val db = (application as App).database
        lifecycleScope.launch {
            db.chatMessageDao().observeBySession(sessionId).collect { messages ->
                adapter.submitList(messages) {
                    if (messages.isNotEmpty()) {
                        binding.rvChat.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }

    /** 发送消息：保存用户消息 -> 更新会话预览 -> 调用 AI -> 保存回复 -> 更新预览。 */
    private fun sendMessage() {
        if (isWaiting) return
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        val db = (application as App).database
        val prefs = (application as App).prefs

        binding.etMessage.text.clear()

        lifecycleScope.launch {
            val now = System.currentTimeMillis()

            // 校验会话是否存在（可能已在列表页被删除），避免 insert 触发外键约束崩溃
            val session = db.chatSessionDao().getById(sessionId)
            if (session == null) {
                android.widget.Toast.makeText(
                    this@ChatDetailActivity, "会话已删除", android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }

            // 1. 保存用户消息
            db.chatMessageDao().insert(
                ChatMessageEntity(sessionId = sessionId, role = "user", content = text)
            )

            // 1b. 更新会话预览；首次发送时自动从消息生成标题
            val newTitle = if (session.title == "新对话") {
                if (text.length > 20) "${text.take(20)}…" else text
            } else {
                session.title
            }
            db.chatSessionDao().updatePreview(sessionId, newTitle, text, now, now)
            binding.tvTitle.text = newTitle

            // 2. 禁用发送按钮，提示正在思考
            isWaiting = true
            binding.btnSend.isEnabled = false
            binding.btnSend.text = getString(R.string.chat_thinking)

            // 3. 读取接口配置
            val apiKey = prefs.aiApiKey.trim()
            val apiUrl = prefs.aiApiUrl.trim()
            val model = prefs.aiModel.trim()

            // 4. 调用 AI 并获取回复（统一收集 reply，避免重复保存逻辑）
            val reply: String = if (apiKey.isEmpty() || apiUrl.isEmpty()) {
                getString(R.string.chat_error)
            } else {
                try {
                    // 取最近 10 条消息作为上下文（DAO 返回 DESC，需反转为时间正序）
                    val history = withContext(Dispatchers.IO) {
                        db.chatMessageDao().getRecentBySession(sessionId, 10).reversed()
                    }

                    val chatMessages = buildList {
                        // 构建包含录音上下文的 system prompt
                        val systemPrompt = buildSystemPrompt(db)
                        add(ChatMessage("system", systemPrompt))
                        history.forEach { msg -> add(ChatMessage(msg.role, msg.content)) }
                    }

                    val request = ChatRequest(
                        model = model,
                        messages = chatMessages,
                        temperature = 0.7
                    )

                    val resp = withContext(Dispatchers.IO) {
                        AIClient.service.chatCompletions(
                            apiUrl,
                            AIClient.bearer(apiKey),
                            request
                        )
                    }

                    if (resp.isSuccessful) {
                        resp.body()?.firstContent ?: "（空回复）"
                    } else {
                        "错误 ${resp.code()}"
                    }
                } catch (e: Exception) {
                    "请求出错: ${e.message}"
                }
            }

            // 5. 保存 AI 回复
            db.chatMessageDao().insert(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = reply
                )
            )

            // 5b. 更新会话预览为最新 AI 回复
            val replyTime = System.currentTimeMillis()
            db.chatSessionDao().updatePreview(sessionId, newTitle, reply, replyTime, replyTime)

            // 6. 恢复发送按钮
            isWaiting = false
            binding.btnSend.isEnabled = true
            binding.btnSend.text = getString(R.string.chat_send)
        }
    }

    /** 构建 system prompt，包含最近的录音转写和 AI 分析结果作为上下文 */
    private suspend fun buildSystemPrompt(db: com.example.ailogapp.data.AppDatabase): String {
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            sb.appendLine("你是一个贴心的个人助手，可以查看用户的录音转写和 AI 分析结果。")
            sb.appendLine("用户可以询问关于录音内容、情绪分析等任何问题，请基于以下上下文回答。")
            sb.appendLine()

            // 获取最近 5 条转写记录
            val transcripts = db.transcriptDao().getRecent(5)
            if (transcripts.isNotEmpty()) {
                sb.appendLine("=== 最近录音转写结果 ===")
                for (t in transcripts.asReversed()) {
                    val preview = if (t.text.length > 200) t.text.take(200) + "..." else t.text
                    sb.appendLine("【${t.dateStr}】$preview")
                }
                sb.appendLine()
            }

            // 获取最近 3 天的 AI 分析
            val analyses = db.analysisDao().getRecent(3)
            if (analyses.isNotEmpty()) {
                sb.appendLine("=== 最近 AI 分析结果 ===")
                for (a in analyses.asReversed()) {
                    sb.appendLine("【${a.dateStr}】情绪: ${a.emotion}(${a.emotionScore})")
                    sb.appendLine("摘要: ${a.summary}")
                    sb.appendLine()
                }
            }

            if (transcripts.isEmpty() && analyses.isEmpty()) {
                sb.appendLine("（暂无录音转写和分析数据）")
            }

            sb.toString()
        }
    }

    // ===== 语音录制相关 =====

    /** 切换语音录制状态 */
    private fun toggleVoiceRecording() {
        if (isRecording) {
            stopRecordingAndSend()
        } else {
            startRecording()
        }
    }

    /** 开始录音 */
    private fun startRecording() {
        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                recordPermissionRequestCode
            )
            return
        }

        if (isWaiting) {
            Toast.makeText(this, "AI正在思考中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 创建录音文件
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "chat_voice_${timeStamp}.m4a"
            val outputDir = File(filesDir, "chat_voice")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            currentRecordingFile = File(outputDir, fileName)

            // 初始化MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentRecordingFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                prepare()
                start()
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // 更新UI
            binding.btnVoice.setColorFilter(
                ContextCompat.getColor(this, R.color.accent_record),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            binding.etMessage.hint = "正在录音... 点击结束"
            binding.btnSend.isEnabled = false

            // 开始计时更新
            updateRecordingTime()

            Toast.makeText(this, "开始录音", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "录音启动失败", Toast.LENGTH_SHORT).show()
            releaseRecorder()
        }
    }

    /** 更新录音时间显示 */
    private fun updateRecordingTime() {
        if (!isRecording) return

        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        binding.etMessage.hint = String.format("正在录音 %02d:%02d... 点击结束", minutes, seconds)

        handler.postDelayed({ updateRecordingTime() }, 1000)
    }

    /** 停止录音并发送 */
    private fun stopRecordingAndSend() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mediaRecorder = null
        isRecording = false
        handler.removeCallbacksAndMessages(null)

        // 恢复UI
        binding.btnVoice.colorFilter = null
        binding.etMessage.hint = getString(R.string.chat_hint)
        binding.btnSend.isEnabled = true

        val file = currentRecordingFile
        if (file != null && file.exists() && file.length() > 0) {
            // 显示正在转写
            binding.etMessage.setText("正在转写语音...")
            binding.btnSend.isEnabled = false
            isWaiting = true

            // 后台转写
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        LocalASREngine.transcribe(this@ChatDetailActivity, file)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ""
                    }
                }

                isWaiting = false
                binding.btnSend.isEnabled = true

                if (text.isNotBlank()) {
                    // 转写成功，自动发送
                    binding.etMessage.setText(text)
                    sendMessage()
                } else {
                    binding.etMessage.text.clear()
                    Toast.makeText(this@ChatDetailActivity, "语音转写失败", Toast.LENGTH_SHORT).show()
                }

                // 清理临时录音文件
                runCatching { file.delete() }
            }
        } else {
            Toast.makeText(this, "录音太短或失败", Toast.LENGTH_SHORT).show()
        }

        currentRecordingFile = null
    }

    /** 释放录音资源 */
    private fun releaseRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // 忽略
        }
        mediaRecorder = null
        isRecording = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == recordPermissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "需要录音权限才能发送语音", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
        // 清理临时录音文件
        val outputDir = File(filesDir, "chat_voice")
        if (outputDir.exists()) {
            outputDir.deleteRecursively()
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_TITLE = "session_title"
    }
}
