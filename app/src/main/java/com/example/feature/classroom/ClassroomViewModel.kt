package com.example.feature.classroom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.audio.LiveDuplexAudioEngine
import com.example.core.audio.TeacherAudioPlayer
import com.example.core.audio.VoiceCaptureManager
import com.example.core.media.MediaProcessingHelper
import com.example.data.remote.AiTeacherGateway
import com.example.data.remote.GurivaAiRepository
import com.example.data.repository.GurivaLocalRepository
import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.BoardPage
import com.example.domain.model.DialogueTurn
import com.example.domain.model.GurivaSubjects
import com.example.domain.model.NormalizedRect
import com.example.domain.model.TeacherStatus
import com.example.domain.model.TeacherTurnResult
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

data class ClassroomUiState(
    val lessonId: String? = null,
    val lessonTitle: String = "New Lesson",
    val subject: String = "Maths",
    val level: String = "Beginner",
    val language: String = "Auto",
    val pace: String = "Balanced",
    val voiceEnabled: Boolean = true,
    val preferredVoice: String = GurivaAiRepository.DEFAULT_VOICE,
    val isLiveCloudReady: Boolean = true,
    val isLiveVoiceConnected: Boolean = false,
    val isLiveVoiceConnecting: Boolean = false,
    val teacherStatus: TeacherStatus = TeacherStatus.IDLE,
    val fullCaption: String = "",
    val revealedCaption: String = "",
    val pages: List<BoardPage> = listOf(BoardPage(pageIndex = 0, title = "Page 1")),
    val currentPageIndex: Int = 0,
    val teacherEvents: List<BoardEvent> = emptyList(),
    val activeTeacherEventId: String? = null,
    val activeTeacherProgress: Float = 1f,
    val uncertainRegion: NormalizedRect? = null,
    val clarificationPrompt: String? = null,
    val boardScale: Float = 1f,
    val boardOffsetX: Float = 0f,
    val boardOffsetY: Float = 0f,
    val equationScale: Float = 1f,
    val fullScreenBoard: Boolean = false,
    val textInputDraft: String = "",
    val showTextDock: Boolean = false,
    val pendingCropBitmap: Bitmap? = null,
    val recentTurns: List<DialogueTurn> = emptyList(),
    val formulas: List<String> = emptyList(),
    val lessonSummary: String = "",
    val currentStep: String = "",
    val activeQuizQuestion: String? = null,
    val activeQuizOptions: List<String> = emptyList(),
    val activeQuizCorrectIndex: Int = 0,
    val activeQuizFeedback: String? = null,
    val isSaving: Boolean = false,
    val noticeMessage: String? = null,
    val errorMessage: String? = null
) {
    val currentPage: BoardPage
        get() = pages.getOrElse(currentPageIndex) { pages.firstOrNull() ?: BoardPage(pageIndex = 0, title = "Page 1") }
}

