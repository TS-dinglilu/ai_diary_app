package com.example.ailogapp.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.util.FileUtils
import com.example.ailogapp.util.PrefsManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 录音管理器核心（AudioRecord + WAV 方案，增强版）。
 *
 * 关键改进（解决进程被杀后录音文件无音频的问题）：
 * 1) 录音线程持有 RandomAccessFile，每 3 秒调用 fsync() 强制刷盘
 * 2) 每 3 秒更新 WAV 头部的 DataSize，确保进程被杀时头部正确
 * 3) 进程被杀时，OS 关闭文件描述符，已 fsync 的数据全部保留
 * 4) 分段录制（默认 3 分钟），每段完成后正确更新头部
 */
class AudioRecorderManager(
    private val context: Context,
    private val prefs: PrefsManager,
    private val callback: Callback
) {

    interface Callback {
        suspend fun onSegmentFinished(record: AudioRecordEntity)
        fun onTick(elapsedSeconds: Long)
        fun onSegmentStarted(fileName: String)
        fun onError(msg: String)
        fun onMicConflict()
    }

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var currentFile: File? = null
    @Volatile private var currentFileName: String = ""
    @Volatile private var isRecording = false
    @Volatile private var recordingThread: Thread? = null

    private val handler = Handler(Looper.getMainLooper())
    private var segmentWallStart: Long = 0
    private var segmentStartTimeMs: Long = 0
    private var segmentRotateAt: Long = 0

    /** IO 协程作用域：用于异步将段录音入库，避免阻塞主线程 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingQueue = ConcurrentLinkedQueue<AudioRecordEntity>()

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        /** 每 3 秒刷盘 + 更新头部 */
        private const val FLUSH_INTERVAL_BYTES = SAMPLE_RATE * 2 * 3  // 3秒的数据量
    }

    fun startRecording() {
        if (isRecording) return
        // 先置位 isRecording，再启动录音线程。
        // 录音线程循环条件为 while(isRecording && ...)，若线程调度先于赋值执行，
        // 会立即退出循环，导致只产生 44 字节空 WAV 的静默失败。
        isRecording = true
        try {
            startNewSegment()
            segmentWallStart = SystemClock.elapsedRealtime()
            handler.post(tickRunnable)
            Log.i(TAG, "录音已启动 -> $currentFileName")
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            callback.onError("启动录音失败: ${e.message}")
            releaseRecorder()
            isRecording = false
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsedSec = (SystemClock.elapsedRealtime() - segmentWallStart) / 1000
            callback.onTick(elapsedSec)
            if (SystemClock.elapsedRealtime() >= segmentRotateAt) {
                rotateToNextSegment()
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun rotateToNextSegment() {
        if (!isRecording) return
        Log.i(TAG, "切换到下一段录音")
        try {
            val (file, startMs) = stopRecorderInternal()
            if (file != null) {
                val durMs = calcAudioDurationMs(file)
                // 时长太短（<1秒）说明没有实际录音，跳过
                if (durMs < 1000) {
                    Log.w(TAG, "段录音太短(${durMs}ms)，丢弃: ${file.name}")
                    file.delete()
                } else {
                    val record = AudioRecordEntity(
                        filePath = file.absolutePath,
                        fileName = file.name,
                        startTime = startMs,
                        endTime = System.currentTimeMillis(),
                        durationMs = durMs,
                        dateStr = FileUtils.dateStr(startMs),
                        fileSizeBytes = file.length(),
                        transcribeStatus = 0
                    )
                    // 段完成后异步入库，避免阻塞主线程。
                    // pendingQueue 仅作为 flushPendingFinished 的兼容保留，不再由此处填充。
                    // stopRecording() 中会同步等待所有 pending 入库完成，确保进程被杀时不丢数据。
                    ioScope.launch {
                        callback.onSegmentFinished(record)
                    }
                }
            }
            startNewSegment()
            segmentWallStart = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.e(TAG, "切换录音段失败", e)
            callback.onError("切换录音段失败: ${e.message}")
        }
    }

    suspend fun flushPendingFinished() {
        while (true) {
            val record = pendingQueue.poll() ?: return
            callback.onSegmentFinished(record)
        }
    }

    fun stopRecording(): AudioRecordEntity? {
        if (!isRecording) return null
        isRecording = false
        handler.removeCallbacks(tickRunnable)
        // join 统一由 stopRecorderInternal 处理，避免重复等待。
        val (file, startMs) = stopRecorderInternal()
        recordingThread = null

        // 同步等待所有 pending 的异步入库完成，避免进程被杀时丢失段录音记录
        runBlocking {
            ioScope.coroutineContext.job.children.toList().joinAll()
        }

        prefs.currentRecordingPath = ""
        prefs.currentRecordingStartMs = 0L

        return if (file != null) {
            val durMs = calcAudioDurationMs(file)
            if (durMs < 1000) {
                // 太短，删除文件，返回 null
                Log.w(TAG, "录音太短(${durMs}ms)，丢弃: ${file.name}")
                file.delete()
                null
            } else {
                AudioRecordEntity(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    startTime = startMs,
                    endTime = System.currentTimeMillis(),
                    durationMs = durMs,
                    dateStr = FileUtils.dateStr(startMs),
                    fileSizeBytes = file.length(),
                    transcribeStatus = 0
                )
            }
        } else null
    }

    fun isRecording(): Boolean = isRecording

    /**
     * 从 WAV 文件实际数据大小计算真实音频时长（毫秒）。
     *
     * WAV 格式：16kHz, 单声道, 16bit
     * 每秒数据量 = 16000 * 1 * 2 = 32000 bytes
     * 时长(ms) = (fileSize - 44字节头) / 32
     *
     * 这样即使进程被杀后 Service 重启，计时虽然继续，
     * 但文件中没有新数据，计算出的时长就是实际录音时长。
     */
    private fun calcAudioDurationMs(file: File): Long {
        val dataBytes = file.length() - WAV_HEADER_SIZE
        if (dataBytes <= 0) return 0
        // 16000 samples/s * 1 channel * 2 bytes/sample = 32000 bytes/s
        return dataBytes * 1000 / (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
    }

    private fun startNewSegment() {
        releaseRecorder()
        segmentStartTimeMs = System.currentTimeMillis()
        currentFile = FileUtils.newRecordingFile(context, segmentStartTimeMs)
        currentFileName = currentFile!!.name

        prefs.currentRecordingPath = currentFile!!.absolutePath
        prefs.currentRecordingStartMs = segmentStartTimeMs

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuf * 2).coerceAtLeast(3200)

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("AudioRecord 初始化失败")
        }
        audioRecord = ar

        writeWavHeader(currentFile!!, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)

        ar.startRecording()
        callback.onSegmentStarted(currentFileName)

        val segmentMs = prefs.segmentMinutes * 60_000L
        segmentRotateAt = SystemClock.elapsedRealtime() + segmentMs

        recordingThread = Thread { recordingLoop(ar, currentFile!!) }.apply {
            name = "AudioRecordThread"
            isDaemon = true
            start()
        }
    }

    /**
     * 录音线程：读取 PCM 写入文件。
     *
     * 关键：每写 3 秒数据就 fsync() + 更新 WAV 头部。
     * 这样即使进程被杀，最近 3 秒内的数据已落盘且头部正确。
     */
    private fun recordingLoop(ar: AudioRecord, file: File) {
        val buffer = ShortArray(1600)  // 100ms @ 16kHz
        var raf: RandomAccessFile? = null
        var bytesSinceLastFlush: Long = 0
        // 使用方法局部变量累计已写入字节数，避免与 startNewSegment 的重置产生竞态：
        // 即使 startNewSegment 启动新段，本段最终刷盘仍使用自己的 localDataBytes。
        var localDataBytes: Long = 0
        try {
            raf = RandomAccessFile(file, "rw")
            raf.seek(WAV_HEADER_SIZE.toLong())

            while (isRecording && ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "AudioRecord.read 返回 $read，麦克风可能被占用")
                    // 切回主线程执行回调，避免在录音线程中调用 stopRecording 导致 join 自身线程卡顿。
                    handler.post { callback.onMicConflict() }
                    break
                }
                if (read > 0) {
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(buffer, 0, read)
                    raf.write(bb.array(), 0, read * 2)
                    localDataBytes += (read * 2).toLong()
                    bytesSinceLastFlush += (read * 2).toLong()

                    // 每 3 秒数据量刷盘 + 更新头部
                    if (bytesSinceLastFlush >= FLUSH_INTERVAL_BYTES) {
                        try {
                            raf.fd.sync()  // 强制写入磁盘
                            updateWavHeaderSize(raf, localDataBytes)
                            // 关键修复：updateWavHeaderSize 会把文件指针移到头部(offset 44)，
                            // 必须重新 seek 到数据末尾，否则后续写入会从 offset 44 开始覆盖已有数据，
                            // 导致无论录音多久，文件大小永远只有 3 秒数据量。
                            raf.seek(WAV_HEADER_SIZE + localDataBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "刷盘失败: ${e.message}")
                        }
                        bytesSinceLastFlush = 0
                    }
                }
            }
            // 循环结束后最终刷盘 + 更新头部（使用本段局部变量，确保头部正确）
            try {
                raf.fd.sync()
                updateWavHeaderSize(raf, localDataBytes)
            } catch (e: Exception) {
                Log.w(TAG, "最终刷盘失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音线程异常", e)
            callback.onError("录音异常: ${e.message}")
        } finally {
            try { raf?.close() } catch (_: Exception) {}
        }
    }

    /** 更新 WAV 头部的 DataSize 和 RIFF size */
    private fun updateWavHeaderSize(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(4)
        val riffSize = (4 + 24 + 8 + dataBytes).toInt()
        rafWriteInt(raf, riffSize)
        raf.seek(40)
        rafWriteInt(raf, dataBytes.toInt())
    }

    private fun stopRecorderInternal(): Pair<File?, Long> {
        val file = currentFile
        val startMs = segmentStartTimeMs
        try { audioRecord?.stop() } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }
        // 等待录音线程完成最终刷盘与头部更新，避免在数据未落盘时操作文件。
        // join 带超时，且本方法仅在主线程（tickRunnable / stopRecording）调用，
        // 不会在录音线程自身调用，因此不会死锁。
        try {
            recordingThread?.join(2000)
        } catch (e: Exception) {
            Log.w(TAG, "等待录音线程结束异常: ${e.message}")
        }
        releaseRecorder()

        if (file != null && file.exists() && file.length() > WAV_HEADER_SIZE) {
            FileUtils.fixWavHeader(file)
            return file to startMs
        }
        if (file != null && file.length() <= WAV_HEADER_SIZE) {
            file.delete()
        }
        return null to startMs
    }

    private fun releaseRecorder() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val raf = RandomAccessFile(file, "rw")
        raf.use { r ->
            r.writeBytes("RIFF")
            rafWriteInt(r, 0)  // FileSize (先写0，后续更新)
            r.writeBytes("WAVE")
            r.writeBytes("fmt ")
            rafWriteInt(r, 16)
            rafWriteShort(r, 1)
            rafWriteShort(r, channels.toShort())
            rafWriteInt(r, sampleRate)
            rafWriteInt(r, byteRate)
            rafWriteShort(r, blockAlign.toShort())
            rafWriteShort(r, bitsPerSample.toShort())
            r.writeBytes("data")
            rafWriteInt(r, 0)  // DataSize (先写0，后续更新)
        }
    }

    private fun rafWriteInt(r: RandomAccessFile, v: Int) {
        r.write(v and 0xFF)
        r.write((v shr 8) and 0xFF)
        r.write((v shr 16) and 0xFF)
        r.write((v shr 24) and 0xFF)
    }

    private fun rafWriteShort(r: RandomAccessFile, v: Short) {
        r.write(v.toInt() and 0xFF)
        r.write((v.toInt() shr 8) and 0xFF)
    }
}
