package ch.ripple.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Asks the local Ollama model (the same one research-server uses) to read a decision question.
 * Returns the model's raw JSON text; callers MUST pass it through [readAiProposal], which keeps
 * only structure and drops every number. Local only: nothing leaves this computer.
 */
class OllamaDecisionReader(
    private val url: String = System.getenv("RIPPLE_OLLAMA_URL") ?: "http://127.0.0.1:11434",
    val model: String = System.getenv("RIPPLE_OLLAMA_MODEL") ?: "qwen2.5:7b",
    private val timeoutMs: Int = 150_000,
) {
    /** True if Ollama answers and has [model]. */
    fun available(): Boolean = runCatching {
        val body = get("$url/api/tags", 2_000)
        Json.parseToJsonElement(body).jsonObject["models"]!!.jsonArray
            .any { (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull == model }
    }.getOrDefault(false)

    fun read(question: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("format", "json")
            putJsonObject("options") { put("temperature", 0); put("seed", 42) }
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", DECISION_READER_PROMPT) })
                add(buildJsonObject { put("role", "user"); put("content", question.take(300)) })
            })
        }.toString()
        val c = URI("$url/api/chat").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 3_000; c.readTimeout = timeoutMs; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.encodeToByteArray()) }
        check(c.responseCode in 200..299) { "Ollama answered HTTP ${c.responseCode}" }
        val res = c.inputStream.use { it.readBytes().decodeToString() }
        val msg = Json.parseToJsonElement(res).jsonObject["message"] as? JsonObject
        return (msg?.get("content") as? JsonPrimitive)?.contentOrNull ?: error("Ollama gave no message")
    }

    private fun get(u: String, timeout: Int): String {
        val c = URI(u).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        return c.inputStream.use { it.readBytes().decodeToString() }
    }
}
