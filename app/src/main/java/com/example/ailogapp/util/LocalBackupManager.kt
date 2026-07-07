package com.example.ailogapp.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 本地备份和恢复管理器。
 *
 * 负责将应用数据打包为本地 ZIP 备份文件，并支持从 ZIP 恢复。
 * 与 [WebDavBackupManager] 不同，本管理器只备份轻量数据（数据库、转写文本、设置），
 * 不备份音频和模型文件，适合快速本地备份/恢复。
 *
 * 备份内容：
 * 1. 数据库文件：ailog.db 及其 WAL（.db-wal）和 SHM（.db-shm）文件
 * 2. 转写文本文件：filesDir/transcripts/ 下的 .txt 文件
 * 3. 笔记数据：已在数据库 notes 表中，随数据库一起备份
 * 4. 设置数据：导出 SharedPreferences（ailog_prefs）为 prefs.json 文件
 *
 * ZIP 内部结构：
 * - database/ailog.db
 * - database/ailog.db-wal（若存在）
 * - database/ailog.db-shm（若存在）
 * - prefs.json
 * - transcripts/<name>.txt
 *
 * 备份文件名格式：ai_diary_backup_yyyyMMdd_HHmmss.zip
 */
object LocalBackupManager {

    private const val TAG = "LocalBackupManager"
    private const val DB_FILE_NAME = "ailog.db"
    private const val PREFS_NAME = "ailog_prefs"
    private const val PREFS_JSON_NAME = "prefs.json"
    private const val DIR_DATABASE = "database"
    private const val DIR_TRANSCRIPTS = "transcripts"

    /** 备份状态 */
    sealed class BackupState {
        /** 备份中，progress 为 0-100 */
        data class BackingUp(val progress: Int) : BackupState()
        /** 备份成功，summary 为汇总信息 */
        data class Success(val summary: String) : BackupState()
        /** 备份失败，message 为错误信息 */
        data class Error(val message: String) : BackupState()
    }

    /** 恢复状态 */
    sealed class RestoreState {
        /** 恢复中，progress 为 0-100 */
        data class Restoring(val progress: Int) : RestoreState()
        /** 恢复成功，summary 为汇总信息 */
        data class Success(val summary: String) : RestoreState()
        /** 恢复失败，message 为错误信息 */
        data class Error(val message: String) : RestoreState()
    }

    /**
     * 执行本地备份，将数据打包为 ZIP 文件保存到 [targetDir]，返回进度 Flow。
     *
     * 备份流程：
     * 1. 执行 WAL checkpoint（PRAGMA wal_checkpoint(FULL)）确保数据库完整
     * 2. 导出 SharedPreferences（ailog_prefs）为 prefs.json
     * 3. 将数据库文件（.db, .db-wal, .db-shm）+ prefs.json + transcripts 目录下的 .txt 文件打包为 ZIP
     * 4. ZIP 文件名格式：ai_diary_backup_yyyyMMdd_HHmmss.zip
     *
     * @param context 应用上下文
     * @param targetDir 备份 ZIP 保存目录（如 getExternalFilesDir(null) 或 SAF 返回的目录）
     */
    fun backup(context: Context, targetDir: File): Flow<BackupState> = flow {
        // 确保备份目标目录存在
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            emit(BackupState.Error("无法创建备份目录: ${targetDir.absolutePath}"))
            return@flow
        }

        // 备份前执行 WAL checkpoint，将 WAL 日志中的数据写入主数据库文件，
        // 确保备份的 .db 文件包含最新数据（Room 默认启用 WAL 模式）
        try {
            val app = context.applicationContext as com.example.ailogapp.App
            val db = app.database.openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            LogUtils.i(context, TAG, "数据库 WAL checkpoint 完成")
        } catch (e: Exception) {
            LogUtils.w(context, TAG, "WAL checkpoint 失败（不影响备份，但数据可能不完整）: ${e.message}")
        }

        // 收集数据库相关文件（.db, .db-wal, .db-shm）
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        val walFile = File(dbFile.parentFile, "$DB_FILE_NAME-wal")
        val shmFile = File(dbFile.parentFile, "$DB_FILE_NAME-shm")
        val dbRelatedFiles = mutableListOf<File>()
        if (dbFile.exists() && dbFile.length() > 0) {
            dbRelatedFiles.add(dbFile)
            if (walFile.exists() && walFile.length() > 0) dbRelatedFiles.add(walFile)
            if (shmFile.exists() && shmFile.length() > 0) dbRelatedFiles.add(shmFile)
        }

