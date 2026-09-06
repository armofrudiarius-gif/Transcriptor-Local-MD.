package md.localtranscript

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import java.nio.charset.StandardCharsets
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
    private var selectedAudioUri: Uri? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var pendingSaveText: String = ""
    private var lastSegments: List<TranscriptSegment> = emptyList()

    private lateinit var fileLabel: TextView
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
    private lateinit var recordButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Transcriptor Local MD v1.2"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "RO-MD + rusisme + Русский • diarizare • 100% local"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(sectionTitle("Fișier audio"))
        fileLabel = TextView(this).apply {
            text = "Niciun fișier selectat"
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(fileLabel)

        root.addView(Button(this).apply {
            text = "Alege fișier audio local"
            setOnClickListener { chooseAudioFile() }
        })

        recordButton = Button(this).apply {
            text = "Înregistrează audio"
            setOnClickListener {
                if (recorder == null) requestOrStartRecording() else stopRecording()
            }
        }
        root.addView(recordButton)

        root.addView(sectionTitle("Limbă"))
        languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LanguageMode.entries.map { it.label }
        )
        languageSpinner.setSelection(LanguageMode.MIXED.ordinal)
        root.addView(languageSpinner)

        root.addView(sectionTitle("Număr de vorbitori"))
        speakerSpinner = Spinner(this)
        speakerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Automat") + (1..10).map { it.toString() }
        )
        root.addView(speakerSpinner)

        root.addView(sectionTitle("Mod de procesare"))
        profileSpinner = Spinner(this)
        profileSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            TranscriptionProfile.entries.map { it.label }
        )
        profileSpinner.setSelection(TranscriptionProfile.BALANCED.ordinal)
        root.addView(profileSpinner)

        root.addView(sectionTitle("Nume vorbitori (opțional)"))
        speakerNamesEdit = EditText(this).apply {
            hint = "1=Ion; 2=Maria"
            minLines = 1
        }
        root.addView(speakerNamesEdit)

        root.addView(sectionTitle("Dicționar local / forme preferate"))
        lexiconEdit = EditText(this).apply {
            hint = "Un termen pe rând sau separat prin virgulă"
            setText("CNA, CNPF, Chișinău, Ialoveni, Anenii Noi, stroică, spravcă, marșrutkă")
            minLines = 2
        }
        root.addView(lexiconEdit)
        root.addView(TextView(this).apply {
            text = "Dicționarul corectează numai forma grafică a aceluiași cuvânt; nu traduce și nu înlocuiește semantic vorbirea."
            textSize = 12f
        })

        val runActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transcribeButton = Button(this).apply {
            text = "Transcrie local"
            setOnClickListener { startTranscription() }
        }
        cancelButton = Button(this).apply {
            text = "Anulează"
            isEnabled = false
            setOnClickListener {
                cancelRequested.set(true)
                statusLabel.text = "Se oprește procesarea locală…"
            }
        }
        runActions.addView(transcribeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        runActions.addView(cancelButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(runActions)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(14)
        ).apply { topMargin = dp(12) })

        statusLabel = TextView(this).apply {
            text = "Datele nu părăsesc telefonul. Aplicația nu solicită permisiune INTERNET."
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(statusLabel)

        root.addView(sectionTitle("Transcriere fidelă, cronologică"))
        transcript = EditText(this).apply {
            minLines = 12
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            hint = "[00:00–00:05] Vorbitor 1 [RO-MD]: …\n[00:05–00:09] Vorbitor 2 [RU]: …"
        }
        root.addView(transcript, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val actions1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions1.addView(Button(this).apply {
            text = "Copiază"
            setOnClickListener { copyTranscript() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions1.addView(Button(this).apply {
            text = "Aplică nume"
            setOnClickListener { applySpeakerNames() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions1)

        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(Button(this).apply {
            text = "TXT"
            setOnClickListener { exportTxt(true) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions2.addView(Button(this).apply {
            text = "TXT fără timp"
            setOnClickListener { exportTxt(false) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions2)

        val actions3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions3.addView(Button(this).apply {
            text = "SRT"
            setOnClickListener { exportSrt() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions3.addView(Button(this).apply {
            text = "JSON"
            setOnClickListener { exportJson() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions3)

        root.addView(TextView(this).apply {
            text = "Confidențialitate: fără cont, fără cloud, fără telemetrie și fără acces la rețea. Fișierele sunt deschise numai la alegerea explicită a utilizatorului."
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })

        return scroll
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun chooseAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK_AUDIO)
    }

    private fun requestOrStartRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun startRecording() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "inregistrare_$stamp.m4a")
            val mr = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingFile = file
            recorder = mr
            recordButton.text = "Oprește înregistrarea"
            statusLabel.text = "Înregistrare locală în curs…"
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            recordingFile = null
            showError("Înregistrarea nu a putut porni", e)
        }
    }

    private fun stopRecording() {
        val mr = recorder ?: return
        try {
            mr.stop()
            val file = recordingFile ?: error("Fișier de înregistrare lipsă")
            selectedAudioUri = Uri.fromFile(file)
            fileLabel.text = file.name
            statusLabel.text = "Înregistrarea a fost salvată local și este gata pentru transcriere."
        } catch (e: Exception) {
            showError("Înregistrarea nu a putut fi salvată", e)
        } finally {
            mr.release()
            recorder = null
            recordButton.text = "Înregistrează audio"
        }
    }

    private fun startTranscription() {
        val uri = selectedAudioUri
        if (uri == null) {
            Toast.makeText(this, "Alege sau înregistrează mai întâi un fișier audio.", Toast.LENGTH_LONG).show()
            return
        }
        if (recorder != null) {
            Toast.makeText(this, "Oprește înregistrarea înainte de transcriere.", Toast.LENGTH_LONG).show()
            return
        }

        val mode = LanguageMode.entries[languageSpinner.selectedItemPosition]
        val speakers = speakerSpinner.selectedItemPosition.let { if (it == 0) null else it }
        val profile = TranscriptionProfile.entries[profileSpinner.selectedItemPosition]
        val preferredTerms = LocalLexicon.parse(lexiconEdit.text?.toString().orEmpty())
        val startedAt = SystemClock.elapsedRealtime()

        cancelRequested.set(false)
        lastSegments = emptyList()
        transcribeButton.isEnabled = false
        cancelButton.isEnabled = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        transcript.setText("")
        statusLabel.text = "Decodare audio locală…"

        worker.execute {
            try {
                val audio = AudioDecoder.decode(this, uri)
                if (cancelRequested.get()) throw CancellationException()
                runOnUiThread {
                    statusLabel.text = "Audio: %.1f secunde • %d Hz".format(Locale.US, audio.durationSeconds, audio.sampleRate)
                    progress.progress = 1
                }
                val engine = OfflineTranscriber(assets)
                val segments = engine.transcribe(
                    audio = audio,
                    languageMode = mode,
                    expectedSpeakers = speakers,
                    profile = profile,
                    preferredTerms = preferredTerms,
                    onProgress = { p, s ->
                        val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000.0)
                        val eta = if (p in 4..99) (elapsed * (100 - p) / p).roundToLong().coerceAtLeast(0) else null
                        runOnUiThread {
                            progress.progress = p
                            statusLabel.text = if (eta != null) "$s • $p% • ~${formatEta(eta)} rămase" else "$s • $p%"
                        }
                    },
                    shouldCancel = { cancelRequested.get() }
                )
                lastSegments = segments
                val text = TranscriptFormatter.format(segments, parseSpeakerNames())
                runOnUiThread {
                    transcript.setText(if (text.isBlank()) "Nu a fost detectată vorbire inteligibilă." else text)
                    statusLabel.text = "Finalizat local • ${segments.map { it.speaker }.distinct().size} vorbitor(i) detectat(ți) • ${profile.label}."
                    progress.progress = 100
                }
            } catch (_: CancellationException) {
                runOnUiThread {
                    statusLabel.text = "Transcriere anulată. Niciun fișier nu a fost transmis în rețea."
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Transcrierea a eșuat", e) }
            } finally {
                runOnUiThread {
                    transcribeButton.isEnabled = true
                    cancelButton.isEnabled = false
                    if (progress.progress < 100) progress.visibility = View.GONE
                }
            }
        }
    }

    private fun applySpeakerNames() {
        if (lastSegments.isEmpty()) return
        transcript.setText(TranscriptFormatter.format(lastSegments, parseSpeakerNames()))
    }

    private fun copyTranscript() {
        val text = transcript.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcriere", text))
        Toast.makeText(this, "Text copiat.", Toast.LENGTH_SHORT).show()
    }

    private fun exportTxt(withTimestamps: Boolean) {
        if (lastSegments.isEmpty()) {
            saveTextFallback("txt")
            return
        }
        val names = parseSpeakerNames()
        val content = if (withTimestamps) TranscriptFormatter.format(lastSegments, names)
        else TranscriptFormatter.withoutTimestamps(lastSegments, names)
        saveAs(content, "text/plain", "txt")
    }

    private fun exportSrt() {
        if (lastSegments.isEmpty()) {
            Toast.makeText(this, "Nu există segmente transcrise pentru SRT.", Toast.LENGTH_SHORT).show()
            return
        }
        saveAs(TranscriptFormatter.toSrt(lastSegments, parseSpeakerNames()), "application/x-subrip", "srt")
    }

    private fun exportJson() {
        if (lastSegments.isEmpty()) {
            Toast.makeText(this, "Nu există segmente transcrise pentru JSON.", Toast.LENGTH_SHORT).show()
            return
        }
        saveAs(TranscriptFormatter.toJson(lastSegments, parseSpeakerNames()), "application/json", "json")
    }

    private fun saveTextFallback(ext: String) {
        val text = transcript.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Nu există text de salvat.", Toast.LENGTH_SHORT).show()
            return
        }
        saveAs(text, "text/plain", ext)
    }

    private fun saveAs(content: String, mime: String, extension: String) {
        pendingSaveText = content
        val name = "transcriere_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".$extension"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, REQ_CREATE_EXPORT)
    }

    @Deprecated("Deprecated in Android API, retained for minSdk compatibility without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_AUDIO -> {
                val uri = data?.data ?: return
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                selectedAudioUri = uri
                fileLabel.text = getDisplayName(uri)
                statusLabel.text = "Fișier local selectat. Nu este încărcat nicăieri."
            }
            REQ_CREATE_EXPORT -> {
                val uri = data?.data ?: return
                try {
                    contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(pendingSaveText.toByteArray(StandardCharsets.UTF_8))
                    } ?: error("Nu poate fi deschis fișierul de ieșire")
                    Toast.makeText(this, "Fișier salvat local.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showError("Fișierul nu a putut fi salvat", e)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else Toast.makeText(this, "Permisiunea microfon este necesară numai pentru înregistrare.", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseSpeakerNames(): Map<Int, String> {
        val raw = speakerNamesEdit.text?.toString().orEmpty()
        val out = linkedMapOf<Int, String>()
        raw.split(';', '\n', ',').forEach { part ->
            val p = part.trim()
            val separator = when {
                '=' in p -> '='
                ':' in p -> ':'
                else -> null
            }
            if (separator != null) {
                val pieces = p.split(separator, limit = 2)
                val id = pieces.getOrNull(0)?.trim()?.toIntOrNull()
                val name = pieces.getOrNull(1)?.trim().orEmpty()
                if (id != null && id in 1..10 && name.isNotBlank()) out[id] = name
            }
        }
        return out
    }

    private fun getDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Fișier audio selectat"
    }

    private fun formatEta(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun showError(prefix: String, error: Throwable) {
        progress.visibility = View.GONE
        statusLabel.text = "$prefix: ${error.message ?: error.javaClass.simpleName}"
        Toast.makeText(this, statusLabel.text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
