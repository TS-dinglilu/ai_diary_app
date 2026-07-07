package com.example.ailogapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 文件与日期工具：管理录音/转写目录、命名、时长格式化。
 */
object FileUtils {

    const val DIR_RECORDINGS = "recordings"
    const val DIR_TRANSCRIPTS = "transcripts"

    fun recordingsDir(context: Context): File =
        File(context.filesDir, DIR_RECORDINGS).apply { if (!exists()) mkdirs() }

    fun transcriptsDir(context: Context): File =
        File(context.filesDir, DIR_TRANSCRIPTS).apply { if (!exists()) mkdirs() }

    /** 生成形如 rec_20260101_083000.wav 的录音文件 */
    fun newRecordingFile(context: Context, startTime: Long): File {
        // SimpleDateFormat 非线程安全，不能作为单例共享（newRecordingFile 可能从录音线程和主线程同时调用）。
        // 方法内局部创建，调用频率低（每段录音一次），开销可忽略。
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val name = "rec_${fmt.format(Date(startTime))}.wav"
        return File(recordingsDir(context), name)
    }

    /**
     * 修复 WAV 文件头部。
     *
     * 进程被杀时，WAV 文件的 DataSize 可能不正确（写入的是 0xFFFFFFFF）。
     * 此方法根据实际文件大小更新 RIFF size 和 data size，
     * 使文件头部与实际数据一致。
     */
    fun fixWavHeader(file: File) {
        try {
            val raf = java.io.RandomAccessFile(file, "rw")
            raf.use { r ->
                val fileSize = file.length()
                val dataSize = fileSize - 44  // 44 = WAV header size
                if (dataSize <= 0) return

                // 修正 RIFF size (offset 4, 4 bytes, little-endian)
                // Long.toInt() 截断到低 32 位，与 WAV 32 位字段一致
                val riffSize = (fileSize - 8).toInt()
                r.seek(4)
                r.write(riffSize and 0xFF)
                r.write((riffSize shr 8) and 0xFF)
                r.write((riffSize shr 16) and 0xFF)
                r.write((riffSize shr 24) and 0xFF)

                // 修正 data size (offset 40, 4 bytes, little-endian)
                val dataSz = dataSize.toInt()
                r.seek(40)
                r.write(dataSz and 0xFF)
                r.write((dataSz shr 8) and 0xFF)
                r.write((dataSz shr 16) and 0xFF)
                r.write((dataSz shr 24) and 0xFF)
            }
        } catch (e: Exception) {
            // 修复失败不影响使用，WAV 播放器通常能处理 DataSize=0xFFFFFFFF
            Log.w("FileUtils", "修复 WAV 头部失败: ${file.name} - ${e.message}", e)
        }
    }

    /** 生成与录音同名的 txt 转写文件 */
    fun newTranscriptFile(context: Context, recordingName: String): File {
        val baseName = recordingName.substringBeforeLast('.')
        return File(transcriptsDir(context), "$baseName.txt")
    }

    fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date())

    fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

    fun dateStr(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

    /** 把毫秒格式化为 HH:MM:SS */
    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0)
            String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, sec)
        else
            String.format(Locale.getDefault(), "%02d:%02d", m, sec)
    }

    /** 把秒格式化为 mm:ss（通知用） */
    fun formatClockSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    fun fileSafeTs(ts: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(ts))
}
