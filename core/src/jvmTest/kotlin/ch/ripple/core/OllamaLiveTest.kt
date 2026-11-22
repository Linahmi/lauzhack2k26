package ch.ripple.core

import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs only with RIPPLE_LIVE_TESTS=1 and a local Ollama: checks the real model's reading survives validation. */
class OllamaLiveTest {
    @Test fun localModelReadingIsValidatedStructureOnly() {
        if (System.getenv("RIPPLE_LIVE_TESTS") != "1") return
        val reader = OllamaDecisionReader()
        if (!reader.available()) return
        val q = "What if I studied abroad in Amsterdam?"
        val raw = reader.read(q)
        val r = readAiProposal(q, interpretDecision(q), raw)
        println("OLLAMA RAW: $raw")
        println("OLLAMA VALIDATED: title=${r.title} location=${r.location} extra=${r.extraChanges.map { it.kind to it.label }} dropped=${r.dropped}")
        assertTrue(r.extraChanges.all { it.amount == null })
        assertTrue(r.location == null || q.contains(r.location!!, ignoreCase = true))
    }
}
