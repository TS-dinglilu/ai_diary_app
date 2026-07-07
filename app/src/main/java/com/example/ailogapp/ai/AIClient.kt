package com.example.ailogapp.ai

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 单例。url 在请求时动态指定，故 builder 不预设 baseUrl。
 * 用一个占位 baseUrl，实际请求通过 @Url 覆盖。
 */
object AIClient {

    val service: AIApiService by lazy { build().create(AIApiService::class.java) }

    private fun build(): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            // Release 包关闭日志，避免 API Key 泄漏；Debug 包仅记录 BASIC 级别
            level = if (com.example.ailogapp.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val gson = GsonBuilder().setLenient().create()
        return Retrofit.Builder()
            .baseUrl("https://placeholder.invalid/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun bearer(key: String): String = if (key.startsWith("Bearer ")) key else "Bearer $key"
}
