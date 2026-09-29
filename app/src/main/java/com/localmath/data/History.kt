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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** One line of the notebook. Either [answer] (LaTeX) or [error] is set; [used] lists earlier values it used. */
@Entity(tableName = "notebook")
data class NotebookEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val input: String,
    val answer: String?,
    val approx: String?,
    val error: String?,
    val used: String?,
    val timestamp: Long
)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebook ORDER BY id ASC")
    fun all(): Flow<List<NotebookEntry>>

    @Insert
    suspend fun insert(entry: NotebookEntry)

    @Query("DELETE FROM notebook WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notebook")
    suspend fun clear()
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notebook` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`input` TEXT NOT NULL, `answer` TEXT, `approx` TEXT, `error` TEXT, `used` TEXT, `timestamp` INTEGER NOT NULL)"
        )
    }
}

@Database(entities = [HistoryEntry::class, NotebookEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun notebook(): NotebookDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "localmath.db")
                .addMigrations(MIGRATION_1_2)   // keeps existing history
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
