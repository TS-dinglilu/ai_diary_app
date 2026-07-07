package com.example.ailogapp.ai

import android.content.Context
import android.util.Log
import com.example.ailogapp.App
import com.example.ailogapp.data.entities.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 说话人管理器。
 *
 * 负责说话人识别和匹配，确保不同录音、不同日期下的相同说话人使用相同编号。
 *
 * 注意：当前实现为简化版本，基于简单的音频特征进行粗略区分。
 * 后续可集成专业的声纹识别模型（如 ECAPA-TDNN、ResNetSE 等）以提高准确率。
 */
object SpeakerManager {

    private const val TAG = "SpeakerManager"

    /** 说话人特征缓存（speakerId -> 特征向量） */
    private val speakerFeatures = mutableMapOf<Int, FloatArray>()

    /** 下一个要分配的说话人ID */
    private var nextSpeakerId = 1

    /** 特征匹配阈值（越小越严格） */
    private const val MATCH_THRESHOLD = 0.5f

    /**
     * 初始化说话人管理器，从数据库加载已有说话人。
     */
    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val db = (context.applicationContext as App).database
                val transcripts = db.transcriptDao().getRecent(100)

                // 找出所有已有的speakerId
                val existingSpeakers = transcripts
                    .map { it.speakerId }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()

                if (existingSpeakers.isNotEmpty()) {
                    nextSpeakerId = existingSpeakers.max() + 1
                    Log.i(TAG, "已加载 ${existingSpeakers.size} 个已有说话人，下一个ID: $nextSpeakerId")
                } else {
                    nextSpeakerId = 1
                    Log.i(TAG, "暂无已有说话人")
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始化说话人管理器失败", e)
            }
        }
    }

    /**
     * 识别音频中的说话人。
     *
     * @param audioSamples 音频采样数据（16kHz，单声道，float）
     * @return 说话人ID（1, 2, 3...）
     */
    fun identifySpeaker(audioSamples: FloatArray): Int {
        if (audioSamples.isEmpty()) return 0

        // 提取简单的音频特征
        val features = extractSimpleFeatures(audioSamples)

        // 尝试匹配已有说话人
        val matchedId = matchSpeaker(features)
        if (matchedId > 0) {
            return matchedId
        }

        // 匹配失败，分配新的说话人ID
        val newId = nextSpeakerId++
        speakerFeatures[newId] = features
        Log.i(TAG, "发现新说话人，分配ID: $newId")
        return newId
    }

    /**
     * 提取简单的音频特征（用于粗略的说话人区分）。
     *
     * 特征包括：
     * - 平均能量
     * - 能量方差
     * - 过零率
     * - 频谱质心（近似）
     *
     * 注意：这些特征对说话人区分的准确率有限，仅用于演示。
     * 实际应用中应使用专业的声纹特征（如 MFCC、x-vector、ECAPA-TDNN 等）。
     */
    private fun extractSimpleFeatures(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(4) { 0f }

        // 1. 平均能量
        var sumEnergy = 0f
        for (sample in samples) {
            sumEnergy += sample * sample
        }
        val avgEnergy = sqrt(sumEnergy / samples.size)

        // 2. 能量方差
        var energyVariance = 0f
        for (sample in samples) {
            val diff = sample * sample - avgEnergy * avgEnergy
            energyVariance += diff * diff
        }
        energyVariance = sqrt(energyVariance / samples.size)

        // 3. 过零率
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)
            ) {
                zeroCrossings++
            }
        }
        val zeroCrossingRate = zeroCrossings.toFloat() / samples.size

        // 4. 简单的频谱特征（使用差分近似）
        var spectralSum = 0f
        for (i in 1 until samples.size) {
            spectralSum += kotlin.math.abs(samples[i] - samples[i - 1])
        }
        val spectralCentroid = spectralSum / samples.size

        return floatArrayOf(avgEnergy, energyVariance, zeroCrossingRate, spectralCentroid)
    }

    /**
     * 匹配说话人。
     *
     * @param features 待匹配的特征向量
     * @return 匹配到的说话人ID，0表示未匹配
     */
    private fun matchSpeaker(features: FloatArray): Int {
        if (speakerFeatures.isEmpty()) return 0

        var bestMatchId = 0
        var bestDistance = Float.MAX_VALUE

        for ((id, storedFeatures) in speakerFeatures) {
            val distance = cosineDistance(features, storedFeatures)
            if (distance < bestDistance) {
                bestDistance = distance
                bestMatchId = id
            }
        }

        return if (bestDistance < MATCH_THRESHOLD) {
            // 更新特征（滑动平均）
            val storedFeatures = speakerFeatures[bestMatchId]!!
            for (i in features.indices) {
                storedFeatures[i] = storedFeatures[i] * 0.9f + features[i] * 0.1f
            }
            bestMatchId
        } else {
            0
        }
    }

    /**
     * 计算两个向量的余弦距离。
     * 余弦距离 = 1 - 余弦相似度
     * 范围：0（完全相同）到 2（完全相反）
     */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        normA = sqrt(normA)
        normB = sqrt(normB)

        if (normA == 0f || normB == 0f) return 1f

        val cosineSimilarity = dotProduct / (normA * normB)
        return 1f - cosineSimilarity
    }

    /**
     * 获取说话人显示名称。
     */
    fun getSpeakerName(speakerId: Int): String {
        return if (speakerId <= 0) {
            "未知"
        } else {
            "说话人$speakerId"
        }
    }

    /**
     * 重置说话人管理器（清除所有缓存）。
     */
    fun reset() {
        speakerFeatures.clear()
        nextSpeakerId = 1
        Log.i(TAG, "说话人管理器已重置")
    }
}
