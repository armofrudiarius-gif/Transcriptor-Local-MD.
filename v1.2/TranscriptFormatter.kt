package md.localtranscript

import java.util.Locale

object TranscriptFormatter {
    private fun t(sec: Float): String {
        val total = sec.coerceAtLeast(0f).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun srtTime(sec: Float): String {
        val millis = (sec.coerceAtLeast(0f) * 1000).toLong()
        val h = millis / 3_600_000
        val m = (millis % 3_600_000) / 60_000
        val s = (millis % 60_000) / 1000
        val ms = millis % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    private fun speakerName(id: Int, names: Map<Int, String>): String =
        names[id]?.takeIf { it.isNotBlank() } ?: "Vorbitor $id"

    private fun langLabel(language: String): String = when (language.lowercase()) {
        "ro" -> "RO-MD"
        "ru" -> "RU"
        else -> "RO-MD/RU"
    }

    fun format(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEach { seg ->
            append("[").append(t(seg.start)).append("–").append(t(seg.end)).append("] ")
            append(speakerName(seg.speaker, names)).append(" [").append(langLabel(seg.language)).append("]: ")
            append(seg.text).append('\n')
        }
    }.trim()

    fun withoutTimestamps(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEach { seg ->
            append(speakerName(seg.speaker, names)).append(" [").append(langLabel(seg.language)).append("]: ")
            append(seg.text).append('\n')
        }
    }.trim()

    fun toSrt(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEachIndexed { index, seg ->
            append(index + 1).append('\n')
            append(srtTime(seg.start)).append(" --> ").append(srtTime(seg.end)).append('\n')
            append(speakerName(seg.speaker, names)).append(": ").append(seg.text).append("\n\n")
        }
    }.trim()

    fun toJson(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        append("[\n")
        items.sortedBy { it.start }.forEachIndexed { index, seg ->
            append("  {\"start\":").append(String.format(Locale.US, "%.3f", seg.start))
            append(",\"end\":").append(String.format(Locale.US, "%.3f", seg.end))
            append(",\"speaker\":").append(seg.speaker)
            append(",\"speakerName\":\"").append(jsonEscape(speakerName(seg.speaker, names))).append("\"")
            append(",\"language\":\"").append(jsonEscape(seg.language)).append("\"")
            append(",\"text\":\"").append(jsonEscape(seg.text)).append("\"}")
            if (index != items.lastIndex) append(',')
            append('\n')
        }
        append(']')
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append(String.format(Locale.US, "\\u%04x", c.code)) else append(c)
            }
        }
    }
}