        // 收集转写文本文件
        val transcriptsDir = File(context.filesDir, DIR_TRANSCRIPTS)
        val txtFiles = transcriptsDir.listFiles { f ->
            f.isFile && f.extension.equals("txt", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

        // 导出 SharedPreferences 为 JSON
        val prefsJson = exportPrefs(context)

        // 总文件数 = 数据库文件 + prefs.json + 转写文件（prefs.json 至少 1 个，不会除零）
        val totalFiles = dbRelatedFiles.size + 1 + txtFiles.size

        LogUtils.i(
            context, TAG,
            "开始本地备份：数据库文件=${dbRelatedFiles.size}，转写文件=${txtFiles.size}，设置=1，总计=$totalFiles 个文件"
        )
        emit(BackupState.BackingUp(0))

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(targetDir, "ai_diary_backup_$timestamp.zip")

        try {
            var totalBytes = 0L

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                var done = 0

                // 1. 备份数据库相关文件（.db, .db-wal, .db-shm）
                for (dbRelatedFile in dbRelatedFiles) {
                    val entryName = "$DIR_DATABASE/${dbRelatedFile.name}"
                    LogUtils.i(
                        context, TAG,
                        "添加数据库文件: ${dbRelatedFile.name}（${LogUtils.formatFileSize(dbRelatedFile.length())}）"
                    )
                    zos.putNextEntry(ZipEntry(entryName))
                    dbRelatedFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    totalBytes += dbRelatedFile.length()
                    done++
                    emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))
                }

                // 2. 备份设置（prefs.json）
                val prefsContent = prefsJson.toString()
                zos.putNextEntry(ZipEntry(PREFS_JSON_NAME))
                zos.write(prefsContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                totalBytes += prefsContent.toByteArray(Charsets.UTF_8).size
                done++
                emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))

