package com.localmath.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One solved problem. [answer] is LaTeX; [input] is exactly what was typed. */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val input: String,
    val kind: String,
    val answer: String,
    val timestamp: Long
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 500")
    fun all(): Flow<List<HistoryEntry>>

    @Insert
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE input = :input")
    suspend fun deleteByInput(input: String)

    @Delete
    suspend fun delete(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "localmath.db")
                .build()
                .also { instance = it }
        }
    }
}

/** Saves a solved problem, keeping only the newest copy of the same input. */
suspend fun HistoryDao.record(input: String, kind: String, answer: String) {
    deleteByInput(input)
    insert(HistoryEntry(input = input, kind = kind, answer = answer, timestamp = System.currentTimeMillis()))
}