class ClassroomViewModel(
    private val app: Application,
    private val localRepo: GurivaLocalRepository,
    private val aiRepo: AiTeacherGateway,
    initialLessonId: String?,
    initialSubject: String,
    initialLevel: String
) : ViewModel() {

    companion object {
        private const val TAG = "ClassroomVM"
    }

    val voiceCapture = VoiceCaptureManager()
    private val audioPlayer = TeacherAudioPlayer(app)

    private val _uiState = MutableStateFlow(
        ClassroomUiState(
            lessonId = initialLessonId,
            subject = initialSubject,
            level = initialLevel,
            isLiveCloudReady = aiRepo.isLiveCloudReady
        )
    )
    val uiState: StateFlow<ClassroomUiState> = _uiState.asStateFlow()

    private val _liveMicAmplitude = MutableStateFlow(0f)
    val isRecordingVoice: StateFlow<Boolean> = voiceCapture.isRecording
    val micAmplitude: StateFlow<Float> = _liveMicAmplitude.asStateFlow()
    val isTeacherSpeaking: StateFlow<Boolean> = audioPlayer.isPlaying

    private var turnJob: Job? = null
    private var boardAnimJob: Job? = null
    private var captionJob: Job? = null
    private var speechJob: Job? = null
    private var liveSessionJob: Job? = null
    @OptIn(PublicPreviewAPI::class)
    private var activeLiveSession: com.google.firebase.ai.type.LiveSession? = null
    private var activeLiveAudioEngine: LiveDuplexAudioEngine? = null
    private val liveWordProcessor = LiveWordStreamProcessor()
    private val liveResponseIdCounter = AtomicLong(1L)
    private val liveMutationMutex = Mutex()
    private var activeLiveBlockEventId: String? = null
    private var stepCounter = 0
    private var liveSessionToken = 0L
    private var hasAppliedInitialProfile = false

    init {
        viewModelScope.launch {
            localRepo.observeProfile().collect { profile ->
                if (profile != null) {
                    val applyVoice = !hasAppliedInitialProfile
                    hasAppliedInitialProfile = true
                    _uiState.update {
                        it.copy(
                            language = profile.preferredLanguage,
                            pace = profile.explanationPace,
                            voiceEnabled = if (applyVoice) profile.voiceEnabled else it.voiceEnabled,
                            preferredVoice = GurivaSubjects.normalizeVoiceSelection(profile.preferredVoice),
                            level = if (initialLessonId.isNullOrBlank()) profile.difficultyLevel else it.level
                        )
                    }
                }
            }
        }
        if (!initialLessonId.isNullOrBlank()) {
            loadExistingLesson(initialLessonId)
        } else {
            _uiState.update {
                it.copy(
                    revealedCaption = "",
                    teacherStatus = TeacherStatus.YOUR_TURN,
                    noticeMessage = if (!aiRepo.isLiveCloudReady) {
                        "Preview mode: Firebase AI Logic is needed for live Gemini answers (no API key stored in app)."
                    } else null
                )
            }
        }
    }

    private fun loadExistingLesson(lessonId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(teacherStatus = TeacherStatus.UNDERSTANDING) }
            val result = localRepo.getLesson(lessonId)
            result.onSuccess { lesson ->
                if (lesson != null) {
                    val events = lesson.boardEvents
                    val turns = lesson.recentTurns
                    val restoredPages = if (events.isEmpty()) {
                        listOf(BoardPage(pageIndex = 0, title = "Page 1"))
                    } else {
                        val maxIdx = events.maxOf { it.pageIndex }.coerceAtLeast(0)
                        val grouped = events.groupBy { it.pageIndex }
                        (0..maxIdx).map { idx ->
                            val pageEvents = grouped[idx].orEmpty()
                            val derivedTitle = pageEvents.firstOrNull { it.pageTitle.isNotBlank() }?.pageTitle
                                ?: "Page ${idx + 1}"
                            BoardPage(
                                pageIndex = idx,
                                title = derivedTitle,
                                events = pageEvents
                            )
                        }
                    }
                    val activeIdx = restoredPages.lastIndex.coerceAtLeast(0)
                    _uiState.update {
                        it.copy(
                            lessonId = lesson.id,
                            lessonTitle = lesson.title,
                            subject = lesson.subject,
                            level = lesson.level,
                            language = lesson.language,
                            lessonSummary = lesson.summary,
                            currentStep = lesson.currentStep,
                            formulas = lesson.formulas,
                            pages = restoredPages,
                            currentPageIndex = activeIdx,
                            teacherEvents = restoredPages[activeIdx].events,
                            recentTurns = turns,
                            revealedCaption = lesson.summary.take(200),
                            teacherStatus = TeacherStatus.YOUR_TURN
                        )
                    }
                } else {
                    _uiState.update { it.copy(teacherStatus = TeacherStatus.YOUR_TURN) }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        teacherStatus = TeacherStatus.YOUR_TURN,
                        errorMessage = err.localizedMessage ?: "Failed to load saved lesson."
                    )
                }
            }
        }
    }

    fun selectPage(index: Int) {
        _uiState.update { state ->
            val clamped = index.coerceIn(0, state.pages.lastIndex.coerceAtLeast(0))
            val targetPage = state.pages.getOrElse(clamped) { state.currentPage }
            state.copy(
                currentPageIndex = clamped,
                teacherEvents = targetPage.events,
                uncertainRegion = targetPage.uncertainRegion,
                revealedCaption = if (targetPage.caption.isNotBlank()) targetPage.caption else state.revealedCaption,
                boardScale = 1f,
                boardOffsetX = 0f,
                boardOffsetY = 0f
            )
        }
    }

    fun openBlankPage() {
        _uiState.update { state ->
            val last = state.pages.lastOrNull()
            if (last != null && last.events.isEmpty()) {
                return@update state.copy(
                    currentPageIndex = last.pageIndex,
                    teacherEvents = emptyList(),
                    uncertainRegion = null,
                    boardScale = 1f,
                    boardOffsetX = 0f,
                    boardOffsetY = 0f
                )
            }
            val nextIdx = state.pages.size
            val newPage = BoardPage(pageIndex = nextIdx, title = "Page ${nextIdx + 1}")
            state.copy(
                pages = state.pages + newPage,
                currentPageIndex = nextIdx,
                teacherEvents = emptyList(),
                uncertainRegion = null,
                boardScale = 1f,
                boardOffsetX = 0f,
                boardOffsetY = 0f
            )
        }
    }

    private fun preparePageForCommand(commandPrompt: String, title: String): Int {
        var targetPageIdx = 0
        _uiState.update { state ->
            val lastPage = state.pages.lastOrNull()
            if (lastPage != null && lastPage.events.isEmpty()) {
                val updatedLast = lastPage.copy(
                    title = title.ifBlank { "Page ${lastPage.pageIndex + 1}" },
                    commandPrompt = commandPrompt
                )
                targetPageIdx = updatedLast.pageIndex
                state.copy(
                    pages = state.pages.dropLast(1) + updatedLast,
                    currentPageIndex = targetPageIdx,
                    teacherEvents = emptyList(),
                    uncertainRegion = null,
                    boardScale = 1f,
                    boardOffsetX = 0f,
                    boardOffsetY = 0f
                )
            } else {
                val newIdx = state.pages.size
                val newPage = BoardPage(
                    pageIndex = newIdx,
                    title = title.ifBlank { "Page ${newIdx + 1}" },
                    commandPrompt = commandPrompt
                )
                targetPageIdx = newIdx
                state.copy(
                    pages = state.pages + newPage,
                    currentPageIndex = newIdx,
                    teacherEvents = emptyList(),
                    uncertainRegion = null,
                    boardScale = 1f,
                    boardOffsetX = 0f,
                    boardOffsetY = 0f
                )
            }
        }
        return targetPageIdx
    }

    private fun updatePageEvents(
        pageIndex: Int,
        transform: (List<BoardEvent>) -> List<BoardEvent>
    ) {
        _uiState.update { state ->
            val updatedPages = state.pages.map { page ->
                if (page.pageIndex == pageIndex) {
                    page.copy(events = transform(page.events))
                } else {
                    page
                }
            }
            val activeEvents = updatedPages.getOrNull(state.currentPageIndex)?.events ?: emptyList()
            state.copy(
                pages = updatedPages,
                teacherEvents = activeEvents
            )
        }
    }

    private fun updatePageMetadata(
        pageIndex: Int,
        caption: String? = null,
        uncertainRegion: NormalizedRect? = null,
        title: String? = null
    ) {
        _uiState.update { state ->
            val updatedPages = state.pages.map { page ->
                if (page.pageIndex == pageIndex) {
                    page.copy(
                        caption = caption ?: page.caption,
                        uncertainRegion = uncertainRegion ?: page.uncertainRegion,
                        title = if (!title.isNullOrBlank() && page.title.startsWith("Page ")) title else page.title
                    )
                } else {
                    page
                }
            }
            val curPage = updatedPages.getOrNull(state.currentPageIndex)
            state.copy(
                pages = updatedPages,
                uncertainRegion = curPage?.uncertainRegion
            )
        }
    }

    /**
     * Preserves all already-displayed spoken words (promoting RENDERING -> COMPLETE)
     * while discarding only unstarted QUEUED events.
     */
    private fun finalizeDisplayedLiveEventsAndDiscardQueued() {
        activeLiveBlockEventId = null
        _uiState.update { state ->
            val cleanedPages = state.pages.map { page ->
                page.copy(
                    events = page.events.mapNotNull { ev ->
                        when (ev.status) {
                            BoardEventStatus.COMPLETE -> ev
                            BoardEventStatus.RENDERING -> if (ev.payload.isNotBlank()) ev.copy(status = BoardEventStatus.COMPLETE) else null
                            BoardEventStatus.QUEUED, BoardEventStatus.CANCELLED -> null
                        }
                    }
                )
            }
            val activeEvents = cleanedPages.getOrNull(state.currentPageIndex)?.events ?: emptyList()
            state.copy(
                pages = cleanedPages,
                teacherEvents = activeEvents,
                activeTeacherEventId = null,
                activeTeacherProgress = 1f
            )
        }
    }

    private fun advanceLiveResponseGeneration(): Long {
        val nextResponseId = liveResponseIdCounter.incrementAndGet()
        activeLiveAudioEngine?.interruptPlayback(nextResponseId)
        liveWordProcessor.startNewResponse(nextResponseId)
        return nextResponseId
    }

    private fun advanceLiveResponseTurnGracefully(): Long {
        val nextResponseId = liveResponseIdCounter.incrementAndGet()
        activeLiveAudioEngine?.advanceTurnGracefully(nextResponseId)
        liveWordProcessor.startNewResponse(nextResponseId)
        return nextResponseId
    }

    fun interruptTeacher() {
        turnJob?.cancel()
        boardAnimJob?.cancel()
        captionJob?.cancel()
        speechJob?.cancel()
        audioPlayer.stopImmediately()

        if (_uiState.value.isLiveVoiceConnected || _uiState.value.isLiveVoiceConnecting) {
            advanceLiveResponseGeneration()
        }
        finalizeDisplayedLiveEventsAndDiscardQueued()
        _uiState.update {
            it.copy(
                activeTeacherEventId = null,
                activeTeacherProgress = 1f,
                teacherStatus = if (it.isLiveVoiceConnected) TeacherStatus.LISTENING else TeacherStatus.YOUR_TURN
            )
        }
    }

    fun togglePause() {
        val current = _uiState.value.teacherStatus
        if (current == TeacherStatus.PAUSED) {
            _uiState.update { it.copy(teacherStatus = TeacherStatus.YOUR_TURN) }
        } else {
            if (_uiState.value.isLiveVoiceConnected || _uiState.value.isLiveVoiceConnecting) {
                stopLiveVoiceSession()
            } else {
                interruptTeacher()
            }
            _uiState.update { it.copy(teacherStatus = TeacherStatus.PAUSED) }
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(PublicPreviewAPI::class)
    fun startLiveVoiceSession() {
        if (_uiState.value.isLiveVoiceConnected || _uiState.value.isLiveVoiceConnecting) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.update { it.copy(errorMessage = "Microphone permission is required for live voice.") }
            return
        }

        val snapshot = _uiState.value
        val activeVoice = GurivaSubjects.normalizeVoiceSelection(snapshot.preferredVoice)
        val liveModel = aiRepo.createLiveVoiceModel(
            subject = snapshot.subject,
            level = snapshot.level,
            preferredLanguage = snapshot.language,
            preferredVoice = activeVoice
        )

        if (liveModel == null) {
            val targetPageIdx = preparePageForCommand("Voice preview", "Voice Preview")
            dispatchTeacherTurn(
                studentDisplayText = "[Live Voice]",
                prompt = "Demonstrate a step-by-step ${snapshot.subject} solution in real time.",
                pageTitle = "Voice Preview",
                targetPageIndex = targetPageIdx
            )
            return
        }

        interruptTeacher()
        val myToken = ++liveSessionToken
        val stepId = "live_${++stepCounter}"
        val effectiveLessonId = snapshot.lessonId ?: "draft_${System.currentTimeMillis()}"

        _uiState.update {
            it.copy(
                isLiveVoiceConnecting = true,
                isLiveVoiceConnected = false,
                voiceEnabled = true,
                preferredVoice = activeVoice,
                errorMessage = null,
                noticeMessage = null,
                teacherStatus = TeacherStatus.UNDERSTANDING,
                revealedCaption = "Connecting..."
            )
        }

        liveSessionJob = viewModelScope.launch {
            val animChannel = Channel<BoardEvent>(Channel.UNLIMITED)
            val animConsumerJob = launch {
                for (ev in animChannel) {
                    if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) break
                    animateSingleBoardEvent(ev, liveMode = true)
                }
            }
            val audioEngine = LiveDuplexAudioEngine()
            activeLiveAudioEngine = audioEngine

            try {
                val session = withTimeout(20_000L) {
                    liveModel.connect()
                }
                if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) {
                    runCatching { session.close() }
                    return@launch
                }
                activeLiveSession = session

                val startResponseId = advanceLiveResponseGeneration()
                activeLiveBlockEventId = null

                audioEngine.startPlaybackLoop(this)
                audioEngine.startMicCapture(
                    scope = this,
                    onPcmChunk = { pcmBytes ->
                        if (myToken == liveSessionToken && _uiState.value.voiceEnabled && !session.isClosed()) {
                            runCatching {
                                session.sendAudioRealtime(InlineData(pcmBytes, "audio/pcm"))
                            }
                        }
                    },
                    onAmplitude = { amp ->
                        _liveMicAmplitude.value = amp
                    }
                )

                _uiState.update {
                    it.copy(
                        isLiveVoiceConnecting = false,
                        isLiveVoiceConnected = true,
                        voiceEnabled = true,
                        teacherStatus = TeacherStatus.LISTENING,
                        revealedCaption = "Listening..."
                    )
                }

                val liveInputBuffer = StringBuilder()
                val cancelledToolCallIds = mutableSetOf<String>()
                var activeOutputPageIdx = _uiState.value.currentPageIndex

                fun finalizeCurrentLiveBlockInState(
                    nextStatus: TeacherStatus,
                    recordTurnText: String? = null
                ) {
                    val closingId = activeLiveBlockEventId
                    activeLiveBlockEventId = null
                    _uiState.update { state ->
                        val updatedPages = if (closingId != null) {
                            state.pages.map { page ->
                                page.copy(
                                    events = page.events.map { ev ->
                                        if (ev.eventId == closingId && ev.status == BoardEventStatus.RENDERING) {
                                            ev.copy(status = BoardEventStatus.COMPLETE)
                                        } else ev
                                    }
                                )
                            }
                        } else {
                            state.pages
                        }
                        val activeEvents = updatedPages.getOrNull(state.currentPageIndex)?.events ?: emptyList()
                        val trimmedTurn = recordTurnText?.trim().orEmpty()
                        val updatedTurns = if (trimmedTurn.isNotBlank() && state.recentTurns.lastOrNull()?.text != trimmedTurn) {
                            state.recentTurns + DialogueTurn("teacher", trimmedTurn, state.language)
                        } else {
                            state.recentTurns
                        }
                        state.copy(
                            pages = updatedPages,
                            teacherEvents = activeEvents,
                            activeTeacherEventId = if (state.activeTeacherEventId == closingId) null else state.activeTeacherEventId,
                            activeTeacherProgress = if (state.activeTeacherEventId == closingId) 1f else state.activeTeacherProgress,
                            teacherStatus = nextStatus,
                            recentTurns = updatedTurns
                        )
                    }
                }

                fun applyWordUpdateToBoard(
                    update: WordStreamUpdate,
                    pageIndex: Int
                ) {
                    if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) return
                    val currentState = _uiState.value
                    val safePageIdx = pageIndex.coerceIn(0, currentState.pages.lastIndex.coerceAtLeast(0))
                    val targetPage = currentState.pages[safePageIdx]

                    // 1. Seal completed long block if paragraph/sentence split occurred
                    val sealedText = update.sealedPrefixText
                    val closingId = if (!sealedText.isNullOrBlank()) activeLiveBlockEventId else null
                    if (closingId != null) {
                        activeLiveBlockEventId = null
                    }

                    // 2. Determine or create active live RENDERING BoardEvent
                    val existingBlockId = activeLiveBlockEventId
                    val targetEventId = existingBlockId ?: "live_w_${update.responseId}_${++stepCounter}"
                    if (existingBlockId == null && update.currentBlockText.isNotBlank()) {
                        activeLiveBlockEventId = targetEventId
                    }

                    val existingCount = targetPage.events.size
                    val updatedType = if (LiveWordStreamProcessor.looksLikeMathText(update.currentBlockText)) {
                        BoardEventType.WRITE_MATH
                    } else {
                        BoardEventType.WRITE_TEXT
                    }

                    _uiState.update { state ->
                        val updatedPages = state.pages.map { page ->
                            if (page.pageIndex != safePageIdx) {
                                page
                            } else {
                                var events = page.events
                                if (closingId != null && !sealedText.isNullOrBlank()) {
                                    events = events.map { ev ->
                                        if (ev.eventId == closingId) {
                                            ev.copy(payload = sealedText, status = BoardEventStatus.COMPLETE)
                                        } else ev
                                    }
                                }
                                if (update.currentBlockText.isNotBlank()) {
                                    val idx = events.indexOfFirst { it.eventId == targetEventId }
                                    if (idx >= 0) {
                                        events = events.toMutableList().apply {
                                            this[idx] = this[idx].copy(
                                                payload = update.currentBlockText,
                                                type = updatedType,
                                                status = BoardEventStatus.RENDERING
                                            )
                                        }
                                    } else {
                                        val suggestedY = nextSuggestedBoardY(events)
                                        val newEv = BoardEvent(
                                            eventId = targetEventId,
                                            lessonId = effectiveLessonId,
                                            stepId = stepId,
                                            sequence = existingCount + 1,
                                            type = updatedType,
                                            payload = update.currentBlockText,
                                            viewport = NormalizedRect(x = 0.06f, y = suggestedY, width = 0.88f, height = 0.09f),
                                            transcriptAnchor = update.currentBlockText.take(120),
                                            status = BoardEventStatus.RENDERING,
                                            pageIndex = safePageIdx,
                                            pageTitle = page.title
                                        )
                                        events = events + newEv
                                    }
                                }
                                page.copy(
                                    events = events,
                                    caption = update.fullResponseText.takeLast(260)
                                )
                            }
                        }
                        val activeEvents = updatedPages.getOrNull(state.currentPageIndex)?.events ?: emptyList()
                        state.copy(
                            pages = updatedPages,
                            teacherEvents = activeEvents,
                            activeTeacherEventId = activeLiveBlockEventId,
                            activeTeacherProgress = 1f,
                            teacherStatus = TeacherStatus.WRITING,
                            fullCaption = update.fullResponseText,
                            revealedCaption = update.fullResponseText.takeLast(260),
                            currentStep = update.currentBlockText.take(60)
                        )
                    }
                }

                // Track initial responseId cleanly
                liveWordProcessor.startNewResponse(startResponseId)

                session.receive().collect { message ->
                    if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) return@collect

                    when (message) {
                        is LiveServerContent -> {
                            var completedTurnToSave = false
                            liveMutationMutex.withLock {
                                if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) return@withLock

                                if (message.interrupted) {
                                    val spokenBeforeInterrupt = liveWordProcessor.currentFullText()
                                    val nextId = advanceLiveResponseGeneration()
                                    liveInputBuffer.clear()
                                    finalizeCurrentLiveBlockInState(
                                        nextStatus = TeacherStatus.LISTENING,
                                        recordTurnText = spokenBeforeInterrupt
                                    )
                                    Log.d(TAG, "Gemini Live interrupted -> advanced responseId=$nextId, preserved words=${spokenBeforeInterrupt.length}")
                                    return@withLock
                                }

                                val turnRespId = liveResponseIdCounter.get()

                                val inText = message.inputTranscription?.text.orEmpty()
                                if (inText.isNotEmpty()) {
                                    val prevIn = liveInputBuffer.toString()
                                    val reconciledIn = LiveWordStreamProcessor.reconcileIncomingTranscription(prevIn, inText)
                                    if (reconciledIn != null) {
                                        liveInputBuffer.clear()
                                        liveInputBuffer.append(reconciledIn)
                                        val cleanIn = LiveWordStreamProcessor.normalizeDisplayText(reconciledIn)
                                        if (cleanIn.isNotBlank() && activeLiveBlockEventId == null) {
                                            _uiState.update {
                                                it.copy(
                                                    teacherStatus = TeacherStatus.LISTENING,
                                                    revealedCaption = "You: ${cleanIn.takeLast(220)}"
                                                )
                                            }
                                        }
                                    }
                                }

                                val outText = message.outputTranscription?.text.orEmpty()
                                if (outText.isNotEmpty()) {
                                    liveInputBuffer.clear()
                                    if (activeLiveBlockEventId == null) {
                                        activeOutputPageIdx = _uiState.value.currentPageIndex
                                    }
                                    val update = liveWordProcessor.processChunk(turnRespId, outText)
                                    if (update != null && update.hasChange) {
                                        applyWordUpdateToBoard(update, activeOutputPageIdx)
                                    }
                                }

                                val audioParts = message.content?.parts
                                    ?.filterIsInstance<InlineDataPart>()
                                    ?.filter {
                                        it.inlineData.isNotEmpty() &&
                                            (it.mimeType.startsWith("audio/", ignoreCase = true) || it.mimeType.isBlank())
                                    }
                                    .orEmpty()
                                for (part in audioParts) {
                                    audioEngine.enqueuePlaybackAudio(turnRespId, part.inlineData)
                                }

                                if (message.turnComplete) {
                                    val finishedText = liveWordProcessor.currentFullText()
                                    advanceLiveResponseTurnGracefully()
                                    liveInputBuffer.clear()
                                    finalizeCurrentLiveBlockInState(
                                        nextStatus = TeacherStatus.LISTENING,
                                        recordTurnText = finishedText
                                    )
                                    completedTurnToSave = finishedText.isNotBlank()
                                }
                            }
                            if (completedTurnToSave) {
                                saveCurrentLesson(silent = true)
                            }
                        }

                        is LiveServerToolCall -> {
                            val responses = mutableListOf<FunctionResponsePart>()
                            val eventsToAnimate = mutableListOf<BoardEvent>()
                            liveMutationMutex.withLock {
                                if (myToken != liveSessionToken || !_uiState.value.voiceEnabled) return@withLock
                                finalizeCurrentLiveBlockInState(nextStatus = TeacherStatus.WRITING)
                                activeOutputPageIdx = _uiState.value.currentPageIndex

                                for (call in message.functionCalls) {
                                    if (call.id != null && cancelledToolCallIds.remove(call.id)) {
                                        responses += FunctionResponsePart(
                                            name = call.name,
                                            response = JsonObject(mapOf("status" to JsonPrimitive("cancelled"))),
                                            id = call.id
                                        )
                                        continue
                                    }
                                    val currentState = _uiState.value
                                    val safeIdx = activeOutputPageIdx.coerceIn(0, currentState.pages.lastIndex.coerceAtLeast(0))
                                    val pageEvents = currentState.pages[safeIdx].events
                                    val pageTitle = currentState.pages[safeIdx].title
                                    val existingIds = pageEvents.map { it.eventId }.toSet()
                                    val suggestedY = nextSuggestedBoardY(pageEvents)
                                    val boardEvent = aiRepo.parseLiveBoardToolCall(
                                        call = call,
                                        lessonId = effectiveLessonId,
                                        stepId = stepId,
                                        existingBoardCount = pageEvents.size,
                                        suggestedY = suggestedY,
                                        existingEventIds = existingIds,
                                        pageIndex = safeIdx,
                                        pageTitle = pageTitle
                                    )
                                    if (boardEvent != null) {
                                        updatePageEvents(safeIdx) { it + boardEvent }
                                        eventsToAnimate += boardEvent
                                    }
                                    responses += FunctionResponsePart(
                                        name = call.name,
                                        response = JsonObject(
                                            mapOf("status" to JsonPrimitive(if (boardEvent != null) "drawn" else "ignored"))
                                        ),
                                        id = call.id
                                    )
                                }
                            }
                            eventsToAnimate.forEach { animChannel.trySend(it) }
                            if (responses.isNotEmpty() && !session.isClosed()) {
                                runCatching { session.sendFunctionResponse(responses) }
                            }
                        }

                        is LiveServerToolCallCancellation -> {
                            liveMutationMutex.withLock {
                                cancelledToolCallIds.addAll(message.functionIds)
                            }
                        }

                        is LiveServerGoAway -> {
                            _uiState.update {
                                it.copy(noticeMessage = "Live session paused. Tap Live to reconnect.")
                            }
                            stopLiveVoiceSession()
                            return@collect
                        }

                        else -> Unit
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini Live connection timed out", e)
                _uiState.update {
                    it.copy(
                        isLiveVoiceConnecting = false,
                        isLiveVoiceConnected = false,
                        teacherStatus = TeacherStatus.YOUR_TURN,
                        errorMessage = "Live voice timed out. Please try again."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Live session error", e)
                _uiState.update {
                    it.copy(
                        isLiveVoiceConnecting = false,
                        isLiveVoiceConnected = false,
                        teacherStatus = TeacherStatus.YOUR_TURN,
                        errorMessage = e.localizedMessage ?: "Live voice connection failed."
                    )
                }
            } finally {
                _liveMicAmplitude.value = 0f
                if (activeLiveAudioEngine === audioEngine) {
                    activeLiveAudioEngine = null
                }
                animChannel.close()
                animConsumerJob.cancel()
                withTimeoutOrNull(1000L) {
                    audioEngine.release()
                }
            }
        }
    }

    @OptIn(PublicPreviewAPI::class)
    fun stopLiveVoiceSession() {
        liveSessionToken++
        val spokenBeforeStop = liveWordProcessor.currentFullText()
        advanceLiveResponseGeneration()
        liveSessionJob?.cancel()
        liveSessionJob = null
        boardAnimJob?.cancel()
        captionJob?.cancel()
        _liveMicAmplitude.value = 0f

        val session = activeLiveSession
        activeLiveSession = null
        val engine = activeLiveAudioEngine
        activeLiveAudioEngine = null

        runCatching { session?.stopReceiving() }
        viewModelScope.launch(NonCancellable) {
            runCatching { engine?.release() }
            runCatching { session?.close() }
        }

        val finalCaption = spokenBeforeStop.ifBlank { _uiState.value.fullCaption.trim() }
        finalizeDisplayedLiveEventsAndDiscardQueued()
        _uiState.update { state ->
            val updatedTurns = if (finalCaption.isNotBlank() && state.recentTurns.lastOrNull()?.text != finalCaption) {
                state.recentTurns + DialogueTurn("teacher", finalCaption, state.language)
            } else {
                state.recentTurns
            }
            state.copy(
                isLiveVoiceConnecting = false,
                isLiveVoiceConnected = false,
                activeTeacherEventId = null,
                activeTeacherProgress = 1f,
                teacherStatus = TeacherStatus.YOUR_TURN,
                recentTurns = updatedTurns
            )
        }
        saveCurrentLesson(silent = true)
    }

    @OptIn(PublicPreviewAPI::class)
    fun submitTextQuestion(rawText: String? = null) {
        val text = (rawText ?: _uiState.value.textInputDraft).trim()
        if (text.isBlank()) return
        val state = _uiState.value
        val liveSession = activeLiveSession

        if (state.isLiveVoiceConnected && state.voiceEnabled && liveSession != null && !liveSession.isClosed()) {
            // Interrupt pending audio/response immediately while preserving all already displayed words
            advanceLiveResponseGeneration()
            finalizeDisplayedLiveEventsAndDiscardQueued()
            _uiState.update {
                it.copy(
                    textInputDraft = "",
                    showTextDock = false,
                    errorMessage = null,
                    teacherStatus = TeacherStatus.UNDERSTANDING,
                    recentTurns = it.recentTurns + DialogueTurn("student", text, it.language)
                )
            }
            val targetPageIdx = _uiState.value.currentPageIndex
            viewModelScope.launch {
                runCatching {
                    liveSession.send(
                        content {
                            text("Student asked: $text. Explain clearly in simple steps as I display every spoken word live on the board. Call draw_on_whiteboard only for key equations or diagrams.")
                        }
                    )
                }.onFailure {
                    dispatchTeacherTurn(
                        studentDisplayText = text,
                        prompt = text,
                        pageTitle = text.take(36),
                        targetPageIndex = targetPageIdx
                    )
                }
            }
            return
        }

        interruptTeacher()
        _uiState.update { it.copy(textInputDraft = "", showTextDock = false, errorMessage = null) }
        val targetPageIdx = preparePageForCommand(
            commandPrompt = text,
            title = text.take(36)
        )

        dispatchTeacherTurn(
            studentDisplayText = text,
            prompt = text,
            pageTitle = text.take(36),
            targetPageIndex = targetPageIdx
        )
    }

    fun onImagePickedForCrop(uri: Uri) {
        viewModelScope.launch {
            val decoded = MediaProcessingHelper.decodeDownscaledBitmap(app, uri)
            decoded.onSuccess { bmp ->
                _uiState.update { it.copy(pendingCropBitmap = bmp, errorMessage = null) }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.localizedMessage ?: "Failed to load image.") }
            }
        }
    }

    fun dismissCropDialog() {
        _uiState.update { it.copy(pendingCropBitmap = null) }
    }

    fun submitCroppedPhoto(bitmap: Bitmap) {
        _uiState.update { it.copy(pendingCropBitmap = null) }
        interruptTeacher()
        val targetPageIdx = preparePageForCommand(
            commandPrompt = "Shared photo question",
            title = "Photo"
        )
        val scaled = MediaProcessingHelper.scaleBitmapMaxDim(bitmap, 1280)
        dispatchTeacherTurn(
            studentDisplayText = "[Shared photo]",
            prompt = "Inspect the student's uploaded question/screenshot carefully. If any symbol or number is blurry or ambiguous, ask before solving. Otherwise explain the concept and guide the first step on this new smartboard page.",
            photoBitmap = scaled,
            pageTitle = "Photo",
            targetPageIndex = targetPageIdx
        )
    }

    fun analyzeSharedScreenFrame(frame: Bitmap?) {
        if (frame == null) {
            _uiState.update { it.copy(noticeMessage = "Waiting for screen frame...") }
            return
        }
        interruptTeacher()
        val targetPageIdx = preparePageForCommand(
            commandPrompt = "Explain shared screen",
            title = "Screen"
        )
        dispatchTeacherTurn(
            studentDisplayText = "[Shared screen]",
            prompt = "Inspect the student's shared Android screen frame. Identify the visible question or topic and explain it step-by-step on this new smartboard page.",
            photoBitmap = MediaProcessingHelper.scaleBitmapMaxDim(frame, 1024),
            pageTitle = "Screen",
            targetPageIndex = targetPageIdx
        )
    }

    fun submitSelectedVideo(uri: Uri) {
        interruptTeacher()
        val targetPageIdx = preparePageForCommand(
            commandPrompt = "Video explanation",
            title = "Video"
        )
        _uiState.update {
            it.copy(
                teacherStatus = TeacherStatus.UNDERSTANDING,
                revealedCaption = "Reading video...",
                errorMessage = null
            )
        }
        viewModelScope.launch {
            val extracted = MediaProcessingHelper.extractVideoFrames(app, uri)
            extracted.onSuccess { payload ->
                dispatchTeacherTurn(
                    studentDisplayText = "[Video (${payload.frames.size} frames)]",
                    prompt = "Inspect these timestamped video frames from the student's video (${payload.durationMs / 1000}s total). Reference timestamps (e.g. at 0:12) to explain what is happening and create structured whiteboard notes on this new page.",
                    videoFrames = payload.frames,
                    pageTitle = "Video",
                    targetPageIndex = targetPageIdx
                )
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        teacherStatus = TeacherStatus.YOUR_TURN,
                        errorMessage = err.localizedMessage ?: "Unable to read video."
                    )
                }
            }
        }
    }

    fun explainDifferently() {
        val lastStudentMsg = _uiState.value.recentTurns.lastOrNull { it.role == "student" }?.text
            ?: "Please explain the current concept with a simpler analogy and diagram."
        interruptTeacher()
        val targetPageIdx = preparePageForCommand(
            commandPrompt = "Re-teach: $lastStudentMsg",
            title = "Re-teach"
        )
        dispatchTeacherTurn(
            studentDisplayText = "Explain another way",
            prompt = "The student did not fully follow the previous explanation for: '$lastStudentMsg'. Teach it on this new smartboard page using a completely different everyday analogy or visual diagram.",
            isAlternativeApproach = true,
            pageTitle = "Re-teach",
            targetPageIndex = targetPageIdx
        )
    }

    fun answerQuizOption(selectedIndex: Int) {
        val state = _uiState.value
        val question = state.activeQuizQuestion ?: return
        val isCorrect = selectedIndex == state.activeQuizCorrectIndex
        val feedback = if (isCorrect) {
            "Right!"
        } else {
            val rightText = state.activeQuizOptions.getOrNull(state.activeQuizCorrectIndex).orEmpty()
            "Answer: $rightText"
        }
        _uiState.update { it.copy(activeQuizFeedback = feedback) }

        viewModelScope.launch {
            localRepo.upsertTopicProgress(
                subject = state.subject,
                topic = state.currentStep.ifBlank { state.subject },
                masteryDelta = if (isCorrect) 12L else 3L,
                quizCompletedDelta = 1L,
                quizCorrectDelta = if (isCorrect) 1L else 0L,
                strengthNote = if (isCorrect) "Solved check question accurately" else "",
                weakAreaNote = if (!isCorrect) "Review: $question" else "",
                lastQuizQuestion = question
            )
        }
    }

    fun dismissQuiz() {
        _uiState.update {
            it.copy(
                activeQuizQuestion = null,
                activeQuizOptions = emptyList(),
                activeQuizFeedback = null
            )
        }
    }

    private fun dispatchTeacherTurn(
        studentDisplayText: String,
        prompt: String,
        photoBitmap: Bitmap? = null,
        audioWavBytes: ByteArray? = null,
        videoFrames: List<com.example.core.media.TimestampedVideoFrame> = emptyList(),
        isAlternativeApproach: Boolean = false,
        pageTitle: String = "",
        targetPageIndex: Int = _uiState.value.currentPageIndex
    ) {
        turnJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val stepId = "step_${++stepCounter}"
            val effectiveLessonId = snapshot.lessonId ?: "draft_${System.currentTimeMillis()}"
            val pageEvents = snapshot.pages.getOrNull(targetPageIndex)?.events ?: emptyList()
            val existingIds = snapshot.pages.flatMap { it.events }.map { it.eventId }.toSet()

            _uiState.update {
                it.copy(
                    teacherStatus = TeacherStatus.UNDERSTANDING,
                    uncertainRegion = null,
                    clarificationPrompt = null,
                    errorMessage = null,
                    noticeMessage = null,
                    recentTurns = it.recentTurns + DialogueTurn("student", studentDisplayText, it.language)
                )
            }

            val result = aiRepo.teachTurn(
                lessonId = effectiveLessonId,
                stepId = stepId,
                subject = snapshot.subject,
                level = snapshot.level,
                preferredLanguage = snapshot.language,
                explanationPace = snapshot.pace,
                lessonSummary = snapshot.lessonSummary,
                recentTurns = _uiState.value.recentTurns,
                existingBoardCount = pageEvents.size,
                existingEventIds = existingIds,
                studentPrompt = prompt,
                photoBitmap = photoBitmap,
                audioWavBytes = audioWavBytes,
                videoFrames = videoFrames,
                isAlternativeApproach = isAlternativeApproach,
                pageIndex = targetPageIndex,
                pageTitle = pageTitle
            )

            result.onSuccess { turn ->
                applyTeacherTurnResult(turn, targetPageIndex)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Connection interrupted."
                val isQuota = msg.contains("429") || msg.contains("quota", ignoreCase = true) || msg.contains("exhausted", ignoreCase = true)
                _uiState.update {
                    it.copy(
                        teacherStatus = if (isQuota) TeacherStatus.QUOTA_BUSY else TeacherStatus.YOUR_TURN,
                        errorMessage = if (isQuota) {
                            "Teacher is momentarily busy — please try again."
                        } else {
                            msg
                        }
                    )
                }
            }
        }
    }

    private fun applyTeacherTurnResult(turn: TeacherTurnResult, targetPageIndex: Int) {
        val mergedFormulas = (_uiState.value.formulas + turn.formulasExtracted).distinct()
        val updatedTitle = if (_uiState.value.lessonTitle == "New Lesson" && turn.topic.isNotBlank()) {
            turn.topic.take(80)
        } else {
            _uiState.value.lessonTitle
        }

        _uiState.update { state ->
            state.copy(
                lessonTitle = updatedTitle,
                subject = turn.subject.ifBlank { state.subject },
                language = if (state.language == "Auto") turn.detectedLanguage else state.language,
                fullCaption = turn.spokenExplanation,
                revealedCaption = if (state.voiceEnabled) "" else turn.spokenExplanation,
                uncertainRegion = turn.uncertainRegion,
                clarificationPrompt = if (turn.needsClarification) turn.clarificationQuestion else null,
                formulas = mergedFormulas,
                lessonSummary = turn.lessonSummary.ifBlank { state.lessonSummary },
                currentStep = turn.currentStep.ifBlank { state.currentStep },
                recentTurns = state.recentTurns + DialogueTurn("teacher", turn.spokenExplanation, turn.detectedLanguage),
                activeQuizQuestion = turn.quizQuestion.takeIf { it.isNotBlank() && turn.quizOptions.size >= 2 },
                activeQuizOptions = turn.quizOptions,
                activeQuizCorrectIndex = turn.quizCorrectIndex,
                activeQuizFeedback = null,
                teacherStatus = if (state.voiceEnabled) TeacherStatus.UNDERSTANDING else TeacherStatus.YOUR_TURN
            )
        }
        updatePageMetadata(
            pageIndex = targetPageIndex,
            caption = turn.spokenExplanation,
            uncertainRegion = turn.uncertainRegion,
            title = turn.topic.take(36)
        )

        if (!_uiState.value.voiceEnabled) {
            val completedEvents = turn.boardEvents.map { it.copy(status = BoardEventStatus.COMPLETE) }
            updatePageEvents(targetPageIndex) { it + completedEvents }
            _uiState.update {
                it.copy(
                    revealedCaption = turn.spokenExplanation,
                    teacherStatus = TeacherStatus.YOUR_TURN
                )
            }
            persistTurnResults(turn)
            return
        }

        // Voice ON: synchronize spoken voice + live caption + smartboard stylus in real time
        speechJob?.cancel()
        boardAnimJob?.cancel()
        captionJob?.cancel()

        val chosenVoice = _uiState.value.preferredVoice
        speechJob = viewModelScope.launch {
            val ttsResult = aiRepo.synthesizeGeminiSpeech(
                text = turn.spokenExplanation,
                language = turn.detectedLanguage,
                preferredVoice = chosenVoice
            )

            if (!isActive || !_uiState.value.voiceEnabled) {
                _uiState.update { it.copy(teacherStatus = TeacherStatus.YOUR_TURN) }
                return@launch
            }

            if (ttsResult.isSuccess) {
                val (audioBytes, mimeType) = ttsResult.getOrThrow()
                val audioDurationMs = TeacherAudioPlayer.estimateAudioDurationMs(audioBytes, mimeType)
                val audioStarted = CompletableDeferred<Unit>()

                updatePageEvents(targetPageIndex) { it + turn.boardEvents }
                _uiState.update { it.copy(teacherStatus = TeacherStatus.EXPLAINING, revealedCaption = "") }

                val audioPlayJob = launch {
                    audioPlayer.playAudioBytes(
                        audioBytes = audioBytes,
                        mimeType = mimeType,
                        onPlaybackStarted = { audioStarted.complete(Unit) }
                    )
                }

                withTimeoutOrNull(500L) { audioStarted.await() }
                launchSynchronizedCaptionAndBoard(turn, audioDurationMs)
                audioPlayJob.join()
            } else {
                // Use on-device TTS fallback so voice + smartboard writing remain simultaneous in real time
                if (!_uiState.value.voiceEnabled) return@launch
                val words = turn.spokenExplanation.split(" ").filter { it.isNotEmpty() }
                val estimatedDurationMs = (words.size * 340L).coerceIn(1400L, 14000L)
                val speechStarted = CompletableDeferred<Unit>()

                updatePageEvents(targetPageIndex) { it + turn.boardEvents }
                _uiState.update {
                    it.copy(
                        teacherStatus = TeacherStatus.EXPLAINING,
                        revealedCaption = "",
                        noticeMessage = if (!aiRepo.isLiveCloudReady) {
                            "Sample board preview · Enable Firebase AI Logic for live Gemini"
                        } else null
                    )
                }

                val deviceTtsJob = launch {
                    audioPlayer.speakWithDeviceTtsFallback(
                        text = turn.spokenExplanation,
                        language = turn.detectedLanguage,
                        onPlaybackStarted = { speechStarted.complete(Unit) }
                    )
                }

                withTimeoutOrNull(700L) { speechStarted.await() }
                launchSynchronizedCaptionAndBoard(turn, estimatedDurationMs)
                deviceTtsJob.join()
            }
        }

        persistTurnResults(turn)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchSynchronizedCaptionAndBoard(
        turn: TeacherTurnResult,
        totalDurationMs: Long
    ) {
        captionJob = launch {
            val words = turn.spokenExplanation.split(" ").filter { it.isNotEmpty() }
            val wordDelayMs = if (words.isEmpty()) 45L else (totalDurationMs / words.size).coerceIn(35L, 260L)
            val builder = StringBuilder()
            for (word in words) {
                if (!isActive || !_uiState.value.voiceEnabled) break
                if (builder.isNotEmpty()) builder.append(' ')
                builder.append(word)
                _uiState.update { it.copy(revealedCaption = builder.toString()) }
                delay(wordDelayMs)
            }
        }

        boardAnimJob = launch {
            val events = turn.boardEvents
            if (events.isNotEmpty()) {
                val totalChars = events.sumOf { it.payload.length.coerceAtLeast(8) }.coerceAtLeast(1)
                val budgetMs = totalDurationMs.coerceAtLeast(900L)
                for (ev in events) {
                    if (!isActive || !_uiState.value.voiceEnabled) break
                    val share = (ev.payload.length.coerceAtLeast(8).toFloat() / totalChars.toFloat())
                    val evDuration = (budgetMs * share).toLong().coerceIn(350L, 2400L)
                    animateSingleBoardEvent(ev, overrideDurationMs = evDuration)
                }
            }
            if (isActive && _uiState.value.voiceEnabled) {
                _uiState.update {
                    if (it.teacherStatus == TeacherStatus.WRITING || it.teacherStatus == TeacherStatus.EXPLAINING) {
                        it.copy(teacherStatus = TeacherStatus.YOUR_TURN)
                    } else it
                }
            }
        }
    }

    private fun persistTurnResults(turn: TeacherTurnResult) {
        viewModelScope.launch {
            saveCurrentLesson(silent = true)
            if (turn.strengthNote.isNotBlank() || turn.weakAreaNote.isNotBlank()) {
                localRepo.upsertTopicProgress(
                    subject = turn.subject,
                    topic = turn.topic,
                    masteryDelta = 6L,
                    quizCompletedDelta = 0L,
                    quizCorrectDelta = 0L,
                    strengthNote = turn.strengthNote,
                    weakAreaNote = turn.weakAreaNote,
                    lastQuizQuestion = turn.checkQuestion
                )
            }
        }
    }

    fun saveCurrentLesson(silent: Boolean = false) {
        val state = _uiState.value
        val allCompletedEvents = state.pages
            .flatMap { it.events }
            .filter { it.status == BoardEventStatus.COMPLETE || it.status == BoardEventStatus.RENDERING }
            .map { if (it.status == BoardEventStatus.RENDERING) it.copy(status = BoardEventStatus.COMPLETE) else it }
        viewModelScope.launch {
            if (!silent) _uiState.update { it.copy(isSaving = true) }
            val result = localRepo.createOrSaveLesson(
                existingLessonId = state.lessonId,
                title = state.lessonTitle,
                subject = state.subject,
                level = state.level,
                language = state.language,
                status = if (state.teacherStatus == TeacherStatus.PAUSED) "paused" else "active",
                summary = state.lessonSummary.ifBlank { state.revealedCaption.take(300) },
                currentStep = state.currentStep,
                formulas = state.formulas,
                unresolvedQuestions = listOfNotNull(state.clarificationPrompt),
                boardEvents = allCompletedEvents,
                recentTurns = state.recentTurns
            )
            result.onSuccess { savedId ->
                _uiState.update {
                    it.copy(
                        lessonId = savedId,
                        isSaving = false,
                        noticeMessage = if (!silent) "Saved." else it.noticeMessage
                    )
                }
            }.onFailure { err ->
                Log.w(TAG, "Lesson save failed", err)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = if (!silent) err.localizedMessage ?: "Failed to save lesson." else it.errorMessage
                    )
                }
            }
        }
    }

    fun clearTeacherBoard() {
        interruptTeacher()
        _uiState.update {
            it.copy(
                pages = listOf(BoardPage(pageIndex = 0, title = "Page 1")),
                currentPageIndex = 0,
                teacherEvents = emptyList(),
                uncertainRegion = null,
                clarificationPrompt = null
            )
        }
    }

    fun onBoardTransformChanged(scale: Float, offsetX: Float, offsetY: Float) {
        _uiState.update {
            it.copy(boardScale = scale, boardOffsetX = offsetX, boardOffsetY = offsetY)
        }
    }

    fun resetBoardZoom() {
        _uiState.update {
            it.copy(boardScale = 1f, boardOffsetX = 0f, boardOffsetY = 0f)
        }
    }

    fun fitBoardToScreen() {
        _uiState.update {
            it.copy(boardScale = 0.95f, boardOffsetX = 0f, boardOffsetY = 0f)
        }
    }

    fun cycleEquationScale() {
        _uiState.update {
            val next = when {
                it.equationScale < 1.15f -> 1.25f
                it.equationScale < 1.40f -> 1.45f
                else -> 1.0f
            }
            it.copy(equationScale = next)
        }
    }

    fun toggleFullScreenBoard() {
        _uiState.update { it.copy(fullScreenBoard = !it.fullScreenBoard) }
    }

    fun toggleTextDock() {
        _uiState.update { it.copy(showTextDock = !it.showTextDock) }
    }

    fun updateTextDraft(text: String) {
        _uiState.update { it.copy(textInputDraft = text) }
    }

    fun toggleVoiceOutput() {
        val next = !_uiState.value.voiceEnabled
        if (!next) {
            _uiState.update { it.copy(voiceEnabled = false) }
            liveSessionToken++
            turnJob?.cancel()
            speechJob?.cancel()
            boardAnimJob?.cancel()
            captionJob?.cancel()
            audioPlayer.stopImmediately()
            if (_uiState.value.isLiveVoiceConnected || _uiState.value.isLiveVoiceConnecting) {
                stopLiveVoiceSession()
            } else {
                finalizeDisplayedLiveEventsAndDiscardQueued()
            }
            _uiState.update {
                it.copy(
                    voiceEnabled = false,
                    teacherStatus = TeacherStatus.YOUR_TURN
                )
            }
        } else {
            _uiState.update {
                it.copy(voiceEnabled = true)
            }
        }
    }

    fun clearNotices() {
        _uiState.update { it.copy(noticeMessage = null, errorMessage = null) }
    }

    private suspend fun animateSingleBoardEvent(
        ev: BoardEvent,
        liveMode: Boolean = false,
        overrideDurationMs: Long? = null
    ) {
        if (!_uiState.value.voiceEnabled) return
        _uiState.update {
            it.copy(
                teacherStatus = TeacherStatus.WRITING,
                activeTeacherEventId = ev.eventId,
                activeTeacherProgress = 0.04f
            )
        }
        val durationMs = overrideDurationMs ?: if (liveMode) {
            (140L + ev.payload.length * 5L).coerceIn(140L, 320L)
        } else {
            (320L + ev.payload.length * 12L).coerceIn(360L, 880L)
        }
        val frameMs = 16L
        val steps = (durationMs / frameMs).toInt().coerceAtLeast(10)
        for (s in 1..steps) {
            delay(frameMs)
            if (!_uiState.value.voiceEnabled) return
            val t = s.toFloat() / steps.toFloat()
            val eased = t * t * (3f - 2f * t)
            _uiState.update { it.copy(activeTeacherProgress = eased.coerceIn(0.04f, 1f)) }
        }
        if (!_uiState.value.voiceEnabled) return

        _uiState.update { state ->
            val updatedPages = state.pages.map { page ->
                if (page.pageIndex == ev.pageIndex) {
                    page.copy(
                        events = page.events.map {
                            if (it.eventId == ev.eventId) it.copy(status = BoardEventStatus.COMPLETE) else it
                        }
                    )
                } else {
                    page
                }
            }
            val activeEvents = updatedPages.getOrNull(state.currentPageIndex)?.events ?: emptyList()
            state.copy(
                pages = updatedPages,
                teacherEvents = activeEvents,
                activeTeacherEventId = null,
                activeTeacherProgress = 1f
            )
        }
    }

    private fun nextSuggestedBoardY(existingEvents: List<BoardEvent>): Float {
        if (existingEvents.isEmpty()) return 0.06f
        val last = existingEvents.last()
        return (last.viewport.y + last.viewport.height + 0.025f).coerceIn(0.06f, 0.90f)
    }

    @OptIn(PublicPreviewAPI::class)
    override fun onCleared() {
        super.onCleared()
        liveSessionToken++
        advanceLiveResponseGeneration()
        liveSessionJob?.cancel()
        runCatching { activeLiveSession?.stopReceiving() }
        activeLiveSession = null
        activeLiveAudioEngine = null
        voiceCapture.cancelCapture()
        audioPlayer.stopImmediately()
    }
}
