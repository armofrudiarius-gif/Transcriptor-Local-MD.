package md.localtranscript

import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFormatterTest {
    private val items = listOf(
        TranscriptSegment(1.2f, 3.4f, 1, "ro", "Salut."),
        TranscriptSegment(3.5f, 5.0f, 2, "ru", "Привет.")
    )

    @Test fun speakerNamesAndRoMdLabelAreUsed() {
        val text = TranscriptFormatter.format(items, mapOf(1 to "Ion"))
        assertTrue(text.contains("Ion [RO-MD]: Salut."))
        assertTrue(text.contains("Vorbitor 2 [RU]: Привет."))
    }

    @Test fun srtAndJsonExportsAreStructured() {
        val srt = TranscriptFormatter.toSrt(items)
        val json = TranscriptFormatter.toJson(items)
        assertTrue(srt.contains("00:00:01,200 --> 00:00:03,400"))
        assertTrue(json.contains("\"speaker\":1"))
        assertTrue(json.contains("Привет"))
    }
}
