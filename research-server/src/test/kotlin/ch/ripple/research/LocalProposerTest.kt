package ch.ripple.research

import ch.ripple.core.Chf
import ch.ripple.core.ProviderFailure
import ch.ripple.core.ProviderResponse
import ch.ripple.core.ResearchCache
import ch.ripple.core.ResearchMode
import ch.ripple.core.SampleData
import ch.ripple.core.planResearch
import ch.ripple.core.runResearch
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LocalProposer with a FAKE Ollama on localhost (deterministic) + an opt-in live test with the real model. */
class LocalProposerTest {
    private val request = planResearch(SampleData.alexContext, SampleData.alexResearchSession).requests.first()
    private val fso = ResearchCache.documents[0]
    private val stubFetch = { url: String -> ResearchCache.documents.firstOrNull { it.url == url } }

    /** Answers /api/chat with [reply] as the assistant message, like Ollama does. */
    private fun fakeOllama(status: Int = 200, reply: String): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/chat") { ex ->
            ex.requestBody.readAllBytes()
            val body = buildJsonObject { putJsonObject("message") { put("role", "assistant"); put("content", reply) } }.toString().encodeToByteArray()
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    @Test fun modelReadsOneDocumentAndCannotChooseTheUrl() {
        // The "model" claims a different URL; the proposer pins sourceUrl to the document it was given.
        val reply = """{"findings":[{"category":"HOUSING_NET_RENT","value":"899","plusMinus":"16","statistic":"mean","currency":"CHF",
            "unit":"CHF per month","basis":"net (ohne Neben- und Heizkosten)","geography":"Lausanne","rooms":1,"tenancy":"all tenancies",
            "periodStart":2022,"periodEnd":2024,"publishedOn":"2026","organization":"BFS","sourceUrl":"https://evil.example.com/x",
            "quote":"Lausanne | 1369 | 9 | 899 | 16","locator":"row Lausanne"}]}"""
        val ollama = fakeOllama(reply = reply)
        try {
            val p = LocalProposer(stubFetch, "http://127.0.0.1:${ollama.address.port}", search = { emptyList() }, maxDocuments = 1)
            val proposal = p.propose(request)
            assertEquals(listOf(fso.url), proposal.documents.map { it.url })
            assertEquals(fso.url, proposal.findings.single().sourceUrl)
            val out = runResearch(request, { ResearchService(p, stubFetch).handle(it) }, null)
            assertEquals(ResearchMode.LIVE, out.mode)
            assertEquals(Chf.francs(899), out.verified.single().estimate!!.value.point)
        } finally { ollama.stop(0) }
    }

    @Test fun inventedNumbersFromTheModelAreRejected() {
        val reply = """{"findings":[{"category":"HOUSING_NET_RENT","value":"850","currency":"CHF","unit":"per month","basis":"net",
            "geography":"Lausanne","rooms":1,"tenancy":"all","periodStart":2024,"periodEnd":2024,"publishedOn":"2026","organization":"BFS",
            "quote":"Lausanne | 1369 | 9 | 899 | 16"}]}"""
        val ollama = fakeOllama(reply = reply)
        try {
            val p = LocalProposer(stubFetch, "http://127.0.0.1:${ollama.address.port}", search = { emptyList() }, maxDocuments = 1)
            val out = runResearch(request, { ResearchService(p, stubFetch).handle(it) }, ResearchCache.snapshot)
            assertEquals(ResearchMode.CACHED, out.mode) // live proposal failed VALUE_IN_QUOTE -> cache
            assertTrue(out.attempts.first().assessments.single().failed.any { it.id == ch.ripple.core.CheckId.VALUE_IN_QUOTE })
        } finally { ollama.stop(0) }
    }

    @Test fun missingOllamaOrModelIsATypedFailure() {
        val down = LocalProposer(stubFetch, "http://127.0.0.1:1", search = { emptyList() }, maxDocuments = 1)
        assertEquals(ProviderFailure.NOT_CONFIGURED, (ResearchService(down, stubFetch).handle(request) as ProviderResponse.Failure).reason)
        val ollama = fakeOllama(status = 404, reply = "")
        try {
            val noModel = LocalProposer(stubFetch, "http://127.0.0.1:${ollama.address.port}", search = { emptyList() }, maxDocuments = 1)
            assertEquals(ProviderFailure.NOT_CONFIGURED, (ResearchService(noModel, stubFetch).handle(request) as ProviderResponse.Failure).reason)
        } finally { ollama.stop(0) }
    }

    @Test fun largeTablesAreTrimmedToTheRequestedArea() {
        val rows = (1..3000).map { "Gemeinde $it | 100 | 2" }
        val text = (listOf("Title", "Header") + rows + "Lausanne | 899 | 16" + "1) Footnote").joinToString("\n")
        val ex = LocalProposer.excerpt(text, request)
        assertTrue("Lausanne | 899 | 16" in ex && "Title" in ex && "1) Footnote" in ex)
        assertTrue(ex.length < text.length / 5)
    }

    /** RIPPLE_LIVE_TESTS=1 with Ollama running and the model pulled: real search, real documents, real local model. */
    @Test fun liveLocalModelEndToEnd() {
        if (System.getenv("RIPPLE_LIVE_TESTS") != "1") return
        val model = System.getenv("RIPPLE_OLLAMA_MODEL") ?: "qwen2.5:7b"
        println("LIVE opendata.swiss candidates: ${searchOpenDataSwiss(request)}")
        val fetcher = SourceFetcher()
        val p = LocalProposer(fetcher::fetch, model = model)
        val started = System.currentTimeMillis()
        val out = runResearch(request, { ResearchService(p, fetcher::fetch).handle(it) }, null)
        println("LIVE LOCAL model=$model mode=${out.mode} unresolved=${out.unresolved} seconds=${(System.currentTimeMillis() - started) / 1000}")
        out.attempts.forEach { a ->
            println("  attempt ${a.mode} provider=${a.provider} failure=${a.failure}")
            a.assessments.forEach { println("    value=${it.finding.value} geo=${it.finding.geography} rooms=${it.finding.rooms} url=${it.finding.sourceUrl} failed=${it.failed.map { c -> c.id }} issues=${it.issues}") }
        }
        out.verified.forEach { println("  VERIFIED ${it.estimate!!.id} ${it.estimate!!.value.point} quote=\"${it.estimate!!.quote}\"") }
    }
}
