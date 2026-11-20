package com.example.feature.lessons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.LessonRecord
import com.example.feature.home.MainAppViewModel
import com.example.feature.home.UiState

@Composable
fun LessonsScreen(
    viewModel: MainAppViewModel,
    onResumeLesson: (lessonId: String, subject: String, level: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lessonsUi by viewModel.lessonsState.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf("All") }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when (val state = lessonsUi) {
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
                val lessons = state.data
                if (lessons.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No lessons yet",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                } else {
                    val subjectFilters = remember(lessons) {
                        listOf("All") + lessons.map { it.subject }.distinct()
                    }
                    if (selectedFilter !in subjectFilters) selectedFilter = "All"
                    val filteredLessons = remember(lessons, selectedFilter) {
                        if (selectedFilter == "All") lessons else lessons.filter { it.subject == selectedFilter }
                    }

                    LazyColumn(
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text("Lessons", style = MaterialTheme.typography.headlineMedium)
                        }
                        if (subjectFilters.size > 2) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(subjectFilters) { subj ->
                                        FilterChip(
                                            selected = selectedFilter == subj,
                                            onClick = { selectedFilter = subj },
                                            label = { Text(subj, style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }
                        }
                        items(filteredLessons, key = { it.id }) { lesson ->
                            LessonHistoryCard(
                                lesson = lesson,
                                onResume = { onResumeLesson(lesson.id, lesson.subject, lesson.level) },
                                onDelete = { viewModel.deleteLesson(lesson.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonHistoryCard(
    lesson: LessonRecord,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onResume)
            .testTag("resume_lesson_${lesson.id}")
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${lesson.subject} · ${lesson.boardEvents.size} notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_lesson_${lesson.id}")
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete lesson",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
