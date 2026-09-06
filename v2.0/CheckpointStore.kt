package md.localtranscript

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class CheckpointStore(private val context: Context) {
    private fun fileFor(key: String): File = File(context.filesDir, "checkpoint_${key.take(24)}.bin")

    fun save(key: String, items: List<TranscriptSegment>) {
        val target = fileFor(key)
        val tmp = File(target.parentFile, target.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(2)
            out.writeInt(items.size)
            items.sortedBy { it.start }.forEach { seg ->
                out.writeFloat(seg.start)
                out.writeFloat(seg.end)
                out.writeInt(seg.speaker)
                out.writeUTF(seg.language)
                out.writeUTF(seg.text)
                out.writeUTF(seg.confidence.name)
                out.writeBoolean(seg.overlap)
                out.writeInt(seg.overlapSpeakers.size)
                seg.overlapSpeakers.forEach(out::writeInt)
            }
        }
        if (target.exists()) target.delete()
        require(tmp.renameTo(target)) { "Checkpointul local nu a putut fi salvat" }
    }

    fun load(key: String): List<TranscriptSegment> {
        val file = fileFor(key)
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val version = input.readInt()
                require(version == 2) { "Versiune checkpoint incompatibilă" }
                val count = input.readInt().coerceIn(0, 100_000)
                val out = ArrayList<TranscriptSegment>(count)
                repeat(count) {
                    val start = input.readFloat()
                    val end = input.readFloat()
                    val speaker = input.readInt()
                    val language = input.readUTF()
                    val text = input.readUTF()
                    val confidence = runCatching { ConfidenceLabel.valueOf(input.readUTF()) }.getOrDefault(ConfidenceLabel.MEDIUM)
                    val overlap = input.readBoolean()
                    val n = input.readInt().coerceIn(0, 32)
                    val overlapSpeakers = ArrayList<Int>(n)
                    repeat(n) { overlapSpeakers += input.readInt() }
                    out += TranscriptSegment(start, end, speaker, language, text, confidence, overlap, overlapSpeakers)
                }
                out.sortedBy { it.start }
            }
        }.getOrElse {
            file.delete()
            emptyList()
        }
    }

    fun clear(key: String) {
        runCatching { fileFor(key).delete() }
    }

    fun exists(key: String): Boolean = fileFor(key).exists()
}
