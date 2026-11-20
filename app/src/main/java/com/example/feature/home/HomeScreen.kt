package com.example.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.GurivaSubjects
import com.example.domain.model.LessonRecord
import com.example.ui.theme.BoardAmberFocus
import com.example.ui.theme.BoardPaperDark
import com.example.ui.theme.BoardTeacherInkDark
import com.example.ui.theme.KalamHandwritingFontFamily

@Composable
fun HomeScreen(
    viewModel: MainAppViewModel,
    onStartNewLesson: (subject: String, level: String) -> Unit,
    onResumeLesson: (lessonId: String, subject: String, level: String) -> Unit,
    onViewAllLessons: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profileUi by viewModel.profileState.collectAsStateWithLifecycle()
    val lessonsUi by viewModel.lessonsState.collectAsStateWithLifecycle()

    val profile = (profileUi as? UiState.Success)?.data
    val lessons = (lessonsUi as? UiState.Success)?.data.orEmpty()

    var selectedSubject by remember { mutableStateOf("Maths") }
    val defaultLevel = profile?.difficultyLevel ?: "Beginner"
    val activeVoice = GurivaSubjects.normalizeVoiceSelection(profile?.preferredVoice)
    val firstName = profile?.displayName?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Student"
    val initial = firstName.first().uppercaseChar().toString()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Minimal Editorial Greeting
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "GURIVA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Hello, $firstName",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Focused Obsidian Smartboard Launchpad
            item {
                Surface(
                    color = BoardPaperDark,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val step = 42f
                            var x = step
                            while (x < size.width) {
                                var y = step
                                while (y < size.height) {
                                    drawCircle(
                                        color = BoardTeacherInkDark.copy(alpha = 0.07f),
                                        radius = 1.25f,
                                        center = Offset(x, y)
                                    )
                                    y += step
                                }
                                x += step
                            }
                        }

                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(BoardAmberFocus)
                                    )
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = BoardTeacherInkDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = activeVoice,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BoardTeacherInkDark
                                    )
                                }
                                Text(
                                    text = "a² + b² = c²",
                                    fontFamily = KalamHandwritingFontFamily,
                                    fontSize = 15.sp,
                                    color = BoardTeacherInkDark.copy(alpha = 0.55f)
                                )
                            }

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GurivaSubjects.all) { subj ->
                                    val isSelected = selectedSubject == subj
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedSubject = subj },
                                        label = { Text(subj, style = MaterialTheme.typography.labelMedium) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = BoardTeacherInkDark,
                                            selectedLabelColor = BoardPaperDark,
                                            containerColor = Color.White.copy(alpha = 0.08f),
                                            labelColor = Color.White.copy(alpha = 0.85f)
                                        ),
                                        border = null
                                    )
                                }
                            }

                            Button(
                                onClick = { onStartNewLesson(selectedSubject, defaultLevel) },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BoardAmberFocus,
                                    contentColor = Color(0xFF1A1404)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("start_learning_button")
                            ) {
                                Icon(
                                    Icons.Rounded.Draw,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Start $selectedSubject Board",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            // Continue Latest Lesson (only when present, compact & clean)
            val latestLesson = lessons.firstOrNull()
            if (latestLesson != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                onResumeLesson(latestLesson.id, latestLesson.subject, latestLesson.level)
                            }
                            .testTag("continue_lesson_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Continue · ${latestLesson.subject}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = latestLesson.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Continue lesson",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Recent Lessons (minimalist, no redundant stat boxes or heavy empty box)
            if (lessons.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onViewAllLessons) { Text("All") }
                    }
                }

                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            val recent = lessons.take(3)
                            recent.forEachIndexed { idx, lesson ->
                                RecentLessonRow(
                                    lesson = lesson,
                                    onClick = { onResumeLesson(lesson.id, lesson.subject, lesson.level) }
                                )
                                if (idx < recent.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "Saved smartboards appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentLessonRow(
    lesson: LessonRecord,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = lesson.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${lesson.subject} · ${lesson.boardEvents.size} notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "Resume",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
