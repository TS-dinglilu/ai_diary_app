package com.example.ailogapp.ai

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

/**
 * OpenAI 兼容接口定义。
 * - [chatCompletions]：聊天补全，用于日记/情绪分析；
 * - [transcribeAudio]：Whisper 兼容音频转文字（multipart 上传）；
 * - [rawPost]：原始 JSON POST，用于 AI 大模型音频转写（多模态 content）。
 */
interface AIApiService {

    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: ChatRequest
    ): Response<ChatResponse>

    @Multipart
    @POST
    suspend fun transcribeAudio(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody
    ): Response<TranscribeResponse>

    /** 原始 JSON POST，返回原始响应体（用于多模态音频转写） */
    @POST
    suspend fun rawPost(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: RequestBody
    ): Response<ResponseBody>
}

/* ---------- 请求 / 响应数据类 ---------- */

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("response_format") val responseFormat: Map<String, String>? = null
)

data class ChatMessage(
    val role: String,    // system / user / assistant
    val content: String
)

data class ChatResponse(
    val choices: List<Choice>?
) {
    val firstContent: String get() = choices?.firstOrNull()?.message?.content ?: ""
}

data class Choice(val message: ChatMessage?)

data class TranscribeResponse(
    val text: String?
)
