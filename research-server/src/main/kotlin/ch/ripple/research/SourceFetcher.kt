package ch.ripple.research

import ch.ripple.core.DocumentCoverage
import ch.ripple.core.SourceDocument
import ch.ripple.core.sourceTier
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fetches a source the extractor cited, ourselves, so validation runs on the real document and
 * not on what the LLM says it contains. Only allowlisted https hosts (also after redirects),
 * one document per cited URL, size- and time-capped. Content is returned as plain text DATA.
 */
class SourceFetcher(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val today: () -> String = { LocalDate.now().toString() },
    private val maxBytes: Int = 8_000_000,
) {
    fun fetch(url: String): SourceDocument? {
        if (sourceTier(url) == null) return null
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Ripple-research-prototype/0.1")
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299 || sourceTier(response.uri().toString()) == null) {
            response.body().close()
            return null
        }
        val bytes = response.body().use { readCapped(it) } ?: return null
        val type = response.headers().firstValue("content-type").orElse("").lowercase()
        val (mediaType, text) = extract(bytes, type) ?: return null
        return SourceDocument(url, today(), mediaType, text, DocumentCoverage.FULL_TEXT, sha256(bytes))
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = input.readNBytes(maxBytes + 1)
        return out.takeIf { it.size <= maxBytes }
    }
}

/** (mediaType, text) for PDF, XLSX, HTML and plain text; null for anything else. */
internal fun extract(bytes: ByteArray, contentType: String): Pair<String, String>? = when {
    contentType.contains("pdf") || bytes.startsWithAscii("%PDF") ->
        "application/pdf" to Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
    contentType.contains("spreadsheet") || bytes.startsWithAscii("PK") ->
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to xlsxText(bytes)
    contentType.contains("html") || contentType.contains("xml") ->
        "text/html" to htmlText(bytes.decodeToString())
    contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("csv") ->
        contentType.substringBefore(';') to bytes.decodeToString()
    else -> null
}

private fun ByteArray.startsWithAscii(p: String) = size >= p.length && p.indices.all { this[it] == p[it].code.toByte() }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** HTML -> visible text: drops script/style, strips tags, decodes common entities. */
internal fun htmlText(html: String): String = html
    .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?s)<!--.*?-->"), " ")
    .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6])>"), "\n")
    .replace(Regex("<[^>]+>"), " ")
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&rsquo;", "’")
    .lines().map { it.replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/**
 * XLSX -> one line per row, cells joined with " | " AT THEIR REAL COLUMN POSITION (gaps kept as
 * empty cells, from the cell reference), so core can check which column header a value sits under.
 * Same format as the cached snapshot.
 */
internal fun xlsxText(bytes: ByteArray): String {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        var total = 0L
        generateSequence { z.nextEntry }.forEach { e ->
            if (e.name == "xl/sharedStrings.xml" || (e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml"))) {
                val data = z.readNBytes(20_000_000)
                total += data.size
                require(total < 50_000_000) { "spreadsheet too large" } // zip-bomb guard
                entries[e.name] = data
            }
        }
    }
    val shared = entries["xl/sharedStrings.xml"]?.let { xml(it) }?.getElementsByTagName("si")?.let { nodes ->
        (0 until nodes.length).map { i -> (nodes.item(i) as Element).getElementsByTagName("t").let { t -> (0 until t.length).joinToString("") { t.item(it).textContent } } }
    }.orEmpty()
    return entries.keys.filter { it.startsWith("xl/worksheets/") }.sorted().flatMap { name ->
        val rows = xml(entries.getValue(name)).getElementsByTagName("row")
        (0 until rows.length).mapNotNull { r ->
            val cells = (rows.item(r) as Element).getElementsByTagName("c")
            val byColumn = (0 until cells.length).mapIndexedNotNull { i, _ ->
                val c = cells.item(i) as Element
                val v = c.getElementsByTagName("v").item(0)?.textContent
                val text = when (c.getAttribute("t")) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> c.getElementsByTagName("t").item(0)?.textContent
                    else -> v?.let { s -> s.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15 }?.toLong()?.toString() ?: s }
                }?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                (columnIndex(c.getAttribute("r")) ?: i) to text
            }.toMap()
            byColumn.keys.maxOrNull()?.let { last -> (0..last).joinToString(" | ") { byColumn[it].orEmpty() } }
        }
    }.joinToString("\n")
}

/** "D10" -> 3; null if there is no reference. */
private fun columnIndex(ref: String): Int? =
    ref.takeWhile { it.isLetter() }.uppercase().takeIf { it.isNotEmpty() }?.fold(0) { n, ch -> n * 26 + (ch - 'A' + 1) }?.minus(1)

/** XML parser hardened against XXE (no DOCTYPE, no external entities). */
private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    isXIncludeAware = false
    isExpandEntityReferences = false
}.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
