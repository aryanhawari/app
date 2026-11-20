package com.example.feature.classroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.domain.model.BoardEvent
import com.example.domain.model.NormalizedRect
import com.example.ui.theme.BoardAmberFocus
import com.example.ui.theme.BoardCorrectionRed
import com.example.ui.theme.BoardPaperDark
import com.example.ui.theme.BoardPaperLight
import com.example.ui.theme.BoardTeacherInk
import com.example.ui.theme.BoardTeacherInkDark
import kotlin.math.abs

@Composable
fun BoardViewport(
    currentPageIndex: Int,
    teacherEvents: List<BoardEvent>,
    activeTeacherEventId: String?,
    activeTeacherProgress: Float,
    uncertainRegion: NormalizedRect?,
    boardScale: Float,
    boardOffsetX: Float,
    boardOffsetY: Float,
    equationScale: Float,
    onTransformChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val paperColor = if (isDark) BoardPaperDark else BoardPaperLight
    val teacherInk = if (isDark) BoardTeacherInkDark else BoardTeacherInk
    val context = LocalContext.current
    val density = LocalDensity.current
    val kalamTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.kalam) }.getOrNull()
    }
    val scrollState = rememberScrollState()
    var followLiveWriting by remember(currentPageIndex) { mutableStateOf(true) }

    val isNearBottom by remember {
        derivedStateOf {
            scrollState.maxValue <= 0 || (scrollState.maxValue - scrollState.value) <= 96
        }
    }

    LaunchedEffect(currentPageIndex) {
        followLiveWriting = true
        scrollState.scrollTo(0)
    }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) {
            followLiveWriting = isNearBottom
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(paperColor)
            .border(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
            .pointerInput(boardScale, boardOffsetX, boardOffsetY) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var currentScale = boardScale
                    var currentOx = boardOffsetX
                    var currentOy = boardOffsetY
                    var zoomActive = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break

                        if (pressedCount >= 2) {
                            zoomActive = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroidSpan = event.calculateCentroidSize(useCurrent = false)
                            if (abs(zoom - 1f) > 0.005f || centroidSpan > 0f) {
                                currentScale = (currentScale * zoom).coerceIn(0.75f, 3.0f)
                                currentOx = (currentOx + pan.x).coerceIn(-600f, 600f)
                                currentOy = (currentOy + pan.y).coerceIn(-600f, 600f)
                                onTransformChanged(currentScale, currentOx, currentOy)
                                event.changes.forEach { it.consume() }
                            }
                        } else if (!zoomActive && currentScale > 1.03f) {
                            val pan = event.calculatePan()
                            if (abs(pan.x) > 1f || abs(pan.y) > 1f) {
                                currentOx = (currentOx + pan.x).coerceIn(-600f, 600f)
                                currentOy = (currentOy + pan.y).coerceIn(-600f, 600f)
                                onTransformChanged(currentScale, currentOx, currentOy)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
    ) {
        val viewportWpx = with(density) { maxWidth.toPx() }
        val viewportHpx = with(density) { maxHeight.toPx() }

        val metrics = remember(
            teacherEvents,
            activeTeacherEventId,
            viewportWpx,
            viewportHpx,
            equationScale
        ) {
            estimateTeacherBoardMetrics(
                events = teacherEvents,
                activeEventId = activeTeacherEventId,
                widthPx = viewportWpx,
                minHeightPx = viewportHpx,
                equationScale = equationScale,
                kalamTypeface = kalamTypeface
            )
        }
        val contentHeightDp = with(density) { metrics.contentHeightPx.toDp() }

        LaunchedEffect(
            metrics.activeWritingBottomPx,
            metrics.contentHeightPx,
            scrollState.maxValue,
            followLiveWriting
        ) {
            if (followLiveWriting && scrollState.maxValue > 0 && !scrollState.isScrollInProgress) {
                val targetScroll = (metrics.activeWritingBottomPx - viewportHpx * 0.76f)
                    .toInt()
                    .coerceIn(0, scrollState.maxValue)
                if (targetScroll > scrollState.value) {
                    scrollState.animateScrollTo(targetScroll)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = scrollState, enabled = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp)
                    .graphicsLayer {
                        scaleX = boardScale
                        scaleY = boardScale
                        translationX = boardOffsetX
                        translationY = boardOffsetY
                    }
            ) {
                TeacherBoardCanvas(
                    events = teacherEvents,
                    activeEventId = activeTeacherEventId,
                    activeEventProgress = activeTeacherProgress,
                    uncertainRegion = uncertainRegion,
                    equationScale = equationScale,
                    baseViewportHeightPx = viewportHpx,
                    teacherInkColor = teacherInk,
                    highlightColor = BoardAmberFocus,
                    correctionColor = BoardCorrectionRed,
                    questionCardColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (teacherEvents.isEmpty() && activeTeacherEventId == null) {
            Text(
                text = "Tap Live or Ask to begin",
                style = MaterialTheme.typography.bodyMedium,
                color = teacherInk.copy(alpha = 0.36f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
