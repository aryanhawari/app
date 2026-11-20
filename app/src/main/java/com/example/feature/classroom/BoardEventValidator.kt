package com.example.feature.classroom

import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.NormalizedRect
import org.json.JSONArray
import org.json.JSONObject

object BoardEventValidator {

    private const val MAX_PAYLOAD_LENGTH = 360
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val SCRIPT_PROTOCOL_REGEX = Regex("(?i)javascript:")

    data class ValidationResult(
        val accepted: List<BoardEvent>,
        val rejectedCount: Int
    )

    fun sanitizePayload(raw: String): String {
        return raw
            .replace(HTML_TAG_REGEX, "")
            .replace(SCRIPT_PROTOCOL_REGEX, "")
            .replace("\u0000", "")
            .trim()
            .take(MAX_PAYLOAD_LENGTH)
    }

    fun validateBatch(
        candidates: List<BoardEvent>,
        existingEventIds: Set<String>
    ): ValidationResult {
        val seenIds = existingEventIds.toMutableSet()
        val accepted = mutableListOf<BoardEvent>()
        var rejected = 0
        var lastSeq = -1

        for ((index, candidate) in candidates.withIndex()) {
            val cleanId = candidate.eventId.trim().take(80)
            if (cleanId.isBlank() || !seenIds.add(cleanId)) {
                rejected++
                continue
            }

            val cleanPayload = sanitizePayload(candidate.payload)
            if (cleanPayload.isBlank() &&
                candidate.type != BoardEventType.WAIT_FOR_STUDENT &&
                candidate.type != BoardEventType.END_STEP &&
                candidate.type != BoardEventType.ERASE
            ) {
                rejected++
                continue
            }

            val rect = candidate.viewport
            if (rect.x.isNaN() || rect.y.isNaN() || rect.width.isNaN() || rect.height.isNaN()) {
                rejected++
                continue
            }

            val safeX = rect.x.coerceIn(0.02f, 0.92f)
            val safeY = rect.y.coerceIn(0.04f, 0.92f)
            val safeW = rect.width.coerceIn(0.04f, (0.98f - safeX).coerceAtLeast(0.04f))
            val safeH = rect.height.coerceIn(0.03f, (0.96f - safeY).coerceAtLeast(0.03f))

            val seq = if (candidate.sequence > lastSeq) candidate.sequence else lastSeq + 1
            lastSeq = seq

            accepted += candidate.copy(
                eventId = cleanId,
                sequence = seq,
                payload = cleanPayload,
                viewport = NormalizedRect(safeX, safeY, safeW, safeH),
                transcriptAnchor = sanitizePayload(candidate.transcriptAnchor).take(160),
                status = if (candidate.status == BoardEventStatus.CANCELLED) BoardEventStatus.CANCELLED else BoardEventStatus.QUEUED
            )

            if (accepted.size >= 20) break
        }

        return ValidationResult(accepted = accepted, rejectedCount = rejected)
    }
}

object BoardEventParser {

    fun parseFromJsonArray(
        jsonArray: JSONArray?,
        lessonId: String,
        stepId: String,
        startingY: Float,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): List<BoardEvent> {
        if (jsonArray == null) return emptyList()
        val parsed = mutableListOf<BoardEvent>()
        var nextY = startingY.coerceIn(0.06f, 0.78f)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val type = BoardEventType.fromRaw(obj.optString("type")) ?: continue
            val payload = obj.optString("payload", "")
            val eventId = obj.optString("eventId")
                .ifBlank { "${stepId}_${System.nanoTime()}_$i" }
            val seq = obj.optInt("sequence", i + 1)
            val anchor = obj.optString("transcriptAnchor", "")

            val x = obj.optDouble("x", 0.07).toFloat()
            val y = obj.optDouble("y", nextY.toDouble()).toFloat()
            val width = obj.optDouble("width", 0.84).toFloat()
            val height = obj.optDouble("height", 0.08).toFloat()

            parsed += BoardEvent(
                eventId = eventId,
                lessonId = lessonId,
                stepId = stepId,
                sequence = seq,
                type = type,
                payload = payload,
                viewport = NormalizedRect(
                    x = if (x.isNaN()) 0.07f else x,
                    y = if (y.isNaN()) nextY else y,
                    width = if (width.isNaN()) 0.84f else width,
                    height = if (height.isNaN()) 0.08f else height
                ),
                transcriptAnchor = anchor,
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = pageTitle
            )

            nextY = (y + height + 0.025f).coerceAtMost(0.86f)
        }
        return parsed
    }

    fun buildLocalFallbackEvents(
        explanation: String,
        lessonId: String,
        stepId: String,
        startingY: Float,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): List<BoardEvent> {
        val lines = explanation
            .split(Regex("(?<=[.!?।])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)

        var y = startingY.coerceIn(0.06f, 0.72f)
        return lines.mapIndexed { idx, line ->
            val looksLikeMath = line.contains('=') || line.contains('+') || line.contains('÷')
            val event = BoardEvent(
                eventId = "${stepId}_fb_${System.nanoTime()}_$idx",
                lessonId = lessonId,
                stepId = stepId,
                sequence = idx + 1,
                type = if (looksLikeMath) BoardEventType.WRITE_MATH else BoardEventType.WRITE_TEXT,
                payload = line,
                viewport = NormalizedRect(x = 0.07f, y = y, width = 0.85f, height = 0.08f),
                transcriptAnchor = line,
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = pageTitle
            )
            y = (y + 0.105f).coerceAtMost(0.84f)
            event
        }
    }

    fun buildSmartLiveNotes(
        spokenTranscript: String,
        lessonId: String,
        stepId: String,
        startingY: Float,
        pageIndex: Int = 0,
        pageTitle: String = ""
    ): List<BoardEvent> {
        val cleaned = spokenTranscript.trim()
        if (cleaned.length < 4) return emptyList()

        // Stream concise real-time spoken phrases and math steps immediately onto the smartboard
        val rawSegments = cleaned
            .split(Regex("(?<=[.!?।,])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length >= 4 }

        val segmentsToWrite = if (rawSegments.isNotEmpty()) {
            val mathSegments = rawSegments.filter {
                it.contains('=') || it.contains('+') || it.contains('-') || it.contains('×') ||
                    it.contains('÷') || it.any(Char::isDigit)
            }
            if (mathSegments.isNotEmpty() && rawSegments.size > 2) {
                rawSegments.take(3)
            } else {
                rawSegments.take(2)
            }
        } else {
            listOf(cleaned)
        }

        var y = startingY.coerceIn(0.06f, 0.82f)
        return segmentsToWrite.mapIndexed { idx, seg ->
            val concise = seg.take(110)
            val isMath = concise.contains('=') || concise.contains('+') || concise.contains('×') ||
                concise.contains('÷') || concise.any(Char::isDigit)
            val event = BoardEvent(
                eventId = "${stepId}_liveauto_${System.nanoTime()}_$idx",
                lessonId = lessonId,
                stepId = stepId,
                sequence = idx + 1,
                type = if (isMath) BoardEventType.WRITE_MATH else BoardEventType.WRITE_TEXT,
                payload = concise,
                viewport = NormalizedRect(x = 0.06f, y = y, width = 0.86f, height = 0.085f),
                transcriptAnchor = concise,
                status = BoardEventStatus.QUEUED,
                pageIndex = pageIndex,
                pageTitle = pageTitle
            )
            y = (y + 0.105f).coerceAtMost(0.86f)
            event
        }
    }
}
