package com.example.ailogapp.data.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.ailogapp.data.entities.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: TranscriptEntity): Long

    @Query("SELECT * FROM transcripts WHERE audioId = :audioId")
    suspend fun getByAudioId(audioId: Long): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE dateStr = :dateStr ORDER BY createdAt ASC")
    suspend fun getByDate(dateStr: String): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE dateStr = :dateStr ORDER BY createdAt ASC")
    fun observeByDate(dateStr: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE audioId IN (:audioIds) ORDER BY createdAt ASC")
    suspend fun getByAudioIds(audioIds: List<Long>): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcripts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM transcripts WHERE dateStr = :dateStr")
    suspend fun deleteByDate(dateStr: String)

    @Query("SELECT DISTINCT dateStr FROM transcripts ORDER BY dateStr ASC")
    suspend fun getAllDates(): List<String>
}
