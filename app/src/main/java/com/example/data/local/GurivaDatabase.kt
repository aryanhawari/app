package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String = "local_student",
    val displayName: String = "Student",
    val preferredLanguage: String = "Auto",
    val explanationPace: String = "Balanced",
    val difficultyLevel: String = "Beginner",
    val voiceEnabled: Boolean = true,
    val preferredVoice: String = "Kore",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey val id: String,
    val title: String,
    val subject: String,
    val level: String,
    val language: String,
    val status: String,
    val summary: String,
    val currentStep: String,
    val formulasJson: String,
    val unresolvedQuestionsJson: String,
    val boardEventsJson: String,
    val recentTurnsJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(tableName = "topic_progress")
data class TopicProgressEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val topic: String,
    val masteryPercent: Long,
    val quizzesCompleted: Long,
    val quizzesCorrect: Long,
    val strengthNote: String,
    val weakAreaNote: String,
    val lastQuizQuestion: String,
    val updatedAtMs: Long
)

@Dao
interface GurivaDao {
    @Query("SELECT * FROM user_profile WHERE id = 'local_student' LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 'local_student' LIMIT 1")
    suspend fun getProfileOnce(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Query("SELECT * FROM lessons ORDER BY updatedAtMs DESC")
    fun observeLessons(): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE id = :lessonId LIMIT 1")
    suspend fun getLessonById(lessonId: String): LessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLesson(lesson: LessonEntity)

    @Query("DELETE FROM lessons WHERE id = :lessonId")
    suspend fun deleteLessonById(lessonId: String)

    @Query("DELETE FROM lessons")
    suspend fun deleteAllLessons()

    @Query("SELECT * FROM topic_progress ORDER BY updatedAtMs DESC")
    fun observeProgress(): Flow<List<TopicProgressEntity>>

    @Query("SELECT * FROM topic_progress WHERE id = :topicId LIMIT 1")
    suspend fun getProgressById(topicId: String): TopicProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: TopicProgressEntity)

    @Query("DELETE FROM topic_progress")
    suspend fun deleteAllProgress()
}

@Database(
    entities = [
        UserProfileEntity::class,
        LessonEntity::class,
        TopicProgressEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GurivaDatabase : RoomDatabase() {
    abstract fun gurivaDao(): GurivaDao

    companion object {
        @Volatile
        private var instance: GurivaDatabase? = null

        fun getInstance(context: Context): GurivaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GurivaDatabase::class.java,
                    "guriva_local.db"
                ).build().also { instance = it }
            }
        }
    }
}
