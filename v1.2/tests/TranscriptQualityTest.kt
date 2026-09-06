package md.localtranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptQualityTest {
    @Test fun acceptsRomanianAndRussianScripts() {
        assertTrue(TranscriptQuality.isUsableTranscript("M-am osvobodit mai devreme."))
        assertTrue(TranscriptQuality.isUsableTranscript("Сегодня mergem la Chișinău."))
        assertEquals("ro/ru", TranscriptQuality.languageLabel("Сегодня mergem", "ro"))
    }

    @Test fun rejectsUnsupportedScriptHallucination() {
        assertFalse(TranscriptQuality.isUsableTranscript("آمو مقرر"))
    }

    @Test fun rejectsLongSingleWordLoops() {
        assertFalse(TranscriptQuality.isUsableTranscript("da da da da da da"))
    }
}
