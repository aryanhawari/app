package com.example.feature.classroom

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.NormalizedRect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class BoardLayoutMetrics(
    val contentHeightPx: Float,
    val activeWritingBottomPx: Float
)

/**
 * Estimates the total pixel height and active writing vertical position needed to render
 * all visible teacher events without vertical overlap, clipping, or draw-phase state mutation.
 */
fun estimateTeacherBoardMetrics(
    events: List<BoardEvent>,
    activeEventId: String?,
    widthPx: Float,
    minHeightPx: Float,
    equationScale: Float,
    kalamTypeface: Typeface?
): BoardLayoutMetrics {
    if (widthPx <= 1f || minHeightPx <= 1f) {
        return BoardLayoutMetrics(minHeightPx, 0f)
    }
    val visible = events.filter {
        it.status == BoardEventStatus.COMPLETE ||
            it.status == BoardEventStatus.RENDERING ||
            it.eventId == activeEventId
    }
    if (visible.isEmpty()) return BoardLayoutMetrics(minHeightPx, 0f)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = kalamTypeface ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textSkewX = -0.04f
    }
    val mathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(kalamTypeface ?: Typeface.SERIF, Typeface.BOLD)
        isFakeBoldText = kalamTypeface != null
        textSkewX = -0.07f
    }

    var minNextTopY = 24f
    var activeBottomPx = 0f
    var lastVisibleBottomPx = 0f

    for (event in visible) {
        val left = (event.viewport.x * widthPx).coerceIn(22f, widthPx - 120f)
        val maxAvailW = (widthPx - left - 20f).coerceAtLeast(100f)
        val boxW = (event.viewport.width * widthPx).coerceIn(100f, maxAvailW)
        val boxH = event.viewport.height * minHeightPx
        val requestedTop = event.viewport.y * minHeightPx
        val top = maxOf(requestedTop, minNextTopY)

        val contentHeight = when (event.type) {
            BoardEventType.WRITE_TEXT -> {
                val sz = (widthPx * 0.041f * equationScale).coerceIn(26f, 54f)
                textPaint.textSize = sz
                val lines = wrapLines(textPaint, event.payload, boxW)
                (lines.size.coerceAtLeast(1) * sz * 1.40f) + 12f
            }
            BoardEventType.WRITE_MATH -> {
                val sz = (widthPx * 0.049f * equationScale).coerceIn(30f, 64f)
                mathPaint.textSize = sz
                val formatted = formatMathSymbols(event.payload)
                val lines = wrapLines(mathPaint, formatted, (boxW - 8f).coerceAtLeast(80f))
                (lines.size.coerceAtLeast(1) * sz * 1.44f) + 18f
            }
            BoardEventType.HIGHLIGHT, BoardEventType.CORRECT -> {
                val sz = (widthPx * 0.041f * equationScale).coerceIn(25f, 52f)
                mathPaint.textSize = sz
                val lines = wrapLines(mathPaint, formatMathSymbols(event.payload), boxW)
                maxOf(boxH, lines.size.coerceAtLeast(1) * sz * 1.40f + 18f)
            }
            BoardEventType.ASK_QUESTION -> {
                val sz = (widthPx * 0.038f * equationScale).coerceIn(22f, 46f)
                textPaint.textSize = sz
                val lines = wrapLines(textPaint, "Try: ${event.payload}", (boxW - 28f).coerceAtLeast(80f))
                maxOf(boxH, lines.size.coerceAtLeast(1) * sz * 1.40f + 32f)
            }
            BoardEventType.DRAW_DIAGRAM, BoardEventType.DRAW_GRAPH -> boxH.coerceAtLeast(170f)
            BoardEventType.DRAW_SHAPE, BoardEventType.CIRCLE -> boxH.coerceAtLeast(90f)
            BoardEventType.WAIT_FOR_STUDENT, BoardEventType.END_STEP, BoardEventType.ERASE -> 0f
            else -> boxH.coerceAtLeast(54f)
        }
        if (contentHeight > 0f) {
            val bottom = top + contentHeight
            lastVisibleBottomPx = bottom
            if (event.eventId == activeEventId || event.status == BoardEventStatus.RENDERING) {
                activeBottomPx = bottom
            }
            minNextTopY = bottom + 22f
        }
    }
    return BoardLayoutMetrics(
        contentHeightPx = maxOf(minHeightPx, minNextTopY + 110f),
        activeWritingBottomPx = if (activeBottomPx > 0f) activeBottomPx else lastVisibleBottomPx
    )
}

