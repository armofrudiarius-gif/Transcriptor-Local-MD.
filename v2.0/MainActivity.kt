package md.localtranscript

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

class MainActivity : Activity() {
    companion object {
        private const val REQ_PICK_AUDIO = 1001
        private const val REQ_CREATE_EXPORT = 1002
        private const val REQ_MIC = 1003
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val checkpointStore by lazy { CheckpointStore(this) }

    private var selectedAudioUri: Uri? = null
    private var selectedFileName: String = ""
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var pendingSaveBytes: ByteArray = ByteArray(0)
    private var lastSegments: MutableList<TranscriptSegment> = mutableListOf()
    private var currentMeta: TranscriptionMeta? = null

    private lateinit var fileLabel: TextView
    private lateinit var hashLabel: TextView
    private lateinit var historyLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var languageSpinner: Spinner
    private lateinit var speakerSpinner: Spinner
    private lateinit var profileSpinner: Spinner
    private lateinit var speakerNamesEdit: EditText
    private lateinit var lexiconEdit: EditText
    private lateinit var transcript: EditText
    private lateinit var transcribeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var pauseButton: Button
    private lateinit var recordButton: Button
    private lateinit var segmentSpinner: Spinner
    private lateinit var segmentTextEdit: EditText
    private lateinit var segmentSpeakerEdit: EditText
    private lateinit var segmentStartEdit: EditText
    private lateinit var segmentEndEdit: EditText
    private lateinit var searchEdit: EditText
    private lateinit var replaceEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadPreferences()
        refreshHistory()
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        playbackHandler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        runCatching { recorder?.stop() }
        recorder?.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Transcriptor Local MD v2.0"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "RO-MD + rusisme + Русский • diarizare • editor juridic • 100% local"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(sectionTitle("Fișier audio"))
        fileLabel = TextView(this).apply { text = "Niciun fișier selectat" }
        root.addView(fileLabel)
        hashLabel = TextView(this).apply { text = "SHA-256: —"; textSize = 12f }
        root.addView(hashLabel)

        root.addView(Button(this).apply {
            text = "Alege fișier audio local"
            setOnClickListener { chooseAudioFile() }
        })
        root.addView(Button(this).apply {
            text = "Calculează SHA-256 audio"
            setOnClickListener { calculateHashOnly() }
        })
        recordButton = Button(this).apply {
            text = "Înregistrează audio"
            setOnClickListener { if (recorder == null) requestOrStartRecording() else stopRecording() }
        }
        root.addView(recordButton)

        root.addView(sectionTitle("Limbă"))
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, LanguageMode.entries.map { it.label })
            setSelection(LanguageMode.MIXED.ordinal)
        }
        root.addView(languageSpinner)

        root.addView(sectionTitle("Număr de vorbitori"))
        speakerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Automat") + (1..10).map(Int::toString))
        }
        root.addView(speakerSpinner)

        root.addView(sectionTitle("Calitate"))
        profileSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TranscriptionProfile.entries.map { it.label })
            setSelection(TranscriptionProfile.ACCURACY.ordinal)
        }
        root.addView(profileSpinner)

        root.addView(sectionTitle("Vorbitori / profiluri locale de denumire"))
        speakerNamesEdit = EditText(this).apply { hint = "1=Ion; 2=Maria" }
        root.addView(speakerNamesEdit)

        root.addView(sectionTitle("Dicționar local RO-MD/RU"))
        lexiconEdit = EditText(this).apply {
            minLines = 2
            hint = "Termeni separați prin virgulă sau linie nouă"
            setText("CNA, CNPF, Chișinău, Ialoveni, Anenii Noi, stroică, spravcă, marșrutkă")
        }
        root.addView(lexiconEdit)

        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transcribeButton = Button(this).apply { text = "TRANSCRIE OFFLINE"; setOnClickListener { startTranscription() } }
        pauseButton = Button(this).apply {
            text = "Pauză"; isEnabled = false
            setOnClickListener { togglePause() }
        }
        cancelButton = Button(this).apply {
            text = "Anulează"; isEnabled = false
            setOnClickListener { cancelRequested.set(true); statusLabel.text = "Se oprește; checkpointul local este păstrat…" }
        }
        runRow.addView(transcribeButton, weight())
        runRow.addView(pauseButton, weight())
        runRow.addView(cancelButton, weight())
        root.addView(runRow)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(10) })
        statusLabel = TextView(this).apply {
            text = "Fără INTERNET, cloud, telemetrie sau analytics."
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusLabel)

        root.addView(sectionTitle("Transcriere fidelă"))
        transcript = EditText(this).apply {
            minLines = 12
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            hint = "[00:00–00:05] Vorbitor 1 [RO-MD]: …"
        }
        root.addView(transcript)

        val basicRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        basicRow.addView(button("Copiază") { copyTranscript() }, weight())
        basicRow.addView(button("Aplică nume") { applySpeakerNames() }, weight())
        basicRow.addView(button("Format juridic") { showLegalFormat() }, weight())
        root.addView(basicRow)

        root.addView(sectionTitle("Editor sincronizat cu audio"))
        segmentSpinner = Spinner(this)
        root.addView(segmentSpinner)
        val editorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        segmentSpeakerEdit = EditText(this).apply { hint = "Speaker"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        segmentStartEdit = EditText(this).apply { hint = "Start sec"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        segmentEndEdit = EditText(this).apply { hint = "End sec"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        editorRow.addView(segmentSpeakerEdit, weight())
        editorRow.addView(segmentStartEdit, weight())
        editorRow.addView(segmentEndEdit, weight())
        root.addView(editorRow)
        segmentTextEdit = EditText(this).apply { hint = "Textul segmentului"; minLines = 2 }
        root.addView(segmentTextEdit)

        val editActions1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editActions1.addView(button("Încarcă") { loadSelectedSegment() }, weight())
        editActions1.addView(button("Redă audio") { playSelectedSegment() }, weight())
        editActions1.addView(button("Salvează editarea") { saveSelectedSegment() }, weight())
        root.addView(editActions1)
        val editActions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editActions2.addView(button("[neinteligibil]") { markUnintelligible() }, weight())
        editActions2.addView(button("Unește cu precedentul") { mergeWithPrevious() }, weight())
        editActions2.addView(button("Împarte la cursor") { splitAtCursor() }, weight())
        root.addView(editActions2)

        root.addView(sectionTitle("Caută / înlocuiește local"))
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        searchEdit = EditText(this).apply { hint = "Caută" }
        replaceEdit = EditText(this).apply { hint = "Înlocuiește" }
        searchRow.addView(searchEdit, weight())
        searchRow.addView(replaceEdit, weight())
        searchRow.addView(button("Aplică") { replaceAll() }, weight())
        root.addView(searchRow)

        root.addView(sectionTitle("Export"))
        val ex1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ex1.addView(button("TXT") { exportText("txt") }, weight())
        ex1.addView(button("SRT") { exportText("srt") }, weight())
        ex1.addView(button("VTT") { exportText("vtt") }, weight())
        root.addView(ex1)
        val ex2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ex2.addView(button("JSON") { exportText("json") }, weight())
        ex2.addView(button("CSV") { exportText("csv") }, weight())
        ex2.addView(button("DOCX") { exportDocx() }, weight())
        root.addView(ex2)
        val ex3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ex3.addView(button("PDF") { exportPdf() }, weight())
        ex3.addView(button("TXT fără timp") { exportText("plain") }, weight())
        root.addView(ex3)

        root.addView(sectionTitle("Istoric local"))
        historyLabel = TextView(this).apply { textSize = 12f }
        root.addView(historyLabel)

        root.addView(TextView(this).apply {
            text = "Notă: eticheta de încredere este o estimare calitativă locală bazată pe semnal și reguli anti-halucinație; nu este o probabilitate calibrată a modelului. Profilurile de vorbitori sunt denumiri atribuite de utilizator, nu identificare biometrică automată."
            textSize = 11f
            setPadding(0, dp(16), 0, 0)
        })
        return scroll
    }

    private fun chooseAudioFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_PICK_AUDIO)
    }

    private fun calculateHashOnly() {
        val uri = selectedAudioUri ?: return toast("Alege un fișier audio.")
        statusLabel.text = "Calcul SHA-256 exclusiv local…"
        worker.execute {
            runCatching { AudioEvidence.sha256(this, uri) }
                .onSuccess { hash -> runOnUiThread { hashLabel.text = "SHA-256: $hash"; statusLabel.text = "SHA-256 calculat local." } }
                .onFailure { e -> runOnUiThread { showError("Calcul SHA-256 eșuat", e) } }
        }
    }

    private fun requestOrStartRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun startRecording() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            dir.mkdirs()
            val file = File(dir, "inregistrare_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
            recordingFile = file
            recordButton.text = "Oprește înregistrarea"
            statusLabel.text = "Înregistrare locală în curs…"
        } catch (e: Exception) { recorder?.release(); recorder = null; showError("Înregistrarea nu a pornit", e) }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        try {
            r.stop()
            val file = recordingFile ?: error("Fișier lipsă")
            selectedAudioUri = Uri.fromFile(file)
            selectedFileName = file.name
            fileLabel.text = file.name
            currentMeta = null
            hashLabel.text = "SHA-256: —"
        } catch (e: Exception) { showError("Înregistrarea nu a fost salvată", e) }
        finally { r.release(); recorder = null; recordButton.text = "Înregistrează audio" }
    }

    private fun startTranscription() {
        val uri = selectedAudioUri ?: return toast("Alege sau înregistrează un fișier audio.")
        if (recorder != null) return toast("Oprește înregistrarea înainte de transcriere.")
        val mode = LanguageMode.entries[languageSpinner.selectedItemPosition]
        val speakers = speakerSpinner.selectedItemPosition.let { if (it == 0) null else it }
        val profile = TranscriptionProfile.entries[profileSpinner.selectedItemPosition]
        val preferredTerms = LocalLexicon.parse(lexiconEdit.text?.toString().orEmpty())
        val startedAt = SystemClock.elapsedRealtime()

        cancelRequested.set(false); pauseRequested.set(false)
        transcribeButton.isEnabled = false; pauseButton.isEnabled = true; cancelButton.isEnabled = true
        pauseButton.text = "Pauză"; progress.visibility = View.VISIBLE; progress.progress = 0
        statusLabel.text = "Calcul hash și verificare checkpoint local…"

        worker.execute {
            var checkpointKey: String? = null
            try {
                val sha = AudioEvidence.sha256(this, uri)
                checkpointKey = sha
                val existing = checkpointStore.load(sha)
                val audio = AudioDecoder.decode(this, uri)
                currentMeta = TranscriptionMeta(selectedFileName.ifBlank { getDisplayName(uri) }, audio.durationSeconds, sha)
                runOnUiThread {
                    hashLabel.text = "SHA-256: $sha"
                    statusLabel.text = if (existing.isNotEmpty()) "Checkpoint găsit: se reia de la ${existing.last().end.toInt()} sec." else "Decodare finalizată. Pornire diarizare…"
                }
                val engine = OfflineTranscriber(assets)
                val segments = engine.transcribe(
                    audio = audio,
                    languageMode = mode,
                    expectedSpeakers = speakers,
                    profile = profile,
                    preferredTerms = preferredTerms,
                    resumeSegments = existing,
                    onProgress = { p, s ->
                        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                        val eta = if (p in 4..99) (elapsed * (100 - p) / p).roundToLong().coerceAtLeast(0) else null
                        runOnUiThread {
                            progress.progress = p
                            statusLabel.text = if (pauseRequested.get()) "PAUZĂ • checkpoint local păstrat" else if (eta != null) "$s • $p% • ~${formatEta(eta)}" else "$s • $p%"
                        }
                    },
                    onCheckpoint = { partial ->
                        lastSegments = partial.toMutableList()
                        checkpointStore.save(sha, partial)
                    },
                    shouldPause = { pauseRequested.get() },
                    shouldCancel = { cancelRequested.get() }
                )
                lastSegments = segments.toMutableList()
                checkpointStore.clear(sha)
                savePreferences()
                appendHistory(currentMeta!!, segments.size)
                runOnUiThread {
                    transcript.setText(TranscriptFormatter.format(segments, parseSpeakerNames()))
                    refreshEditor()
                    refreshHistory()
                    progress.progress = 100
                    val overlapCount = segments.count { it.overlap }
                    statusLabel.text = "Finalizat local • ${segments.map { it.speaker }.distinct().size} vorbitori • ${segments.size} segmente • $overlapCount suprapuneri marcate."
                }
            } catch (_: CancellationException) {
                runOnUiThread { statusLabel.text = "Procesare oprită. Checkpointul local a fost păstrat pentru reluare."; progress.visibility = View.GONE }
            } catch (e: Exception) {
                runOnUiThread { showError("Transcrierea a eșuat", e) }
            } finally {
                runOnUiThread {
                    transcribeButton.isEnabled = true; pauseButton.isEnabled = false; cancelButton.isEnabled = false
                    pauseRequested.set(false); pauseButton.text = "Pauză"
                    if (progress.progress < 100) progress.visibility = View.GONE
                }
            }
        }
    }

    private fun togglePause() {
        val now = !pauseRequested.get()
        pauseRequested.set(now)
        pauseButton.text = if (now) "Continuă" else "Pauză"
        statusLabel.text = if (now) "PAUZĂ • checkpointul rămâne local" else "Reluare procesare…"
    }

    private fun refreshEditor() {
        val labels = if (lastSegments.isEmpty()) listOf("Niciun segment") else lastSegments.mapIndexed { i, s ->
            "${i + 1}. ${formatShort(s.start)}–${formatShort(s.end)} • V${s.speaker} • ${s.confidence.label}${if (s.overlap) " • OVERLAP" else ""}"
        }
        segmentSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (lastSegments.isNotEmpty()) { segmentSpinner.setSelection(0); loadSelectedSegment() }
    }

    private fun selectedIndex(): Int = segmentSpinner.selectedItemPosition.coerceIn(0, (lastSegments.size - 1).coerceAtLeast(0))

    private fun loadSelectedSegment() {
        if (lastSegments.isEmpty()) return
        val seg = lastSegments[selectedIndex()]
        segmentSpeakerEdit.setText(seg.speaker.toString())
        segmentStartEdit.setText(String.format(Locale.US, "%.3f", seg.start))
        segmentEndEdit.setText(String.format(Locale.US, "%.3f", seg.end))
        segmentTextEdit.setText(seg.text)
    }

    private fun saveSelectedSegment() {
        if (lastSegments.isEmpty()) return
        val i = selectedIndex(); val old = lastSegments[i]
        val speaker = segmentSpeakerEdit.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: old.speaker
        val start = segmentStartEdit.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: old.start
        val end = segmentEndEdit.text.toString().toFloatOrNull()?.coerceAtLeast(start + 0.05f) ?: old.end
        val text = segmentTextEdit.text.toString().trim().ifBlank { "[neinteligibil]" }
        lastSegments[i] = old.copy(start = start, end = end, speaker = speaker, text = text)
        lastSegments.sortBy { it.start }
        updateTranscriptAndEditor()
    }

    private fun markUnintelligible() {
        if (lastSegments.isEmpty()) return
        val i = selectedIndex(); lastSegments[i] = lastSegments[i].copy(text = "[neinteligibil]", confidence = ConfidenceLabel.LOW)
        updateTranscriptAndEditor()
    }

    private fun mergeWithPrevious() {
        if (lastSegments.size < 2) return
        val i = selectedIndex(); if (i <= 0) return toast("Selectează un segment după primul.")
        val a = lastSegments[i - 1]; val b = lastSegments[i]
        if (a.speaker != b.speaker) return toast("Pentru siguranță, se unesc numai segmente ale aceluiași vorbitor.")
        val merged = a.copy(
            end = maxOf(a.end, b.end),
            text = TextPostProcessor.clean(a.text + " " + b.text),
            confidence = if (a.confidence == ConfidenceLabel.LOW || b.confidence == ConfidenceLabel.LOW) ConfidenceLabel.LOW else ConfidenceLabel.MEDIUM,
            overlap = a.overlap || b.overlap,
            overlapSpeakers = (a.overlapSpeakers + b.overlapSpeakers).distinct().sorted()
        )
        lastSegments[i - 1] = merged; lastSegments.removeAt(i); updateTranscriptAndEditor()
    }

    private fun splitAtCursor() {
        if (lastSegments.isEmpty()) return
        val i = selectedIndex(); val old = lastSegments[i]
        val text = segmentTextEdit.text.toString()
        val cursor = segmentTextEdit.selectionStart.coerceIn(0, text.length)
        if (cursor <= 0 || cursor >= text.length) return toast("Poziționează cursorul în interiorul textului.")
        val left = text.substring(0, cursor).trim(); val right = text.substring(cursor).trim()
        if (left.isBlank() || right.isBlank()) return toast("Ambele părți trebuie să conțină text.")
        val ratio = left.length.toFloat() / (left.length + right.length)
        val mid = old.start + (old.end - old.start) * ratio.coerceIn(0.15f, 0.85f)
        lastSegments[i] = old.copy(end = mid, text = left)
        lastSegments.add(i + 1, old.copy(start = mid, text = right))
        updateTranscriptAndEditor()
    }

    private fun playSelectedSegment() {
        val uri = selectedAudioUri ?: return
        if (lastSegments.isEmpty()) return
        val seg = lastSegments[selectedIndex()]
        runCatching {
            playbackHandler.removeCallbacksAndMessages(null)
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                seekTo((seg.start * 1000).toInt())
                start()
            }
            playbackHandler.postDelayed({ runCatching { player?.pause() } }, ((seg.end - seg.start).coerceAtLeast(0.1f) * 1000).toLong())
        }.onFailure { showError("Redarea segmentului a eșuat", it) }
    }

    private fun replaceAll() {
        val find = searchEdit.text.toString(); if (find.isBlank()) return
        val replacement = replaceEdit.text.toString()
        lastSegments = lastSegments.map { it.copy(text = it.text.replace(find, replacement, ignoreCase = false)) }.toMutableList()
        updateTranscriptAndEditor()
    }

    private fun updateTranscriptAndEditor() {
        transcript.setText(TranscriptFormatter.format(lastSegments, parseSpeakerNames()))
        refreshEditor()
    }

    private fun applySpeakerNames() { savePreferences(); updateTranscriptAndEditor() }

    private fun showLegalFormat() {
        val meta = currentMeta ?: return toast("Finalizează mai întâi o transcriere.")
        transcript.setText(TranscriptFormatter.legalFormat(lastSegments, parseSpeakerNames(), meta))
    }

    private fun copyTranscript() {
        val text = transcript.text?.toString().orEmpty(); if (text.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Transcriere", text))
        toast("Text copiat.")
    }

    private fun exportText(type: String) {
        if (lastSegments.isEmpty()) return toast("Nu există segmente de exportat.")
        val names = parseSpeakerNames()
        val triple = when (type) {
            "srt" -> Triple(TranscriptFormatter.toSrt(lastSegments, names), "application/x-subrip", "srt")
            "vtt" -> Triple(TranscriptFormatter.toVtt(lastSegments, names), "text/vtt", "vtt")
            "json" -> Triple(TranscriptFormatter.toJson(lastSegments, names), "application/json", "json")
            "csv" -> Triple(TranscriptFormatter.toCsv(lastSegments, names), "text/csv", "csv")
            "plain" -> Triple(TranscriptFormatter.withoutTimestamps(lastSegments, names), "text/plain", "txt")
            else -> Triple(TranscriptFormatter.format(lastSegments, names), "text/plain", "txt")
        }
        saveBytes(triple.first.toByteArray(Charsets.UTF_8), triple.second, triple.third)
    }

    private fun exportDocx() {
        val meta = currentMeta ?: return toast("Finalizează mai întâi o transcriere.")
        if (lastSegments.isEmpty()) return
        saveBytes(DocumentExporter.docx(lastSegments, parseSpeakerNames(), meta), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
    }

    private fun exportPdf() {
        val meta = currentMeta ?: return toast("Finalizează mai întâi o transcriere.")
        if (lastSegments.isEmpty()) return
        saveBytes(DocumentExporter.pdf(lastSegments, parseSpeakerNames(), meta), "application/pdf", "pdf")
    }

    private fun saveBytes(bytes: ByteArray, mime: String, ext: String) {
        pendingSaveBytes = bytes
        val name = "transcriere_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
        }, REQ_CREATE_EXPORT)
    }

    @Deprecated("Retained for minSdk compatibility without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_AUDIO -> {
                val uri = data?.data ?: return
                val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                selectedAudioUri = uri; selectedFileName = getDisplayName(uri); fileLabel.text = selectedFileName
                hashLabel.text = "SHA-256: —"; currentMeta = null; lastSegments.clear(); transcript.setText(""); refreshEditor()
                statusLabel.text = "Fișier selectat local. Nu este transmis nicăieri."
            }
            REQ_CREATE_EXPORT -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(pendingSaveBytes) } ?: error("Fișierul de ieșire nu poate fi deschis")
                }.onSuccess { toast("Fișier salvat local.") }.onFailure { showError("Export eșuat", it) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else toast("Permisiunea microfon este necesară numai pentru înregistrare.")
        }
    }

    private fun parseSpeakerNames(): Map<Int, String> {
        val out = linkedMapOf<Int, String>()
        speakerNamesEdit.text.toString().split(';', '\n', ',').forEach { p0 ->
            val p = p0.trim(); val sep = when { '=' in p -> '='; ':' in p -> ':'; else -> null }
            if (sep != null) {
                val a = p.split(sep, limit = 2); val id = a.getOrNull(0)?.trim()?.toIntOrNull(); val name = a.getOrNull(1)?.trim().orEmpty()
                if (id != null && id in 1..99 && name.isNotBlank()) out[id] = name
            }
        }
        return out
    }

    private fun savePreferences() {
        getSharedPreferences("local_settings", MODE_PRIVATE).edit()
            .putString("speaker_names", speakerNamesEdit.text.toString())
            .putString("lexicon", lexiconEdit.text.toString()).apply()
    }

    private fun loadPreferences() {
        val p = getSharedPreferences("local_settings", MODE_PRIVATE)
        speakerNamesEdit.setText(p.getString("speaker_names", "") ?: "")
        val lex = p.getString("lexicon", null); if (!lex.isNullOrBlank()) lexiconEdit.setText(lex)
    }

    private fun appendHistory(meta: TranscriptionMeta, count: Int) {
        val p = getSharedPreferences("history", MODE_PRIVATE)
        val old = p.getString("items", "").orEmpty().lines().filter { it.isNotBlank() }.take(9)
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())} • ${meta.fileName} • $count seg. • ${meta.audioSha256.take(12)}…"
        p.edit().putString("items", (listOf(line) + old).joinToString("\n")).apply()
    }

    private fun refreshHistory() { historyLabel.text = getSharedPreferences("history", MODE_PRIVATE).getString("items", "Nicio transcriere finalizată încă.") }

    private fun getDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "Fișier audio"
    }

    private fun formatEta(seconds: Long): String = if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s rămase" else "${seconds}s rămase"
    private fun formatShort(sec: Float): String = "%02d:%02d".format(Locale.US, sec.toInt() / 60, sec.toInt() % 60)
    private fun sectionTitle(s: String) = TextView(this).apply { text = s; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(4)) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun showError(prefix: String, e: Throwable) { progress.visibility = View.GONE; statusLabel.text = "$prefix: ${e.message ?: e.javaClass.simpleName}"; toast(statusLabel.text.toString()) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
