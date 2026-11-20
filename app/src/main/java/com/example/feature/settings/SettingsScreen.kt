package com.example.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.GurivaSubjects
import com.example.feature.home.MainAppViewModel
import com.example.feature.home.UiState

private fun voicePersonaDisplay(voice: String): String = when (voice) {
    "Kore" -> "Kore · Clear"
    "Sulafat" -> "Sulafat · Warm"
    "Puck" -> "Puck · Upbeat"
    "Vindemiatrix" -> "Vindemiatrix · Gentle"
    else -> voice
}

@Composable
fun SettingsScreen(
    viewModel: MainAppViewModel,
    modifier: Modifier = Modifier
) {
    val profileUi by viewModel.profileState.collectAsStateWithLifecycle()
    val notice by viewModel.actionNotice.collectAsStateWithLifecycle()
    val profile = (profileUi as? UiState.Success)?.data

    var displayName by remember { mutableStateOf("Student") }
    var preferredLanguage by remember { mutableStateOf("Auto") }
    var explanationPace by remember { mutableStateOf("Balanced") }
    var difficultyLevel by remember { mutableStateOf("Beginner") }
    var voiceEnabled by remember { mutableStateOf(true) }
    var preferredVoice by remember { mutableStateOf("Kore") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        if (profile != null) {
            displayName = profile.displayName
            preferredLanguage = profile.preferredLanguage
            explanationPace = profile.explanationPace
            difficultyLevel = profile.difficultyLevel
            voiceEnabled = profile.voiceEnabled
            preferredVoice = GurivaSubjects.normalizeVoiceSelection(profile.preferredVoice)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Clear local data?") },
            text = { Text("This deletes saved lessons and topic progress from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteAllSavedData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
            }

            if (!notice.isNullOrBlank()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(notice.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearNotice() }) { Text("OK") }
                        }
                    }
                }
            }

            // Learning Preferences
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Language", style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GurivaSubjects.languages) { lang ->
                                    FilterChip(
                                        selected = preferredLanguage == lang,
                                        onClick = { preferredLanguage = lang },
                                        label = { Text(lang, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Pace", style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GurivaSubjects.paces) { pace ->
                                    FilterChip(
                                        selected = explanationPace == pace,
                                        onClick = { explanationPace = pace },
                                        label = { Text(pace, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Level", style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GurivaSubjects.levels) { lvl ->
                                    FilterChip(
                                        selected = difficultyLevel == lvl,
                                        onClick = { difficultyLevel = lvl },
                                        label = { Text(lvl, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Voice Preferences
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text("Teacher Voice", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(
                                checked = voiceEnabled,
                                onCheckedChange = { voiceEnabled = it }
                            )
                        }

                        if (voiceEnabled) {
                            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GurivaSubjects.voices) { voice ->
                                    FilterChip(
                                        selected = preferredVoice == voice,
                                        onClick = { preferredVoice = voice },
                                        label = { Text(voicePersonaDisplay(voice), style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.updatePreferences(
                                    preferredLanguage = preferredLanguage,
                                    explanationPace = explanationPace,
                                    difficultyLevel = difficultyLevel,
                                    voiceEnabled = voiceEnabled,
                                    preferredVoice = preferredVoice,
                                    displayName = displayName
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().testTag("save_preferences_button")
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }

            // Minimal Local Reset Action (no Auth / Account box)
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("delete_all_data_button")
                    ) {
                        Text("Clear Saved Lessons & Progress", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
