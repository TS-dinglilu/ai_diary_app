package com.example.ailogapp.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * GitHub 自动更新检查器。
 *
 * 通过 GitHub Releases API 获取最新版本，对比当前应用版本号判断是否需要更新，
 * 支持下载 APK 并通过 FileProvider 触发系统安装界面。
 *
 * 仓库地址：https://github.com/TS-dinglilu/ai_diary_app
 * Releases API：https://api.github.com/repos/TS-dinglilu/ai_diary_app/releases/latest
 *
 * 使用方式：
 * ```
 * UpdateChecker.checkForUpdate(context).collect { state ->
 *     when (state) {
 *         is UpdateChecker.UpdateState.UpdateAvailable -> { /* 提示用户 */ }
 *         is UpdateChecker.UpdateState.Downloaded -> UpdateChecker.installApk(context, state.apkPath)
 *         // ...
 *     }
 * }
 * ```
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASE_URL =
        "https://api.github.com/repos/TS-dinglilu/ai_diary_app/releases/latest"
    private const val APK_FILE_NAME = "update.apk"

    /**
     * 全局复用的 OkHttpClient 单例，避免每次检查更新/下载都新建实例。
     * readTimeout 取较大值（300s）以兼容 APK 大文件下载场景。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 检查 GitHub 最新 Release，对比版本号判断是否需要更新。
     *
     * 仅检查版本，不自动下载。用户确认后才调用 [downloadApk] 下载。
     *
     * @param context 应用上下文
     */
    fun checkForUpdate(context: Context): Flow<UpdateState> = flow {
        emit(UpdateState.Checking)

        try {
            val request = Request.Builder()
                .url(RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ai-diary-app-update-checker")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                when (response.code) {
                    403 -> {
                        LogUtils.w(context, TAG, "GitHub API 速率限制 (HTTP 403)")
                        emit(UpdateState.Error("GitHub API 请求过于频繁，请稍后再试"))
                    }
                    404 -> {
                        LogUtils.w(context, TAG, "未找到任何 Release (HTTP 404)")
                        emit(UpdateState.NoUpdate)
                    }
                    else -> {
                        LogUtils.w(context, TAG, "获取 Release 失败: HTTP ${response.code}")
                        emit(UpdateState.Error("获取版本信息失败: HTTP ${response.code}"))
                    }
                }
                response.close()
                return@flow
            }

            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) {
                emit(UpdateState.Error("Release 响应为空"))
                return@flow
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            if (tagName.isBlank()) {
                emit(UpdateState.Error("无法解析版本号 tag_name"))
                return@flow
            }

            val latestVersion = tagName.removePrefix("v").trim()
            val currentVersion = getCurrentVersion(context)

            LogUtils.i(
                context, TAG,
                "当前版本: $currentVersion, 最新版本: $latestVersion (tag: $tagName)"
            )

            if (!isNewerVersion(currentVersion, latestVersion)) {
                emit(UpdateState.NoUpdate)
                return@flow
            }

            // 解析 APK 下载链接：优先取 assets 中以 .apk 结尾的资源
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        apkUrl = url
                        break
                    }
                }
            }

            val description = json.optString("body", "").trim().ifBlank { "暂无更新说明" }

            emit(UpdateState.UpdateAvailable(latestVersion, description, apkUrl ?: ""))
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "检查更新异常: ${e.message}", e)
            emit(UpdateState.Error(e.message ?: "检查更新失败"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 下载 APK 到 context.getExternalFilesDir(null)/update.apk。
     *
     * 在用户确认更新后调用，不主动触发。
     *
     * @param context 应用上下文
     * @param apkUrl APK 下载地址（来自 [UpdateState.UpdateAvailable.apkUrl]）
     */
    fun downloadApk(context: Context, apkUrl: String): Flow<UpdateState> = flow {
        val targetFile = File(context.getExternalFilesDir(null), APK_FILE_NAME)
        if (targetFile.parentFile?.exists() != true) {
            targetFile.parentFile?.mkdirs()
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }

        try {
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "ai-diary-app-update-checker")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(UpdateState.Error("下载失败: HTTP ${response.code}"))
                response.close()
                return@flow
            }

            val totalSize = response.body?.contentLength() ?: -1L
            emit(UpdateState.Downloading(0))

            response.body?.byteStream()?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloadedBytes = 0L
                    var lastProgress = -1

                    while (true) {
                        bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalSize > 0) {
                            val progress =
                                ((downloadedBytes * 100) / totalSize).toInt().coerceIn(0, 100)
                            if (progress != lastProgress && progress % 5 == 0) {
                                emit(UpdateState.Downloading(progress))
                                lastProgress = progress
                            }
                        }
                    }
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                LogUtils.i(
                    context, TAG,
                    "APK 下载完成: ${targetFile.absolutePath} (${targetFile.length()} bytes)"
                )
                emit(UpdateState.Downloaded(targetFile.absolutePath))
            } else {
                emit(UpdateState.Error("下载文件不完整"))
            }
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "APK 下载异常: ${e.message}", e)
            emit(UpdateState.Error(e.message ?: "下载失败"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 通过 FileProvider 触发系统安装界面。
     *
     * 需要 Manifest 已声明 REQUEST_INSTALL_PACKAGES 权限，且 FileProvider 已映射
     * external-files-path（见 res/xml/file_paths.xml）。
     */
    fun installApk(context: Context, apkPath: String) {
        try {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                LogUtils.w(context, TAG, "APK 文件不存在: $apkPath")
                return
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "触发安装失败: ${e.message}", e)
        }
    }

    /**
     * 动态获取当前应用版本名（来自 PackageManager，不硬编码）。
     */
    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "获取当前版本失败: ${e.message}", e)
            "1.0"
        }
    }

    /**
     * 语义化版本比较：判断 latest 是否比 current 新。
     * 支持形如 "1.0"、"1.1.0"、"1.0.3" 的版本号。
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /** 更新状态 */
    sealed class UpdateState {
        /** 正在检查更新 */
        object Checking : UpdateState()

        /** 发现新版本，apkUrl 为 GitHub Release 中的 APK 下载链接 */
        data class UpdateAvailable(val version: String, val description: String, val apkUrl: String) : UpdateState()

        /** 已是最新版本 */
        object NoUpdate : UpdateState()

        /** 正在下载，progress 0-100 */
        data class Downloading(val progress: Int) : UpdateState()

        /** 下载完成，apkPath 为 APK 文件路径 */
        data class Downloaded(val apkPath: String) : UpdateState()

        /** 出错 */
        data class Error(val message: String) : UpdateState()
    }
}
