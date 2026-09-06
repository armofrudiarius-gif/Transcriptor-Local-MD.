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

    private fun srtTime(sec: Float, comma: Boolean = true): String {
        val millis = (sec.coerceAtLeast(0f) * 1000).toLong()
        val h = millis / 3_600_000
        val m = (millis % 3_600_000) / 60_000
        val s = (millis % 60_000) / 1000
        val ms = millis % 1000
        return String.format(Locale.US, if (comma) "%02d:%02d:%02d,%03d" else "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    fun speakerName(id: Int, names: Map<Int, String>): String =
        names[id]?.takeIf { it.isNotBlank() } ?: "Vorbitor $id"

    private fun langLabel(language: String): String = when (language.lowercase()) {
        "ro" -> "RO-MD"
        "ru" -> "RU"
        else -> "RO-MD/RU"
    }

    private fun overlapSuffix(seg: TranscriptSegment): String = if (seg.overlap) {
        val ids = seg.overlapSpeakers.distinct().sorted().joinToString("+") { "V$it" }
        if (ids.isBlank()) " [VORBIRE SUPRAPUSĂ]" else " [VORBIRE SUPRAPUSĂ: $ids]"
    } else ""

    fun format(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEach { seg ->
            append("[").append(t(seg.start)).append("–").append(t(seg.end)).append("] ")
            append(speakerName(seg.speaker, names)).append(" [").append(langLabel(seg.language)).append("]")
            append(overlapSuffix(seg)).append(": ").append(seg.text)
            if (seg.confidence == ConfidenceLabel.LOW) append(" [încredere: scăzută]")
            append('\n')
        }
    }.trim()

    fun withoutTimestamps(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEach { seg ->
            append(speakerName(seg.speaker, names)).append(" [").append(langLabel(seg.language)).append("]")
            append(overlapSuffix(seg)).append(": ").append(seg.text).append('\n')
        }
    }.trim()

    fun legalFormat(items: List<TranscriptSegment>, names: Map<Int, String>, meta: TranscriptionMeta): String = buildString {
        append("TRANSCRIERE AUTOMATĂ OFFLINE\n")
        append("Fișier: ").append(meta.fileName).append('\n')
        append("Durata: ").append(t(meta.durationSeconds)).append('\n')
        append("SHA-256 audio: ").append(meta.audioSha256).append('\n')
        append("Motor: ").append(meta.modelName).append("\n\n")
        items.sortedBy { it.start }.forEachIndexed { index, seg ->
            append("Segment ").append(index + 1).append("\n")
            append(t(seg.start)).append(" – ").append(t(seg.end)).append('\n')
            append(speakerName(seg.speaker, names).uppercase()).append(" [").append(langLabel(seg.language)).append("]")
            if (seg.overlap) append(" [VORBIRE SUPRAPUSĂ]")
            append("\n").append(seg.text).append("\n\n")
        }
        append("Notă: transcriere generată automat. Pentru utilizare probatorie, conținutul trebuie confruntat cu înregistrarea audio originală.")
    }

    fun toSrt(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        items.sortedBy { it.start }.forEachIndexed { index, seg ->
            append(index + 1).append('\n')
            append(srtTime(seg.start)).append(" --> ").append(srtTime(seg.end)).append('\n')
            append(speakerName(seg.speaker, names)).append(": ").append(seg.text).append("\n\n")
        }
    }.trim()

    fun toVtt(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        append("WEBVTT\n\n")
        items.sortedBy { it.start }.forEach { seg ->
            append(srtTime(seg.start, false)).append(" --> ").append(srtTime(seg.end, false)).append('\n')
            append(speakerName(seg.speaker, names)).append(": ").append(seg.text).append("\n\n")
        }
    }.trim()

    fun toCsv(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        append("start,end,speaker,speaker_name,language,confidence,overlap,text\n")
        items.sortedBy { it.start }.forEach { seg ->
            append(String.format(Locale.US, "%.3f", seg.start)).append(',')
            append(String.format(Locale.US, "%.3f", seg.end)).append(',')
            append(seg.speaker).append(',')
            append(csvEscape(speakerName(seg.speaker, names))).append(',')
            append(csvEscape(langLabel(seg.language))).append(',')
            append(csvEscape(seg.confidence.label)).append(',')
            append(seg.overlap).append(',')
            append(csvEscape(seg.text)).append('\n')
        }
    }.trim()

    fun toJson(items: List<TranscriptSegment>, names: Map<Int, String> = emptyMap()): String = buildString {
        append("[\n")
        items.sortedBy { it.start }.forEachIndexed { index, seg ->
            append("  {\"start\":").append(String.format(Locale.US, "%.3f", seg.start))
            append(",\"end\":").append(String.format(Locale.US, "%.3f", seg.end))
            append(",\"speaker\":").append(seg.speaker)
            append(",\"speakerName\":\"").append(jsonEscape(speakerName(seg.speaker, names))).append("\"")
            append(",\"language\":\"").append(jsonEscape(langLabel(seg.language))).append("\"")
            append(",\"confidence\":\"").append(jsonEscape(seg.confidence.label)).append("\"")
            append(",\"overlap\":").append(seg.overlap)
            append(",\"overlapSpeakers\":[").append(seg.overlapSpeakers.joinToString(",")).append(']')
            append(",\"text\":\"").append(jsonEscape(seg.text)).append("\"}")
            if (index != items.lastIndex) append(',')
            append('\n')
        }
        append(']')
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

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
