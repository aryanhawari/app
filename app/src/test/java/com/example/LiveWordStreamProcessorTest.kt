package com.example

import com.example.feature.classroom.LiveWordStreamProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWordStreamProcessorTest {

    @Test
    fun incrementalDeltas_streamWordByWordImmediately() {
        val processor = LiveWordStreamProcessor()
        processor.startNewResponse(1L)

        val chunks = listOf("Hi,", " how", " are", " you?")
        val emitted = chunks.mapNotNull { chunk ->
            processor.processChunk(1L, chunk)?.currentBlockText
        }

        assertEquals(
            listOf("Hi,", "Hi, how", "Hi, how are", "Hi, how are you?"),
            emitted
        )
    }

    @Test
    fun cumulativeSnapshots_progressivelyRevealWithoutDuplication() {
        val processor = LiveWordStreamProcessor()
        processor.startNewResponse(1L)

        val snapshots = listOf(
            "Hi",
            "Hi, how",
            "Hi, how are",
            "Hi, how are you?",
            "Hi, how are you?" // duplicate final snapshot
        )
        val emitted = snapshots.mapNotNull { snap ->
            processor.processChunk(1L, snap)?.currentBlockText
        }

        assertEquals(
            listOf("Hi", "Hi, how", "Hi, how are", "Hi, how are you?"),
            emitted
        )
    }

    @Test
    fun partialWordSplits_updateInPlaceUntilBoundaryComplete() {
        val processor = LiveWordStreamProcessor()
        processor.startNewResponse(1L)

        val u1 = processor.processChunk(1L, "New")
        assertNotNull(u1)
        assertEquals("New", u1!!.currentBlockText)
        assertEquals(0, u1.completeWordsCount)
        assertEquals("New", u1.partialWord)

        val u2 = processor.processChunk(1L, "ton's ")
        assertNotNull(u2)
        assertEquals("Newton's", u2!!.currentBlockText)
        assertEquals(1, u2.completeWordsCount)
        assertEquals("", u2.partialWord)

        val u3 = processor.processChunk(1L, "Sec")
        assertNotNull(u3)
        assertEquals("Newton's Sec", u3!!.currentBlockText)
        assertEquals(1, u3.completeWordsCount)
        assertEquals("Sec", u3.partialWord)

        val u4 = processor.processChunk(1L, "ond Law.")
        assertNotNull(u4)
        assertEquals("Newton's Second Law.", u4!!.currentBlockText)
    }

    @Test
    fun newtonsSecondLawExplanation_streamsInExactOrderAndSplitsLongBlocksCleanly() {
        val processor = LiveWordStreamProcessor(maxBlockCharsBeforeSplit = 90)
        processor.startNewResponse(1L)

        val tokens = listOf(
            "Newton's", " Second", " Law", " states", " that", " force", " equals", " mass",
            " times", " acceleration.", " In", " formula", " form,", " we", " write",
            " F", " =", " m", " ×", " a.", " So", " heavier", " objects", " need",
            " more", " force", " to", " accelerate."
        )

        val allBlocks = mutableListOf<String>()
        var latestActive = ""
        for (token in tokens) {
            val update = processor.processChunk(1L, token) ?: continue
            val sealed = update.sealedPrefixText
            if (!sealed.isNullOrBlank()) {
                allBlocks += sealed
            }
            latestActive = update.currentBlockText
        }
        if (latestActive.isNotBlank()) {
            allBlocks += latestActive
        }

        val combined = allBlocks.joinToString(" ")
        assertEquals(
            "Newton's Second Law states that force equals mass times acceleration. " +
                "In formula form, we write F = m × a. " +
                "So heavier objects need more force to accelerate.",
            combined
        )
        assertTrue(allBlocks.size >= 2)
    }

    @Test
    fun interruption_advancesResponseIdAndIgnoresStaleChunks() {
        val processor = LiveWordStreamProcessor()
        processor.startNewResponse(1L)

        val first = processor.processChunk(1L, "Newton's first ")
        val second = processor.processChunk(1L, "law says")
        assertEquals("Newton's first law says", second?.currentBlockText)

        // Student interrupts: new response starts with responseId = 2L
        processor.startNewResponse(2L)

        // Late/cancelled chunks from responseId 1L MUST be dropped
        assertNull(processor.processChunk(1L, " an object stays"))
        assertNull(processor.processChunk(1L, " in motion."))

        // New response chunks arrive cleanly with responseId = 2L
        val newUpdate1 = processor.processChunk(2L, "Sure,")
        val newUpdate2 = processor.processChunk(2L, " let's look at F = m * a.")
        assertEquals("Sure,", newUpdate1?.currentBlockText)
        assertEquals("Sure, let's look at F = m * a.", newUpdate2?.currentBlockText)
        assertNotNull(first)
    }

    @Test
    fun punctuationRevisedShortPrefix_doesNotDuplicateWords() {
        val processor = LiveWordStreamProcessor()
        processor.startNewResponse(5L)

        val u1 = processor.processChunk(5L, "Hi,")
        val u2 = processor.processChunk(5L, "Hi how")
        val u3 = processor.processChunk(5L, "Hi how are you?")

        assertEquals("Hi,", u1?.currentBlockText)
        assertEquals("Hi how", u2?.currentBlockText)
        assertEquals("Hi how are you?", u3?.currentBlockText)
    }
}
