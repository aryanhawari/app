package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.core.firebase.isFirebaseAiProvisioned
import com.example.core.media.TimestampedVideoFrame
import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.DialogueTurn
import com.example.domain.model.NormalizedRect
import com.example.domain.model.TeacherTurnResult
import com.example.feature.classroom.BoardEventParser
import com.example.feature.classroom.BoardEventValidator
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface AiTeacherGateway {
    val isLiveCloudReady: Boolean

    @OptIn(PublicPreviewAPI::class)
    fun createLiveVoiceModel(
        subject: String,
        level: String,
        preferredLanguage: String,
        preferredVoice: String = GurivaAiRepository.DEFAULT_VOICE
    ): LiveGenerativeModel?

    fun parseLiveBoardToolCall(
        call: FunctionCallPart,
        lessonId: String,
        stepId: String,
        existingBoardCount: Int,
        suggestedY: Float,
        existingEventIds: Set<String>,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): BoardEvent?

    suspend fun teachTurn(
        lessonId: String,
        stepId: String,
        subject: String,
        level: String,
        preferredLanguage: String,
        explanationPace: String,
        lessonSummary: String,
        recentTurns: List<DialogueTurn>,
        existingBoardCount: Int,
        existingEventIds: Set<String>,
        studentPrompt: String,
        handwritingBitmap: Bitmap? = null,
        strokeContextHint: String = "",
        photoBitmap: Bitmap? = null,
        audioWavBytes: ByteArray? = null,
        videoFrames: List<TimestampedVideoFrame> = emptyList(),
        isAlternativeApproach: Boolean = false,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): Result<TeacherTurnResult>

    suspend fun synthesizeGeminiSpeech(
        text: String,
        language: String,
        preferredVoice: String = GurivaAiRepository.DEFAULT_VOICE
    ): Result<Pair<ByteArray, String>>

    suspend fun generateTopicQuiz(
        subject: String,
        topic: String,
        level: String,
        language: String
    ): Result<TeacherTurnResult>
}

object AiGatewayFactory {
    fun create(context: Context): AiTeacherGateway {
        return if (isFirebaseAiProvisioned(context)) {
            GurivaAiRepository()
        } else {
            SamplePreviewAiRepository()
        }
    }
}

/**
 * Primary Gemini AI teacher backed by Firebase AI Logic + App Check attestation.
 * Stores ZERO API keys in the APK or BuildConfig so keys cannot be extracted.
 */
class GurivaAiRepository : AiTeacherGateway {

    companion object {
        private const val TAG = "GurivaAiRepository"
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"
        private const val TTS_MODEL_NAME = "gemini-3.1-flash-tts-preview"
        const val DEFAULT_VOICE = "Kore"
    }

    override val isLiveCloudReady: Boolean = true

    private val boardEventItemSchema = Schema.obj(
        properties = mapOf(
            "type" to Schema.enumeration(
                values = BoardEventType.entries.map { it.name },
                description = "Drawing or board command type"
            ),
            "payload" to Schema.string(
                description = "Equation, concise label, diagram spec (e.g. 'BoxA->BoxB:label'), graph spec (e.g. 'y=2x+1'), or question"
            ),
            "x" to Schema.double(description = "Normalized left coordinate 0.04..0.85"),
            "y" to Schema.double(description = "Normalized top coordinate 0.06..0.88"),
            "width" to Schema.double(description = "Normalized width 0.08..0.88"),
            "height" to Schema.double(description = "Normalized height 0.05..0.40"),
            "transcriptAnchor" to Schema.string(description = "Short spoken phrase anchored to this board item")
        )
    )

