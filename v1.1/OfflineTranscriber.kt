package md.localtranscript

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import kotlin.math.max
import kotlin.math.min

class OfflineTranscriber(private val assets: AssetManager) {
    companion object {
        private const val WHISPER_ENCODER = "models/whisper/small-encoder.int8.onnx"
        private const val WHISPER_DECODER = "models/whisper/small-decoder.int8.onnx"
        private const val WHISPER_TOKENS = "models/whisper/small-tokens.txt"
        private const val SEGMENTATION = "models/diarization/segmentation.onnx"
        private const val EMBEDDING = "models/diarization/embedding.onnx"
        private const val MAX_WHISPER_SECONDS = 27.5f
        private const val LANGUAGE_PROBE_SECONDS = 24f
    }

    fun transcribe(
        audio: AudioData,
        languageMode: LanguageMode,
        expectedSpeakers: Int?,
        onProgress: (Int, String) -> Unit
    ): List<TranscriptSegment> {
        validateAssets()
        onProgress(2, "Pregătire audio locală…")

        val diarizer = createDiarizer(expectedSpeakers)
        try {
            val targetRate = diarizer.sampleRate()
            val mono16k = Resampler.linear(audio.samples, audio.sampleRate, targetRate)
            val duration = mono16k.size.toFloat() / targetRate
            onProgress(5, "Separarea vorbitorilor…")

            val rawSegments = diarizer.processWithCallback(mono16k, callback = { done, total, _ ->
                val p = if (total <= 0) 25 else 5 + ((done.toDouble() / total) * 30).toInt()
                onProgress(p.coerceIn(5, 35), "Separarea vorbitorilor… $done/$total")
                0
            }).toList()

            val merged = normalizeAndMergeSegments(
                if (rawSegments.isEmpty()) {
                    listOf(OfflineSpeakerDiarizationSegment(0f, duration, 0))
                } else rawSegments,
                duration
            )
            val diarized = splitLongSegments(merged)

            val speakerMap = linkedMapOf<Int, Int>()
            diarized.forEach { seg -> speakerMap.getOrPut(seg.speaker) { speakerMap.size + 1 } }

            val speakerLanguages = determineSpeakerLanguages(
                mono16k = mono16k,
                sampleRate = targetRate,
                mergedSegments = merged,
                mode = languageMode,
                onProgress = onProgress
            )

            val recognizers = linkedMapOf<String, OfflineRecognizer>()
            try {
                val result = ArrayList<TranscriptSegment>(diarized.size)
                diarized.forEachIndexed { index, seg ->
                    val durationSec = seg.end - seg.start
                    if (durationSec < 0.45f) return@forEachIndexed

                    val speakerNumber = speakerMap.getValue(seg.speaker)
                    val language = speakerLanguages[seg.speaker] ?: when (languageMode) {
                        LanguageMode.RUSSIAN -> "ru"
                        else -> "ro"
                    }

                    val recognizer = recognizers.getOrPut(language) { createRecognizer(language) }
                    val edge = 0.06f
                    val start = max(0f, seg.start - edge)
                    val end = min(duration, seg.end + edge)
                    val from = (start * targetRate).toInt().coerceIn(0, mono16k.size)
                    val to = (end * targetRate).toInt().coerceIn(from, mono16k.size)
                    if (to - from < targetRate / 3) return@forEachIndexed

                    val chunk = mono16k.copyOfRange(from, to)
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(chunk, targetRate)
                        recognizer.decode(stream)
                        val rr = recognizer.getResult(stream)
                        val cleaned = TextPostProcessor.clean(rr.text)
                        if (isUsableTranscript(cleaned)) {
                            result += TranscriptSegment(
                                start = seg.start,
                                end = seg.end,
                                speaker = speakerNumber,
                                language = language,
                                text = cleaned
                            )
                        }
                    } finally {
                        stream.release()
                    }

                    val p = 44 + (((index + 1).toDouble() / diarized.size.coerceAtLeast(1)) * 55).toInt()
                    onProgress(p.coerceAtMost(99), "Transcriere locală ${index + 1}/${diarized.size}…")
                }
                onProgress(100, "Transcriere finalizată local.")
                return result.sortedBy { it.start }
            } finally {
                recognizers.values.forEach { runCatching { it.release() } }
            }
        } finally {
            diarizer.release()
        }
    }

    private fun determineSpeakerLanguages(
        mono16k: FloatArray,
        sampleRate: Int,
        mergedSegments: List<OfflineSpeakerDiarizationSegment>,
        mode: LanguageMode,
        onProgress: (Int, String) -> Unit
    ): Map<Int, String> {
        val speakers = mergedSegments.map { it.speaker }.distinct()
        if (mode == LanguageMode.ROMANIAN_MD) return speakers.associateWith { "ro" }
        if (mode == LanguageMode.RUSSIAN) return speakers.associateWith { "ru" }

        onProgress(37, "Detectare locală limbă RO/RU…")
        val detector = createLanguageDetector()
        try {
            val globalProbe = buildLanguageProbe(mono16k, sampleRate, mergedSegments, null)
            val global = detectSupportedLanguage(detector, globalProbe, sampleRate) ?: "ro"

            val out = linkedMapOf<Int, String>()
            speakers.forEachIndexed { i, speaker ->
                val probe = buildLanguageProbe(mono16k, sampleRate, mergedSegments, speaker)
                val detected = if (probe.size >= sampleRate * 2) {
                    detectSupportedLanguage(detector, probe, sampleRate)
                } else null
                out[speaker] = detected ?: global
                val p = 38 + (((i + 1).toFloat() / speakers.size.coerceAtLeast(1)) * 5).toInt()
                onProgress(p.coerceAtMost(43), "Limbă vorbitor ${i + 1}: ${(out[speaker] ?: global).uppercase()}")
            }
            return out
        } finally {
            detector.release()
        }
    }

    private fun createLanguageDetector(): SpokenLanguageIdentification {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        return SpokenLanguageIdentification(
            assetManager = assets,
            config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = WHISPER_ENCODER,
                    decoder = WHISPER_DECODER,
                    tailPaddings = 300
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu"
            )
        )
    }

    private fun detectSupportedLanguage(
        detector: SpokenLanguageIdentification,
        samples: FloatArray,
        sampleRate: Int
    ): String? {
        if (samples.isEmpty()) return null
        val stream = detector.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            when (detector.compute(stream).lowercase()) {
                "ro" -> "ro"
                "ru" -> "ru"
                else -> null
            }
        } finally {
            stream.release()
        }
    }

    private fun buildLanguageProbe(
        samples: FloatArray,
        sampleRate: Int,
        segments: List<OfflineSpeakerDiarizationSegment>,
        speaker: Int?
    ): FloatArray {
        val maxSamples = (LANGUAGE_PROBE_SECONDS * sampleRate).toInt()
        val silence = FloatArray((0.08f * sampleRate).toInt())
        val parts = ArrayList<FloatArray>()
        var total = 0

        for (seg in segments) {
            if (speaker != null && seg.speaker != speaker) continue
            if (seg.end - seg.start < 0.35f) continue
            val from = (seg.start * sampleRate).toInt().coerceIn(0, samples.size)
            var to = (seg.end * sampleRate).toInt().coerceIn(from, samples.size)
            if (total + (to - from) > maxSamples) {
                to = (from + (maxSamples - total)).coerceAtMost(samples.size)
            }
            if (to > from) {
                parts += samples.copyOfRange(from, to)
                total += to - from
                if (total < maxSamples) {
                    val s = min(silence.size, maxSamples - total)
                    if (s > 0) {
                        parts += if (s == silence.size) silence else silence.copyOf(s)
                        total += s
                    }
                }
            }
            if (total >= maxSamples) break
        }

        if (parts.isEmpty()) {
            return samples.copyOf(min(samples.size, maxSamples))
        }
        val out = FloatArray(total)
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }

    private fun createDiarizer(expectedSpeakers: Int?): OfflineSpeakerDiarization {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = SEGMENTATION,
                    windowShiftRatio = 0.1f
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = EMBEDDING,
                numThreads = threads,
                debug = false,
                provider = "cpu"
            ),
            clustering = FastClusteringConfig(
                numClusters = expectedSpeakers ?: -1,
                threshold = 0.5f
            ),
            minDurationOn = 0.25f,
            minDurationOff = 0.45f
        )
        return OfflineSpeakerDiarization(assetManager = assets, config = config)
    }

    private fun createRecognizer(language: String): OfflineRecognizer {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = WHISPER_ENCODER,
                    decoder = WHISPER_DECODER,
                    language = language,
                    task = "transcribe",
                    tailPaddings = 300,
                    enableTokenTimestamps = false,
                    enableSegmentTimestamps = false
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
                tokens = WHISPER_TOKENS
            ),
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(assetManager = assets, config = config)
    }

    private fun normalizeAndMergeSegments(
        input: List<OfflineSpeakerDiarizationSegment>,
        duration: Float
    ): List<OfflineSpeakerDiarizationSegment> {
        val clean = input
            .map {
                OfflineSpeakerDiarizationSegment(
                    start = it.start.coerceIn(0f, duration),
                    end = it.end.coerceIn(0f, duration),
                    speaker = it.speaker
                )
            }
            .filter { it.end > it.start }
            .sortedWith(compareBy<OfflineSpeakerDiarizationSegment> { it.start }.thenBy { it.end })

        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<OfflineSpeakerDiarizationSegment>()
        for (seg in clean) {
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == seg.speaker && seg.start - prev.end <= 0.35f) {
                out[out.lastIndex] = OfflineSpeakerDiarizationSegment(
                    start = prev.start,
                    end = max(prev.end, seg.end),
                    speaker = prev.speaker
                )
            } else {
                out += seg
            }
        }
        return out
    }

    private fun splitLongSegments(
        input: List<OfflineSpeakerDiarizationSegment>,
        maxSeconds: Float = MAX_WHISPER_SECONDS
    ): List<OfflineSpeakerDiarizationSegment> {
        val out = ArrayList<OfflineSpeakerDiarizationSegment>()
        for (seg in input) {
            var start = seg.start
            while (seg.end - start > maxSeconds) {
                out += OfflineSpeakerDiarizationSegment(start, start + maxSeconds, seg.speaker)
                start += maxSeconds
            }
            if (seg.end > start) out += OfflineSpeakerDiarizationSegment(start, seg.end, seg.speaker)
        }
        return out
    }

    private fun isUsableTranscript(text: String): Boolean {
        if (text.isBlank()) return false
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        if (text.length <= 2 && letters <= 1) return false
        return true
    }

    private fun validateAssets() {
        listOf(WHISPER_ENCODER, WHISPER_DECODER, WHISPER_TOKENS, SEGMENTATION, EMBEDDING).forEach { path ->
            assets.open(path, AssetManager.ACCESS_STREAMING).use { input ->
                require(input.read() >= 0) { "Model local lipsă: $path" }
            }
        }
    }
}
