package com.example.feature.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.TopicProgress
import com.example.feature.home.ActivePracticeQuiz
import com.example.feature.home.MainAppViewModel
import com.example.feature.home.UiState

@Composable
fun ProgressScreen(
    viewModel: MainAppViewModel,
    modifier: Modifier = Modifier
) {
    val progressUi by viewModel.progressState.collectAsStateWithLifecycle()
    val profileUi by viewModel.profileState.collectAsStateWithLifecycle()
    val activeQuiz by viewModel.practiceQuiz.collectAsStateWithLifecycle()
    val isGeneratingQuiz by viewModel.isGeneratingQuiz.collectAsStateWithLifecycle()

    val profile = (profileUi as? UiState.Success)?.data
    val level = profile?.difficultyLevel ?: "Beginner"
    val lang = profile?.preferredLanguage ?: "English"

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when (val state = progressUi) {
            is UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is UiState.Success -> {
                val topics = state.data
                LazyColumn(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text("Progress", style = MaterialTheme.typography.headlineMedium)
                    }

                    activeQuiz?.let { quiz ->
                        item {
                            PracticeQuizCard(
                                quiz = quiz,
                                onSelectOption = { viewModel.answerPracticeQuiz(it) },
                                onDismiss = { viewModel.dismissPracticeQuiz() }
                            )
                        }
                    }

                    if (topics.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Outlined.EmojiEvents,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text("No progress yet", style = MaterialTheme.typography.titleMedium)
                                Button(
                                    onClick = { viewModel.startPracticeQuiz("Maths", "Linear Equations", level, lang) },
                                    enabled = !isGeneratingQuiz,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.testTag("start_sample_quiz_button")
                                ) {
                                    if (isGeneratingQuiz) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Outlined.Quiz, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Quick Maths Quiz")
                                    }
                                }
                            }
                        }
                    } else {
                        item {
                            val avgMastery = topics.map { it.masteryPercent }.average().toInt()
                            val totalCorrect = topics.sumOf { it.quizzesCorrect }
                            val totalChecks = topics.sumOf { it.quizzesCompleted }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SummaryMetric("$avgMastery%", "Mastery", MaterialTheme.colorScheme.primary)
                                    SummaryMetric("$totalCorrect/$totalChecks", "Checks", MaterialTheme.colorScheme.tertiary)
                                    SummaryMetric("${topics.size}", "Topics", MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                        items(topics, key = { it.id }) { topic ->
                            TopicProgressCard(
                                progress = topic,
                                isGeneratingQuiz = isGeneratingQuiz,
                                onQuizTopic = { viewModel.startPracticeQuiz(topic.subject, topic.topic, level, lang) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    value: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, color = accent)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PracticeQuizCard(
    quiz: ActivePracticeQuiz,
    onSelectOption: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${quiz.subject} · ${quiz.topic}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close quiz", modifier = Modifier.size(18.dp))
                }
            }
            Text(quiz.question, style = MaterialTheme.typography.titleMedium)
            quiz.options.forEachIndexed { idx, opt ->
                val isSelected = quiz.selectedIndex == idx
                OutlinedButton(
                    onClick = { onSelectOption(idx) },
                    enabled = quiz.selectedIndex == null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(text = opt, style = MaterialTheme.typography.bodyMedium)
                }
            }
            quiz.feedback?.let { fb ->
                Text(
                    text = fb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TopicProgressCard(
    progress: TopicProgress,
    isGeneratingQuiz: Boolean,
    onQuizTopic: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(progress.subject, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(progress.topic, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "${progress.masteryPercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { (progress.masteryPercent.toFloat() / 100f).coerceIn(0.04f, 1f) },
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${progress.quizzesCorrect}/${progress.quizzesCompleted} checks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onQuizTopic,
                    enabled = !isGeneratingQuiz,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Quiz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Practice", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