    private val teacherTurnSchema = Schema.obj(
        properties = mapOf(
            "spokenExplanation" to Schema.string(
                description = "Warm, natural, simple spoken teacher explanation (1 short step at a time, explaining WHY)."
            ),
            "detectedLanguage" to Schema.string(
                description = "Detected or selected student language (English, Hindi, Bhojpuri, Nepali, Maithili, Mixed)."
            ),
            "subject" to Schema.string(description = "Subject name such as Maths, Physics, Chemistry, Biology, Programming, English"),
            "topic" to Schema.string(description = "Concise topic name"),
            "needsClarification" to Schema.boolean(
                description = "True ONLY if handwriting/photo has an ambiguous digit, symbol, or label that should be asked about before solving."
            ),
            "clarificationQuestion" to Schema.string(
                description = "Polite natural question asking the student to clarify an unclear mark, or empty string."
            ),
            "uncertainX" to Schema.double(description = "Normalized X of unclear handwriting region 0..1"),
            "uncertainY" to Schema.double(description = "Normalized Y of unclear handwriting region 0..1"),
            "uncertainWidth" to Schema.double(description = "Normalized width of unclear handwriting region 0.05..0.6"),
            "uncertainHeight" to Schema.double(description = "Normalized height of unclear handwriting region 0.05..0.4"),
            "boardEvents" to Schema.array(
                items = boardEventItemSchema,
                description = "Ordered progressive whiteboard drawing commands for this single teaching step"
            ),
            "formulasExtracted" to Schema.array(
                items = Schema.string(),
                description = "Key equations or formulas written in this step"
            ),
            "lessonSummary" to Schema.string(description = "Updated compact 1-2 sentence lesson memory summary"),
            "currentStep" to Schema.string(description = "Short label of current step"),
            "checkQuestion" to Schema.string(description = "Optional natural check-for-understanding question at a natural point"),
            "strengthNote" to Schema.string(description = "Observed student strength"),
            "weakAreaNote" to Schema.string(description = "Observed misconception or area needing gentle practice"),
            "quizQuestion" to Schema.string(description = "Optional short follow-up practice question"),
            "quizOptions" to Schema.array(items = Schema.string(), description = "Up to 4 short multiple choice answers if quizQuestion is non-empty"),
            "quizCorrectIndex" to Schema.integer(description = "0-based index of the right answer in quizOptions")
        ),
        optionalProperties = listOf(
            "uncertainX",
            "uncertainY",
            "uncertainWidth",
            "uncertainHeight",
            "checkQuestion",
            "quizQuestion",
            "quizOptions",
            "quizCorrectIndex"
        )
    )

    private val teacherSystemInstruction = content {
        text(
            """
            You are GURIVA, a warm, patient, human-like personal teacher sitting beside the student at a whiteboard.
            Core teaching principles:
            - Use everyday, clear language. Never use robotic customer-service phrases or repeat generic praise.
            - Diagnose what the student is asking and what is confusing BEFORE dumping a full answer.
            - Teach ONE meaningful step at a time and always explain WHY that step is needed.
            - Use familiar examples, analogies, diagrams, or graphs on the whiteboard when they clarify.
            - NEVER GUESS unclear handwriting, digits, operators, or symbols. If something is ambiguous (e.g. 3 vs 8, + vs t, 5 vs S), set needsClarification=true, provide uncertainX/Y/Width/Height highlighting that region, and ask naturally like "I can't clearly read this digit—did you write 3 or 8?" before solving.
            - Correct mistakes kindly and specifically without shaming.
            - Ask a natural check-for-understanding question at a meaningful pause, not after every single sentence.
            - Detect and match the student's language (English, Hindi, Bhojpuri, Nepali, Maithili, or mixed-language speech) unless they request another language. Keep whiteboard labels consistent with the spoken language.
            - Position boardEvents in normalized [0..1] coordinates neatly stacked vertically without overlapping existing work. Keep each step compact (2 to 5 boardEvents).
            - Use WRITE_MATH for equations, WRITE_TEXT for short notes, DRAW_DIAGRAM for labeled concepts ('A -> B : note'), DRAW_GRAPH for linear/quadratic/trig curves ('y = 2x + 1'), HIGHLIGHT or CIRCLE to focus attention, and CORRECT when gently fixing a mistake.
            """.trimIndent()
        )
    }

