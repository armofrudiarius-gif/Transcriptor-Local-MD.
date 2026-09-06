package md.localtranscript

import org.junit.Assert.assertTrue
import org.junit.Test

class MaxPrecisionConfigTest {
    @Test
    fun accuracyProfileKeepsLongWhisperContext() {
        val p = TranscriptionProfile.ACCURACY
        assertTrue("Accuracy mode must use near-native Whisper context", p.maxChunkSeconds >= 25f)
        assertTrue("Accuracy mode must not split on micro-pauses", p.minPauseSeconds >= 0.60f)
        assertTrue("Accuracy mode needs boundary context", p.edgePaddingSeconds >= 0.25f)
        assertTrue("Accuracy mode should merge natural same-speaker gaps", p.mergeGapSeconds >= 0.75f)
    }
}
