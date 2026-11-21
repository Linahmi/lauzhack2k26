package ch.ripple.app.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.ripple.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The supported demo only. Raw speech never reaches the core's free-text command parser. */
class FinancialTwinController(private val onConfirmed: (String) -> Unit = {}) {
    var briefing by mutableStateOf<Briefing?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pending by mutableStateOf<PendingDeposit?>(null)
        private set
    private var graph: DecisionGraph? = null
    private val mutex = Mutex()

    data class PendingDeposit(val command: TwinCommand.Correct, val nodeId: String, val value: InputState)

    suspend fun initialize(restoredAnswer: String? = null) = mutex.withLock {
        if (graph != null) return@withLock
        busy = true
        error = null
        try {
            val result = withContext(Dispatchers.Default) {
                var g = startGraph(SampleData.alexSession)
                // Replay ONLY a previously explicitly confirmed typed value, never a draft/transcript.
                val value = DepositInput.restore(restoredAnswer)
                if (value != null) {
                    val update = g.dispatch(TwinCommand.Correct(Correction.AnswerInput("deposit", value)), confirmedByUser = true) as CommandResult.Updated
                    g = update.update.graph
                    g to update.update.briefing
                } else g to g.briefing()
            }
            graph = result.first
            briefing = result.second
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "The demo could not be calculated. Please try again."
        } finally { busy = false }
    }

    fun discardPending() { pending = null }

    suspend fun proposeDeposit(value: InputState): Boolean = mutex.withLock {
        val current = graph ?: return@withLock false
        busy = true
        error = null
        try {
            val inputId = current.active.session.inputs.singleOrNull { it.id == "deposit" }?.id ?: return@withLock false
            val command = TwinCommand.Correct(Correction.AnswerInput(inputId, value))
            val result = withContext(Dispatchers.Default) { current.dispatch(command, confirmedByUser = false) }
            if (result is CommandResult.NeedsConfirmation) {
                pending = PendingDeposit(result.command, current.activeNodeId, value)
                true
            } else false
        } finally { busy = false }
    }

    suspend fun confirmDeposit(): Boolean = mutex.withLock {
        val proposal = pending ?: return@withLock false
        val current = graph ?: return@withLock false
        if (proposal.nodeId != current.activeNodeId) { pending = null; error = "The plan changed. Please review your answer again."; return@withLock false }
        busy = true
        error = null
        try {
            val result = withContext(Dispatchers.Default) { current.dispatch(proposal.command, confirmedByUser = true) }
            if (result is CommandResult.Updated) {
                graph = result.update.graph
                briefing = result.update.briefing
                pending = null
                onConfirmed(DepositInput.save(proposal.value))
                true
            } else false
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "That answer could not be applied. Your previous plan is unchanged."; false
        } finally { busy = false }
    }
}

/** Input conversion, not financial modelling. Whole-field matching rejects ages, signs and multiple amounts. */
object DepositInput {
    fun parse(text: String, language: String): InputState? {
        val normalized = text.trim().replace('\u00a0', ' ').replace('\u202f', ' ')
        if (normalized.lowercase().trimEnd('.', '!') in setOf("i'm not sure", "i am not sure", "not sure", "i don't know", "je ne sais pas", "pas sûr", "pas sure")) return InputState.Unknown
        val amount = normalized.removePrefix("CHF ").removePrefix("chf ").trim()
        val pattern = if (language == "fr") Regex("(?:[0-9]{1,3}(?:[ '][0-9]{3})+|[0-9]+)(?:,[0-9]{1,2})?")
            else Regex("(?:[0-9]{1,3}(?:[,' ][0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?")
        if (!pattern.matches(amount)) return null
        val decimal = if (language == "fr") ',' else '.'
        val parts = amount.split(decimal)
        val francs = parts[0].filter(Char::isDigit).toLongOrNull() ?: return null
        val cents = if (parts.size == 2) parts[1].padEnd(2, '0').toLongOrNull() ?: return null else 0L
        if (francs > (Long.MAX_VALUE - cents) / 100) return null
        return InputState.Exact(Chf(francs * 100 + cents))
    }
    fun save(value: InputState): String = when (value) {
        is InputState.Exact -> "exact:${value.value.rappen}"
        InputState.Unknown -> "unknown"
        is InputState.Range -> error("Range entry is not part of this guided UI")
    }
    fun restore(value: String?): InputState? = when {
        value == "unknown" -> InputState.Unknown
        value?.startsWith("exact:") == true -> value.substringAfter(':').toLongOrNull()?.takeIf { it >= 0 }?.let { InputState.Exact(Chf(it)) }
        else -> null
    }
    fun label(value: InputState, french: Boolean): String = when (value) {
        is InputState.Exact -> if (french) "Garantie de loyer : ${value.value.format()}" else "Refundable deposit: ${value.value.format()}"
        InputState.Unknown -> if (french) "Garantie de loyer : inconnue. Les limites de l'exemple restent utilisées." else "Deposit: unknown. The stated demo bounds will still be used."
        is InputState.Range -> "${value.low.format()} – ${value.high.format()}"
    }
}

/** Only named demo intents, never financial values or unrestricted conversational understanding. */
object MovingDemoIntent {
    fun supports(text: String): Boolean = text.trim().lowercase().replace('’', '\'').trimEnd('.', '!', '?') in setOf(
        "i'm thinking about moving out", "i am thinking about moving out", "i'm thinking about moving",
        "i am thinking about moving", "i'm thinking about moving soon", "i am thinking about moving soon",
        "moving out", "moving home", "i want to move", "je pense déménager", "je souhaite déménager", "déménager",
    )
}
