package com.esafirm.sharingan.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    @Query("SELECT * FROM manga ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<Manga>>

    @Query("SELECT * FROM manga WHERE id = :id")
    suspend fun get(id: Long): Manga?

    @Query("SELECT * FROM manga WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): Manga?

    @Insert
    suspend fun insert(manga: Manga): Long

    @Query("UPDATE manga SET lastPage = :page, status = :status, lastReadAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, status: ReadStatus, now: Long)

    @Query("UPDATE manga SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM manga WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Manga::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
}
