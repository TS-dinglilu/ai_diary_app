package com.example.ailogapp.data.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.ailogapp.data.entities.AnalysisEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(analysis: AnalysisEntity): Long

    @Query("SELECT * FROM analyses WHERE dateStr = :dateStr")
    suspend fun getByDate(dateStr: String): AnalysisEntity?

    @Query("SELECT * FROM analyses WHERE dateStr = :dateStr")
    fun observeByDate(dateStr: String): Flow<AnalysisEntity?>

    /** 最近 N 天的分析，用于结合历史生成当天结果 */
    @Query("SELECT * FROM analyses WHERE dateStr < :dateStr ORDER BY dateStr DESC LIMIT :limit")
    suspend fun getRecentBefore(dateStr: String, limit: Int): List<AnalysisEntity>

    @Query("SELECT * FROM analyses ORDER BY dateStr DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AnalysisEntity>

    @Query("SELECT * FROM analyses ORDER BY dateStr DESC")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("DELETE FROM analyses WHERE dateStr = :dateStr")
    suspend fun deleteByDate(dateStr: String)

    @Query("SELECT DISTINCT dateStr FROM analyses ORDER BY dateStr ASC")
    suspend fun getAllDates(): List<String>
}
