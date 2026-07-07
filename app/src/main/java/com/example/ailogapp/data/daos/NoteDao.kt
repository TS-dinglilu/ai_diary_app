package com.example.ailogapp.data.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.ailogapp.data.entities.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE dateStr = :dateStr ORDER BY createdAt ASC")
    suspend fun getByDate(dateStr: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE dateStr = :dateStr ORDER BY createdAt ASC")
    fun observeByDate(dateStr: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE sourceType = :sourceType AND sourceId = :sourceId ORDER BY createdAt ASC")
    fun observeBySource(sourceType: String, sourceId: Long): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteBySource(sourceType: String, sourceId: Long)
}
