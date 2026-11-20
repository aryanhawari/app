package com.example.feature.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.AiTeacherGateway
import com.example.data.repository.GurivaLocalRepository
import com.example.domain.model.LessonRecord
import com.example.domain.model.TopicProgress
import com.example.domain.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ActivePracticeQuiz(
    val subject: String,
    val topic: String,
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val selectedIndex: Int? = null,
    val feedback: String? = null
)

class MainAppViewModel(
    private val localRepo: GurivaLocalRepository,
    private val aiRepo: AiTeacherGateway
) : ViewModel() {

    companion object {
        private const val TAG = "MainAppVM"
        private const val STOP_TIMEOUT_MS = 5000L
    }

    val isLiveCloudReady: Boolean = aiRepo.isLiveCloudReady

    init {
        viewModelScope.launch {
            localRepo.ensureProfileExists()
        }
    }

    val profileState: StateFlow<UiState<UserProfile?>> = localRepo.observeProfile()
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Profile stream error", error)
            emit(UiState.Error(error.message ?: "Unable to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val lessonsState: StateFlow<UiState<List<LessonRecord>>> = localRepo.observeLessons()
        .map<List<LessonRecord>, UiState<List<LessonRecord>>> { UiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Lessons stream error", error)
            emit(UiState.Error(error.message ?: "Unable to load lessons"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val progressState: StateFlow<UiState<List<TopicProgress>>> = localRepo.observeProgress()
        .map<List<TopicProgress>, UiState<List<TopicProgress>>> { UiState.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Progress stream error", error)
            emit(UiState.Error(error.message ?: "Unable to load progress"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    private val _practiceQuiz = MutableStateFlow<ActivePracticeQuiz?>(null)
    val practiceQuiz: StateFlow<ActivePracticeQuiz?> = _practiceQuiz.asStateFlow()

    private val _isGeneratingQuiz = MutableStateFlow(false)
    val isGeneratingQuiz: StateFlow<Boolean> = _isGeneratingQuiz.asStateFlow()

    private val _actionNotice = MutableStateFlow<String?>(null)
    val actionNotice: StateFlow<String?> = _actionNotice.asStateFlow()

    fun deleteLesson(lessonId: String) {
        viewModelScope.launch {
            val res = localRepo.deleteLesson(lessonId)
            _actionNotice.value = if (res.isSuccess) "Lesson deleted." else res.exceptionOrNull()?.localizedMessage ?: "Delete failed."
        }
    }

    fun updatePreferences(
        preferredLanguage: String,
        explanationPace: String,
        difficultyLevel: String,
        voiceEnabled: Boolean,
        preferredVoice: String,
        displayName: String
    ) {
        viewModelScope.launch {
            val res = localRepo.updateProfilePreferences(
                preferredLanguage = preferredLanguage,
                explanationPace = explanationPace,
                difficultyLevel = difficultyLevel,
                voiceEnabled = voiceEnabled,
                preferredVoice = preferredVoice,
                displayName = displayName
            )
            _actionNotice.value = if (res.isSuccess) "Saved." else res.exceptionOrNull()?.localizedMessage ?: "Save failed."
        }
    }

    fun deleteAllSavedData() {
        viewModelScope.launch {
            val res = localRepo.deleteAllUserData()
            _actionNotice.value = if (res.isSuccess) {
                "Local lessons and progress cleared."
            } else {
                res.exceptionOrNull()?.localizedMessage ?: "Failed to clear data."
            }
        }
    }

    fun startPracticeQuiz(subject: String, topic: String, level: String, language: String) {
        if (_isGeneratingQuiz.value) return
        viewModelScope.launch {
            _isGeneratingQuiz.value = true
            val res = aiRepo.generateTopicQuiz(subject, topic, level, language)
            _isGeneratingQuiz.value = false
            res.onSuccess { turn ->
                val q = turn.quizQuestion.ifBlank { turn.checkQuestion.ifBlank { turn.spokenExplanation } }
                val opts = if (turn.quizOptions.size >= 2) turn.quizOptions else listOf("True", "False")
                _practiceQuiz.value = ActivePracticeQuiz(
                    subject = subject,
                    topic = topic,
                    question = q,
                    options = opts,
                    correctIndex = turn.quizCorrectIndex.coerceIn(0, opts.lastIndex)
                )
            }.onFailure { err ->
                _actionNotice.value = err.localizedMessage ?: "Could not create quiz right now."
            }
        }
    }

    fun answerPracticeQuiz(selectedIdx: Int) {
        val quiz = _practiceQuiz.value ?: return
        if (quiz.selectedIndex != null) return
        val correct = selectedIdx == quiz.correctIndex
        val fb = if (correct) {
            "Right answer!"
        } else {
            "Answer: ${quiz.options.getOrNull(quiz.correctIndex).orEmpty()}"
        }
        _practiceQuiz.update { it?.copy(selectedIndex = selectedIdx, feedback = fb) }

        viewModelScope.launch {
            localRepo.upsertTopicProgress(
                subject = quiz.subject,
                topic = quiz.topic,
                masteryDelta = if (correct) 10L else 2L,
                quizCompletedDelta = 1L,
                quizCorrectDelta = if (correct) 1L else 0L,
                strengthNote = if (correct) "Strong in ${quiz.topic}" else "",
                weakAreaNote = if (!correct) "Review ${quiz.topic}" else "",
                lastQuizQuestion = quiz.question
            )
        }
    }

    fun dismissPracticeQuiz() {
        _practiceQuiz.value = null
    }

    fun clearNotice() {
        _actionNotice.value = null
    }
}
