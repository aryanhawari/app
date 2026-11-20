package com.example.data.repository

import android.content.Context
import com.example.data.local.GurivaDao
import com.example.data.local.GurivaDatabase
import com.example.data.local.LessonEntity
import com.example.data.local.TopicProgressEntity
import com.example.data.local.UserProfileEntity
import com.example.domain.model.BoardEvent
import com.example.domain.model.DialogueTurn
import com.example.domain.model.GurivaSubjects
import com.example.domain.model.LessonRecord
import com.example.domain.model.TopicProgress
import com.example.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

class GurivaLocalRepository(
    private val dao: GurivaDao
) {
    constructor(context: Context) : this(GurivaDatabase.getInstance(context).gurivaDao())

    fun observeProfile(): Flow<UserProfile?> = dao.observeProfile().map { entity ->
        entity?.toDomain()
    }

    fun observeLessons(): Flow<List<LessonRecord>> = dao.observeLessons().map { list ->
        list.map { it.toDomain() }
    }

    fun observeProgress(): Flow<List<TopicProgress>> = dao.observeProgress().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun ensureProfileExists(): Result<Unit> {
        return runCatching {
            if (dao.getProfileOnce() == null) {
                dao.upsertProfile(UserProfileEntity())
            }
        }
    }

    suspend fun updateProfilePreferences(
        preferredLanguage: String,
        explanationPace: String,
        difficultyLevel: String,
        voiceEnabled: Boolean,
        preferredVoice: String,
        displayName: String
    ): Result<Unit> {
        return runCatching {
            val safeLang = if (preferredLanguage in GurivaSubjects.languages) preferredLanguage else "Auto"
            val safePace = if (explanationPace in GurivaSubjects.paces) explanationPace else "Balanced"
            val safeLevel = if (difficultyLevel in GurivaSubjects.levels) difficultyLevel else "Beginner"
            val safeName = displayName.trim().ifBlank { "Student" }.take(80)
            val safeVoice = GurivaSubjects.normalizeVoiceSelection(preferredVoice)

            dao.upsertProfile(
                UserProfileEntity(
                    id = "local_student",
                    displayName = safeName,
                    preferredLanguage = safeLang,
                    explanationPace = safePace,
                    difficultyLevel = safeLevel,
                    voiceEnabled = voiceEnabled,
                    preferredVoice = safeVoice,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getLesson(lessonId: String): Result<LessonRecord?> {
        return runCatching {
            require(lessonId.isNotBlank()) { "Lesson ID required" }
            dao.getLessonById(lessonId)?.toDomain()
        }
    }

    suspend fun createOrSaveLesson(
        existingLessonId: String?,
        title: String,
        subject: String,
        level: String,
        language: String,
        status: String,
        summary: String,
        currentStep: String,
        formulas: List<String>,
        unresolvedQuestions: List<String>,
        boardEvents: List<BoardEvent>,
        recentTurns: List<DialogueTurn>
    ): Result<String> {
        return runCatching {
            val now = System.currentTimeMillis()
            val resolvedId = existingLessonId?.takeIf { it.isNotBlank() } ?: "lesson_${UUID.randomUUID()}"
            val existing = dao.getLessonById(resolvedId)

            val safeTitle = title.trim().ifBlank { "$subject Lesson" }.take(160)
            val safeSubject = subject.trim().ifBlank { "Maths" }.take(60)
            val safeLevel = if (level in GurivaSubjects.levels) level else "Beginner"
            val safeLanguage = language.trim().ifBlank { "Auto" }.take(40)
            val safeStatus = if (status in listOf("active", "paused", "completed")) status else "active"

            val entity = LessonEntity(
                id = resolvedId,
                title = safeTitle,
                subject = safeSubject,
                level = safeLevel,
                language = safeLanguage,
                status = safeStatus,
                summary = summary.trim(),
                currentStep = currentStep.trim(),
                formulasJson = encodeStringList(formulas.map { it.trim() }.filter { it.isNotBlank() }.distinct()),
                unresolvedQuestionsJson = encodeStringList(unresolvedQuestions.map { it.trim() }.filter { it.isNotBlank() }),
                boardEventsJson = encodeBoardEvents(boardEvents),
                recentTurnsJson = encodeDialogueTurns(recentTurns),
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now
            )
            dao.upsertLesson(entity)
            resolvedId
        }
    }

    suspend fun deleteLesson(lessonId: String): Result<Unit> {
        return runCatching {
            require(lessonId.isNotBlank()) { "Lesson ID required" }
            dao.deleteLessonById(lessonId)
        }
    }

    suspend fun upsertTopicProgress(
        subject: String,
        topic: String,
        masteryDelta: Long,
        quizCompletedDelta: Long,
        quizCorrectDelta: Long,
        strengthNote: String,
        weakAreaNote: String,
        lastQuizQuestion: String
    ): Result<Unit> {
        return runCatching {
            val safeSubject = subject.trim().ifBlank { "Maths" }.take(60)
            val safeTopic = topic.trim().ifBlank { "General Practice" }.take(120)
            val docId = "${safeSubject}_${safeTopic}"
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "general" }
                .take(100)

            val existing = dao.getProgressById(docId)
            val now = System.currentTimeMillis()
            if (existing == null) {
                val completed = quizCompletedDelta.coerceAtLeast(0L)
                val correct = quizCorrectDelta.coerceIn(0L, completed)
                val mastery = masteryDelta.coerceIn(10L, 100L)
                dao.upsertProgress(
                    TopicProgressEntity(
                        id = docId,
                        subject = safeSubject,
                        topic = safeTopic,
                        masteryPercent = mastery,
                        quizzesCompleted = completed,
                        quizzesCorrect = correct,
                        strengthNote = strengthNote.trim(),
                        weakAreaNote = weakAreaNote.trim(),
                        lastQuizQuestion = lastQuizQuestion.trim(),
                        updatedAtMs = now
                    )
                )
            } else {
                val newCompleted = (existing.quizzesCompleted + quizCompletedDelta).coerceAtLeast(0L)
                val newCorrect = (existing.quizzesCorrect + quizCorrectDelta).coerceIn(0L, newCompleted)
                val newMastery = (existing.masteryPercent + masteryDelta).coerceIn(0L, 100L)
                dao.upsertProgress(
                    existing.copy(
                        subject = safeSubject,
                        topic = safeTopic,
                        masteryPercent = newMastery,
                        quizzesCompleted = newCompleted,
                        quizzesCorrect = newCorrect,
                        strengthNote = strengthNote.trim().ifBlank { existing.strengthNote },
                        weakAreaNote = weakAreaNote.trim().ifBlank { existing.weakAreaNote },
                        lastQuizQuestion = lastQuizQuestion.trim().ifBlank { existing.lastQuizQuestion },
                        updatedAtMs = now
                    )
                )
            }
        }
    }

    suspend fun deleteAllUserData(): Result<Unit> {
        return runCatching {
            dao.deleteAllLessons()
            dao.deleteAllProgress()
        }
    }

    private fun UserProfileEntity.toDomain() = UserProfile(
        id = id,
        displayName = displayName,
        preferredLanguage = preferredLanguage,
        explanationPace = explanationPace,
        difficultyLevel = difficultyLevel,
        voiceEnabled = voiceEnabled,
        preferredVoice = GurivaSubjects.normalizeVoiceSelection(preferredVoice),
        updatedAtMs = updatedAtMs
    )

    private fun LessonEntity.toDomain() = LessonRecord(
        id = id,
        title = title,
        subject = subject,
        level = level,
        language = language,
        status = status,
        summary = summary,
        currentStep = currentStep,
        formulas = decodeStringList(formulasJson),
        unresolvedQuestions = decodeStringList(unresolvedQuestionsJson),
        boardEvents = decodeBoardEvents(boardEventsJson, id),
        recentTurns = decodeDialogueTurns(recentTurnsJson),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs
    )

    private fun TopicProgressEntity.toDomain() = TopicProgress(
        id = id,
        subject = subject,
        topic = topic,
        masteryPercent = masteryPercent,
        quizzesCompleted = quizzesCompleted,
        quizzesCorrect = quizzesCorrect,
        strengthNote = strengthNote,
        weakAreaNote = weakAreaNote,
        lastQuizQuestion = lastQuizQuestion,
        updatedAtMs = updatedAtMs
    )

    private fun encodeStringList(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStringList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                arr.optString(idx).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeBoardEvents(events: List<BoardEvent>): String {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun decodeBoardEvents(raw: String, fallbackLessonId: String): List<BoardEvent> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                arr.optJSONObject(idx)?.let { BoardEvent.fromJson(it, fallbackLessonId) }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeDialogueTurns(turns: List<DialogueTurn>): String {
        val arr = JSONArray()
        turns.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun decodeDialogueTurns(raw: String): List<DialogueTurn> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                arr.optJSONObject(idx)?.let { DialogueTurn.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }
}
