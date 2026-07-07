package com.example.ailogapp.data.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.ailogapp.data.entities.AudioRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AudioRecordEntity): Long

    @Update
    suspend fun update(record: AudioRecordEntity)

    @Query("UPDATE audio_records SET endTime = :endTime, durationMs = :durationMs, fileSizeBytes = :fileSize WHERE id = :id")
    suspend fun finalize(id: Long, endTime: Long, durationMs: Long, fileSize: Long)

    @Query("UPDATE audio_records SET transcribeStatus = :status WHERE id = :id")
    suspend fun updateTranscribeStatus(id: Long, status: Int)

    /** 将所有"转写中"(status=1)的录音重置为"未转写"(status=0)，用于 App 启动时修复卡住的转写 */
    @Query("UPDATE audio_records SET transcribeStatus = 0 WHERE transcribeStatus = 1")
    suspend fun resetStuckTranscribing()

    /** 将所有已转写或转写失败的录音重置为"未转写"(status=0)，用于重新转写所有录音 */
    @Query("UPDATE audio_records SET transcribeStatus = 0 WHERE transcribeStatus IN (2, 3)")
    suspend fun resetAllTranscribed()

    /** 批量重置转写状态为未转写 */
    @Query("UPDATE audio_records SET transcribeStatus = 0 WHERE id IN (:ids)")
    suspend fun resetTranscribeStatus(ids: List<Long>)

    @Query("UPDATE audio_records SET analyzedInDay = :analyzed WHERE id IN (:ids)")
    suspend fun markAnalyzed(ids: List<Long>, analyzed: Boolean)

    @Query("SELECT * FROM audio_records WHERE transcribeStatus = :status ORDER BY startTime ASC")
    suspend fun getByStatus(status: Int): List<AudioRecordEntity>

    /** 当天尚未转写或转写失败的录音 */
    @Query("SELECT * FROM audio_records WHERE dateStr = :dateStr AND transcribeStatus IN (0, 3) ORDER BY startTime ASC")
    suspend fun getUntranscribedByDate(dateStr: String): List<AudioRecordEntity>

    @Query("SELECT * FROM audio_records WHERE dateStr = :dateStr ORDER BY startTime ASC")
    fun observeByDate(dateStr: String): Flow<List<AudioRecordEntity>>

    @Query("SELECT * FROM audio_records ORDER BY startTime DESC")
    fun observeAll(): Flow<List<AudioRecordEntity>>

    @Query("SELECT * FROM audio_records WHERE dateStr = :dateStr ORDER BY startTime ASC")
    suspend fun getByDate(dateStr: String): List<AudioRecordEntity>

    @Query("SELECT * FROM audio_records WHERE id = :id")
    suspend fun getById(id: Long): AudioRecordEntity?

    // ---- 删除录音相关 ----

    /** 按 ID 批量删除数据库记录（关联的 transcripts 表会通过外键 CASCADE 自动清理） */
    @Query("DELETE FROM audio_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 按 ID 列表查询录音（用于删除前加载文件路径） */
    @Query("SELECT * FROM audio_records WHERE id IN (:ids) ORDER BY startTime DESC")
    suspend fun getByIds(ids: List<Long>): List<AudioRecordEntity>

    /** 所有不同日期（去重降序），用于筛选下拉 */
    @Query("SELECT DISTINCT dateStr FROM audio_records ORDER BY dateStr DESC")
    suspend fun getAllDates(): List<String>

    /** 按日期范围 + 转写状态查询录音 */
    @Query("""
        SELECT * FROM audio_records
        WHERE dateStr IN (:dates)
        AND transcribeStatus IN (:statuses)
        ORDER BY startTime DESC
    """)
    suspend fun filter(dates: List<String>, statuses: List<Int>): List<AudioRecordEntity>

    /** 按日期范围查询所有状态的录音 */
    @Query("SELECT * FROM audio_records WHERE dateStr IN (:dates) ORDER BY startTime DESC")
    suspend fun filterByDates(dates: List<String>): List<AudioRecordEntity>

    /** 统计未转写的录音数量（状态为0或3） */
    @Query("SELECT COUNT(*) FROM audio_records WHERE transcribeStatus IN (0, 3)")
    suspend fun countUntranscribed(): Int

    /** 检查某天是否有录音 */
    @Query("SELECT COUNT(*) > 0 FROM audio_records WHERE dateStr = :dateStr")
    suspend fun hasRecordsForDate(dateStr: String): Boolean

    // ---- 软删除相关 ----

    /** 标记录音为已删除（软删除，只删除录音文件，保留数据库记录和转写） */
    @Query("UPDATE audio_records SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun markAsDeleted(ids: List<Long>)

    /** 标记录音为未删除（恢复） */
    @Query("UPDATE audio_records SET isDeleted = 0 WHERE id IN (:ids)")
    suspend fun markAsUndeleted(ids: List<Long>)

    /** 获取所有未删除的录音（按日期） */
    @Query("SELECT * FROM audio_records WHERE dateStr = :dateStr AND isDeleted = 0 ORDER BY startTime ASC")
    suspend fun getActiveByDate(dateStr: String): List<AudioRecordEntity>
}