fun estimateTeacherBoardContentHeightPx(
    events: List<BoardEvent>,
    activeEventId: String?,
    widthPx: Float,
    minHeightPx: Float,
    equationScale: Float,
    kalamTypeface: Typeface?
): Float = estimateTeacherBoardMetrics(
    events = events,
    activeEventId = activeEventId,
    widthPx = widthPx,
    minHeightPx = minHeightPx,
    equationScale = equationScale,
    kalamTypeface = kalamTypeface
).contentHeightPx

@Composable
fun TeacherBoardCanvas(
    events: List<BoardEvent>,
    activeEventId: String?,
    activeEventProgress: Float,
    uncertainRegion: NormalizedRect?,
    equationScale: Float,
    baseViewportHeightPx: Float,
    teacherInkColor: Color,
    highlightColor: Color,
    correctionColor: Color,
    questionCardColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kalamTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.kalam) }.getOrNull()
    }

    val handwritingPaint = remember(kalamTypeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = kalamTypeface ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSkewX = -0.04f
        }
    }
    val mathHandwritingPaint = remember(kalamTypeface) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(kalamTypeface ?: Typeface.SERIF, Typeface.BOLD)
            isFakeBoldText = kalamTypeface != null
            textSkewX = -0.07f
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val baseH = if (baseViewportHeightPx > 1f) baseViewportHeightPx else h
        if (w <= 1f || h <= 1f) return@Canvas

        // Fine smartboard dot-matrix texture
        val dotSpacing = 44f
        val dotColor = teacherInkColor.copy(alpha = 0.085f)
        var gx = dotSpacing
        while (gx < w) {
            var gy = dotSpacing
            while (gy < h) {
                drawCircle(color = dotColor, radius = 1.45f, center = Offset(gx, gy))
                gy += dotSpacing
            }
            gx += dotSpacing
        }

        val visibleEvents = events.filter {
            it.status == BoardEventStatus.COMPLETE ||
                it.status == BoardEventStatus.RENDERING ||
                it.eventId == activeEventId
        }

        // Dynamic vertical cursor ensures zero text overlap or clipping across wrapped Hindi/English/Math blocks
        var minNextTopY = 24f

        for (event in visibleEvents) {
            val isActivelyWriting = event.eventId == activeEventId
            val isLiveWordStreaming = event.status == BoardEventStatus.RENDERING
            val progress = when {
                isLiveWordStreaming -> 1f
                isActivelyWriting -> activeEventProgress.coerceIn(0.02f, 1f)
                else -> 1f
            }
            val showEndTip = isLiveWordStreaming && isActivelyWriting
            val left = (event.viewport.x * w).coerceIn(22f, w - 120f)
            val maxAvailW = (w - left - 20f).coerceAtLeast(100f)
            val boxW = (event.viewport.width * w).coerceIn(100f, maxAvailW)
            val boxH = event.viewport.height * baseH
            val top = maxOf(event.viewport.y * baseH, minNextTopY)

            var consumedHeight = 0f

            when (event.type) {
                BoardEventType.WRITE_TEXT -> {
                    val textSize = (w * 0.041f * equationScale).coerceIn(26f, 54f)
                    val res = drawHandwrittenLinesAnimated(
                        paint = handwritingPaint,
                        text = event.payload,
                        x = left,
                        y = top,
                        maxWidth = boxW,
                        textSizePx = textSize,
                        color = teacherInkColor,
                        progress = progress,
                        showEndTip = showEndTip
                    )
                    consumedHeight = res.heightPx
                    if (isActivelyWriting) {
                        res.activeTip?.let { drawStylusNib(it, highlightColor, teacherInkColor) }
                    }
                }

                BoardEventType.WRITE_MATH -> {
                    val formatted = formatMathSymbols(event.payload)
                    val textSize = (w * 0.049f * equationScale).coerceIn(30f, 64f)

                    drawCircle(
                        color = highlightColor.copy(alpha = 0.80f * progress.coerceAtLeast(0.3f)),
                        radius = 4.2f,
                        center = Offset((left - 12f).coerceAtLeast(10f), top + textSize * 0.62f)
                    )

                    val res = drawHandwrittenLinesAnimated(
                        paint = mathHandwritingPaint,
                        text = formatted,
                        x = left + 4f,
                        y = top,
                        maxWidth = (boxW - 8f).coerceAtLeast(80f),
                        textSizePx = textSize,
                        color = teacherInkColor,
                        progress = progress,
                        showEndTip = showEndTip
                    )
                    consumedHeight = res.heightPx + 8f

                    if (formatted.contains('=') && progress > 0.22f && res.lastLineWidth > 0f) {
                        val underlineY = top + res.heightPx + 2f
                        val underlineProgress = ((progress - 0.22f) / 0.78f).coerceIn(0f, 1f)
                        val lineW = res.lastLineWidth.coerceIn(40f, boxW * 0.92f)
                        drawHandWavyLine(
                            start = Offset(left + 4f, underlineY),
                            end = Offset(left + 4f + lineW * underlineProgress, underlineY),
                            color = highlightColor.copy(alpha = 0.65f),
                            strokeWidth = 3.2f
                        )
                    }

                    if (isActivelyWriting) {
                        res.activeTip?.let { drawStylusNib(it, highlightColor, teacherInkColor) }
                    }
                }

                BoardEventType.DRAW_LINE -> {
                    val endX = left + boxW * progress
                    val midY = top + 24f
                    drawHandWavyLine(
                        start = Offset(left, midY),
                        end = Offset(endX, midY),
                        color = teacherInkColor,
                        strokeWidth = 4.5f
                    )
                    consumedHeight = 44f
                    if (isActivelyWriting) {
                        drawStylusNib(Offset(endX, midY), highlightColor, teacherInkColor)
                    }
                }

                BoardEventType.DRAW_ARROW -> {
                    val start = Offset(left, top + 28f)
                    val end = Offset(left + boxW * 0.36f * progress, top + 28f)
                    drawHandArrow(start, end, highlightColor, 4.5f)
                    var labelH = 48f
                    if (event.payload.isNotBlank() && progress > 0.30f) {
                        val res = drawHandwrittenLinesAnimated(
                            paint = handwritingPaint,
                            text = event.payload,
                            x = left + boxW * 0.40f,
                            y = top,
                            maxWidth = boxW * 0.58f,
                            textSizePx = (w * 0.036f * equationScale).coerceIn(22f, 44f),
                            color = teacherInkColor,
                            progress = ((progress - 0.30f) / 0.70f).coerceIn(0f, 1f)
                        )
                        labelH = maxOf(labelH, res.heightPx)
                    }
                    consumedHeight = labelH
                    if (isActivelyWriting) {
                        drawStylusNib(end, highlightColor, teacherInkColor)
                    }
                }

                BoardEventType.DRAW_SHAPE -> {
                    val shapeH = boxH.coerceAtLeast(100f)
                    drawShapeEvent(event.payload, left, top, boxW, shapeH, progress, teacherInkColor, handwritingPaint, equationScale)
                    consumedHeight = shapeH
                }

                BoardEventType.DRAW_DIAGRAM -> {
                    val diagH = boxH.coerceAtLeast(150f)
                    drawDiagramEvent(event.payload, left, top, boxW, diagH, progress, teacherInkColor, highlightColor, handwritingPaint, equationScale)
                    consumedHeight = diagH
                }

                BoardEventType.DRAW_GRAPH -> {
                    val graphH = boxH.coerceAtLeast(180f)
                    drawGraphEvent(event.payload, left, top, boxW, graphH, progress, teacherInkColor, highlightColor, handwritingPaint, equationScale)
                    consumedHeight = graphH
                }

                BoardEventType.HIGHLIGHT -> {
                    val textSize = (w * 0.041f * equationScale).coerceIn(25f, 50f)
                    mathHandwritingPaint.textSize = textSize
                    val previewLines = wrapLines(mathHandwritingPaint, event.payload, (boxW - 16f).coerceAtLeast(80f))
                    val swipeH = maxOf(46f, previewLines.size.coerceAtLeast(1) * textSize * 1.38f + 14f)
                    drawRoundRect(
                        color = highlightColor.copy(alpha = 0.22f),
                        topLeft = Offset(left - 4f, top + 2f),
                        size = Size((boxW + 8f) * progress, swipeH),
                        cornerRadius = CornerRadius(14f, 14f)
                    )
                    if (event.payload.isNotBlank()) {
                        val res = drawHandwrittenLinesAnimated(
                            paint = mathHandwritingPaint,
                            text = event.payload,
                            x = left + 8f,
                            y = top + 6f,
                            maxWidth = (boxW - 16f).coerceAtLeast(80f),
                            textSizePx = textSize,
                            color = teacherInkColor,
                            progress = progress,
                            showEndTip = showEndTip
                        )
                        consumedHeight = maxOf(swipeH, res.heightPx + 10f)
                        if (isActivelyWriting) {
                            res.activeTip?.let { drawStylusNib(it, highlightColor, teacherInkColor) }
                        }
                    } else {
                        consumedHeight = swipeH
                    }
                }

                BoardEventType.CIRCLE -> {
                    val ovalW = boxW.coerceAtLeast(90f)
                    val textSize = (w * 0.038f * equationScale).coerceIn(22f, 44f)
                    mathHandwritingPaint.textSize = textSize
                    val lines = wrapLines(mathHandwritingPaint, formatMathSymbols(event.payload), (ovalW - 32f).coerceAtLeast(50f))
                    val ovalH = maxOf(boxH, 68f, lines.size.coerceAtLeast(1) * textSize * 1.4f + 26f)
                    drawHandOval(
                        topLeft = Offset(left, top),
                        size = Size(ovalW, ovalH),
                        color = highlightColor,
                        progress = progress
                    )
                    if (event.payload.isNotBlank()) {
                        drawHandwrittenLinesAnimated(
                            paint = mathHandwritingPaint,
                            text = formatMathSymbols(event.payload),
                            x = left + 16f,
                            y = top + 14f,
                            maxWidth = (ovalW - 32f).coerceAtLeast(50f),
                            textSizePx = textSize,
                            color = teacherInkColor,
                            progress = progress
                        )
                    }
                    consumedHeight = ovalH
                }

                BoardEventType.CORRECT -> {
                    val midY = top + 24f
                    drawHandWavyLine(
                        start = Offset(left, midY),
                        end = Offset(left + boxW * 0.36f * progress, midY),
                        color = correctionColor.copy(alpha = 0.78f),
                        strokeWidth = 3.5f
                    )
                    val res = drawHandwrittenLinesAnimated(
                        paint = mathHandwritingPaint,
                        text = formatMathSymbols(event.payload),
                        x = left,
                        y = top + 12f,
                        maxWidth = boxW,
                        textSizePx = (w * 0.042f * equationScale).coerceIn(25f, 52f),
                        color = correctionColor,
                        progress = progress
                    )
                    consumedHeight = res.heightPx + 14f
                    if (isActivelyWriting) {
                        res.activeTip?.let { drawStylusNib(it, correctionColor, teacherInkColor) }
                    }
                }

                BoardEventType.ASK_QUESTION -> {
                    val textSize = (w * 0.038f * equationScale).coerceIn(22f, 46f)
                    val promptText = "Try: ${event.payload}"
                    handwritingPaint.textSize = textSize
                    val lines = wrapLines(handwritingPaint, promptText, (boxW - 28f).coerceAtLeast(80f))
                    val cardH = maxOf(boxH, 68f, lines.size.coerceAtLeast(1) * textSize * 1.4f + 26f)
                    drawRoundRect(
                        color = questionCardColor.copy(alpha = 0.42f),
                        topLeft = Offset(left, top),
                        size = Size(boxW, cardH),
                        cornerRadius = CornerRadius(14f, 14f)
                    )
                    drawRoundRect(
                        color = highlightColor.copy(alpha = 0.85f),
                        topLeft = Offset(left, top),
                        size = Size(boxW, cardH),
                        cornerRadius = CornerRadius(14f, 14f),
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    )
                    val res = drawHandwrittenLinesAnimated(
                        paint = handwritingPaint,
                        text = promptText,
                        x = left + 14f,
                        y = top + 10f,
                        maxWidth = (boxW - 28f).coerceAtLeast(80f),
                        textSizePx = textSize,
                        color = teacherInkColor,
                        progress = progress
                    )
                    consumedHeight = maxOf(cardH, res.heightPx + 16f)
                    if (isActivelyWriting) {
                        res.activeTip?.let { drawStylusNib(it, highlightColor, teacherInkColor) }
                    }
                }

                BoardEventType.ERASE -> {
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }

                BoardEventType.WAIT_FOR_STUDENT,
                BoardEventType.END_STEP -> Unit
            }

            if (consumedHeight > 0f) {
                minNextTopY = top + consumedHeight + 22f
            }
        }

        // Highlight uncertain handwriting region when teacher asks for clarification
        if (uncertainRegion != null) {
            val rx = uncertainRegion.x * w
            val ry = uncertainRegion.y * baseH
            val rw = uncertainRegion.width * w
            val rh = uncertainRegion.height * baseH
            drawRoundRect(
                color = highlightColor.copy(alpha = 0.16f),
                topLeft = Offset(rx, ry),
                size = Size(rw, rh),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawRoundRect(
                color = highlightColor,
                topLeft = Offset(rx, ry),
                size = Size(rw, rh),
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(
                    width = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
                )
            )
        }
    }
}

