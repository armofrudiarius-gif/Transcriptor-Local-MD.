package md.localtranscript

import java.text.Normalizer

object LocalLexicon {
    fun parse(raw: String): List<String> = raw
        .split('\n', ',', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { fold(it) }

    fun applyPreferredForms(text: String, preferred: List<String>): String {
        if (text.isBlank() || preferred.isEmpty()) return text
        val singles = preferred.filter { !it.any(Char::isWhitespace) }.associateBy { fold(it) }
        val multi = preferred.filter { it.any(Char::isWhitespace) }

        var out = if (singles.isEmpty()) text else buildString {
            val word = Regex("[\\p{L}\\p{M}\\p{N}’'_-]+")
            var last = 0
            word.findAll(text).forEach { m ->
                append(text, last, m.range.first)
                val replacement = singles[fold(m.value)]
                append(replacement ?: m.value)
                last = m.range.last + 1
            }
            append(text, last, text.length)
        }

        for (term in multi.sortedByDescending { it.length }) {
            val pattern = Regex("(?iu)(?<!\\p{L})${Regex.escape(term)}(?!\\p{L})")
            out = out.replace(pattern, term)
        }
        return out
    }

    internal fun fold(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
    }
}
