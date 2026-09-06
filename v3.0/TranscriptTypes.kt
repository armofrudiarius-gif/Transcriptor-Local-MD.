package md.localtranscript

enum class LanguageMode(val label: String, val whisperCode: String) {
    MIXED("Automat RO-MD / RU", ""),
    ROMANIAN_MD("Română (Republica Moldova)", "ro"),
    RUSSIAN("Русский", "ru")
}

enum class TranscriptionProfile(
    val label: String,
    val maxChunkSeconds: Float,
    val minLanguageProbeSeconds: Float,
    val minPauseSeconds: Float,
    val mergeGapSeconds: Float,
    val edgePaddingSeconds: Float,
    val diarizationThreshold: Float
) {
    RAPID("Rapid", 20f, 3.0f, 0.45f, 0.30f, 0.05f, 0.50f),
    BALANCED("Echilibrat", 20f, 2.5f, 0.48f, 0.55f, 0.16f, 0.50f),
    ACCURACY("Precizie maximă — context extins", 26f, 3.5f, 0.65f, 0.85f, 0.30f, 0.48f)
}

enum class ConfidenceLabel(val label: String) {
    HIGH("Ridicată"),
    MEDIUM("Medie"),
    LOW("Scăzută")
}

data class TranscriptSegment(
    val start: Float,
    val end: Float,
    val speaker: Int,
    val language: String,
    val text: String,
    val confidence: ConfidenceLabel = ConfidenceLabel.MEDIUM,
    val overlap: Boolean = false,
    val overlapSpeakers: List<Int> = emptyList()
)

data class TranscriptionMeta(
    val fileName: String,
    val durationSeconds: Float,
    val audioSha256: String,
    val modelName: String = "Whisper Small multilingual INT8 / sherpa-onnx 1.13.7 / v3 context-max"
)
