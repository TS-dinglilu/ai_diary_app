package com.example.ailogapp.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * LLM 模型下载管理器。
 *
 * 负责下载 Gemma-2 2B int8 量化模型到 app 的 filesDir 目录。
 * 支持断点续传和进度回调。
 *
 * 下载链接来自用户在设置中填写的 URL（ModelScope / GitHub Release 等）。
 */
object LLMModelDownloader {

    private const val TAG = "LLMModelDownloader"
    private const val MODEL_DIR = "llm"
    private const val MODEL_FILE = "gemma-2b-it.bin"

    /** 模型文件路径 */
    fun modelPath(context: Context): String {
        return File(context.filesDir, "$MODEL_DIR/$MODEL_FILE").absolutePath
    }

    /** 模型文件是否存在且完整 */
    fun isModelAvailable(context: Context): Boolean {
        val file = File(modelPath(context))
        return file.exists() && file.length() > 100_000_000
    }

    /** 获取模型文件大小（MB），实时读取文件实际大小 */
    fun modelSizeMB(context: Context): Long {
        val file = File(modelPath(context))
        return if (file.exists()) file.length() / 1_048_576 else 0
    }

    /** 已下载的临时文件大小（MB），用于显示断点续传进度。实时读取文件实际大小 */
    fun tempSizeMB(context: Context): Long {
        val file = tempFile(context)
        return if (file.exists()) file.length() / 1_048_576 else 0
    }

    /** 临时下载文件 */
    private fun tempFile(context: Context): File {
        return File(context.filesDir, "$MODEL_DIR/$MODEL_FILE.tmp")
    }

    /**
     * 下载模型，返回进度 Flow（0-100）。
     * @param downloadUrl 用户在设置中填写的下载链接
     */
    fun download(context: Context, downloadUrl: String): Flow<DownloadState> = flow {
        if (downloadUrl.isBlank()) {
            emit(DownloadState.Error("请先填写模型下载链接"))
            return@flow
        }

        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) modelDir.mkdirs()

        val targetFile = File(modelPath(context))
        val tmpFile = tempFile(context)

        // 如果临时文件不存在，从头开始
        var downloadedBytes = if (tmpFile.exists()) tmpFile.length() else 0L

        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            emit(DownloadState.Downloading(if (downloadedBytes > 0) (downloadedBytes * 100 / 1_800_000_000).toInt() else 0))

            val requestBuilder = Request.Builder().url(downloadUrl)
            // 断点续传
            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                Log.w(TAG, "下载失败 $downloadUrl: ${response.code}")
                emit(DownloadState.Error("HTTP ${response.code}"))
                response.close()
                return@flow
            }

            // 断点续传时若服务器忽略 Range 头返回 200（完整内容），需重置 downloadedBytes，
            // 否则 append 模式会将完整内容追加到已有部分文件后，导致文件损坏。
            if (downloadedBytes > 0 && response.code == 200) {
                Log.w(TAG, "服务器忽略 Range 请求返回完整内容，重新从头下载")
                downloadedBytes = 0
            }

            val totalSize = response.body?.contentLength() ?: -1
            val totalWithDownloaded = if (totalSize > 0) totalSize + downloadedBytes else 1_800_000_000L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(tmpFile, downloadedBytes > 0).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastProgress = -1

                    while (true) {
                        bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = ((downloadedBytes * 100) / totalWithDownloaded).toInt()
                            .coerceIn(0, 100)
                        if (progress != lastProgress && progress % 5 == 0) {
                            emit(DownloadState.Downloading(progress))
                            lastProgress = progress
                        }
                    }
                }
            }

            // 下载完成，重命名。检查 renameTo 返回值，失败时回退到复制。
            if (tmpFile.exists() && tmpFile.length() > 100_000_000) {
                if (tmpFile.renameTo(targetFile)) {
                    emit(DownloadState.Success(targetFile.absolutePath))
                    return@flow
                } else {
                    Log.w(TAG, "renameTo 失败，尝试复制")
                    try {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        tmpFile.delete()
                        emit(DownloadState.Success(targetFile.absolutePath))
                        return@flow
                    } catch (e: Exception) {
                        emit(DownloadState.Error("模型文件保存失败: ${e.message}"))
                        return@flow
                    }
                }
            } else {
                emit(DownloadState.Error("下载文件不完整"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载异常 $downloadUrl: ${e.message}")
            emit(DownloadState.Error(e.message ?: "下载失败"))
        }
    }.flowOn(Dispatchers.IO)

    /** 删除模型文件 */
    fun deleteModel(context: Context) {
        File(modelPath(context)).delete()
        tempFile(context).delete()
        LocalLLMEngine.release()
    }

    /** 下载状态 */
    sealed class DownloadState {
        data class Downloading(val progress: Int) : DownloadState()
        data class Success(val path: String) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
