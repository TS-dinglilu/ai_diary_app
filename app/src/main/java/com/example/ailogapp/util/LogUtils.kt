package com.example.ailogapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * 日志工具类：将日志写入本地 txt 文件，方便调试和问题排查。
 *
 * 功能：
 * 1. 支持不同级别日志（DEBUG、INFO、WARN、ERROR）
 * 2. 按天生成日志文件，自动清理过期日志
 * 3. 线程安全，支持多线程写入
 * 4. 提供导出功能
 * 5. 记录线程信息、调用位置等详细信息
 */
object LogUtils {

    private const val TAG = "LogUtils"
    private const val DIR_LOGS = "logs"
    private const val MAX_LOG_DAYS = 30  // 最多保留30天日志
    private val lock = ReentrantLock()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 是否启用详细日志（包含调用位置和线程信息） */
    var enableDetailedLog = true

    /** 获取日志目录 */
    fun logsDir(context: Context): File =
        File(context.filesDir, DIR_LOGS).apply { if (!exists()) mkdirs() }

    /** 获取今天的日志文件 */
    fun todayLogFile(context: Context): File =
        File(logsDir(context), "log_${fileNameFormat.format(Date())}.txt")

    /**
     * 记录调试日志
     */
    fun d(context: Context, tag: String, message: String) {
        writeLog(context, "DEBUG", tag, message)
        Log.d(tag, message)
    }

    /**
     * 记录信息日志
     */
    fun i(context: Context, tag: String, message: String) {
        writeLog(context, "INFO", tag, message)
        Log.i(tag, message)
    }

    /**
     * 记录警告日志
     */
    fun w(context: Context, tag: String, message: String) {
        writeLog(context, "WARN", tag, message)
        Log.w(tag, message)
    }

    /**
     * 记录警告日志（带异常）
     */
    fun w(context: Context, tag: String, message: String, throwable: Throwable?) {
        val fullMsg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeLog(context, "WARN", tag, fullMsg)
        Log.w(tag, message, throwable)
    }

    /**
     * 记录错误日志
     */
    fun e(context: Context, tag: String, message: String) {
        writeLog(context, "ERROR", tag, message)
        Log.e(tag, message)
    }

    /**
     * 记录错误日志（带异常）
     */
    fun e(context: Context, tag: String, message: String, throwable: Throwable?) {
        val fullMsg = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeLog(context, "ERROR", tag, fullMsg)
        Log.e(tag, message, throwable)
    }

    /**
     * 写入日志文件
     */
    private fun writeLog(context: Context, level: String, tag: String, message: String) {
        lock.lock()
        try {
            val logFile = todayLogFile(context)
            val timeStr = dateFormat.format(Date())

            val logBuilder = StringBuilder()
            logBuilder.append("[$timeStr] [$level] [$tag]")

            // 详细日志模式：添加线程信息和调用位置
            if (enableDetailedLog) {
                val threadName = Thread.currentThread().name
                logBuilder.append(" [Thread:$threadName]")

                // 获取调用位置（跳过本类的调用栈）
                val stackTrace = Thread.currentThread().stackTrace
                for (i in stackTrace.indices) {
                    val element = stackTrace[i]
                    if (element.className == LogUtils::class.java.name) {
                        // 找到 LogUtils 的调用，再往后找实际的调用者
                        if (i + 3 < stackTrace.size) {
                            val caller = stackTrace[i + 3]
                            val className = caller.className.substringAfterLast('.')
                            logBuilder.append(" [$className.${caller.methodName}:${caller.lineNumber}]")
                        }
                        break
                    }
                }
            }

            logBuilder.append(" $message\n")

            FileWriter(logFile, true).use { writer ->
                writer.write(logBuilder.toString())
            }

            // 清理过期日志
            cleanOldLogs(context)
        } catch (e: IOException) {
            Log.e(TAG, "写入日志失败: ${e.message}", e)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 清理过期日志文件
     */
    private fun cleanOldLogs(context: Context) {
        try {
            val dir = logsDir(context)
            val files = dir.listFiles() ?: return
            val cutoffTime = System.currentTimeMillis() - MAX_LOG_DAYS * 24 * 60 * 60 * 1000L

            for (file in files) {
                if (file.lastModified() < cutoffTime && file.name.startsWith("log_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理过期日志失败: ${e.message}")
        }
    }

    /**
     * 读取今天的日志内容
     */
    fun readTodayLog(context: Context): String {
        return readLogFile(todayLogFile(context))
    }

    /**
     * 读取指定日志文件内容
     */
    fun readLogFile(file: File): String {
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 获取所有日志文件列表
     */
    fun getAllLogFiles(context: Context): List<File> {
        val dir = logsDir(context)
        return dir.listFiles()?.filter { it.name.startsWith("log_") }?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * 导出所有日志到一个文件
     */
    fun exportAllLogs(context: Context, outputFile: File): Boolean {
        lock.lock()
        return try {
            val logFiles = getAllLogFiles(context)
            if (logFiles.isEmpty()) return false

            FileWriter(outputFile).use { writer ->
                writer.write("===== AI 日记 运行日志 =====\n")
                writer.write("导出时间: ${dateFormat.format(Date())}\n")
                writer.write("日志文件数量: ${logFiles.size}\n\n")

                for (file in logFiles) {
                    writer.write("\n===== ${file.name} =====\n")
                    writer.write(readLogFile(file))
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "导出日志失败: ${e.message}", e)
            false
        } finally {
            lock.unlock()
        }
    }

    /**
     * 清空所有日志
     */
    fun clearAllLogs(context: Context) {
        lock.lock()
        try {
            val dir = logsDir(context)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.startsWith("log_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清空日志失败: ${e.message}")
        } finally {
            lock.unlock()
        }
    }

    /**
     * 获取日志总大小（字节）
     */
    fun getTotalLogSize(context: Context): Long {
        val dir = logsDir(context)
        return dir.listFiles()?.filter { it.name.startsWith("log_") }?.sumOf { it.length() } ?: 0L
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024))
        }
    }
}
