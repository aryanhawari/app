package com.example

import com.example.domain.model.BoardEvent
import com.example.domain.model.BoardEventStatus
import com.example.domain.model.BoardEventType
import com.example.domain.model.NormalizedRect
import com.example.feature.classroom.BoardEventParser
import com.example.feature.classroom.BoardEventValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardEventValidatorTest {

    @Test
    fun validateBatch_deduplicatesIdsAndSanitizesUnsafeHtmlAndClampsCoordinates() {
        val candidates = listOf(
            BoardEvent(
                eventId = "evt_1",
                lessonId = "lesson_a",
                stepId = "step_1",
                sequence = 1,
                type = BoardEventType.WRITE_MATH,
                payload = "<script>alert(1)</script>2x + 5 = 15",
                viewport = NormalizedRect(x = -0.2f, y = 0.1f, width = 2.0f, height = 0.08f)
            ),
            BoardEvent(
                eventId = "evt_1", // duplicate ID
                lessonId = "lesson_a",
                stepId = "step_1",
                sequence = 2,
                type = BoardEventType.WRITE_TEXT,
                payload = "Duplicate",
                viewport = NormalizedRect(0.1f, 0.2f, 0.5f, 0.08f)
            ),
            BoardEvent(
                eventId = "evt_2",
                lessonId = "lesson_a",
                stepId = "step_1",
                sequence = 1, // non-monotonic sequence should be repaired
                type = BoardEventType.WRITE_TEXT,
                payload = "Subtract 5 from both sides",
                viewport = NormalizedRect(0.08f, 0.25f, 0.8f, 0.08f)
            )
        )

        val result = BoardEventValidator.validateBatch(candidates, existingEventIds = setOf("evt_old"))
        assertEquals(2, result.accepted.size)
        assertEquals(1, result.rejectedCount)

        val first = result.accepted[0]
        assertFalse(first.payload.contains("<script>"))
        assertEquals("alert(1)2x + 5 = 15", first.payload)
        assertTrue(first.viewport.x >= 0.02f)
        assertTrue(first.viewport.x + first.viewport.width <= 1.0f)

        val second = result.accepted[1]
        assertEquals(2, second.sequence)
        assertEquals(BoardEventStatus.QUEUED, second.status)
    }

    @Test
    fun buildLocalFallbackEvents_producesValidOrderedEvents() {
        val events = BoardEventParser.buildLocalFallbackEvents(
            explanation = "First subtract 5. Then divide by 2.",
            lessonId = "lesson_1",
            stepId = "step_1",
            startingY = 0.1f
        )
        assertEquals(2, events.size)
        assertEquals(1, events[0].sequence)
        assertEquals(2, events[1].sequence)
        assertTrue(events[1].viewport.y > events[0].viewport.y)
    }

    @Test
    fun buildSmartLiveNotes_extractsMathEquationsForHandwrittenBoard() {
        val events = BoardEventParser.buildSmartLiveNotes(
            spokenTranscript = "देखो सबसे पहले 2 + 2 = 4 होता है। फिर x + y = 2 हल करते हैं।",
            lessonId = "lesson_live",
            stepId = "live_1",
            startingY = 0.12f
        )
        assertEquals(2, events.size)
        assertEquals(BoardEventType.WRITE_MATH, events[0].type)
        assertEquals(BoardEventType.WRITE_MATH, events[1].type)
    }

    @Test
    fun normalizeVoiceSelection_mapsLegacyNamesToKore() {
        assertEquals("Kore", com.example.domain.model.GurivaSubjects.normalizeVoiceSelection("Warm Mentor"))
        assertEquals("Kore", com.example.domain.model.GurivaSubjects.normalizeVoiceSelection(""))
        assertEquals("Sulafat", com.example.domain.model.GurivaSubjects.normalizeVoiceSelection("Sulafat"))
    }

    @Test
    fun buildSmartLiveNotes_streamsShortRealtimePhrases() {
        val events = BoardEventParser.buildSmartLiveNotes(
            spokenTranscript = "x = 5",
            lessonId = "lesson_live",
            stepId = "live_2",
            startingY = 0.08f,
            pageIndex = 1,
            pageTitle = "Page 2"
        )
        assertEquals(1, events.size)
        assertEquals(BoardEventType.WRITE_MATH, events[0].type)
        assertEquals(1, events[0].pageIndex)
        assertEquals("Page 2", events[0].pageTitle)
    }

    @Test
    fun estimateAudioDurationMs_calculatesPcmDuration() {
        // 24000 Hz * 2 bytes/sample = 48000 bytes for 1 second (1000ms)
        val oneSecondPcm = ByteArray(48000)
        val ms = com.example.core.audio.TeacherAudioPlayer.estimateAudioDurationMs(
            audioBytes = oneSecondPcm,
            mimeType = "audio/pcm;rate=24000"
        )
        assertEquals(1000L, ms)
    }
}
