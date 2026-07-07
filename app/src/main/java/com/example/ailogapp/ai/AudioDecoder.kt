package com.example.ailogapp.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 将音频文件解码为 16kHz 单声道 16-bit PCM。
 *
 * 支持两种路径：
 * 1. WAV 文件：直接读取 PCM 数据，跳过 MediaExtractor + MediaCodec，效率最高
 * 2. m4a/aac/mp4 等：用 MediaExtractor + MediaCodec 解码后重采样
 *
 * sherpa-onnx 的 ASR 引擎要求输入为 16kHz 单声道 float 数组。
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000

    /**
     * 解码音频文件，返回 16kHz 单声道 float 数组（取值范围 -1.0 ~ 1.0）。
     * @param path 音频文件路径（wav/m4a/aac/mp4 等）
     * @return float 数组，失败返回空数组
     */
    fun decodeToFloatArray(path: String): FloatArray {
        return try {
            if (path.lowercase().endsWith(".wav")) {
                decodeWav(path)
            } else {
                decodeCompressed(path)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "解码失败: ${e.message}", e)
            FloatArray(0)
        }
    }

    /**
     * 直接读取 WAV 文件的 PCM 数据，无需 MediaExtractor + MediaCodec。
     *
     * WAV 文件结构：44 字节标准头 + PCM 数据。
     * 本方法解析头部获取采样率/通道数/位深，然后直接读取 PCM 并归一化为 float。
     */
    private fun decodeWav(path: String): FloatArray {
        val raf = RandomAccessFile(path, "r")
        return try {
            // 读取 RIFF 头
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") {
                android.util.Log.e(TAG, "不是有效的 WAV 文件: $path")
                return FloatArray(0)
            }
            raf.seek(20) // audioFormat
            val audioFormat = readLittleEndianShort(raf)
            if (audioFormat != 1) { // 1 = PCM
                android.util.Log.e(TAG, "WAV 格式不支持(非PCM): $audioFormat")
                return FloatArray(0)
            }
            val channels = readLittleEndianShort(raf)
            val sampleRate = readLittleEndianInt(raf)
            raf.seek(34) // bitsPerSample
            val bitsPerSample = readLittleEndianShort(raf)

            if (bitsPerSample != 16) {
                android.util.Log.e(TAG, "WAV 位深不支持(非16bit): $bitsPerSample")
                return FloatArray(0)
            }

            // 找到 data chunk
            raf.seek(12)
            var dataOffset = -1L
            var dataSize = 0L
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val chunkSize = readLittleEndianInt(raf).toLong() and 0xFFFFFFFFL
                if (String(chunkId) == "data") {
                    dataOffset = raf.filePointer
                    dataSize = chunkSize
                    break
                }
                raf.seek(raf.filePointer + chunkSize)
            }

            if (dataOffset < 0 || dataSize <= 0) {
                android.util.Log.e(TAG, "WAV 文件未找到 data chunk")
                return FloatArray(0)
            }

            // 读取 PCM 数据。先 coerce 到 Long 范围避免超大文件 .toInt() 溢出为负数
            raf.seek(dataOffset)
            val bytesToRead = minOf(dataSize, raf.length() - dataOffset)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val pcmBytes = ByteArray(bytesToRead)
            raf.readFully(pcmBytes)

            // 转换为 float，混音为单声道
            val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shortBuf = bb.asShortBuffer()
            val totalFrames = shortBuf.remaining() / channels
            val samples = FloatArray(totalFrames)
            for (i in 0 until totalFrames) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += shortBuf.get()
                }
                samples[i] = (sum / channels).toFloat() / 32768.0f
            }

            // 重采样到 16kHz
            resample(samples, sampleRate, TARGET_SAMPLE_RATE)
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    private fun readLittleEndianShort(raf: RandomAccessFile): Int {
        val lo = raf.read()
        val hi = raf.read()
        return (hi shl 8) or lo
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Int {
        val lo = raf.read()
        val mid1 = raf.read()
        val mid2 = raf.read()
        val hi = raf.read()
        return (hi shl 24) or (mid2 shl 16) or (mid1 shl 8) or lo
    }

    /**
     * 用 MediaExtractor + MediaCodec 解码压缩音频（m4a/aac/mp4 等）。
     * 全程 try-finally 保护，确保 MediaCodec/MediaExtractor 在异常时也能释放，
     * 避免 native 实例泄漏（Android 限制约 32 个 MediaCodec 实例）。
     */
    private fun decodeCompressed(path: String): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)

            // 找到音频轨道
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                return FloatArray(0)
            }

            extractor.selectTrack(audioTrackIndex)
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // srcChannels 声明为 var：解码器输出格式变化时需更新
            var srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            // 用 MediaCodec 解码
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            // 用 ByteArrayOutputStream 收集 PCM 字节，避免 ArrayList<Float> 逐个装箱的 GC 压力
            val byteStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                // 喂入数据
                if (!sawInputEOS) {
                    val inputBufIdx = codec.dequeueInputBuffer(10000)
                    if (inputBufIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 取出解码后的 PCM
                val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 解码器输出格式变化（如通道数），重新读取以避免帧计算错位
                        srcChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outputBufIdx >= 0 -> {
                        val outputBuf = codec.getOutputBuffer(outputBufIdx)!!
                        if (bufferInfo.size > 0) {
                            // 读取 16-bit PCM short，混音为单声道后写入字节流
                            outputBuf.position(bufferInfo.offset)
                            outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shortBuf.remaining() / srcChannels
                            for (i in 0 until frames) {
                                var sum = 0
                                for (ch in 0 until srcChannels) {
                                    sum += shortBuf.get()
                                }
                                // 取平均值实现混音，以小端 16-bit 写入字节流（暂不转 float，避免装箱）
                                val mixed = sum / srcChannels
                                byteStream.write(mixed and 0xFF)
                                byteStream.write((mixed shr 8) and 0xFF)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            }

            // 一次性将 PCM 字节转为 FloatArray，归一化到 -1.0~1.0
            val bytes = byteStream.toByteArray()
            val samples = FloatArray(bytes.size / 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = bb.short.toFloat() / 32768.0f
            }

            // 重采样到 16kHz
            return resample(samples, srcSampleRate, TARGET_SAMPLE_RATE)
        } finally {
            // 确保 native 资源释放，每个 release 单独 try-catch 防止一个失败影响另一个
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * 简单线性插值重采样。
     */
    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            if (idx + 1 < input.size) {
                output[i] = input[idx] * (1 - frac).toFloat() + input[idx + 1] * frac.toFloat()
            } else if (idx < input.size) {
                output[i] = input[idx]
            }
        }
        return output
    }
}
