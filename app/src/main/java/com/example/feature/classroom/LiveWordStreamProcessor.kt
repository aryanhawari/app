package com.example.feature.classroom

/**
 * Result emitted immediately on every incoming Gemini Live output transcription chunk.
 */
data class WordStreamUpdate(
    val responseId: Long,
    val fullResponseText: String,
    val sealedPrefixText: String?,
    val currentBlockText: String,
    val completeWordsCount: Int,
    val partialWord: String,
    val hasChange: Boolean
)

/**
 * Real-time incremental text processor + word boundary detector for Gemini Live output transcription.
 *
 * Handles:
 * - True incremental deltas (e.g. "Hi", ", ", "how", " are", " you?")
 * - Cumulative transcription snapshots (e.g. "Hi", "Hi, how", "Hi, how are", "Hi, how are you?")
 * - Partial-word updates (e.g. "New" -> "Newton's", or cumulative "Hi, ho" -> "Hi, how")
 * - Duplicate final snapshots (ignored without repeating words)
 * - Long-response paragraph/sentence block splitting without losing or duplicating words
 */
class LiveWordStreamProcessor(
    private val maxBlockCharsBeforeSplit: Int = 240
) {
    private var activeResponseId: Long = 0L
    private val rawFullBuffer = StringBuilder()
    private var currentBlockStartOffset: Int = 0

    fun startNewResponse(responseId: Long) {
        activeResponseId = responseId
        rawFullBuffer.clear()
        currentBlockStartOffset = 0
    }

    fun currentResponseId(): Long = activeResponseId

    fun currentFullText(): String = normalizeDisplayText(rawFullBuffer.toString())

    fun processChunk(responseId: Long, incomingRaw: String): WordStreamUpdate? {
        if (responseId != activeResponseId) return null
        if (incomingRaw.isEmpty()) return null

        val prevRaw = rawFullBuffer.toString()
        val updatedRaw = reconcileIncomingTranscription(prevRaw, incomingRaw) ?: return null
        if (updatedRaw == prevRaw) return null

        rawFullBuffer.clear()
        rawFullBuffer.append(updatedRaw)

        // Ensure block split offset remains valid if a prefix revision shortened slightly
        if (currentBlockStartOffset > updatedRaw.length) {
            currentBlockStartOffset = 0
        }

        var sealedPrefix: String? = null
        val slice = updatedRaw.substring(currentBlockStartOffset)

        // Split cleanly into a new board block ONLY at an already-completed paragraph/sentence break
        // once the current block is sufficiently long so nothing ever buffers or waits for a sentence.
        val splitPoint = findCompletedSplitPoint(slice, maxBlockCharsBeforeSplit)
        if (splitPoint > 0) {
            val finishedPart = normalizeDisplayText(slice.substring(0, splitPoint))
            if (finishedPart.isNotBlank()) {
                sealedPrefix = finishedPart
                currentBlockStartOffset += splitPoint
                while (currentBlockStartOffset < updatedRaw.length && updatedRaw[currentBlockStartOffset].isWhitespace()) {
                    currentBlockStartOffset++
                }
            }
        }

        val activeSlice = updatedRaw.substring(currentBlockStartOffset)
        val normalizedBlock = normalizeDisplayText(activeSlice)
        val normalizedFull = normalizeDisplayText(updatedRaw)
        val boundaryInfo = analyzeWordBoundaries(activeSlice)

        return WordStreamUpdate(
            responseId = responseId,
            fullResponseText = normalizedFull,
            sealedPrefixText = sealedPrefix,
            currentBlockText = normalizedBlock,
            completeWordsCount = boundaryInfo.completeWords.size,
            partialWord = boundaryInfo.partialWord,
            hasChange = true
        )
    }

    companion object {
        private val COMPLETE_WORD_REGEX = Regex("\\S+")

        data class WordBoundarySnapshot(
            val completeWords: List<String>,
            val partialWord: String
        )

        /**
         * Reconciles an incoming transcription chunk against [prevRaw], handling:
         * 1. Exact duplicate snapshot
         * 2. Cumulative snapshot extending [prevRaw]
         * 3. Trailing duplicate/regression snapshot contained inside [prevRaw]
         * 4. Cumulative minor prefix revision (casing/punctuation refinement on snapshot)
         * 5. Suffix-to-prefix partial overlap or pure incremental delta append
         */
        fun reconcileIncomingTranscription(prevRaw: String, incomingRaw: String): String? {
            if (incomingRaw.isEmpty()) return null
            if (prevRaw.isEmpty()) return incomingRaw

            // 1. Exact duplicate cumulative snapshot
            if (incomingRaw == prevRaw) return null

            // 2. Standard cumulative snapshot: incoming starts with prevRaw
            if (incomingRaw.startsWith(prevRaw)) {
                return incomingRaw
            }

            // 3. Cumulative snapshot that slightly lags prevRaw (missing trailing char/space)
            if (prevRaw.startsWith(incomingRaw) && (prevRaw.length - incomingRaw.length) <= 4) {
                return null
            }

            // 4. Cumulative revision (e.g. capitalization or punctuation updated across full snapshot)
            val commonPrefix = commonPrefixLengthIgnoreCase(prevRaw, incomingRaw)
            if (incomingRaw.length >= prevRaw.length &&
                commonPrefix >= maxOf(6, (prevRaw.length * 0.72f).toInt())
            ) {
                return incomingRaw
            }

            // 4b. Short-prefix punctuation refinement (e.g. "Hi," -> "Hi how" or "Hi, how")
            if (!incomingRaw.first().isWhitespace()) {
                val normPrevWords = stripPunctuationForPrefix(prevRaw)
                val normInWords = stripPunctuationForPrefix(incomingRaw)
                if (normPrevWords.isNotEmpty() &&
                    normInWords.startsWith(normPrevWords, ignoreCase = true) &&
                    normInWords.length > normPrevWords.length
                ) {
                    return incomingRaw
                }
            }

            // 5. Final full-sentence snapshot repeated when prevRaw only differs in leading whitespace
            val trimmedPrev = prevRaw.trimStart()
            val trimmedIncoming = incomingRaw.trimStart()
            if (trimmedPrev.isNotEmpty() && trimmedIncoming == trimmedPrev) {
                return null
            }
            if (trimmedPrev.isNotEmpty() && trimmedIncoming.startsWith(trimmedPrev) && trimmedIncoming.length > trimmedPrev.length) {
                return incomingRaw
            }

            // 6. Standard incremental delta append (preserves spaces, punctuation, and mid-word splits)
            return prevRaw + incomingRaw
        }

        private fun stripPunctuationForPrefix(text: String): String {
            return text.trim()
                .replace(Regex("[,.;:!?।\"'()]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun analyzeWordBoundaries(text: String): WordBoundarySnapshot {
            if (text.isBlank()) return WordBoundarySnapshot(emptyList(), "")
            val endsWithBoundary = text.last().isWhitespace()
            val tokens = COMPLETE_WORD_REGEX.findAll(text).map { it.value }.toList()
            if (tokens.isEmpty()) return WordBoundarySnapshot(emptyList(), "")
            return if (endsWithBoundary) {
                WordBoundarySnapshot(completeWords = tokens, partialWord = "")
            } else {
                WordBoundarySnapshot(
                    completeWords = tokens.dropLast(1),
                    partialWord = tokens.last()
                )
            }
        }

        fun normalizeDisplayText(raw: String): String {
            if (raw.isEmpty()) return ""
            // Collapse accidental double spaces while preserving single spaces and newlines
            return raw
                .replace(Regex("[ \\t]{2,}"), " ")
                .trimStart()
                .trimEnd { it == ' ' || it == '\t' || it == '\r' }
        }

        fun looksLikeMathText(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            return trimmed.contains('=') ||
                trimmed.contains('×') ||
                trimmed.contains('÷') ||
                trimmed.contains('≤') ||
                trimmed.contains('≥') ||
                trimmed.contains('≠')
        }

        private fun commonPrefixLengthIgnoreCase(a: String, b: String): Int {
            val max = minOf(a.length, b.length)
            var i = 0
            while (i < max && a[i].equals(b[i], ignoreCase = true)) {
                i++
            }
            return i
        }

        private fun findCompletedSplitPoint(slice: String, thresholdChars: Int): Int {
            val paraBreak = slice.indexOf("\n\n")
            if (paraBreak >= 12) return paraBreak + 2
            if (slice.length < thresholdChars) return -1

            // Split only AFTER a completed sentence ending + trailing space so the active word is complete
            val match = Regex("[.!?।]\\s+").findAll(slice).lastOrNull { it.range.last + 1 >= thresholdChars / 2 }
            return if (match != null) match.range.last + 1 else -1
        }
    }
}
