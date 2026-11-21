package ch.ripple.research

import ch.ripple.core.DocumentCoverage
import ch.ripple.core.HttpResearchProvider
import ch.ripple.core.ProposedFinding
import ch.ripple.core.ProviderFailure
import ch.ripple.core.ProviderResponse
import ch.ripple.core.ResearchCache
import ch.ripple.core.ResearchMode
import ch.ripple.core.ResearchRequest
import ch.ripple.core.SampleData
import ch.ripple.core.SourceDocument
import ch.ripple.core.planResearch
import ch.ripple.core.runResearch
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deterministic server tests: stub proposer + stub fetcher, real HTTP on localhost. No network, no API key. */
class ResearchServerTest {
    private val request: ResearchRequest = planResearch(SampleData.alexContext, SampleData.alexResearchSession).requests.first()
    private val fsoUrl = ResearchCache.documents[0].url

    /** Behaves like Claude citing the real FSO table; its document comes from the (stub) fetcher. */
    private val stubProposer = object : FindingProposer {
        override val name = "stub"
        override fun propose(request: ResearchRequest) = listOf(ResearchCache.findings[0].copy(requestId = request.id))
    }
    private val stubFetch = { url: String -> ResearchCache.documents.firstOrNull { it.url == url } }

    @Test fun serviceReturnsProposalsWithIndependentlyFetchedDocuments() {
        val r = ResearchService(stubProposer, stubFetch).handle(request) as ProviderResponse.Success
        assertEquals(listOf(fsoUrl), r.documents.map { it.url })
        assertEquals("stub", r.provider)
    }

    @Test fun missingKeyRateLimitAndProposerErrorsBecomeTypedFailures() {
        assertEquals(ProviderFailure.NOT_CONFIGURED, (ResearchService(null, stubFetch).handle(request) as ProviderResponse.Failure).reason)
        val limited = ResearchService(stubProposer, stubFetch, maxPerHour = 1)
        limited.handle(request)
        assertEquals(ProviderFailure.RATE_LIMITED, (limited.handle(request) as ProviderResponse.Failure).reason)
        val refusing = object : FindingProposer {
            override val name = "stub"
            override fun propose(request: ResearchRequest): List<ProposedFinding> = throw ProposerException(ProviderFailure.REFUSED)
        }
        assertEquals(ProviderFailure.REFUSED, (ResearchService(refusing, stubFetch).handle(request) as ProviderResponse.Failure).reason)
    }

    @Test fun httpRoundTripThroughTheAppClientVerifiesLive() {
        val server = startServer(ResearchService(stubProposer, stubFetch), port = 0, token = "t0ken", llmConfigured = true)
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/v1/research"
            val out = runResearch(request, HttpResearchProvider(endpoint, "t0ken"), null)
            assertEquals(ResearchMode.LIVE, out.mode)
            assertEquals(ch.ripple.core.Chf.francs(899), out.verified.single().estimate!!.value.point)
            // Wrong token: typed failure, and the app falls back to the cache.
            val denied = runResearch(request, HttpResearchProvider(endpoint, "wrong"), ResearchCache.snapshot)
            assertEquals(ResearchMode.CACHED, denied.mode)
            assertEquals(ProviderFailure.NOT_CONFIGURED, denied.attempts.first().failure)
        } finally {
            server.stop(0)
        }
        // Server down: network failure -> cache.
        val offline = runResearch(request, HttpResearchProvider("http://127.0.0.1:1/v1/research", timeoutMs = 2_000), ResearchCache.snapshot)
        assertEquals(ResearchMode.CACHED, offline.mode)
        assertEquals(ProviderFailure.NETWORK, offline.attempts.first().failure)
    }

    @Test fun fetcherRefusesHostsOutsideTheAllowlist() {
        assertEquals(null, SourceFetcher().fetch("https://example.com/rents.pdf"))
        assertEquals(null, SourceFetcher().fetch("http://www.bfs.admin.ch/plain-http"))
    }

    @Test fun xlsxRowsExtractInTheCachedFormat() {
        val xlsx = zip(
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Lausanne</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="10"><c t="s"><v>0</v></c><c><v>1369</v></c><c><v>9</v></c><c><v>899.0</v></c><c><v>16</v></c><c/></row></sheetData></worksheet>""",
        )
        assertEquals("Lausanne | 1369 | 9 | 899 | 16", xlsxText(xlsx))
        val (type, _) = extract(xlsx, "application/octet-stream")!!
        assertTrue(type.contains("spreadsheet"))
    }

    @Test fun xmlExternalEntitiesAreRejected() {
        val evil = zip("xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><worksheet><sheetData><row><c t="inlineStr"><is><t>&e;</t></is></c></row></sheetData></worksheet>""")
        assertFailsWith<Exception> { xlsxText(evil) }
    }

    @Test fun htmlBecomesVisibleTextOnly() {
        val t = htmlText("<html><head><script>steal()</script><style>p{}</style></head><body><p>Loyer moyen&nbsp;: 1&#39;432 francs</p><!-- hidden --></body></html>")
        assertEquals("Loyer moyen : 1'432 francs", t)
        assertFalse("steal" in t)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.encodeToByteArray()); z.closeEntry() } }
    }.toByteArray()
}

/**
 * Optional live tests: real network (and a real Claude call if ANTHROPIC_API_KEY is set).
 * Run with RIPPLE_LIVE_TESTS=1 ./gradlew :research-server:test. Skipped otherwise.
 */
class LiveResearchTest {
    private val enabled = System.getenv("RIPPLE_LIVE_TESTS") == "1"

    @Test fun realSourcesAreRetrievedAndTheCachedPassagesStillMatch() {
        if (!enabled) return
        val request = planResearch(SampleData.alexContext, SampleData.alexResearchSession).requests.first()
        for (cached in ResearchCache.documents) {
            val live: SourceDocument = SourceFetcher().fetch(cached.url) ?: error("could not fetch ${cached.url}")
            assertEquals(DocumentCoverage.FULL_TEXT, live.coverage)
            println("LIVE ${cached.url} sha256=${live.sha256} cachedSha256=${cached.sha256} chars=${live.text.length}")
            // Every cached quote must still be found in the freshly retrieved full document.
            ResearchCache.findings.filter { it.sourceUrl == cached.url }.forEach { f ->
                val a = ch.ripple.core.validateFinding(request, f.copy(requestId = request.id), listOf(live), ch.ripple.core.RetrievalMode.LIVE_RESEARCH)
                println("  live check value=${f.value} failed=${a.failed.map { it.id }}")
                assertTrue(a.checks.single { it.id == ch.ripple.core.CheckId.QUOTE_IN_DOCUMENT }.passed, "quote not found live: ${f.quote}")
            }
        }
    }

    @Test fun claudeResearchEndToEnd() {
        if (!enabled || System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) return
        val request = planResearch(SampleData.alexContext, SampleData.alexResearchSession).requests.first()
        val proposer = ClaudeProposer(com.anthropic.client.okhttp.AnthropicOkHttpClient.fromEnv())
        val out = runResearch(request, { ResearchService(proposer, SourceFetcher()::fetch).handle(it) }, null)
        println("LIVE CLAUDE mode=${out.mode} unresolved=${out.unresolved} verified=${out.verified.map { it.estimate!!.id }}")
        out.attempts.flatMap { it.assessments }.forEach { a -> println("  ${a.finding.value} ${a.finding.sourceUrl} failed=${a.failed.map { it.id }}") }
    }
}
