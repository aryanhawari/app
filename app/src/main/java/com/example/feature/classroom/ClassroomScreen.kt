package com.example.feature.classroom

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.TeacherStatus
import com.example.service.ScreenShareController
import com.example.service.ScreenShareForegroundService
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomScreen(
    viewModel: ClassroomViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val isTeacherSpeaking by viewModel.isTeacherSpeaking.collectAsStateWithLifecycle()
    val isScreenSharing by ScreenShareController.isSharing.collectAsStateWithLifecycle()
    val sharedFrame by ScreenShareController.latestSampledFrame.collectAsStateWithLifecycle()

    var showShareSheet by remember { mutableStateOf(false) }
    var showScreenConsentNotice by remember { mutableStateOf(false) }
    var captionExpanded by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    BackHandler {
        when {
            uiState.fullScreenBoard -> viewModel.toggleFullScreenBoard()
            uiState.isLiveVoiceConnected || uiState.isLiveVoiceConnecting -> {
                viewModel.stopLiveVoiceSession()
                onNavigateBack()
            }
            else -> {
                viewModel.saveCurrentLesson(silent = true)
                onNavigateBack()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onImagePickedForCrop(uri)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.submitSelectedVideo(uri)
    }

    val cameraTakePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            viewModel.onImagePickedForCrop(uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempCaptureUri(context)
            pendingCameraUri = uri
            cameraTakePictureLauncher.launch(uri)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startLiveVoiceSession()
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val svcIntent = Intent(context, ScreenShareForegroundService::class.java).apply {
                action = ScreenShareForegroundService.ACTION_START
                putExtra(ScreenShareForegroundService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(ScreenShareForegroundService.EXTRA_RESULT_DATA, res.data)
            }
            ContextCompat.startForegroundService(context, svcIntent)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    uiState.pendingCropBitmap?.let { bmp ->
        ImageCropDialog(
            initialBitmap = bmp,
            onDismiss = { viewModel.dismissCropDialog() },
            onConfirm = { cropped -> viewModel.submitCroppedPhoto(cropped) }
        )
    }

    if (showScreenConsentNotice) {
        AlertDialog(
            onDismissRequest = { showScreenConsentNotice = false },
            title = { Text("Share screen?") },
            text = {
                Text("GURIVA reads sampled screen frames only when you tap Explain screen.")
            },
            confirmButton = {
                Button(onClick = {
                    showScreenConsentNotice = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                    }
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenConsentNotice = false }) { Text("Cancel") }
            }
        )
    }

    if (showShareSheet) {
        ModalBottomSheet(onDismissRequest = { showShareSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Share", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(2.dp))
                ShareOptionRow(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = "Photo or Screenshot",
                    testTag = "pick_photo_button",
                    onClick = {
                        showShareSheet = false
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                ShareOptionRow(
                    icon = Icons.Outlined.CameraAlt,
                    title = "Camera",
                    testTag = "take_photo_button",
                    onClick = {
                        showShareSheet = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            val uri = createTempCaptureUri(context)
                            pendingCameraUri = uri
                            cameraTakePictureLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
                ShareOptionRow(
                    icon = Icons.Outlined.PresentToAll,
                    title = if (isScreenSharing) "Stop Screen Share" else "Share Screen",
                    testTag = "dock_screen_button",
                    onClick = {
                        showShareSheet = false
                        if (isScreenSharing) {
                            ScreenShareController.stopSharing(context)
                        } else {
                            showScreenConsentNotice = true
                        }
                    }
                )
                ShareOptionRow(
                    icon = Icons.Outlined.VideoLibrary,
                    title = "Video Clip",
                    testTag = "dock_video_button",
                    onClick = {
                        showShareSheet = false
                        videoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ClassroomTopBar(
                subject = uiState.subject,
                teacherStatus = uiState.teacherStatus,
                isLiveConnected = uiState.isLiveVoiceConnected,
                isLiveConnecting = uiState.isLiveVoiceConnecting,
                voiceEnabled = uiState.voiceEnabled,
                isTeacherSpeaking = isTeacherSpeaking,
                fullScreenBoard = uiState.fullScreenBoard,
                equationScale = uiState.equationScale,
                isSaving = uiState.isSaving,
                onBack = {
                    if (uiState.isLiveVoiceConnected || uiState.isLiveVoiceConnecting) {
                        viewModel.stopLiveVoiceSession()
                    }
                    viewModel.saveCurrentLesson(silent = true)
                    onNavigateBack()
                },
                onToggleVoice = { viewModel.toggleVoiceOutput() },
                onStopInterrupt = {
                    if (uiState.isLiveVoiceConnected || uiState.isLiveVoiceConnecting) {
                        viewModel.stopLiveVoiceSession()
                    } else {
                        viewModel.interruptTeacher()
                    }
                },
                onTogglePause = { viewModel.togglePause() },
                onSave = { viewModel.saveCurrentLesson(silent = false) },
                onToggleFullScreen = { viewModel.toggleFullScreenBoard() },
                onFitBoard = { viewModel.fitBoardToScreen() },
                onResetZoom = { viewModel.resetBoardZoom() },
                onEnlargeEquations = { viewModel.cycleEquationScale() },
                onClearBoard = { viewModel.clearTeacherBoard() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isScreenSharing) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Screen live",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { viewModel.analyzeSharedScreenFrame(sharedFrame) },
                                modifier = Modifier.testTag("ask_about_screen_button")
                            ) {
                                Text("Explain")
                            }
                            TextButton(
                                onClick = { ScreenShareController.stopSharing(context) },
                                modifier = Modifier.testTag("stop_screen_share_button")
                            ) {
                                Text("Stop", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Minimal Notices, Borderless Subtitle Strip & Quiz Check
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 12.dp)
            ) {
                AnimatedVisibility(visible = uiState.errorMessage != null || uiState.noticeMessage != null) {
                    Surface(
                        color = if (uiState.errorMessage != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = uiState.errorMessage ?: uiState.noticeMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.errorMessage != null) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearNotices() },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = !uiState.fullScreenBoard && uiState.revealedCaption.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { captionExpanded = !captionExpanded }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = uiState.revealedCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (captionExpanded) 6 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (uiState.teacherStatus == TeacherStatus.YOUR_TURN && uiState.recentTurns.isNotEmpty() && !uiState.isLiveVoiceConnected) {
                            TextButton(onClick = { viewModel.explainDifferently() }) {
                                Icon(
                                    Icons.Outlined.AutoFixHigh,
                                    contentDescription = "Re-teach",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Re-teach", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Icon(
                            imageVector = if (captionExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (captionExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                val quizQ = uiState.activeQuizQuestion
                AnimatedVisibility(visible = !uiState.fullScreenBoard && quizQ != null && uiState.activeQuizOptions.size >= 2) {
                    if (quizQ != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = quizQ,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.dismissQuiz() },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Close check", modifier = Modifier.size(16.dp))
                                    }
                                }
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    uiState.activeQuizOptions.forEachIndexed { idx, opt ->
                                        AssistChip(
                                            onClick = { viewModel.answerQuizOption(idx) },
                                            label = { Text(opt, style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                                uiState.activeQuizFeedback?.let { fb ->
                                    Text(
                                        text = fb,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // HERO WORKSPACE: Unobstructed Smartboard with Floating Page Pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                BoardViewport(
                    currentPageIndex = uiState.currentPageIndex,
                    teacherEvents = uiState.teacherEvents,
                    activeTeacherEventId = uiState.activeTeacherEventId,
                    activeTeacherProgress = uiState.activeTeacherProgress,
                    uncertainRegion = uiState.uncertainRegion,
                    boardScale = uiState.boardScale,
                    boardOffsetX = uiState.boardOffsetX,
                    boardOffsetY = uiState.boardOffsetY,
                    equationScale = uiState.equationScale,
                    onTransformChanged = { s, ox, oy -> viewModel.onBoardTransformChanged(s, ox, oy) },
                    modifier = Modifier.fillMaxSize().testTag("whiteboard_viewport")
                )

                if (!uiState.fullScreenBoard) {
                    FloatingPagePill(
                        currentPageIndex = uiState.currentPageIndex,
                        totalPages = uiState.pages.size,
                        onPreviousPage = { viewModel.selectPage(uiState.currentPageIndex - 1) },
                        onNextPage = { viewModel.selectPage(uiState.currentPageIndex + 1) },
                        onNewPage = { viewModel.openBlankPage() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                }
            }

            // Inline Text Command Bar (when Ask is toggled)
            AnimatedVisibility(visible = uiState.showTextDock) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.textInputDraft,
                                onValueChange = { viewModel.updateTextDraft(it) },
                                placeholder = { Text("Ask anything...", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { viewModel.submitTextQuestion() }),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f).testTag("classroom_text_input")
                            )
                            FilledIconButton(
                                onClick = { viewModel.submitTextQuestion() },
                                enabled = uiState.textInputDraft.isNotBlank(),
                                modifier = Modifier.size(48.dp).testTag("classroom_send_text_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }

            // Clean 3-Action Dock: Live | Share | Ask
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveVoiceActionButton(
                            isConnected = uiState.isLiveVoiceConnected,
                            isConnecting = uiState.isLiveVoiceConnecting,
                            isRecordingFallback = isRecording,
                            onClick = {
                                if (uiState.isLiveVoiceConnected || uiState.isLiveVoiceConnecting) {
                                    viewModel.stopLiveVoiceSession()
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    viewModel.startLiveVoiceSession()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CleanDockButton(
                                icon = Icons.Rounded.AddPhotoAlternate,
                                label = "Share",
                                active = showShareSheet || isScreenSharing,
                                testTag = "dock_photo_button",
                                onClick = { showShareSheet = true }
                            )
                            CleanDockButton(
                                icon = Icons.Rounded.Keyboard,
                                label = "Ask",
                                active = uiState.showTextDock,
                                testTag = "dock_text_button",
                                onClick = { viewModel.toggleTextDock() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingPagePill(
    currentPageIndex: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onNewPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPageIndex > 0,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("prev_page_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Previous page",
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = "${currentPageIndex + 1}/$totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("board_page_indicator")
            )
            IconButton(
                onClick = onNextPage,
                enabled = currentPageIndex < totalPages - 1,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("next_page_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Next page",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onNewPage,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("new_board_page_button")
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "New page",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ClassroomTopBar(
    subject: String,
    teacherStatus: TeacherStatus,
    isLiveConnected: Boolean,
    isLiveConnecting: Boolean,
    voiceEnabled: Boolean,
    isTeacherSpeaking: Boolean,
    fullScreenBoard: Boolean,
    equationScale: Float,
    isSaving: Boolean,
    onBack: () -> Unit,
    onToggleVoice: () -> Unit,
    onStopInterrupt: () -> Unit,
    onTogglePause: () -> Unit,
    onSave: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onFitBoard: () -> Unit,
    onResetZoom: () -> Unit,
    onEnlargeEquations: () -> Unit,
    onClearBoard: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val statusColor by animateColorAsState(
        targetValue = when {
            !voiceEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
            isLiveConnected -> MaterialTheme.colorScheme.tertiary
            isLiveConnecting -> MaterialTheme.colorScheme.primary
            teacherStatus == TeacherStatus.LISTENING -> MaterialTheme.colorScheme.tertiary
            teacherStatus == TeacherStatus.UNDERSTANDING ||
                teacherStatus == TeacherStatus.EXPLAINING ||
                teacherStatus == TeacherStatus.WRITING -> MaterialTheme.colorScheme.primary
            teacherStatus == TeacherStatus.QUOTA_BUSY -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusColor"
    )

    val statusLabel = when {
        !voiceEnabled -> "Muted"
        isLiveConnecting -> "Connecting"
        isLiveConnected && teacherStatus == TeacherStatus.WRITING -> "Writing"
        isLiveConnected -> "Live"
        else -> teacherStatus.label
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    IconButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = subject,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleVoice,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("toggle_voice_button")
                    ) {
                        Icon(
                            imageVector = if (voiceEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            contentDescription = if (voiceEnabled) "Mute voice" else "Enable voice",
                            tint = when {
                                !voiceEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                isTeacherSpeaking || isLiveConnected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    if (isLiveConnected || teacherStatus == TeacherStatus.EXPLAINING || teacherStatus == TeacherStatus.WRITING || isTeacherSpeaking) {
                        IconButton(
                            onClick = onStopInterrupt,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("interrupt_teacher_button")
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = "Stop teacher", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("classroom_more_menu")
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isSaving) "Saving..." else "Save Lesson") },
                                leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                                enabled = !isSaving,
                                modifier = Modifier.testTag("save_lesson_button"),
                                onClick = {
                                    menuExpanded = false
                                    onSave()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (teacherStatus == TeacherStatus.PAUSED) "Resume" else "Pause") },
                                leadingIcon = {
                                    Icon(
                                        if (teacherStatus == TeacherStatus.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePause()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (fullScreenBoard) "Exit Full Screen" else "Full Screen") },
                                leadingIcon = {
                                    Icon(
                                        if (fullScreenBoard) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.testTag("fullscreen_board_button"),
                                onClick = {
                                    menuExpanded = false
                                    onToggleFullScreen()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Fit Board") },
                                leadingIcon = { Icon(Icons.Rounded.FitScreen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onFitBoard()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset Zoom") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                modifier = Modifier.testTag("reset_zoom_button"),
                                onClick = {
                                    menuExpanded = false
                                    onResetZoom()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Text Size (${(equationScale * 100).toInt()}%)") },
                                leadingIcon = { Icon(Icons.Rounded.ZoomIn, contentDescription = null) },
                                modifier = Modifier.testTag("enlarge_equation_button"),
                                onClick = {
                                    menuExpanded = false
                                    onEnlargeEquations()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Board") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onClearBoard()
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
        }
    }
}

@Composable
private fun LiveVoiceActionButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    isRecordingFallback: Boolean,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "livePulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "pulse"
    )
    val active = isConnected || isRecordingFallback

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (active) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
        ),
        modifier = Modifier
            .height(48.dp)
            .then(if (active) Modifier.scale(pulse) else Modifier)
            .testTag("dock_voice_button")
    ) {
        when {
            isConnecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting", style = MaterialTheme.typography.labelLarge)
            }
            active -> {
                Icon(Icons.Rounded.Stop, contentDescription = "End Live", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop", style = MaterialTheme.typography.labelLarge)
            }
            else -> {
                Icon(Icons.Rounded.GraphicEq, contentDescription = "Start Live Voice", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Live", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CleanDockButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(19.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

@Composable
private fun ShareOptionRow(
    icon: ImageVector,
    title: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private fun createTempCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
