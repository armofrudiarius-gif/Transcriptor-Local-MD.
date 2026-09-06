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
    BALANCED("Echilibrat", 14f, 1.8f, 0.32f, 0.38f, 0.08f, 0.50f),
    ACCURACY("Precizie maximă", 9f, 1.1f, 0.22f, 0.45f, 0.12f, 0.48f)
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
    val modelName: String = "Whisper Small multilingual INT8 / sherpa-onnx 1.13.7"
)
