package md.localtranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptV2Test {
    @Test
    fun vttAndCsvContainSpeakerAndOverlapMetadata() {
        val items = listOf(
            TranscriptSegment(1f, 2.5f, 1, "ro/ru", "m-am osvobodit", ConfidenceLabel.MEDIUM, true, listOf(1, 2))
        )
        val vtt = TranscriptFormatter.toVtt(items, mapOf(1 to "Ion"))
        val csv = TranscriptFormatter.toCsv(items, mapOf(1 to "Ion"))
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("Ion: m-am osvobodit"))
        assertTrue(csv.contains("RO-MD/RU"))
        assertTrue(csv.contains("true"))
    }

    @Test
    fun foreignScriptsAreRejectedButCyrillicAndLatinMixIsAllowed() {
        assertTrue(TranscriptQuality.isUsableTranscript("сейчас vorbesc cu el"))
        assertTrue(!TranscriptQuality.isUsableTranscript("مرحبا test"))
    }

    @Test
    fun lowSignalGetsLowConfidence() {
        val samples = FloatArray(16000) { 0.00001f }
        assertEquals(ConfidenceLabel.LOW, TranscriptQuality.estimateConfidence(samples, 0.1f, "text scurt"))
    }
}
