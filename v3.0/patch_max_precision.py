from pathlib import Path

root = Path("project")
asr = root / "app/src/main/java/md/localtranscript/OfflineTranscriber.kt"
ui = root / "app/src/main/java/md/localtranscript/MainActivity.kt"

text = asr.read_text(encoding="utf-8")

old = """            val merged = normalizeAndMergeSegments(sourceSegments, duration)\n            val diarized = splitForRecognition(merged, mono16k, targetRate, profile)\n"""
new = """            val merged = normalizeAndMergeSegments(sourceSegments, duration)\n\n            // v3.0: Whisper performs materially better when a short one-speaker utterance\n            // keeps its complete linguistic context. v2.0 split Accuracy mode at 9 s and\n            // at ~0.22 s pauses, which could fragment clear Moldovan Romanian phrases.\n            val oneSpeaker = speakerMap.size == 1\n            val diarized = if (profile == TranscriptionProfile.ACCURACY && oneSpeaker && duration <= 27.0f) {\n                val first = merged.minOfOrNull { it.start } ?: 0f\n                val last = merged.maxOfOrNull { it.end } ?: duration\n                val speaker = merged.firstOrNull()?.speaker ?: sourceSegments.first().speaker\n                listOf(\n                    OfflineSpeakerDiarizationSegment(\n                        start = max(0f, first - 0.20f),\n                        end = min(duration, last + 0.20f),\n                        speaker = speaker\n                    )\n                )\n            } else {\n                splitForRecognition(merged, mono16k, targetRate, profile)\n            }\n"""
if old not in text:
    raise SystemExit("Expected v2 diarization block not found")
text = text.replace(old, new, 1)

old_detector = """                    tailPaddings = 300\n                ),\n                numThreads = threads,"""
new_detector = """                    tailPaddings = 600\n                ),\n                numThreads = threads,"""
if old_detector not in text:
    raise SystemExit("Expected language-detector padding block not found")
text = text.replace(old_detector, new_detector, 1)

old_rec = """                        tailPaddings = 300,\n                        enableTokenTimestamps = false,"""
new_rec = """                        tailPaddings = when (profile) {\n                            TranscriptionProfile.RAPID -> 300\n                            TranscriptionProfile.BALANCED -> 600\n                            TranscriptionProfile.ACCURACY -> 1000\n                        },\n                        enableTokenTimestamps = false,"""
if old_rec not in text:
    raise SystemExit("Expected recognizer padding block not found")
text = text.replace(old_rec, new_rec, 1)

asr.write_text(text, encoding="utf-8")

text = ui.read_text(encoding="utf-8")
if "import android.graphics.Color" not in text:
    text = text.replace("import android.graphics.Typeface\n", "import android.graphics.Typeface\nimport android.graphics.Color\n")
text = text.replace("Transcriptor Local MD v2.0", "Transcriptor Local MD v3.0")
text = text.replace(
    "RO-MD + rusisme + Русский • diarizare • editor juridic • 100% local",
    "MAX PRECISION • RO-MD + rusisme + Русский • 100% offline"
)
text = text.replace(
    "        super.onCreate(savedInstanceState)\n        setContentView(buildUi())",
    "        super.onCreate(savedInstanceState)\n        window.statusBarColor = Color.rgb(18, 18, 18)\n        window.navigationBarColor = Color.rgb(18, 18, 18)\n        setContentView(buildUi())"
)
text = text.replace(
    "        val scroll = ScrollView(this)\n        val root = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(18), dp(18), dp(18), dp(28))\n        }",
    "        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 18, 18)) }\n        val root = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(18), dp(18), dp(18), dp(28))\n            setBackgroundColor(Color.rgb(18, 18, 18))\n        }"
)
ui.write_text(text, encoding="utf-8")

print("v3.0 max-precision and dark-theme source patch applied")
