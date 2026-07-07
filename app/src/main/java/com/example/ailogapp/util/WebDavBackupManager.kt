package com.example.ailogapp.util

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 坚果云 WebDAV 云备份管理器。
 *
 * 备份内容：数据库（含转写、AI分析、笔记、聊天）+ 转写文本文件 + 设置。
 * 不备份音频文件和模型文件（体积过大）。
 *
 * 备份路径结构：
 * - ai_diary_app_backup/database/ailog.db (+ .db-wal, .db-shm)
 * - ai_diary_app_backup/database/prefs.json
 * - ai_diary_app_backup/transcripts/xxx.txt
 */
object WebDavBackupManager {

    private const val TAG = "WebDavBackupManager"
    private const val BACKUP_ROOT = "ai_diary_app_backup"
    private const val DIR_DATABASE = "database"
    private const val DIR_TRANSCRIPTS = "transcripts"
    private const val DB_FILE_NAME = "ailog.db"
    private const val PREFS_FILE_NAME = "prefs.json"

    private val MEDIA_TYPE_OCTET = "application/octet-stream".toMediaType()
    private val MEDIA_TYPE_XML = "application/xml; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    sealed class BackupState {
        data class BackingUp(val progress: Int) : BackupState()
        data class Success(val summary: String) : BackupState()
        data class Error(val message: String) : BackupState()
    }

    sealed class RestoreState {
        data class Restoring(val progress: Int) : RestoreState()
        data class Success(val summary: String) : RestoreState()
        data class Error(val message: String) : RestoreState()
    }

    private enum class UploadResult { Uploaded, Skipped }

    // ==================== 备份 ====================

    fun backup(context: Context): Flow<BackupState> = flow {
        val prefs = PrefsManager(context)
        val baseUrl = prefs.webdavUrl
        val email = prefs.webdavEmail
        val password = prefs.webdavPassword

        if (baseUrl.isBlank() || email.isBlank() || password.isBlank()) {
            emit(BackupState.Error("请先填写 WebDAV 服务器地址、邮箱和密钥"))
            return@flow
        }

        val auth = buildBasicAuth(email, password)

        // WAL checkpoint
        walCheckpoint(context)

        // 收集待备份文件
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        val walFile = File(dbFile.parentFile, "$DB_FILE_NAME-wal")
        val shmFile = File(dbFile.parentFile, "$DB_FILE_NAME-shm")
        val transcriptsDir = File(context.filesDir, "transcripts")
        val txtFiles = transcriptsDir.listFiles { f ->
            f.isFile && f.extension.equals("txt", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

        // 导出设置为 JSON
        val prefsJsonFile = File(context.cacheDir, PREFS_FILE_NAME)
        exportPrefsToJson(context, prefsJsonFile)

        // 收集所有待备份文件
        val dbRelatedFiles = mutableListOf<File>()
        if (dbFile.exists() && dbFile.length() > 0) {
            dbRelatedFiles.add(dbFile)
            if (walFile.exists() && walFile.length() > 0) dbRelatedFiles.add(walFile)
            if (shmFile.exists() && shmFile.length() > 0) dbRelatedFiles.add(shmFile)
        }
        val totalFiles = dbRelatedFiles.size + 1 + txtFiles.size  // +1 for prefs.json

        if (totalFiles == 0) {
            emit(BackupState.Error("没有需要备份的文件"))
            return@flow
        }

        LogUtils.i(context, TAG, "开始云端备份：数据库=${dbRelatedFiles.size}文件，转写=${txtFiles.size}个，设置=1，总计=$totalFiles")
        emit(BackupState.BackingUp(0))

        try {
            ensureDir(baseUrl, auth, BACKUP_ROOT, context)
            ensureDir(baseUrl, auth, "$BACKUP_ROOT/$DIR_DATABASE", context)
            if (txtFiles.isNotEmpty()) {
                ensureDir(baseUrl, auth, "$BACKUP_ROOT/$DIR_TRANSCRIPTS", context)
            }

            var done = 0
            var uploadedCount = 0
            var skippedCount = 0
            var uploadedBytes = 0L

            // 1. 数据库文件
            for (dbRelatedFile in dbRelatedFiles) {
                val remotePath = "$BACKUP_ROOT/$DIR_DATABASE/${dbRelatedFile.name}"
                LogUtils.i(context, TAG, "备份: ${dbRelatedFile.name}（${LogUtils.formatFileSize(dbRelatedFile.length())}）")
                when (uploadFile(baseUrl, auth, remotePath, dbRelatedFile, context)) {
                    UploadResult.Uploaded -> { uploadedCount++; uploadedBytes += dbRelatedFile.length() }
                    UploadResult.Skipped -> skippedCount++
                }
                done++
                emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))
            }

            // 2. 设置 JSON
            val prefsRemotePath = "$BACKUP_ROOT/$DIR_DATABASE/$PREFS_FILE_NAME"
            LogUtils.i(context, TAG, "备份: $PREFS_FILE_NAME（${LogUtils.formatFileSize(prefsJsonFile.length())}）")
            when (uploadFile(baseUrl, auth, prefsRemotePath, prefsJsonFile, context)) {
                UploadResult.Uploaded -> { uploadedCount++; uploadedBytes += prefsJsonFile.length() }
                UploadResult.Skipped -> skippedCount++
            }
            done++
            emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))

            // 3. 转写文本
            for (txt in txtFiles) {
                val remotePath = "$BACKUP_ROOT/$DIR_TRANSCRIPTS/${txt.name}"
                when (uploadFile(baseUrl, auth, remotePath, txt, context)) {
                    UploadResult.Uploaded -> { uploadedCount++; uploadedBytes += txt.length() }
                    UploadResult.Skipped -> skippedCount++
                }
                done++
                emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))
            }

            prefsJsonFile.delete()
            val summary = buildString {
                append("备份完成：共 $totalFiles 个文件，上传 $uploadedCount 个")
                if (skippedCount > 0) append("，跳过 $skippedCount 个")
                append("，总量 ${LogUtils.formatFileSize(uploadedBytes)}")
            }
            LogUtils.i(context, TAG, summary)
            emit(BackupState.Success(summary))
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "备份失败: ${e.message}", e)
            emit(BackupState.Error(e.message ?: "备份失败"))
        }
    }.flowOn(Dispatchers.IO)

    // ==================== 恢复 ====================

    fun restore(context: Context): Flow<RestoreState> = flow {
        val prefs = PrefsManager(context)
        val baseUrl = prefs.webdavUrl
        val email = prefs.webdavEmail
        val password = prefs.webdavPassword

        if (baseUrl.isBlank() || email.isBlank() || password.isBlank()) {
            emit(RestoreState.Error("请先填写 WebDAV 服务器地址、邮箱和密钥"))
            return@flow
        }

        val auth = buildBasicAuth(email, password)

        // 待下载文件列表
        val dbFiles = listOf("$DB_FILE_NAME", "$DB_FILE_NAME-wal", "$DB_FILE_NAME-shm", PREFS_FILE_NAME)
        val totalSteps = dbFiles.size + 1  // +1 for transcripts
        var done = 0
        var restoredCount = 0

        LogUtils.i(context, TAG, "开始从云端恢复")
        emit(RestoreState.Restoring(0))

        val app = context.applicationContext as com.example.ailogapp.App
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        val dbParent = dbFile.parentFile
        if (dbParent == null) {
            emit(RestoreState.Error("无法获取数据库目录"))
            return@flow
        }
        // 临时数据库备份目录，用于在替换失败时回滚数据库文件
        val dbBackupDir = File(context.cacheDir, "restore_db_backup").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var dbClosed = false

        try {
            // 1. 下载文件到临时目录
            val tempDir = File(context.cacheDir, "restore_temp").apply { mkdirs() }

            for (fileName in dbFiles) {
                val remotePath = "$BACKUP_ROOT/$DIR_DATABASE/$fileName"
                val localFile = File(tempDir, fileName)
                val downloaded = downloadFile(baseUrl, auth, remotePath, localFile, context)
                if (downloaded && localFile.exists() && localFile.length() > 0) {
                    restoredCount++
                    LogUtils.i(context, TAG, "已下载: $fileName（${LogUtils.formatFileSize(localFile.length())}）")
                }
                done++
                emit(RestoreState.Restoring((done * 100 / totalSteps).coerceIn(0, 100)))
            }

            // 2. 下载转写文件
            val transcriptFiles = listFiles(baseUrl, auth, "$BACKUP_ROOT/$DIR_TRANSCRIPTS", context)
            val transcriptsDir = File(context.filesDir, "transcripts").apply { mkdirs() }

            for (tfName in transcriptFiles) {
                val remotePath = "$BACKUP_ROOT/$DIR_TRANSCRIPTS/$tfName"
                val localFile = File(transcriptsDir, tfName)
                if (downloadFile(baseUrl, auth, remotePath, localFile, context)) {
                    restoredCount++
                }
            }
            done++
            emit(RestoreState.Restoring((done * 100 / totalSteps).coerceIn(0, 100)))

            // 3. 关闭数据库前，先将现有数据库文件备份到临时目录，便于替换失败时回滚
            for (fileName in dbFiles) {
                if (fileName == PREFS_FILE_NAME) continue
                val src = File(dbParent, fileName)
                if (src.exists()) {
                    src.copyTo(File(dbBackupDir, fileName), overwrite = true)
                }
            }
            LogUtils.i(context, TAG, "已备份现有数据库文件到临时目录: ${dbBackupDir.absolutePath}")

            // 关闭数据库，替换文件
            try {
                app.database.close()
            } catch (e: Exception) {
                LogUtils.w(context, TAG, "关闭数据库异常（继续恢复）: ${e.message}")
            }
            dbClosed = true

            // 替换数据库文件
            for (fileName in dbFiles) {
                if (fileName == PREFS_FILE_NAME) continue
                val tempFile = File(tempDir, fileName)
                val targetFile = File(dbParent, fileName)
                if (tempFile.exists() && tempFile.length() > 0) {
                    targetFile.parentFile?.mkdirs()
                    tempFile.copyTo(targetFile, overwrite = true)
                } else {
                    targetFile.delete()
                }
            }

            // 4. 恢复设置
            val prefsJsonFile = File(tempDir, PREFS_FILE_NAME)
            if (prefsJsonFile.exists()) {
                restorePrefsFromJson(context, prefsJsonFile)
            }

            // 5. 重新打开数据库
            app.database.openHelper.writableDatabase

            // 替换成功，清理临时目录与数据库备份目录
            dbBackupDir.deleteRecursively()
            tempDir.deleteRecursively()

            val summary = "恢复完成：共恢复 $restoredCount 个文件"
            LogUtils.i(context, TAG, summary)
            emit(RestoreState.Success(summary))
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "恢复失败: ${e.message}", e)
            // 若数据库已关闭且替换可能失败，从临时备份恢复原数据库文件
            if (dbClosed) {
                try {
                    // 删除可能已部分写入的数据库文件
                    for (fileName in dbFiles) {
                        if (fileName == PREFS_FILE_NAME) continue
                        File(dbParent, fileName).delete()
                    }
                    // 从临时备份目录恢复原文件
                    val backupFiles = dbBackupDir.listFiles() ?: emptyArray()
                    for (bf in backupFiles) {
                        bf.copyTo(File(dbParent, bf.name), overwrite = true)
                    }
                    LogUtils.i(context, TAG, "已从临时备份目录恢复原数据库文件")
                    // 重新打开数据库
                    app.database.openHelper.writableDatabase
                    LogUtils.i(context, TAG, "回滚后数据库已重新打开")
                } catch (re: Exception) {
                    LogUtils.e(context, TAG, "回滚数据库文件失败: ${re.message}", re)
                }
            }
            // 清理临时数据库备份目录
            dbBackupDir.deleteRecursively()
            emit(RestoreState.Error(e.message ?: "恢复失败"))
        }
    }.flowOn(Dispatchers.IO)

    // ==================== 内部方法 ====================

    private fun walCheckpoint(context: Context) {
        try {
            val app = context.applicationContext as com.example.ailogapp.App
            val db = app.database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            LogUtils.i(context, TAG, "数据库 WAL checkpoint 完成")
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "WAL checkpoint 失败: ${e.message}")
        }
    }

    private fun exportPrefsToJson(context: Context, outputFile: File) {
        val sp = context.getSharedPreferences("ailog_prefs", android.content.Context.MODE_PRIVATE)
        val json = JSONObject()
        for ((key, value) in sp.all) {
            val entry = JSONObject()
            entry.put("type", when (value) {
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is String -> "String"
                is Set<*> -> "StringSet"
                else -> "String"
            })
            entry.put("value", value)
            json.put(key, entry)
        }
        outputFile.writeText(json.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun restorePrefsFromJson(context: Context, jsonFile: File) {
        val sp = context.getSharedPreferences("ailog_prefs", android.content.Context.MODE_PRIVATE)
        val json = JSONObject(jsonFile.readText())
        // 合并 clear 与逐项 put 到同一个 editor，并使用 commit() 同步写入，
        // 确保恢复后立即读取 prefs 能拿到新值
        val editor = sp.edit().clear()
        for (key in json.keys()) {
            val entry = json.getJSONObject(key)
            val type = entry.getString("type")
            when (type) {
                "Boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                "Int" -> editor.putInt(key, entry.getInt("value"))
                "Long" -> editor.putLong(key, entry.getLong("value"))
                "Float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                "String" -> editor.putString(key, entry.getString("value"))
                "StringSet" -> {
                    val arr = entry.getJSONArray("value")
                    val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.commit()
        LogUtils.i(context, TAG, "设置已从 JSON 恢复")
    }

    private fun buildBasicAuth(email: String, password: String): String {
        return "Basic " + Base64.encodeToString("$email:$password".toByteArray(), Base64.NO_WRAP)
    }

    private fun uploadFile(baseUrl: String, auth: String, remotePath: String, file: File, context: Context): UploadResult {
        val fullUrl = buildUrl(baseUrl, remotePath)
        val remoteSize = propfindSize(fullUrl, auth, context)
        if (remoteSize != null && remoteSize == file.length()) {
            return UploadResult.Skipped
        }
        if (!putFile(fullUrl, auth, file, context)) {
            throw IOException("上传失败: ${file.name}")
        }
        return UploadResult.Uploaded
    }

    private fun ensureDir(baseUrl: String, auth: String, dirPath: String, context: Context) {
        val segments = dirPath.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else "$current/$seg"
            val code = mkcol(buildUrl(baseUrl, current), auth)
            if (code != 201 && code != 405) {
                LogUtils.w(context, TAG, "MKCOL $current 返回 $code")
            }
        }
    }

    private fun mkcol(url: String, auth: String): Int {
        val request = Request.Builder().url(url).header("Authorization", auth).method("MKCOL", null).build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun propfindSize(url: String, auth: String, context: Context): Long? {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:"><D:prop><D:getcontentlength/></D:prop></D:propfind>""".trimMargin()
        val request = Request.Builder().url(url).header("Authorization", auth)
            .header("Depth", "0").header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", body.toRequestBody(MEDIA_TYPE_XML)).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 207) return null
                val xml = response.body?.string() ?: return null
                Regex("""<[^>]*getcontentlength[^>]*>\s*(\d+)\s*<""", RegexOption.IGNORE_CASE)
                    .find(xml)?.groupValues?.get(1)?.toLongOrNull()
            }
        } catch (e: Exception) { null }
    }

    private fun putFile(url: String, auth: String, file: File, context: Context): Boolean {
        val request = Request.Builder().url(url).header("Authorization", auth)
            .header("Content-Type", "application/octet-stream")
            .put(file.asRequestBody(MEDIA_TYPE_OCTET)).build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "PUT 异常: ${e.message}", e)
            false
        }
    }

    private fun downloadFile(baseUrl: String, auth: String, remotePath: String, localFile: File, context: Context): Boolean {
        val url = buildUrl(baseUrl, remotePath)
        val request = Request.Builder().url(url).header("Authorization", auth).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtils.w(context, TAG, "下载失败 $remotePath: HTTP ${response.code}")
                    return false
                }
                localFile.parentFile?.mkdirs()
                response.body?.byteStream()?.use { input ->
                    localFile.outputStream().use { input.copyTo(it) }
                }
                true
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "下载异常: ${e.message}", e)
            false
        }
    }

    /** PROPFIND 列出目录下的文件名 */
    private fun listFiles(baseUrl: String, auth: String, dirPath: String, context: Context): List<String> {
        val url = buildUrl(baseUrl, dirPath)
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:"><D:prop><D:displayname/></D:prop></D:propfind>""".trimMargin()
        val request = Request.Builder().url(url).header("Authorization", auth)
            .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", body.toRequestBody(MEDIA_TYPE_XML)).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 207) return emptyList()
                val xml = response.body?.string() ?: return emptyList()
                // 提取 href 中的文件名
                val hrefs = Regex("""<[^>]*href[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
                    .findAll(xml).map { it.groupValues[1] }.toList()
                // 过滤掉目录本身，只保留文件；对 URL 编码的文件名解码，
                // 避免后续 buildUrl 再次编码导致双重编码（服务器返回 404）
                hrefs.map { href ->
                    val name = href.substringAfterLast("/").substringAfterLast("%2F")
                    URLDecoder.decode(name, "UTF-8")
                }
                    .filter { it.isNotBlank() && !it.endsWith("/") }
                    .distinct()
            }
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "listFiles 异常: ${e.message}")
            emptyList()
        }
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val encoded = path.split("/").filter { it.isNotEmpty() }.joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return base + encoded
    }
}
