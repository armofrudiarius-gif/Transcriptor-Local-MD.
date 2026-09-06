package md.localtranscript

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocumentExporter {
    fun docx(items: List<TranscriptSegment>, names: Map<Int, String>, meta: TranscriptionMeta): ByteArray {
        val text = TranscriptFormatter.legalFormat(items, names, meta)
        val documentXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
            text.split('\n').forEach { line ->
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(xmlEscape(line.ifEmpty { " " }))
                append("</w:t></w:r></w:p>")
            }
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>")
            append("</w:body></w:document>")
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            addZip(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            addZip(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            addZip(zip, "word/document.xml", documentXml)
        }
        return out.toByteArray()
    }

    fun pdf(items: List<TranscriptSegment>, names: Map<Int, String>, meta: TranscriptionMeta): ByteArray {
        val lines = wrapLines(TranscriptFormatter.legalFormat(items, names, meta), 92)
        val document = PdfDocument()
        val paint = Paint().apply {
            textSize = 10f
            isAntiAlias = true
        }
        val pageWidth = 595
        val pageHeight = 842
        val left = 42f
        val top = 48f
        val bottom = 48f
        val lineHeight = 14f
        val linesPerPage = ((pageHeight - top - bottom) / lineHeight).toInt().coerceAtLeast(1)
        var pageNumber = 1
        var index = 0
        while (index < lines.size) {
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val page = document.startPage(info)
            var y = top
            val end = minOf(lines.size, index + linesPerPage)
            for (i in index until end) {
                page.canvas.drawText(lines[i], left, y, paint)
                y += lineHeight
            }
            document.finishPage(page)
            pageNumber++
            index = end
        }
        if (lines.isEmpty()) {
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = document.startPage(info)
            page.canvas.drawText("Transcriere goală", left, top, paint)
            document.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun wrapLines(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        text.split('\n').forEach { raw ->
            if (raw.length <= maxChars) {
                out += raw
            } else {
                var rest = raw.trimEnd()
                while (rest.length > maxChars) {
                    var cut = rest.lastIndexOf(' ', maxChars)
                    if (cut < maxChars / 2) cut = maxChars
                    out += rest.substring(0, cut).trimEnd()
                    rest = rest.substring(cut).trimStart()
                }
                out += rest
            }
        }
        return out
    }

    private fun addZip(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