    private val teacherModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = teacherTurnSchema
                temperature = 0.4f
            },
            systemInstruction = teacherSystemInstruction
        )
    }

    private val ttsModels = ConcurrentHashMap<String, GenerativeModel>()

    @OptIn(PublicPreviewAPI::class)
    private fun getTtsModel(preferredVoice: String): GenerativeModel {
        val voiceName = resolveGeminiVoiceName(preferredVoice)
        return ttsModels.getOrPut(voiceName) {
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = TTS_MODEL_NAME,
                generationConfig = generationConfig {
                    responseModalities = listOf(ResponseModality.AUDIO)
                    speechConfig = SpeechConfig(
                        voice = Voice(voiceName)
                    )
                }
            )
        }
    }

    private fun resolveGeminiVoiceName(preferredVoice: String): String = when (preferredVoice) {
        "Sulafat" -> "Sulafat"
        "Vindemiatrix" -> "Vindemiatrix"
        "Puck" -> "Puck"
        else -> DEFAULT_VOICE
    }

    private val drawOnWhiteboardDeclaration = FunctionDeclaration(
        name = "draw_on_whiteboard",
        description = "Write a handwritten equation step, short concept note, labeled diagram ('A -> B : note'), or graph ('y = 2x + 1') directly onto the student's live smartboard using the teacher's handwriting stylus.",
        parameters = mapOf(
            "type" to Schema.enumeration(
                values = listOf("WRITE_MATH", "WRITE_TEXT", "DRAW_DIAGRAM", "DRAW_GRAPH", "HIGHLIGHT", "CIRCLE", "CORRECT"),
                description = "Smartboard handwriting/drawing command type"
            ),
            "payload" to Schema.string(description = "Concise handwritten equation, 2-8 word step note, diagram spec, or graph equation"),
            "y" to Schema.double(description = "Normalized vertical slot between 0.08 and 0.84; increment by ~0.12 for each new step")
        ),
        optionalParameters = listOf("y")
    )

    @OptIn(PublicPreviewAPI::class)
    override fun createLiveVoiceModel(
        subject: String,
        level: String,
        preferredLanguage: String,
        preferredVoice: String
    ): LiveGenerativeModel {
        val voiceName = resolveGeminiVoiceName(preferredVoice)
        val liveConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            speechConfig = SpeechConfig(voice = Voice(voiceName))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
        }
        val liveSystemInstruction = content {
            text(
                """
                You are GURIVA, a warm, human personal teacher standing with a digital handwriting stylus at an interactive smartboard beside the student.
                Context: Subject = $subject, Level = $level, Preferred Language = $preferredLanguage.

                CRITICAL SMARTBOARD RULES:
                - Every word you speak is automatically typed onto the student's Smart Whiteboard word-by-word in real time as you speak.
                - Speak in clear, natural, everyday sentences—one simple step at a time, explaining WHY.
                - Call `draw_on_whiteboard` ONLY when a visual formula/equation (`WRITE_MATH`, e.g., "F = m × a"), diagram (`DRAW_DIAGRAM`, e.g., "Force -> Mass × Acceleration"), graph (`DRAW_GRAPH`), or key focus (`HIGHLIGHT` / `CIRCLE`) helps clarify. Do NOT duplicate ordinary spoken sentences with `WRITE_TEXT` tool calls.
                - Never say "I cannot write" or "I can only speak". Stack any `draw_on_whiteboard` visuals cleanly downward (`y` from 0.12 to 0.84) so they sit neatly alongside your live written explanation.
                - Match the student's spoken language (English, Hindi, Hinglish, Bhojpuri, Nepali, Maithili).
                """.trimIndent()
            )
        }
        return Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = LIVE_MODEL_NAME,
            generationConfig = liveConfig,
            tools = listOf(Tool.functionDeclarations(listOf(drawOnWhiteboardDeclaration))),
            systemInstruction = liveSystemInstruction
        )
    }

    override fun parseLiveBoardToolCall(
        call: FunctionCallPart,
        lessonId: String,
        stepId: String,
        existingBoardCount: Int,
        suggestedY: Float,
        existingEventIds: Set<String>,
        pageIndex: Int,
        pageTitle: String
    ): BoardEvent? {
        if (call.name != "draw_on_whiteboard") return null
        val typeStr = call.args["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val payload = call.args["payload"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (payload.isBlank()) return null

        val type = runCatching { BoardEventType.valueOf(typeStr) }.getOrDefault(BoardEventType.WRITE_TEXT)
        val requestedY = call.args["y"]?.jsonPrimitive?.doubleOrNull?.toFloat()
        val y = (requestedY ?: suggestedY).coerceIn(0.06f, 0.84f)
        val height = when (type) {
            BoardEventType.DRAW_DIAGRAM, BoardEventType.DRAW_GRAPH -> 0.24f
            else -> 0.095f
        }
        val candidate = BoardEvent(
            eventId = "live_${UUID.randomUUID().toString().take(8)}",
            lessonId = lessonId,
            stepId = stepId,
            sequence = existingBoardCount + 1,
            type = type,
            payload = payload,
            viewport = NormalizedRect(x = 0.06f, y = y, width = 0.88f, height = height),
            status = BoardEventStatus.QUEUED,
            pageIndex = pageIndex,
            pageTitle = pageTitle
        )
        return BoardEventValidator.validateBatch(listOf(candidate), existingEventIds).accepted.firstOrNull()
    }

    override suspend fun teachTurn(
        lessonId: String,
        stepId: String,
        subject: String,
        level: String,
        preferredLanguage: String,
        explanationPace: String,
        lessonSummary: String,
        recentTurns: List<DialogueTurn>,
        existingBoardCount: Int,
        existingEventIds: Set<String>,
        studentPrompt: String,
        handwritingBitmap: Bitmap?,
        strokeContextHint: String,
        photoBitmap: Bitmap?,
        audioWavBytes: ByteArray?,
        videoFrames: List<TimestampedVideoFrame>,
        isAlternativeApproach: Boolean,
        pageIndex: Int,
        pageTitle: String
    ): Result<TeacherTurnResult> {
        return try {
            val startingY = if (existingBoardCount == 0) 0.08f else (0.08f + (existingBoardCount % 6) * 0.12f).coerceIn(0.08f, 0.72f)
            val contextBlock = buildString {
                appendLine("Subject: $subject | Student Level: $level | Preferred Language: $preferredLanguage | Pace: $explanationPace")
                if (lessonSummary.isNotBlank()) {
                    appendLine("Prior Lesson Summary: $lessonSummary")
                }
                if (recentTurns.isNotEmpty()) {
                    appendLine("Recent Classroom Turns:")
                    recentTurns.takeLast(6).forEach { turn ->
                        appendLine("- ${turn.role}: ${turn.text}")
                    }
                }
                appendLine("Suggested starting normalized Y on teacher board: $startingY (avoid overlapping earlier items).")
                if (strokeContextHint.isNotBlank()) {
                    appendLine("Student Whiteboard Vector Context: $strokeContextHint")
                }
                if (isAlternativeApproach) {
                    appendLine("IMPORTANT: The student asked for a DIFFERENT explanation or example. Change your analogy, visual diagram, or step breakdown.")
                }
                appendLine("Student Input: $studentPrompt")
            }

            val inputContent = content {
                text(contextBlock)
                if (handwritingBitmap != null) {
                    text("Student handwriting board snapshot (inspect digits, symbols, equations carefully; never guess unclear writing):")
                    image(handwritingBitmap)
                }
                if (photoBitmap != null) {
                    text("Student shared photo/screenshot/screen-frame:")
                    image(photoBitmap)
                }
                if (audioWavBytes != null && audioWavBytes.isNotEmpty()) {
                    text("Student spoken voice audio (listen carefully to language, tone, and question):")
                    inlineData(audioWavBytes, "audio/wav")
                }
                if (videoFrames.isNotEmpty()) {
                    text("Student selected video sampled at key timestamps:")
                    videoFrames.forEach { frame ->
                        text("Video frame at ${frame.label}:")
                        image(frame.bitmap)
                    }
                }
            }

            val response = withTimeout(60_000L) {
                teacherModel.generateContent(inputContent)
            }
            val rawJson = response.text?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("GURIVA returned an empty response."))

            val parsed = parseTeacherTurn(
                rawJson = rawJson,
                lessonId = lessonId,
                stepId = stepId,
                startingY = startingY,
                existingEventIds = existingEventIds,
                fallbackSubject = subject,
                pageIndex = pageIndex,
                pageTitle = pageTitle
            )
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini request timed out", e)
            Result.failure(Exception("Teacher response timed out. Your board draft is saved—please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini teachTurn failed", e)
            Result.failure(e)
        }
    }

    override suspend fun synthesizeGeminiSpeech(
        text: String,
        language: String,
        preferredVoice: String
    ): Result<Pair<ByteArray, String>> {
        val speakable = text
            .replace(Regex("[*_`#]+"), " ")
            .replace("->", " to ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (speakable.isBlank()) {
            return Result.failure(IllegalArgumentException("Empty caption text for voice synthesis."))
        }
        return try {
            val langHint = if (language.equals("Auto", ignoreCase = true) || language.isBlank()) "" else " in $language"
            val cleanPrompt = "Speak warmly and naturally as a patient teacher$langHint: $speakable"
            val ttsModel = getTtsModel(preferredVoice)
            val response = withTimeout(30_000L) {
                ttsModel.generateContent(cleanPrompt)
            }
            val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
            val audioParts = parts.filterIsInstance<InlineDataPart>().filter {
                it.inlineData.isNotEmpty() &&
                    (it.mimeType.startsWith("audio/", ignoreCase = true) || it.mimeType.isBlank())
            }
            if (audioParts.isNotEmpty()) {
                val combined = if (audioParts.size == 1) {
                    audioParts.first().inlineData
                } else {
                    val out = java.io.ByteArrayOutputStream()
                    audioParts.forEach { out.write(it.inlineData) }
                    out.toByteArray()
                }
                val mime = audioParts.first().mimeType.ifBlank { "audio/pcm;rate=24000" }
                Result.success(combined to mime)
            } else {
                Result.failure(IllegalStateException("Gemini TTS returned no audio bytes."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini TTS timed out", e)
            Result.failure(Exception("Teacher voice timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini TTS audio synthesis failed", e)
            Result.failure(e)
        }
    }

    override suspend fun generateTopicQuiz(
        subject: String,
        topic: String,
        level: String,
        language: String
    ): Result<TeacherTurnResult> {
        return teachTurn(
            lessonId = "quiz_${System.currentTimeMillis()}",
            stepId = "quiz_step_1",
            subject = subject,
            level = level,
            preferredLanguage = language,
            explanationPace = "Concise",
            lessonSummary = "Generating a quick check-for-understanding question on $topic.",
            recentTurns = emptyList(),
            existingBoardCount = 0,
            existingEventIds = emptySet(),
            studentPrompt = "Create one clear check-for-understanding multiple-choice quiz question on '$topic' in $subject at $level level. Include quizQuestion, 4 short quizOptions, quizCorrectIndex, and 1 boardEvent showing the problem."
        )
    }

    private fun parseTeacherTurn(
        rawJson: String,
        lessonId: String,
        stepId: String,
        startingY: Float,
        existingEventIds: Set<String>,
        fallbackSubject: String,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): TeacherTurnResult {
        val obj = JSONObject(rawJson)
        val spoken = obj.optString("spokenExplanation", "").trim()
            .ifBlank { "Let's look at this step by step together." }
        val detectedLang = obj.optString("detectedLanguage", "English").ifBlank { "English" }
        val subject = obj.optString("subject", fallbackSubject).ifBlank { fallbackSubject }
        val topic = obj.optString("topic", subject).ifBlank { subject }
        val needsClarification = obj.optBoolean("needsClarification", false)
        val clarificationQ = obj.optString("clarificationQuestion", "").trim()

        val uncertainRegion = if (needsClarification && obj.has("uncertainX")) {
            NormalizedRect(
                x = obj.optDouble("uncertainX", 0.25).toFloat().coerceIn(0.05f, 0.85f),
                y = obj.optDouble("uncertainY", 0.25).toFloat().coerceIn(0.05f, 0.85f),
                width = obj.optDouble("uncertainWidth", 0.20).toFloat().coerceIn(0.08f, 0.6f),
                height = obj.optDouble("uncertainHeight", 0.14).toFloat().coerceIn(0.06f, 0.4f)
            )
        } else null

        val resolvedPageTitle = pageTitle.ifBlank { topic }
        val rawEvents = BoardEventParser.parseFromJsonArray(
            jsonArray = obj.optJSONArray("boardEvents"),
            lessonId = lessonId,
            stepId = stepId,
            startingY = startingY,
            pageIndex = pageIndex,
            pageTitle = resolvedPageTitle
        )
        val validated = BoardEventValidator.validateBatch(rawEvents, existingEventIds)
        val finalEvents = if (validated.accepted.isNotEmpty()) {
            validated.accepted
        } else {
            val fallback = BoardEventParser.buildLocalFallbackEvents(
                explanation = if (needsClarification && clarificationQ.isNotBlank()) clarificationQ else spoken,
                lessonId = lessonId,
                stepId = stepId,
                startingY = startingY,
                pageIndex = pageIndex,
                pageTitle = resolvedPageTitle
            )
            BoardEventValidator.validateBatch(fallback, existingEventIds).accepted
        }

        val formulas = mutableListOf<String>()
        obj.optJSONArray("formulasExtracted")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optString(i).trim()
                if (f.isNotBlank()) formulas += f
            }
        }

        val quizOpts = mutableListOf<String>()
        obj.optJSONArray("quizOptions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optString(i).trim()
                if (opt.isNotBlank()) quizOpts += opt
            }
        }

        return TeacherTurnResult(
            spokenExplanation = if (needsClarification && clarificationQ.isNotBlank()) clarificationQ else spoken,
            detectedLanguage = detectedLang,
            subject = subject,
            topic = topic,
            needsClarification = needsClarification,
            clarificationQuestion = clarificationQ,
            uncertainRegion = uncertainRegion,
            boardEvents = finalEvents,
            formulasExtracted = formulas,
            lessonSummary = obj.optString("lessonSummary", "").trim(),
            currentStep = obj.optString("currentStep", topic).trim(),
            checkQuestion = obj.optString("checkQuestion", "").trim(),
            strengthNote = obj.optString("strengthNote", "").trim(),
            weakAreaNote = obj.optString("weakAreaNote", "").trim(),
            quizQuestion = obj.optString("quizQuestion", "").trim(),
            quizOptions = quizOpts,
            quizCorrectIndex = obj.optInt("quizCorrectIndex", 0).coerceIn(0, (quizOpts.size - 1).coerceAtLeast(0))
        )
    }
}

/**
 * Fallback sample implementation used ONLY when Firebase AI Logic has not been provisioned yet
 * (for example, after canceling the Firebase setup dialog on a remixed project).
 * Keeps zero API keys in the APK and demonstrates synchronized voice + handwriting safely.
 */
class SamplePreviewAiRepository : AiTeacherGateway {

    override val isLiveCloudReady: Boolean = false

    @OptIn(PublicPreviewAPI::class)
    override fun createLiveVoiceModel(
        subject: String,
        level: String,
        preferredLanguage: String,
        preferredVoice: String
    ): LiveGenerativeModel? = null

    override fun parseLiveBoardToolCall(
        call: FunctionCallPart,
        lessonId: String,
        stepId: String,
        existingBoardCount: Int,
        suggestedY: Float,
        existingEventIds: Set<String>,
        pageIndex: Int,
        pageTitle: String
    ): BoardEvent? = null

    override suspend fun teachTurn(
        lessonId: String,
        stepId: String,
        subject: String,
        level: String,
        preferredLanguage: String,
        explanationPace: String,
        lessonSummary: String,
        recentTurns: List<DialogueTurn>,
        existingBoardCount: Int,
        existingEventIds: Set<String>,
        studentPrompt: String,
        handwritingBitmap: Bitmap?,
        strokeContextHint: String,
        photoBitmap: Bitmap?,
        audioWavBytes: ByteArray?,
        videoFrames: List<TimestampedVideoFrame>,
        isAlternativeApproach: Boolean,
        pageIndex: Int,
        pageTitle: String
    ): Result<TeacherTurnResult> {
        val resolvedTitle = pageTitle.ifBlank { subject }
        val sampleLines = listOf(
            BoardEvent(
                eventId = "${stepId}_sample_1",
                lessonId = lessonId,
                stepId = stepId,
                sequence = 1,
                type = BoardEventType.WRITE_TEXT,
                payload = "Sample preview · $subject",
                viewport = NormalizedRect(0.06f, 0.08f, 0.88f, 0.09f),
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = resolvedTitle
            ),
            BoardEvent(
                eventId = "${stepId}_sample_2",
                lessonId = lessonId,
                stepId = stepId,
                sequence = 2,
                type = BoardEventType.WRITE_MATH,
                payload = "2x + 4 = 10 -> x = 3",
                viewport = NormalizedRect(0.06f, 0.21f, 0.88f, 0.10f),
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = resolvedTitle
            ),
            BoardEvent(
                eventId = "${stepId}_sample_3",
                lessonId = lessonId,
                stepId = stepId,
                sequence = 3,
                type = BoardEventType.HIGHLIGHT,
                payload = "Connect Firebase AI Logic for live Gemini replies",
                viewport = NormalizedRect(0.06f, 0.35f, 0.88f, 0.10f),
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = resolvedTitle
            )
        )
        val validated = BoardEventValidator.validateBatch(sampleLines, existingEventIds).accepted
        return Result.success(
            TeacherTurnResult(
                spokenExplanation = "Sample board preview for your question. Enable Firebase AI Logic to unlock live Gemini voice and real-time solving without storing any API key.",
                detectedLanguage = if (preferredLanguage == "Auto") "English" else preferredLanguage,
                subject = subject,
                topic = resolvedTitle,
                needsClarification = false,
                clarificationQuestion = "",
                uncertainRegion = null,
                boardEvents = validated,
                formulasExtracted = listOf("2x + 4 = 10"),
                lessonSummary = "Sample smartboard preview (Firebase AI Logic required for live AI).",
                currentStep = "Sample Preview",
                checkQuestion = "",
                strengthNote = "",
                weakAreaNote = "",
                quizQuestion = "",
                quizOptions = emptyList(),
                quizCorrectIndex = 0
            )
        )
    }

    override suspend fun synthesizeGeminiSpeech(
        text: String,
        language: String,
        preferredVoice: String
    ): Result<Pair<ByteArray, String>> {
        return Result.failure(IllegalStateException("Firebase AI Logic setup required for Gemini TTS"))
    }

    override suspend fun generateTopicQuiz(
        subject: String,
        topic: String,
        level: String,
        language: String
    ): Result<TeacherTurnResult> {
        return Result.success(
            TeacherTurnResult(
                spokenExplanation = "Sample practice check for $topic.",
                detectedLanguage = if (language == "Auto") "English" else language,
                subject = subject,
                topic = topic,
                needsClarification = false,
                clarificationQuestion = "",
                uncertainRegion = null,
                boardEvents = emptyList(),
                formulasExtracted = emptyList(),
                lessonSummary = "Sample quiz preview.",
                currentStep = topic,
                checkQuestion = "",
                strengthNote = "",
                weakAreaNote = "",
                quizQuestion = "Sample check (Firebase AI Logic required for live quizzes): If 2x + 4 = 10, what is x?",
                quizOptions = listOf("2", "3", "4", "6"),
                quizCorrectIndex = 1
            )
        )
    }
}
