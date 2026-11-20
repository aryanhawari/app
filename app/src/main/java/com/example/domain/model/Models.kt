package com.example.domain.model

import androidx.annotation.Keep
import org.json.JSONObject

enum class BoardEventType {
    WRITE_TEXT,
    WRITE_MATH,
    DRAW_LINE,
    DRAW_ARROW,
    DRAW_SHAPE,
    DRAW_DIAGRAM,
    DRAW_GRAPH,
    HIGHLIGHT,
    CIRCLE,
    ERASE,
    CORRECT,
    ASK_QUESTION,
    WAIT_FOR_STUDENT,
    END_STEP;

    companion object {
        fun fromRaw(raw: String?): BoardEventType? {
            if (raw.isNullOrBlank()) return null
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

enum class BoardEventStatus {
    QUEUED,
    RENDERING,
    COMPLETE,
    CANCELLED;

    companion object {
        fun fromRaw(raw: String?): BoardEventStatus {
            val normalized = raw?.trim()?.uppercase()
            return entries.firstOrNull { it.name == normalized } ?: COMPLETE
        }
    }
}

data class NormalizedRect(
    val x: Float = 0.08f,
    val y: Float = 0.10f,
    val width: Float = 0.80f,
    val height: Float = 0.08f
)

data class BoardEvent(
    val eventId: String,
    val lessonId: String,
    val stepId: String,
    val sequence: Int,
    val type: BoardEventType,
    val payload: String,
    val viewport: NormalizedRect,
    val transcriptAnchor: String = "",
    val status: BoardEventStatus = BoardEventStatus.QUEUED,
    val pageIndex: Int = 0,
    val pageTitle: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("lessonId", lessonId)
        put("stepId", stepId)
        put("sequence", sequence)
        put("type", type.name)
        put("payload", payload)
        put("x", viewport.x.toDouble())
        put("y", viewport.y.toDouble())
        put("width", viewport.width.toDouble())
        put("height", viewport.height.toDouble())
        put("transcriptAnchor", transcriptAnchor)
        put("status", status.name.lowercase())
        put("pageIndex", pageIndex)
        put("pageTitle", pageTitle)
    }

    companion object {
        fun fromJson(obj: JSONObject, fallbackLessonId: String): BoardEvent? {
            val eventId = obj.optString("eventId").takeIf { it.isNotBlank() } ?: return null
            val type = BoardEventType.fromRaw(obj.optString("type")) ?: return null
            val payload = obj.optString("payload", "")
            val x = obj.optDouble("x", 0.08).toFloat().coerceIn(0f, 0.95f)
            val y = obj.optDouble("y", 0.10).toFloat().coerceIn(0f, 0.95f)
            val w = obj.optDouble("width", 0.80).toFloat().coerceIn(0.02f, 1f - x)
            val h = obj.optDouble("height", 0.08).toFloat().coerceIn(0.02f, 1f - y)
            return BoardEvent(
                eventId = eventId,
                lessonId = obj.optString("lessonId").ifBlank { fallbackLessonId },
                stepId = obj.optString("stepId").ifBlank { "step_1" },
                sequence = obj.optInt("sequence", 0),
                type = type,
                payload = payload,
                viewport = NormalizedRect(x, y, w, h),
                transcriptAnchor = obj.optString("transcriptAnchor", ""),
                status = BoardEventStatus.fromRaw(obj.optString("status")),
                pageIndex = obj.optInt("pageIndex", 0).coerceAtLeast(0),
                pageTitle = obj.optString("pageTitle", "")
            )
        }
    }
}

data class BoardPage(
    val pageIndex: Int,
    val title: String = "",
    val commandPrompt: String = "",
    val events: List<BoardEvent> = emptyList(),
    val caption: String = "",
    val uncertainRegion: NormalizedRect? = null
)

data class StrokePoint(
    val x: Float,
    val y: Float
)

data class StudentStroke(
    val strokeId: String,
    val colorHex: Long,
    val strokeWidth: Float,
    val eraserUsed: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val points: List<StrokePoint>
)

data class DialogueTurn(
    val role: String,
    val text: String,
    val language: String = "Auto",
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("text", text)
        put("language", language)
        put("timestampMs", timestampMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): DialogueTurn? {
            val role = obj.optString("role").takeIf { it.isNotBlank() } ?: return null
            val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return null
            return DialogueTurn(
                role = role,
                text = text,
                language = obj.optString("language", "Auto"),
                timestampMs = obj.optLong("timestampMs", 0L)
            )
        }
    }
}

enum class TeacherStatus(val label: String) {
    IDLE("Ready"),
    LISTENING("Listening"),
    UNDERSTANDING("Thinking"),
    EXPLAINING("Speaking"),
    WRITING("Writing"),
    YOUR_TURN("Ready"),
    PAUSED("Paused"),
    QUOTA_BUSY("Busy")
}

enum class InputModeType {
    VOICE,
    WRITE,
    PHOTO,
    SCREEN_SHARE,
    VIDEO,
    TEXT
}

@Keep
data class UserProfile(
    val id: String = "local_student",
    val displayName: String = "Student",
    val preferredLanguage: String = "Auto",
    val explanationPace: String = "Balanced",
    val difficultyLevel: String = "Beginner",
    val voiceEnabled: Boolean = true,
    val preferredVoice: String = "Kore",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Keep
data class LessonRecord(
    val id: String = "",
    val title: String = "New Lesson",
    val subject: String = "Maths",
    val level: String = "Beginner",
    val language: String = "Auto",
    val status: String = "active",
    val summary: String = "",
    val currentStep: String = "",
    val formulas: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val boardEvents: List<BoardEvent> = emptyList(),
    val recentTurns: List<DialogueTurn> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Keep
data class TopicProgress(
    val id: String = "",
    val subject: String = "Maths",
    val topic: String = "General",
    val masteryPercent: Long = 0L,
    val quizzesCompleted: Long = 0L,
    val quizzesCorrect: Long = 0L,
    val strengthNote: String = "",
    val weakAreaNote: String = "",
    val lastQuizQuestion: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class TeacherTurnResult(
    val spokenExplanation: String,
    val detectedLanguage: String,
    val subject: String,
    val topic: String,
    val needsClarification: Boolean,
    val clarificationQuestion: String,
    val uncertainRegion: NormalizedRect?,
    val boardEvents: List<BoardEvent>,
    val formulasExtracted: List<String>,
    val lessonSummary: String,
    val currentStep: String,
    val checkQuestion: String,
    val strengthNote: String,
    val weakAreaNote: String,
    val quizQuestion: String,
    val quizOptions: List<String>,
    val quizCorrectIndex: Int
)

object GurivaSubjects {
    val all = listOf(
        "Maths",
        "Physics",
        "Chemistry",
        "Biology",
        "Computer Science",
        "Programming",
        "English",
        "Social Studies",
        "Languages"
    )

    val languages = listOf(
        "Auto",
        "English",
        "Hindi",
        "Bhojpuri",
        "Nepali",
        "Maithili",
        "Mixed"
    )

    val paces = listOf("Gentle", "Balanced", "Concise")
    val levels = listOf("Beginner", "Intermediate", "Advanced")
    val voices = listOf("Kore", "Sulafat", "Puck", "Vindemiatrix")

    fun normalizeVoiceSelection(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed in voices) trimmed else "Kore"
    }
}
