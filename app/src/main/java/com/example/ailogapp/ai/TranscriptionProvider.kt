package com.example.ailogapp.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.ailogapp.util.PrefsManager
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音转文字提供者，支持四种模式：
 *
 * 1) [PrefsManager.MODE_LOCAL]   内置模型：使用 sherpa-onnx 离线引擎，完全本地运行，无需网络。
 * 2) [PrefsManager.MODE_WHISPER] Whisper API：multipart 上传音频到 /v1/audio/transcriptions。
 * 3) [PrefsManager.MODE_AI]      AI 大模型：将音频以 base64 编码，通过 chat/completions 接口
 *                                发给支持音频输入的 LLM（如 GPT-4o），让模型直接转写。
 * 4) [PrefsManager.MODE_OFF]     关闭：仅生成空 txt 占位，等待后续处理。
 */
class TranscriptionProvider(private val context: Context) {

    private val prefs = PrefsManager(context)

    /** 返回转写文本；source 标识来源。失败时返回空文本 + 错误来源。 */
    suspend fun transcribe(audioFile: File): Result {
        val mode = prefs.transcribeMode
        val apiKey = prefs.aiApiKey.trim()

        return when (mode) {
            PrefsManager.MODE_LOCAL -> {
                tryLocalOffline(audioFile)
            }
            PrefsManager.MODE_WHISPER -> {
                val url = prefs.transcribeUrl.trim()
                if (url.isEmpty() || apiKey.isEmpty()) {
                    Log.w(TAG, "Whisper 模式但未配置接口地址/Key")
                    Result("", "local-noconfig", 0)
                } else {
                    tryWhisper(url, apiKey, audioFile)
                }
            }
            PrefsManager.MODE_AI -> {
                val url = prefs.aiApiUrl.trim()
                if (url.isEmpty() || apiKey.isEmpty()) {
                    Log.w(TAG, "AI 模式但未配置接口地址/Key")
                    Result("", "local-noconfig", 0)
                } else {
                    tryAiLlm(url, apiKey, audioFile)
                }
            }
            else -> {
                // 关闭模式：仅占位
                Result("", "local", 0)
            }
        }
    }

    /**
     * 内置离线模型转写：使用 sherpa-onnx 本地模型，完全离线运行，无需网络。
     *
     * 模型文件打包在 assets/sherpa/ 目录，首次调用时通过 [LocalASREngine] 加载。
     * 转写在 Default 线程池执行，避免阻塞调用方。
     */
    private suspend fun tryLocalOffline(file: File): Result {
        return try {
            val (text, speakerId) = withContext(Dispatchers.Default) {
                LocalASREngine.transcribeWithSpeaker(context, file)
            }
            if (text.isEmpty()) {
                Log.w(TAG, "离线转写返回空文本")
                Result("", "local-error", 0)
            } else {
                Result(text, "local", speakerId)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "离线转写失败", e)
            Result("", "local-error", 0)
        }
    }

    /**
     * Whisper 兼容接口：multipart 上传音频文件。
     */
    private suspend fun tryWhisper(url: String, apiKey: String, file: File): Result {
        return tryWhisperInternal(url, apiKey, file, prefs.transcribeModel)
    }

    /** Whisper 兼容接口内部实现，可指定模型名 */
    private suspend fun tryWhisperInternal(url: String, apiKey: String, file: File, model: String): Result {
        return try {
            val mediaType = audioMimeType(file).toMediaType()
            val reqFile = file.asRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", file.name, reqFile)
            val modelPart = model.toRequestBody("text/plain".toMediaType())
            val language = "zh".toRequestBody("text/plain".toMediaType())

            val resp = AIClient.service.transcribeAudio(
                url, AIClient.bearer(apiKey), filePart, modelPart, language
            )
            if (resp.isSuccessful) {
                Result(resp.body()?.text ?: "", "whisper", 0)
            } else {
                Log.w(TAG, "Whisper 返回 ${resp.code()}: ${resp.errorBody()?.string()}")
                Result("", "local-error", 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Whisper 转写失败，回退本地占位", e)
            Result("", "local-error", 0)
        }
    }

    /**
     * AI 大模型转写：将音频编码为 base64，通过 chat/completions 接口发送，
     * 让支持音频输入的 LLM（如 GPT-4o）直接输出文字。
     *
     * 请求体结构（OpenAI 兼容）：
     * ```json
     * {
     *   "model": "gpt-4o",
     *   "messages": [{
     *     "role": "user",
     *     "content": [
     *       {"type": "text", "text": "请将以下音频转为文字，只输出转写内容"},
     *       {"type": "input_audio", "input_audio": {"data": "<base64>", "format": "m4a"}}
     *     ]
     *   }]
     * }
     * ```
     */
    private suspend fun tryAiLlm(url: String, apiKey: String, file: File): Result {
        return try {
            // 流式分块编码，避免一次性 file.readBytes() 把整个音频读入内存导致 OOM
            val audioBase64 = encodeFileToBase64(file)
            val model = prefs.aiModel.ifBlank { "gpt-4o" }

            // 构建多模态 content：文本指令 + base64 音频
            val contentArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "请将以下音频转为文字，只输出转写内容，不要添加额外说明。")
                })
                add(JsonObject().apply {
                    addProperty("type", "input_audio")
                    add("input_audio", JsonObject().apply {
                        addProperty("data", audioBase64)
                        addProperty("format", audioFormat(file))
                    })
                })
            }

