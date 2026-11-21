package ch.ripple.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Calls the research proxy (research-server/) over HTTP. The app never holds an LLM or search
 * API key: only the proxy does. Every failure becomes a ProviderResponse.Failure, so runResearch()
 * falls back to the cached snapshot. HttpURLConnection also exists on Android, so this file can
 * be copied to androidMain unchanged if Developer B wants live research there.
 */
class HttpResearchProvider(
    private val endpoint: String = "http://127.0.0.1:8787/v1/research",
    private val token: String? = null,
    private val timeoutMs: Int = 90_000,
) : ResearchProvider {
    override fun research(request: ResearchRequest): ProviderResponse = try {
        val c = URI(endpoint).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 5_000
        c.readTimeout = timeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        token?.let { c.setRequestProperty("X-Ripple-Token", it) }
        c.outputStream.use { it.write(ResearchJson.encodeRequest(request).encodeToByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        when {
            code == 429 -> ProviderResponse.Failure(ProviderFailure.RATE_LIMITED)
            code !in 200..299 && body.isBlank() -> ProviderResponse.Failure(ProviderFailure.PROVIDER_ERROR, "HTTP $code")
            else -> ResearchJson.decodeResponse(body)
        }
    } catch (e: SocketTimeoutException) {
        ProviderResponse.Failure(ProviderFailure.TIMEOUT, e.message)
    } catch (e: IOException) {
        ProviderResponse.Failure(ProviderFailure.NETWORK, e.message)
    } catch (e: IllegalArgumentException) {
        ProviderResponse.Failure(ProviderFailure.NOT_CONFIGURED, e.message)
    }
}
