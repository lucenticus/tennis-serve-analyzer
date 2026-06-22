package com.tennis.analyzer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "serve_results")
data class ServeResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val timestampMs: Long,
    val overallScore: Float,
    val elbowAngle: Float,
    val trunkTilt: Float,
    val shoulderRotation: Float,
    val legDriveScore: Float,
    val adviceGiven: String   // JSON-список советов
)

@Entity(tableName = "training_sessions")
data class TrainingSession(
    @PrimaryKey val id: String,
    val startMs: Long,
    val endMs: Long,
    val totalServes: Int,
    val avgScore: Float
)

@Dao
interface ServeDao {
    @Insert
    suspend fun insertServe(result: ServeResult)

    @Query("SELECT * FROM serve_results WHERE sessionId = :sessionId ORDER BY timestampMs")
    fun getServesForSession(sessionId: String): Flow<List<ServeResult>>

    @Query("SELECT AVG(overallScore) FROM serve_results WHERE timestampMs > :sinceMs")
    suspend fun avgScoreSince(sinceMs: Long): Float?

    @Query("SELECT * FROM serve_results ORDER BY timestampMs DESC LIMIT 50")
    fun getRecentServes(): Flow<List<ServeResult>>
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(session: TrainingSession)

    @Query("SELECT * FROM training_sessions ORDER BY startMs DESC LIMIT 20")
    fun getRecentSessions(): Flow<List<TrainingSession>>
}

@Database(
    entities = [ServeResult::class, TrainingSession::class],
    version = 1,
    exportSchema = false
)
abstract class TrainingDatabase : RoomDatabase() {
    abstract fun serveDao(): ServeDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: TrainingDatabase? = null

        fun get(context: android.content.Context): TrainingDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, TrainingDatabase::class.java, "training.db")
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