                // 3. 备份转写文本文件
                for (txt in txtFiles) {
                    val entryName = "$DIR_TRANSCRIPTS/${txt.name}"
                    zos.putNextEntry(ZipEntry(entryName))
                    txt.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    totalBytes += txt.length()
                    done++
                    emit(BackupState.BackingUp((done * 100 / totalFiles).coerceIn(0, 100)))
                }
            }

            val summary = buildString {
                append("备份完成：共 $totalFiles 个文件")
                append("，数据库 ${dbRelatedFiles.size} 个")
                append("，转写 ${txtFiles.size} 个")
                append("，设置 1 个")
                append("，总大小 ${LogUtils.formatFileSize(totalBytes)}")
                append("\n保存至：${zipFile.absolutePath}")
            }
            LogUtils.i(context, TAG, summary)
            emit(BackupState.Success(summary))
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "备份失败: ${e.message}", e)
            // 删除可能已生成的不完整 ZIP 文件
            if (zipFile.exists()) zipFile.delete()
            emit(BackupState.Error(e.message ?: "备份失败"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 从本地 ZIP 备份文件恢复数据，返回进度 Flow。
     *
     * 恢复流程：
     * 1. 校验并打开用户选择的 ZIP 文件
     * 2. 关闭数据库连接（App.database.close()）
     * 3. 解压数据库文件到 context.getDatabasePath("ailog.db") 对应位置
     * 4. 解压 transcripts 到 context.filesDir/transcripts/
     * 5. 从 prefs.json 恢复 SharedPreferences
     * 6. 重新打开数据库
     *
     * @param context 应用上下文
     * @param zipFile 用户选择的备份 ZIP 文件
     */
    fun restore(context: Context, zipFile: File): Flow<RestoreState> = flow {
        if (!zipFile.exists() || !zipFile.isFile) {
            emit(RestoreState.Error("备份文件不存在: ${zipFile.absolutePath}"))
            return@flow
        }

        LogUtils.i(context, TAG, "开始从备份恢复: ${zipFile.name}（${LogUtils.formatFileSize(zipFile.length())}）")
        emit(RestoreState.Restoring(0))

        val app = context.applicationContext as com.example.ailogapp.App

        // 数据库相关文件路径（提前声明，便于在 catch 块中回滚）
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        val dbParent = dbFile.parentFile
        if (dbParent == null) {
            emit(RestoreState.Error("无法获取数据库目录"))
            return@flow
        }
        val existingWal = File(dbParent, "$DB_FILE_NAME-wal")
        val existingShm = File(dbParent, "$DB_FILE_NAME-shm")

        // 临时备份目录，用于在恢复失败时回滚数据库文件
        val backupDir = File(context.cacheDir, "restore_backup").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var dbClosed = false
        var originalsDeleted = false

        try {
            // 先打开 ZIP 校验有效性，避免对无效文件关闭数据库
            val zip = ZipFile(zipFile)

            // 关闭数据库前，先将现有数据库文件复制到临时备份目录，便于恢复失败时回滚
            for (f in listOf(dbFile, existingWal, existingShm)) {
                if (f.exists()) {
                    f.copyTo(File(backupDir, f.name), overwrite = true)
                }
            }
            LogUtils.i(context, TAG, "已备份现有数据库文件到临时目录: ${backupDir.absolutePath}")

            // 关闭数据库连接，确保可以安全覆盖数据库文件
            try {
                app.database.close()
                LogUtils.i(context, TAG, "数据库已关闭")
            } catch (e: Exception) {
                LogUtils.w(context, TAG, "关闭数据库异常（继续恢复）: ${e.message}")
            }
            dbClosed = true

            // 准备目录
            if (!dbParent.exists()) dbParent.mkdirs()
            val transcriptsDir = File(context.filesDir, DIR_TRANSCRIPTS).apply { if (!exists()) mkdirs() }

            // 删除现有数据库文件，确保恢复后无残留的旧 WAL/SHM 造成数据不一致
            dbFile.delete()
            existingWal.delete()
            existingShm.delete()
            originalsDeleted = true

            var restoredDb = 0
            var restoredTxt = 0
            var prefsRestored = false
            var prefsKeyCount = 0

            zip.use { zf ->
                val total = zf.size().coerceAtLeast(1)
                var done = 0

                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        done++
                        emit(RestoreState.Restoring((done * 100 / total).coerceIn(0, 100)))
                        continue
                    }
                    val name = entry.name
                    when {
                        // 数据库文件：解压到数据库目录
                        name.startsWith("$DIR_DATABASE/") -> {
                            val dbName = name.substringAfter("$DIR_DATABASE/")
                            // 仅允许直接文件名，防止路径穿越
                            if (dbName.isNotEmpty() && !dbName.contains("/") && !dbName.contains("..")) {
                                val target = File(dbParent, dbName)
                                zf.getInputStream(entry).use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                }
                                restoredDb++
                                LogUtils.i(
                                    context, TAG,
                                    "恢复数据库文件: $dbName（${LogUtils.formatFileSize(target.length())}）"
                                )
                            }
                        }
                        // 设置文件：从 JSON 恢复 SharedPreferences
                        name == PREFS_JSON_NAME -> {
                            val content = zf.getInputStream(entry).bufferedReader().use { it.readText() }
                            prefsKeyCount = restorePrefs(context, content)
                            prefsRestored = true
                            LogUtils.i(context, TAG, "恢复设置完成：$prefsKeyCount 项")
                        }
                        // 转写文件：解压到 transcripts 目录
                        name.startsWith("$DIR_TRANSCRIPTS/") -> {
                            val fileName = name.substringAfter("$DIR_TRANSCRIPTS/")
                            if (fileName.isNotEmpty() && !fileName.contains("/") && !fileName.contains("..")) {
                                val target = File(transcriptsDir, fileName)
                                zf.getInputStream(entry).use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                }
                                restoredTxt++
                            }
                        }
                        // 未知条目：跳过并记录
                        else -> {
                            LogUtils.w(context, TAG, "跳过未知备份项: $name")
                        }
                    }
                    done++
                    emit(RestoreState.Restoring((done * 100 / total).coerceIn(0, 100)))
                }
            }

            // 解压成功，删除临时备份目录
            backupDir.deleteRecursively()
            LogUtils.i(context, TAG, "恢复成功，已删除临时备份目录")

            // 重新打开数据库（访问 openHelper.writableDatabase 会触发重新连接）
            try {
                app.database.openHelper.writableDatabase
                LogUtils.i(context, TAG, "数据库已重新打开")
            } catch (e: Exception) {
                LogUtils.w(context, TAG, "重新打开数据库异常: ${e.message}")
            }

            val summary = buildString {
                append("恢复完成：数据库文件 $restoredDb 个")
                append("，转写文件 $restoredTxt 个")
                append("，设置 ${if (prefsRestored) "$prefsKeyCount 项" else "未包含"}")
            }
            LogUtils.i(context, TAG, summary)
            emit(RestoreState.Success(summary))
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "恢复失败: ${e.message}", e)
            // 若原数据库文件已被删除，从临时备份目录恢复原文件
            if (originalsDeleted) {
                try {
                    dbFile.delete()
                    existingWal.delete()
                    existingShm.delete()
                    val backupFiles = backupDir.listFiles() ?: emptyArray()
                    for (bf in backupFiles) {
                        bf.copyTo(File(dbParent, bf.name), overwrite = true)
                    }
                    LogUtils.i(context, TAG, "已从临时备份目录恢复原数据库文件")
                } catch (re: Exception) {
                    LogUtils.e(context, TAG, "回滚数据库文件失败: ${re.message}", re)
                }
            }
            // 确保数据库回到可用状态：若已关闭则重新打开
            if (dbClosed) {
                try {
                    app.database.openHelper.writableDatabase
                    LogUtils.i(context, TAG, "回滚后数据库已重新打开")
                } catch (re: Exception) {
                    LogUtils.w(context, TAG, "回滚后重新打开数据库异常: ${re.message}")
                }
            }
            // 清理临时备份目录
            backupDir.deleteRecursively()
            emit(RestoreState.Error(e.message ?: "恢复失败"))
        }
    }.flowOn(Dispatchers.IO)

    // ---- 内部辅助方法 ----

    /**
     * 导出 SharedPreferences（ailog_prefs）为 JSONObject。
     *
     * 遍历 `prefs.all`，将每个键值对连同类型信息封装为：
     * `{ "type": "Boolean|Int|Long|Float|String|StringSet", "value": <值> }`
     * 恢复时按 type 字段分别写入，避免类型丢失。
     */
    private fun exportPrefs(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val json = JSONObject()
        for ((key, value) in all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> {
                    entry.put("type", "Boolean"); entry.put("value", value)
                }
                is Int -> {
                    entry.put("type", "Int"); entry.put("value", value)
                }
                is Long -> {
                    entry.put("type", "Long"); entry.put("value", value)
                }
                is Float -> {
                    entry.put("type", "Float"); entry.put("value", value)
                }
                is String -> {
                    entry.put("type", "String"); entry.put("value", value)
                }
                is Set<*> -> {
                    entry.put("type", "StringSet")
                    val arr = JSONArray()
                    value.forEach { arr.put(it.toString()) }
                    entry.put("value", arr)
                }
                null -> {
                    entry.put("type", "String")
                    entry.put("value", "")
                }
                else -> {
                    // 未知类型退化为字符串，避免备份中断
                    entry.put("type", "String")
                    entry.put("value", value.toString())
                    LogUtils.w(context, TAG, "未知偏好类型 key=$key value=$value，按字符串导出")
                }
            }
            json.put(key, entry)
        }
        return json
    }

    /**
     * 从 JSON 字符串恢复 SharedPreferences（ailog_prefs）。
     *
     * 先 clear 清空现有设置，再按类型（Boolean, Int, Long, Float, String, StringSet）分别写入，
     * 最后 commit() 提交（clear 在 put 之前执行，put 的值会被保留）。
     *
     * @return 实际恢复的键值对数量
     */
    private fun restorePrefs(context: Context, content: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject(content)
        val editor = prefs.edit()
        editor.clear()
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = json.getJSONObject(key)
            val type = entry.getString("type")
            val handled = when (type) {
                "Boolean" -> {
                    editor.putBoolean(key, entry.getBoolean("value")); true
                }
                "Int" -> {
                    editor.putInt(key, entry.getInt("value")); true
                }
                "Long" -> {
                    editor.putLong(key, entry.getLong("value")); true
                }
                "Float" -> {
                    // JSON 数字统一以 double 存储，Float 需经 getDouble 转 float 还原
                    editor.putFloat(key, entry.getDouble("value").toFloat()); true
                }
                "String" -> {
                    editor.putString(key, entry.getString("value")); true
                }
                "StringSet" -> {
                    val arr = entry.getJSONArray("value")
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) {
                        set.add(arr.getString(i))
                    }
                    editor.putStringSet(key, set); true
                }
                else -> {
                    LogUtils.w(context, TAG, "未知偏好类型 key=$key type=$type，跳过")
                    false
                }
            }
            if (handled) count++
        }
        editor.commit()
        return count
    }
}