            // 直接构建 JSON 请求体（多模态 content 为数组，非字符串）
            val requestBodyJson = JsonObject().apply {
                addProperty("model", model)
                add("messages", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", contentArray)
                    })
                })
                addProperty("temperature", 0.0)
            }

            val mediaType = "application/json".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val resp = AIClient.service.rawPost(url, AIClient.bearer(apiKey), requestBody)
            if (resp.isSuccessful) {
                // 解析 chat completion 响应
                val text = resp.body()?.string() ?: ""
                val parsed = parseChatResponse(text)
                Result(parsed, "ai-llm", 0)
            } else {
                Log.w(TAG, "AI LLM 返回 ${resp.code()}: ${resp.errorBody()?.string()}")
                Result("", "local-error", 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI LLM 转写失败，回退本地占位", e)
            Result("", "local-error", 0)
        }
    }

    /**
     * 流式 Base64 编码：分块读取文件并编码，避免一次性 file.readBytes() + Base64
     * 把整个文件（可能数十 MB）同时驻留内存导致 OOM。
     *
     * 关键点：Base64 分块拼接时，每块的字节长度必须是 3 的倍数，否则中间块会被
     * 错误地补上 '=' 填充，导致拼接结果与整体编码不一致。这里 buffer 取 3072 (3*1024)，
     * 并在读取循环中尽量填满 buffer，保证除最后一块外都是 3 的倍数；最后一块允许
     * 不足 3 的倍数，其编码末尾的 '=' 填充正好就是整体编码的尾部，结果正确。
     */
    private fun encodeFileToBase64(file: File): String {
        val sb = StringBuilder()
        val bufferSize = 3072 // 3KB = 3 * 1024，保证 3 字节对齐
        val buffer = ByteArray(bufferSize)
        file.inputStream().use { input ->
            while (true) {
                var totalRead = 0
                // 尽量填满 buffer：InputStream.read 不保证一次读满，需循环读取
                while (totalRead < bufferSize) {
                    val read = input.read(buffer, totalRead, bufferSize - totalRead)
                    if (read <= 0) break
                    totalRead += read
                }
                if (totalRead == 0) break // 文件读完
                val chunk = if (totalRead == bufferSize) buffer else buffer.copyOf(totalRead)
                sb.append(Base64.encodeToString(chunk, Base64.NO_WRAP))
                if (totalRead < bufferSize) break // 已到文件末尾
            }
        }
        return sb.toString()
    }

    /** 从 chat completion 响应中提取文字。content 可能是字符串或数组格式。 */
    private fun parseChatResponse(json: String): String {
        return try {
            val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
            val content = obj.getAsJsonArray("choices")?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content") ?: return ""
            // 部分 OpenAI 兼容 API 返回 content 为数组格式：
            // [{"type":"text","text":"..."}]，需逐项提取 text 字段拼接。
            when {
                content.isJsonPrimitive -> content.asString
                content.isJsonArray -> {
                    content.asJsonArray.joinToString("") { elem ->
                        elem?.asJsonObject?.get("text")?.asString ?: ""
                    }
                }
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 AI 响应失败", e)
            ""
        }
    }

    data class Result(val text: String, val source: String, val speakerId: Int = 0)

    /**
     * 根据文件扩展名返回音频 MIME 类型。
     * 录音格式为 WAV，但也支持导入的 m4a/aac/mp3 等格式。
     */
    private fun audioMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    /**
     * 根据文件扩展名返回 OpenAI 兼容的音频格式标识。
     * 用于 AI 大模型多模态转写时的 format 字段。
     */
    private fun audioFormat(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "wav" -> "wav"
            "m4a" -> "m4a"
            "aac" -> "aac"
            "mp3" -> "mp3"
            "flac" -> "flac"
            "ogg" -> "ogg"
            else -> "mp3"
        }
    }

    companion object { private const val TAG = "TranscriptionProvider" }
}