private data class HandwrittenDrawResult(
    val heightPx: Float,
    val lastLineWidth: Float,
    val activeTip: Offset?
)

private fun formatMathSymbols(raw: String): String {
    return raw
        .replace("*", "×")
        .replace("sqrt(", "√(")
        .replace("<=", "≤")
        .replace(">=", "≥")
        .replace("!=", "≠")
        .replace("^2", "²")
        .replace("^3", "³")
        .replace("->", "→")
}

private fun wrapLines(
    paint: Paint,
    text: String,
    maxWidthPx: Float
): List<String> {
    if (text.isBlank()) return emptyList()
    val safeMaxWidth = maxWidthPx.coerceAtLeast(80f)
    val lines = mutableListOf<String>()

    text.split('\n').forEach { paragraph ->
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return@forEach
        var current = StringBuilder()
        for (word in words) {
            // If a single long token exceeds safeMaxWidth, break it safely so nothing ever clips horizontally
            if (paint.measureText(word) > safeMaxWidth) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                var chunk = StringBuilder()
                for (ch in word) {
                    val nextChunk = chunk.toString() + ch
                    if (paint.measureText(nextChunk) <= safeMaxWidth || chunk.isEmpty()) {
                        chunk.append(ch)
                    } else {
                        lines += chunk.toString()
                        chunk = StringBuilder().append(ch)
                    }
                }
                if (chunk.isNotEmpty()) {
                    current = chunk
                }
                continue
            }

            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= safeMaxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
    }
    return lines
}

