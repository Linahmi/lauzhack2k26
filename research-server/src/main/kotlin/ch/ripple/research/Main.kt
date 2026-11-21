package ch.ripple.research

import ch.ripple.core.ProviderFailure
import ch.ripple.core.ProviderResponse
import ch.ripple.core.ResearchJson
import ch.ripple.core.ResearchRequest
import ch.ripple.core.SourceDocument
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Proposer -> fetch cited sources -> return both. Cost controls: requests per hour, one research
 * run at a time, bounded tool uses per run (see ClaudeProposer), at most 5 documents per run.
 */
class ResearchService(
    private val proposer: FindingProposer?,
    private val fetch: (String) -> SourceDocument?,
    private val maxPerHour: Int = 20,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val calls = ArrayDeque<Long>()
    private val running = Semaphore(1)

    fun handle(request: ResearchRequest): ProviderResponse {
        val p = proposer ?: return ProviderResponse.Failure(ProviderFailure.NOT_CONFIGURED, "research server has no LLM API key configured")
        synchronized(calls) {
            val t = now()
            while (calls.isNotEmpty() && t - calls.first() > 3_600_000) calls.removeFirst()
            if (calls.size >= maxPerHour) return ProviderResponse.Failure(ProviderFailure.RATE_LIMITED, "hourly research limit reached")
            calls.addLast(t)
        }
        if (!running.tryAcquire()) return ProviderResponse.Failure(ProviderFailure.RATE_LIMITED, "another research run is in progress")
        return try {
            val proposal = p.propose(request)
            val have = proposal.documents.map { it.url }.toSet()
            val fetched = proposal.findings.mapNotNull { it.sourceUrl }.distinct().filter { it !in have }.take(5)
                .mapNotNull { url -> runCatching { fetch(url) }.getOrNull() }
            ProviderResponse.Success(proposal.findings, proposal.documents + fetched, p.name)
        } catch (e: ProposerException) {
            ProviderResponse.Failure(e.reason, e.message)
        } catch (e: Exception) {
            ProviderResponse.Failure(ProviderFailure.PROVIDER_ERROR, e.javaClass.simpleName)
        } finally {
            running.release()
        }
    }
}

/** POST /v1/research (ResearchJson request -> response), GET /health. */
fun startServer(service: ResearchService, bind: String = "127.0.0.1", port: Int = 8787, token: String? = null, llmConfigured: Boolean): HttpServer {
    val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    server.createContext("/health") { ex -> ex.reply(200, """{"status":"ok","llmConfigured":$llmConfigured}""") }
    server.createContext("/v1/research") { ex ->
        when {
            ex.requestMethod != "POST" -> ex.reply(405, """{"status":"error","reason":"BAD_RESPONSE","message":"POST only"}""")
            token != null && ex.requestHeaders.getFirst("X-Ripple-Token") != token -> ex.reply(401, """{"status":"error","reason":"NOT_CONFIGURED","message":"bad token"}""")
            else -> {
                val body = ex.requestBody.use { it.readNBytes(16_385) }
                val request = runCatching { ResearchJson.decodeRequest(body.decodeToString()) }.getOrNull()
                if (body.size > 16_384 || request == null) {
                    ex.reply(400, """{"status":"error","reason":"BAD_RESPONSE","message":"malformed request"}""")
                } else {
                    val response = service.handle(request)
                    val code = if ((response as? ProviderResponse.Failure)?.reason == ProviderFailure.RATE_LIMITED) 429 else 200
                    ex.reply(code, ResearchJson.encodeResponse(response))
                }
            }
        }
    }
    server.executor = Executors.newFixedThreadPool(2)
    server.start()
    return server
}

private fun HttpExchange.reply(code: Int, json: String) {
    val bytes = json.encodeToByteArray()
    responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

/**
 * Configuration (environment only; never in the apps or the repository):
 *  RIPPLE_RESEARCH_LLM      "ollama" (free, local) or "anthropic"; default: anthropic if ANTHROPIC_API_KEY is set, else ollama
 *  RIPPLE_OLLAMA_URL        default http://127.0.0.1:11434
 *  RIPPLE_OLLAMA_MODEL      default qwen2.5:7b
 *  ANTHROPIC_API_KEY        Claude API key (only for RIPPLE_RESEARCH_LLM=anthropic)
 *  RIPPLE_RESEARCH_BIND     default 127.0.0.1 (use 0.0.0.0 only on a trusted network, with a token)
 *  RIPPLE_RESEARCH_PORT     default 8787
 *  RIPPLE_RESEARCH_TOKEN    optional shared secret expected in X-Ripple-Token
 *  RIPPLE_RESEARCH_MAX_PER_HOUR  default 20
 *  RIPPLE_RESEARCH_MODEL    default claude-opus-5-5
 */
fun main() {
    val env = System.getenv()
    val hasKey = !env["ANTHROPIC_API_KEY"].isNullOrBlank()
    val fetcher = SourceFetcher()
    val llm = env["RIPPLE_RESEARCH_LLM"] ?: if (hasKey) "anthropic" else "ollama"
    val proposer: FindingProposer? = when (llm) {
        "anthropic" -> if (hasKey) ClaudeProposer(
            AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(150)).maxRetries(1).build(),
            model = env["RIPPLE_RESEARCH_MODEL"] ?: "claude-opus-5-5",
        ) else null
        "ollama" -> LocalProposer(fetcher::fetch, env["RIPPLE_OLLAMA_URL"] ?: "http://127.0.0.1:11434", env["RIPPLE_OLLAMA_MODEL"] ?: "qwen2.5:7b")
        else -> error("RIPPLE_RESEARCH_LLM must be 'ollama' or 'anthropic'")
    }
    val bind = env["RIPPLE_RESEARCH_BIND"] ?: "127.0.0.1"
    val port = env["RIPPLE_RESEARCH_PORT"]?.toInt() ?: 8787
    val token = env["RIPPLE_RESEARCH_TOKEN"]?.takeIf { it.isNotBlank() }
    require(bind == "127.0.0.1" || bind == "localhost" || token != null) { "set RIPPLE_RESEARCH_TOKEN when binding beyond localhost" }
    startServer(ResearchService(proposer, fetcher::fetch, env["RIPPLE_RESEARCH_MAX_PER_HOUR"]?.toInt() ?: 20), bind, port, token, proposer != null)
    println("Ripple research proxy on http://$bind:$port (${proposer?.name ?: "no LLM configured: clients use cached research"})")
}
