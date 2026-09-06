package md.localtranscript

import kotlin.math.sqrt

object TranscriptQuality {
    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }

    fun isSpeechLikely(samples: FloatArray, globalRms: Float): Boolean {
        if (samples.isEmpty()) return false
        val r = rms(samples)
        var peak = 0f
        for (s in samples) peak = maxOf(peak, kotlin.math.abs(s))
        val adaptiveFloor = maxOf(0.0007f, globalRms * 0.045f)
        return r >= adaptiveFloor && peak >= adaptiveFloor * 2.2f
    }

    fun isUsableTranscript(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        val letters = clean.filter { it.isLetter() }
        if (letters.size < 2) return false
        if (letters.any { !isLatin(it) && !isCyrillic(it) }) return false

        val tokens = clean.lowercase().split(Regex("\\s+")).filter { it.any(Char::isLetter) }
        if (tokens.size >= 5) {
            var run = 1
            for (i in 1 until tokens.size) {
                if (tokens[i] == tokens[i - 1]) {
                    run++
                    if (run >= 5) return false
                } else run = 1
            }
        }
        return true
    }

    fun languageLabel(text: String, fallback: String): String {
        var latin = 0
        var cyr = 0
        for (c in text) when {
            c.isLetter() && isLatin(c) -> latin++
            c.isLetter() && isCyrillic(c) -> cyr++
        }
        return when {
            latin > 0 && cyr > 0 -> "ro/ru"
            cyr > 0 -> "ru"
            latin > 0 -> "ro"
            else -> fallback
        }
    }

    internal fun isLatin(c: Char): Boolean {
        val code = c.code
        return code in 0x0041..0x007A || code in 0x00C0..0x024F || code in 0x1E00..0x1EFF
    }

    internal fun isCyrillic(c: Char): Boolean = c.code in 0x0400..0x052F
}