/**
 * Draws wrapped handwritten lines with generous vertical clip bounds (preserving Devanagari matras,
 * ascenders, and superscripts) and progressive left-to-right stroke reveal.
 */
private fun DrawScope.drawHandwrittenLinesAnimated(
    paint: Paint,
    text: String,
    x: Float,
    y: Float,
    maxWidth: Float,
    textSizePx: Float,
    color: Color,
    progress: Float,
    showEndTip: Boolean = false
): HandwrittenDrawResult {
    if (text.isBlank()) return HandwrittenDrawResult(0f, 0f, null)
    paint.color = color.toArgb()
    paint.textSize = textSizePx
    val fm = paint.fontMetrics
    val lineHeight = maxOf(textSizePx * 1.38f, (fm.bottom - fm.top) * 1.15f)

    val lines = wrapLines(paint, text, maxWidth)
    if (lines.isEmpty()) return HandwrittenDrawResult(0f, 0f, null)

    var activeTip: Offset? = null
    var lastLineWidth = 0f
    val totalLines = lines.size
    val nativeCanvas = drawContext.canvas.nativeCanvas

    lines.forEachIndexed { idx, line ->
        val lineProgress = if (progress >= 0.995f) {
            1f
        } else {
            (progress * totalLines - idx).coerceIn(0f, 1f)
        }
        if (lineProgress <= 0f) return@forEachIndexed

        val baselineY = y - fm.ascent + idx * lineHeight
        val lineWidth = paint.measureText(line)
        lastLineWidth = lineWidth
        val slightHandTilt = ((idx % 2) * 1.1f - 0.55f)

        if (lineProgress >= 0.995f) {
            nativeCanvas.drawText(line, x, baselineY + slightHandTilt, paint)
            if (showEndTip && idx == lines.lastIndex) {
                activeTip = Offset(
                    (x + lineWidth + 8f).coerceAtMost(x + maxWidth),
                    baselineY + fm.ascent * 0.25f
                )
            }
        } else {
            val revealRight = x + (lineWidth + 14f) * lineProgress
            nativeCanvas.save()
            nativeCanvas.clipRect(
                x - 14f,
                baselineY + fm.top - 10f,
                revealRight,
                baselineY + fm.bottom + 10f
            )
            nativeCanvas.drawText(line, x, baselineY + slightHandTilt, paint)
            nativeCanvas.restore()
            activeTip = Offset(revealRight, baselineY + fm.ascent * 0.25f)
        }
    }

    val totalHeight = lines.size * lineHeight
    return HandwrittenDrawResult(totalHeight, lastLineWidth, activeTip)
}

