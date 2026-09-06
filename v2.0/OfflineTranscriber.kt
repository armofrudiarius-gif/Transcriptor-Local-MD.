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
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OfflineTranscriber(private val assets: AssetManager) {
    companion object {
        private const val WHISPER_ENCODER = "models/whisper/small-encoder.int8.onnx"
        private const val WHISPER_DECODER = "models/whisper/small-decoder.int8.onnx"
        private const val WHISPER_TOKENS = "models/whisper/small-tokens.txt"
        private const val SEGMENTATION = "models/diarization/segmentation.onnx"
        private const val EMBEDDING = "models/diarization/embedding.onnx"
        private const val LANGUAGE_PROBE_SECONDS = 24f
    }

    private data class OverlapRange(val start: Float, val end: Float, val speakers: List<Int>)

    fun transcribe(
        audio: AudioData,
        languageMode: LanguageMode,
        expectedSpeakers: Int?,
        profile: TranscriptionProfile,
        preferredTerms: List<String>,
        resumeSegments: List<TranscriptSegment> = emptyList(),
        onProgress: (Int, String) -> Unit,
        onCheckpoint: (List<TranscriptSegment>) -> Unit = {},
        shouldPause: () -> Boolean = { false },
        shouldCancel: () -> Boolean = { false }
    ): List<TranscriptSegment> {
        validateAssets()
        waitIfPaused(shouldPause, shouldCancel)
        onProgress(2, "Pregătire audio locală…")

        val diarizer = createDiarizer(expectedSpeakers, profile)
        try {
            val targetRate = diarizer.sampleRate()
            val mono16k = Resampler.linear(audio.samples, audio.sampleRate, targetRate)
            val duration = mono16k.size.toFloat() / targetRate
            val globalRms = TranscriptQuality.rms(mono16k)
            waitIfPaused(shouldPause, shouldCancel)
            onProgress(5, "Separarea vorbitorilor…")

            val rawSegments = diarizer.processWithCallback(mono16k, callback = callback@{ done, total, _ ->
                if (shouldCancel()) return@callback 1
                while (shouldPause()) {
                    if (shouldCancel()) return@callback 1
                    Thread.sleep(120)
                }
                val p = if (total <= 0) 25 else 5 + ((done.toDouble() / total) * 27).toInt()
                onProgress(p.coerceIn(5, 32), "Separarea vorbitorilor… $done/$total")
                0
            }).toList()
            waitIfPaused(shouldPause, shouldCancel)

            val sourceSegments = if (rawSegments.isEmpty()) {
                listOf(OfflineSpeakerDiarizationSegment(0f, duration, 0))
            } else rawSegments

            val speakerMap = linkedMapOf<Int, Int>()
            sourceSegments.sortedBy { it.start }.forEach { seg ->
                speakerMap.getOrPut(seg.speaker) { speakerMap.size + 1 }
            }
            val overlaps = findOverlaps(sourceSegments, speakerMap)

            val merged = normalizeAndMergeSegments(sourceSegments, duration)
            val diarized = splitForRecognition(merged, mono16k, targetRate, profile)

            val speakerLanguages = determineSpeakerLanguages(
                mono16k, targetRate, merged, languageMode, onProgress, shouldPause, shouldCancel
            )

            val languageDetector = if (languageMode == LanguageMode.MIXED) createLanguageDetector() else null
            val recognizers = linkedMapOf<String, OfflineRecognizer>()
            try {
                val rawResult = ArrayList<TranscriptSegment>(resumeSegments.size + diarized.size)
                rawResult += resumeSegments.sortedBy { it.start }
                val resumeUntil = resumeSegments.maxOfOrNull { it.end } ?: -1f

                diarized.forEachIndexed { index, seg ->
                    waitIfPaused(shouldPause, shouldCancel)
                    if (seg.end <= resumeUntil + 0.02f) return@forEachIndexed
                    val durationSec = seg.end - seg.start
                    if (durationSec < 0.48f) return@forEachIndexed

                    val edge = profile.edgePaddingSeconds
                    val start = max(0f, seg.start - edge)
                    val end = min(duration, seg.end + edge)
                    val from = (start * targetRate).toInt().coerceIn(0, mono16k.size)
                    val to = (end * targetRate).toInt().coerceIn(from, mono16k.size)
                    if (to - from < targetRate / 3) return@forEachIndexed

                    val chunk = mono16k.copyOfRange(from, to)
                    if (!TranscriptQuality.isSpeechLikely(chunk, globalRms)) return@forEachIndexed

                    val fallbackLanguage = speakerLanguages[seg.speaker] ?: when (languageMode) {
                        LanguageMode.RUSSIAN -> "ru"
                        else -> "ro"
                    }
                    val language = when (languageMode) {
                        LanguageMode.ROMANIAN_MD -> "ro"
                        LanguageMode.RUSSIAN -> "ru"
                        LanguageMode.MIXED -> if (durationSec >= profile.minLanguageProbeSeconds && languageDetector != null) {
                            detectSupportedLanguage(languageDetector, chunk, targetRate) ?: fallbackLanguage
                        } else fallbackLanguage
                    }

                    val recognizer = recognizers.getOrPut(language) { createRecognizer(language, profile) }
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(chunk, targetRate)
                        recognizer.decode(stream)
                        val rr = recognizer.getResult(stream)
                        var cleaned = TextPostProcessor.clean(rr.text)
                        cleaned = LocalLexicon.applyPreferredForms(cleaned, preferredTerms)
                        if (TranscriptQuality.isUsableTranscript(cleaned)) {
                            val overlapInfo = overlaps.filter { overlapDuration(it.start, it.end, seg.start, seg.end) >= 0.12f }
                            val overlapSpeakers = overlapInfo.flatMap { it.speakers }.distinct().sorted()
                            val item = TranscriptSegment(
                                start = seg.start,
                                end = seg.end,
                                speaker = speakerMap.getValue(seg.speaker),
                                language = TranscriptQuality.languageLabel(cleaned, language),
                                text = cleaned,
                                confidence = TranscriptQuality.estimateConfidence(chunk, globalRms, cleaned),
                                overlap = overlapSpeakers.size >= 2,
                                overlapSpeakers = overlapSpeakers
                            )
                            rawResult += item
                            onCheckpoint(mergeTranscriptSegments(rawResult.sortedBy { it.start }, profile.mergeGapSeconds))
                        }
                    } finally {
                        stream.release()
                    }

                    val p = 43 + (((index + 1).toDouble() / diarized.size.coerceAtLeast(1)) * 56).toInt()
                    onProgress(p.coerceAtMost(99), "Transcriere locală ${index + 1}/${diarized.size}…")
                }
                waitIfPaused(shouldPause, shouldCancel)
                val result = mergeTranscriptSegments(rawResult.sortedBy { it.start }, profile.mergeGapSeconds)
                onCheckpoint(result)
                onProgress(100, "Transcriere finalizată local.")
                return result
            } finally {
                languageDetector?.release()
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
        onProgress: (Int, String) -> Unit,
        shouldPause: () -> Boolean,
        shouldCancel: () -> Boolean
    ): Map<Int, String> {
        val speakers = mergedSegments.map { it.speaker }.distinct()
        if (mode == LanguageMode.ROMANIAN_MD) return speakers.associateWith { "ro" }
        if (mode == LanguageMode.RUSSIAN) return speakers.associateWith { "ru" }

        onProgress(34, "Detectare locală limbă RO/RU…")
        val detector = createLanguageDetector()
        try {
            val globalProbe = buildLanguageProbe(mono16k, sampleRate, mergedSegments, null)
            val global = detectSupportedLanguage(detector, globalProbe, sampleRate) ?: "ro"
            val out = linkedMapOf<Int, String>()
            speakers.forEachIndexed { i, speaker ->
                waitIfPaused(shouldPause, shouldCancel)
                val probe = buildLanguageProbe(mono16k, sampleRate, mergedSegments, speaker)
                val detected = if (probe.size >= sampleRate * 2) detectSupportedLanguage(detector, probe, sampleRate) else null
                out[speaker] = detected ?: global
                val p = 35 + (((i + 1).toFloat() / speakers.size.coerceAtLeast(1)) * 6).toInt()
                onProgress(p.coerceAtMost(41), "Limbă de bază vorbitor ${i + 1}: ${(out[speaker] ?: global).uppercase()}")
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

    private fun detectSupportedLanguage(detector: SpokenLanguageIdentification, samples: FloatArray, sampleRate: Int): String? {
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
            if (total + (to - from) > maxSamples) to = (from + (maxSamples - total)).coerceAtMost(samples.size)
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
        if (parts.isEmpty()) return samples.copyOf(min(samples.size, maxSamples))
        val out = FloatArray(total)
        var pos = 0
        parts.forEach { part -> part.copyInto(out, pos); pos += part.size }
        return out
    }

    private fun createDiarizer(expectedSpeakers: Int?, profile: TranscriptionProfile): OfflineSpeakerDiarization {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        return OfflineSpeakerDiarization(
            assetManager = assets,
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = SEGMENTATION, windowShiftRatio = 0.1f),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = EMBEDDING, numThreads = threads, debug = false, provider = "cpu"),
                clustering = FastClusteringConfig(numClusters = expectedSpeakers ?: -1, threshold = profile.diarizationThreshold),
                minDurationOn = 0.25f,
                minDurationOff = 0.42f
            )
        )
    }

    private fun createRecognizer(language: String, profile: TranscriptionProfile): OfflineRecognizer {
        val maxThreads = when (profile) {
            TranscriptionProfile.RAPID -> 4
            TranscriptionProfile.BALANCED -> 4
            TranscriptionProfile.ACCURACY -> 3
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, maxThreads)
        return OfflineRecognizer(
            assetManager = assets,
            config = OfflineRecognizerConfig(
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
        )
    }

    private fun normalizeAndMergeSegments(input: List<OfflineSpeakerDiarizationSegment>, duration: Float): List<OfflineSpeakerDiarizationSegment> {
        val clean = input.map {
            OfflineSpeakerDiarizationSegment(it.start.coerceIn(0f, duration), it.end.coerceIn(0f, duration), it.speaker)
        }.filter { it.end - it.start >= 0.15f }
            .sortedWith(compareBy<OfflineSpeakerDiarizationSegment> { it.start }.thenBy { it.end })
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<OfflineSpeakerDiarizationSegment>()
        for (seg in clean) {
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == seg.speaker && seg.start - prev.end <= 0.32f) {
                out[out.lastIndex] = OfflineSpeakerDiarizationSegment(prev.start, max(prev.end, seg.end), prev.speaker)
            } else out += seg
        }
        return out
    }

    private fun splitForRecognition(
        input: List<OfflineSpeakerDiarizationSegment>,
        samples: FloatArray,
        sampleRate: Int,
        profile: TranscriptionProfile
    ): List<OfflineSpeakerDiarizationSegment> {
        val out = ArrayList<OfflineSpeakerDiarizationSegment>()
        for (seg in input) {
            val pauseSplit = splitAtAcousticPauses(seg, samples, sampleRate, profile.minPauseSeconds)
            for (piece in pauseSplit) splitLongPiece(piece, samples, sampleRate, profile.maxChunkSeconds, out)
        }
        return out.sortedWith(compareBy<OfflineSpeakerDiarizationSegment> { it.start }.thenBy { it.end })
    }

    private fun splitAtAcousticPauses(
        seg: OfflineSpeakerDiarizationSegment,
        samples: FloatArray,
        sampleRate: Int,
        minPauseSeconds: Float
    ): List<OfflineSpeakerDiarizationSegment> {
        if (seg.end - seg.start < 2.4f) return listOf(seg)
        val from = (seg.start * sampleRate).toInt().coerceIn(0, samples.size)
        val to = (seg.end * sampleRate).toInt().coerceIn(from, samples.size)
        if (to <= from) return listOf(seg)
        val frame = (0.04f * sampleRate).toInt().coerceAtLeast(1)
        val minPauseFrames = (minPauseSeconds / 0.04f).toInt().coerceAtLeast(2)
        val segmentRms = TranscriptQuality.rms(samples.copyOfRange(from, to))
        val silenceThreshold = max(0.0008f, segmentRms * 0.16f)
        val splits = ArrayList<Float>()
        var silenceStartFrame = -1
        var frameIndex = 0
        var pos = from
        while (pos < to) {
            val end = min(pos + frame, to)
            var sum = 0.0
            for (i in pos until end) sum += samples[i] * samples[i]
            val r = sqrt(sum / max(1, end - pos)).toFloat()
            if (r < silenceThreshold) {
                if (silenceStartFrame < 0) silenceStartFrame = frameIndex
            } else if (silenceStartFrame >= 0) {
                val count = frameIndex - silenceStartFrame
                if (count >= minPauseFrames) {
                    val splitSec = seg.start + (silenceStartFrame + count / 2) * 0.04f
                    if (splitSec - seg.start >= 0.7f && seg.end - splitSec >= 0.7f) splits += splitSec
                }
                silenceStartFrame = -1
            }
            frameIndex++
            pos += frame
        }
        if (splits.isEmpty()) return listOf(seg)
        val result = ArrayList<OfflineSpeakerDiarizationSegment>()
        var start = seg.start
        for (split in splits.distinct().sorted()) {
            if (split - start >= 0.55f) {
                result += OfflineSpeakerDiarizationSegment(start, split, seg.speaker)
                start = split
            }
        }
        if (seg.end - start >= 0.35f) result += OfflineSpeakerDiarizationSegment(start, seg.end, seg.speaker)
        return if (result.isEmpty()) listOf(seg) else result
    }

    private fun splitLongPiece(
        seg: OfflineSpeakerDiarizationSegment,
        samples: FloatArray,
        sampleRate: Int,
        maxSeconds: Float,
        out: MutableList<OfflineSpeakerDiarizationSegment>
    ) {
        var start = seg.start
        while (seg.end - start > maxSeconds) {
            val target = start + maxSeconds
            val split = findLowEnergySplit(samples, sampleRate, start, seg.end, target, 1.5f)
            val safe = split.coerceIn(start + 1.0f, min(seg.end - 0.5f, target + 1.5f))
            out += OfflineSpeakerDiarizationSegment(start, safe, seg.speaker)
            start = safe
        }
        if (seg.end - start >= 0.35f) out += OfflineSpeakerDiarizationSegment(start, seg.end, seg.speaker)
    }

    private fun findLowEnergySplit(
        samples: FloatArray,
        sampleRate: Int,
        segmentStart: Float,
        segmentEnd: Float,
        target: Float,
        radius: Float
    ): Float {
        val left = max(segmentStart + 0.7f, target - radius)
        val right = min(segmentEnd - 0.5f, target + radius)
        if (right <= left) return target
        val step = (0.05f * sampleRate).toInt().coerceAtLeast(1)
        val win = (0.16f * sampleRate).toInt().coerceAtLeast(step)
        var bestTime = target
        var bestRms = Float.MAX_VALUE
        var center = (left * sampleRate).toInt()
        val endCenter = (right * sampleRate).toInt()
        while (center <= endCenter) {
            val from = (center - win / 2).coerceIn(0, samples.size)
            val to = (center + win / 2).coerceIn(from, samples.size)
            if (to > from) {
                var sum = 0.0
                for (i in from until to) sum += samples[i] * samples[i]
                val r = sqrt(sum / (to - from)).toFloat()
                if (r < bestRms) { bestRms = r; bestTime = center.toFloat() / sampleRate }
            }
            center += step
        }
        return bestTime
    }

    private fun findOverlaps(
        items: List<OfflineSpeakerDiarizationSegment>,
        speakerMap: Map<Int, Int>
    ): List<OverlapRange> {
        val sorted = items.sortedBy { it.start }
        val out = ArrayList<OverlapRange>()
        for (i in sorted.indices) {
            val a = sorted[i]
            for (j in i + 1 until sorted.size) {
                val b = sorted[j]
                if (b.start >= a.end) break
                if (a.speaker == b.speaker) continue
                val start = max(a.start, b.start)
                val end = min(a.end, b.end)
                if (end - start >= 0.12f) {
                    val speakers = listOfNotNull(speakerMap[a.speaker], speakerMap[b.speaker]).distinct().sorted()
                    out += OverlapRange(start, end, speakers)
                }
            }
        }
        return out
    }

    private fun overlapDuration(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float): Float =
        (min(aEnd, bEnd) - max(aStart, bStart)).coerceAtLeast(0f)

    private fun mergeTranscriptSegments(items: List<TranscriptSegment>, maxGap: Float): List<TranscriptSegment> {
        if (items.isEmpty()) return items
        val out = ArrayList<TranscriptSegment>()
        for (item in items) {
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == item.speaker && prev.language == item.language &&
                prev.overlap == item.overlap && item.start - prev.end in -0.15f..maxGap && item.end - prev.start <= 30f
            ) {
                out[out.lastIndex] = prev.copy(
                    end = max(prev.end, item.end),
                    text = TextPostProcessor.clean(prev.text + " " + item.text),
                    confidence = worse(prev.confidence, item.confidence),
                    overlapSpeakers = (prev.overlapSpeakers + item.overlapSpeakers).distinct().sorted()
                )
            } else out += item
        }
        return out
    }

    private fun worse(a: ConfidenceLabel, b: ConfidenceLabel): ConfidenceLabel {
        val rank = mapOf(ConfidenceLabel.HIGH to 0, ConfidenceLabel.MEDIUM to 1, ConfidenceLabel.LOW to 2)
        return if (rank.getValue(a) >= rank.getValue(b)) a else b
    }

    private fun waitIfPaused(shouldPause: () -> Boolean, shouldCancel: () -> Boolean) {
        while (shouldPause()) {
            checkCancelled(shouldCancel)
            Thread.sleep(150)
        }
        checkCancelled(shouldCancel)
    }

    private fun checkCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw CancellationException("Transcriere anulată")
    }

    private fun validateAssets() {
        listOf(WHISPER_ENCODER, WHISPER_DECODER, WHISPER_TOKENS, SEGMENTATION, EMBEDDING).forEach { path ->
            assets.open(path, AssetManager.ACCESS_STREAMING).use { input ->
                require(input.read() >= 0) { "Model local lipsă: $path" }
            }
        }
    }
}