private fun DrawScope.drawStylusNib(
    tip: Offset,
    accent: Color,
    ink: Color
) {
    drawCircle(
        color = accent.copy(alpha = 0.28f),
        radius = 15f,
        center = tip
    )
    drawCircle(
        color = accent,
        radius = 6.5f,
        center = tip
    )
    drawCircle(
        color = ink,
        radius = 3f,
        center = tip
    )
}

private fun DrawScope.drawHandWavyLine(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float
) {
    val dx = end.x - start.x
    if (dx <= 2f) return
    val p = Path().apply {
        moveTo(start.x, start.y)
        val midX = (start.x + end.x) * 0.5f
        val q1X = start.x + dx * 0.25f
        val q3X = start.x + dx * 0.75f
        quadraticTo(q1X, start.y + 2.2f, midX, start.y - 1.2f)
        quadraticTo(q3X, start.y - 2.4f, end.x, end.y + 0.8f)
    }
    drawPath(
        path = p,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawHandOval(
    topLeft: Offset,
    size: Size,
    color: Color,
    progress: Float
) {
    val cx = topLeft.x + size.width * 0.5f
    val cy = topLeft.y + size.height * 0.5f
    val rx = size.width * 0.5f
    val ry = size.height * 0.5f
    val fullPath = Path()
    val segments = 32
    for (i in 0..segments) {
        val t = (i.toFloat() / segments) * (2f * PI.toFloat() * 1.04f) - 0.3f
        val wobble = 1f + 0.04f * sin(t * 3f)
        val px = cx + rx * wobble * cos(t)
        val py = cy + ry * wobble * sin(t)
        if (i == 0) fullPath.moveTo(px, py) else fullPath.lineTo(px, py)
    }
    val measure = PathMeasure().apply { setPath(fullPath, false) }
    val partial = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0.05f, 1f), partial, true)
    drawPath(
        path = partial,
        color = color,
        style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawHandArrow(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float
) {
    val midX = (start.x + end.x) * 0.5f
    val curve = Path().apply {
        moveTo(start.x, start.y)
        quadraticTo(midX, start.y - 8f, end.x, end.y)
    }
    drawPath(curve, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
    val angle = atan2((end.y - (start.y - 8f)).toDouble(), (end.x - midX).toDouble())
    val headLen = 16f
    val a1 = angle - PI / 5.5
    val a2 = angle + PI / 5.5
    drawLine(
        color = color,
        start = end,
        end = Offset(
            (end.x - headLen * cos(a1)).toFloat(),
            (end.y - headLen * sin(a1)).toFloat()
        ),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    drawLine(
        color = color,
        start = end,
        end = Offset(
            (end.x - headLen * cos(a2)).toFloat(),
            (end.y - headLen * sin(a2)).toFloat()
        ),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawShapeEvent(
    payload: String,
    left: Float,
    top: Float,
    boxW: Float,
    boxH: Float,
    progress: Float,
    color: Color,
    textPaint: Paint,
    scale: Float
) {
    val lower = payload.lowercase()
    val w = boxW.coerceAtLeast(80f)
    val h = boxH.coerceAtLeast(80f)
    when {
        "circle" in lower -> {
            val d = minOf(w, h)
            drawHandOval(Offset(left, top), Size(d, d), color, progress)
        }
        "triangle" in lower -> {
            val p = Path().apply {
                moveTo(left + w * 0.5f, top)
                lineTo(left + w, top + h)
                lineTo(left, top + h)
                close()
            }
            val measure = PathMeasure().apply { setPath(p, true) }
            val partial = Path()
            measure.getSegment(0f, measure.length * progress, partial, true)
            drawPath(partial, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        else -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w * progress, h),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
    if (payload.isNotBlank()) {
        drawHandwrittenLinesAnimated(
            paint = textPaint,
            text = payload,
            x = left + 12f,
            y = top + h * 0.30f,
            maxWidth = w - 24f,
            textSizePx = (size.width * 0.032f * scale).coerceIn(20f, 38f),
            color = color,
            progress = progress
        )
    }
}

private fun DrawScope.drawDiagramEvent(
    payload: String,
    left: Float,
    top: Float,
    boxW: Float,
    boxH: Float,
    progress: Float,
    ink: Color,
    accent: Color,
    textPaint: Paint,
    scale: Float
) {
    val parts = payload.split("->").map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size >= 2) {
        val nodeW = (boxW / (parts.size * 1.35f)).coerceAtLeast(92f)
        val nodeH = (boxH * 0.72f).coerceAtLeast(62f)
        val gap = if (parts.size > 1) ((boxW - nodeW * parts.size) / (parts.size - 1)).coerceAtLeast(18f) else 0f
        parts.forEachIndexed { idx, label ->
            val nx = left + idx * (nodeW + gap)
            val ny = top + 8f
            val nodeProg = (progress * parts.size - idx).coerceIn(0f, 1f)
            if (nodeProg <= 0f) return@forEachIndexed
            drawRoundRect(
                color = accent.copy(alpha = 0.14f * nodeProg),
                topLeft = Offset(nx, ny),
                size = Size(nodeW, nodeH),
                cornerRadius = CornerRadius(14f, 14f)
            )
            drawRoundRect(
                color = ink.copy(alpha = nodeProg),
                topLeft = Offset(nx, ny),
                size = Size(nodeW, nodeH),
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
            )
            drawHandwrittenLinesAnimated(
                paint = textPaint,
                text = label,
                x = nx + 10f,
                y = ny + 10f,
                maxWidth = nodeW - 20f,
                textSizePx = (size.width * 0.030f * scale).coerceIn(18f, 32f),
                color = ink,
                progress = nodeProg
            )
            if (idx < parts.lastIndex && nodeProg > 0.6f) {
                drawHandArrow(
                    start = Offset(nx + nodeW + 4f, ny + nodeH / 2f),
                    end = Offset(nx + nodeW + gap - 4f, ny + nodeH / 2f),
                    color = accent,
                    strokeWidth = 3.5f
                )
            }
        }
    } else {
        drawShapeEvent(payload, left, top, boxW, boxH, progress, ink, textPaint, scale)
    }
}

private fun DrawScope.drawGraphEvent(
    payload: String,
    left: Float,
    top: Float,
    boxW: Float,
    boxH: Float,
    progress: Float,
    ink: Color,
    accent: Color,
    textPaint: Paint,
    scale: Float
) {
    val gw = boxW.coerceAtLeast(160f)
    val gh = boxH.coerceAtLeast(150f)
    val cx = left + gw / 2f
    val cy = top + gh / 2f

    drawHandArrow(Offset(left, cy), Offset(left + gw, cy), ink.copy(alpha = 0.55f), 2.8f)
    drawHandArrow(Offset(cx, top + gh), Offset(cx, top), ink.copy(alpha = 0.55f), 2.8f)

    val lower = payload.lowercase()
    val curve = Path()
    val steps = 40
    val maxIdx = (steps * progress).toInt().coerceAtLeast(1)
    var lastTip: Offset? = null
    for (i in 0..maxIdx) {
        val t = (i.toFloat() / steps) * 2f - 1f
        val yNorm = when {
            "x^2" in lower || "x²" in lower -> -(t * t) * 0.7f
            "sin" in lower -> -sin(t * PI).toFloat() * 0.65f
            "-" in lower -> t * 0.65f
            else -> -t * 0.7f
        }
        val px = cx + t * (gw * 0.44f)
        val py = cy + yNorm * (gh * 0.44f)
        if (i == 0) curve.moveTo(px, py) else curve.lineTo(px, py)
        lastTip = Offset(px, py)
    }
    drawPath(curve, color = accent, style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (progress < 0.99f && lastTip != null) {
        drawStylusNib(lastTip, accent, ink)
    }

    drawHandwrittenLinesAnimated(
        paint = textPaint,
        text = formatMathSymbols(payload),
        x = left + 10f,
        y = top + 4f,
        maxWidth = gw - 20f,
        textSizePx = (size.width * 0.031f * scale).coerceIn(18f, 34f),
        color = accent,
        progress = progress
    )
}
