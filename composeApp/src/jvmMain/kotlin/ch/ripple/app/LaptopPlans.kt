package ch.ripple.app

import ch.ripple.app.phone.RoutesDecision
import ch.ripple.app.phone.StoredSession
import ch.ripple.app.phone.Step
import ch.ripple.app.phone.Via
import ch.ripple.app.phone.decodeSession
import ch.ripple.app.phone.encode
import ch.ripple.core.DecisionGraph
import ch.ripple.core.DecisionSpec
import ch.ripple.core.GraphChange
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The laptop's saved plans, in the phone's versioned session format (phone/SessionCodec): only
 * each decision's structure and the ordered steps the person took. Numbers are never stored; the
 * engine replays the steps on load. One file in the user's app-data folder, written atomically;
 * an unreadable file is moved aside (never deleted) and reported.
 */
internal class LaptopPlans(private val dir: File = defaultDir()) {
    private val file = File(dir, "routes-laptop-plans.json")

    /** Set when a saved file could not be read (it was kept aside). */
    var problem: String? = null
        private set

    fun load(): List<RoutesDecision> {
        if (!file.exists()) return emptyList()
        return runCatching { decodeSession(file.readText()).decisions }.getOrElse { e ->
            val aside = File(dir, "routes-laptop-plans.unreadable-${System.currentTimeMillis()}.json")
            file.renameTo(aside)
            problem = "Routes couldn't read your saved plans (${e.message ?: e.javaClass.simpleName}). The file was kept as ${aside.name}."
            emptyList()
        }
    }

    fun save(plans: List<RoutesDecision>) {
        dir.mkdirs()
        val tmp = File(dir, "routes-laptop-plans.json.tmp")
        tmp.writeText(StoredSession(null, plans, null).encode())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun defaultDir(): File = File(
            System.getenv("ROUTES_DATA_DIR")
                ?: System.getenv("APPDATA")?.let { "$it/Routes" }
                ?: (System.getProperty("user.home") + "/.routes"),
        )
    }
}

/**
 * The steps that rebuild [g] exactly (node ids are deterministic: n0, n1, …), ending on the node
 * being viewed. Times of steps already saved are kept.
 */
internal fun stepsOf(g: DecisionGraph, previous: List<Step> = emptyList()): List<Step> {
    val made = previous.filter { it !is Step.Select }
    val now = System.currentTimeMillis()
    val steps = g.nodes.drop(1).mapIndexed { i, n ->
        val at = made.getOrNull(i)?.at ?: now
        when (val c = n.change) {
            is GraphChange.Corrected -> Step.Correct(n.parentId!!, at, Via.TAP, c.correction)
            is GraphChange.Explored -> Step.Explore(n.parentId!!, at, Via.TAP, c.hypothesis)
            else -> error("the laptop does not create '${c::class.simpleName}' versions")
        }
    }
    val last = g.nodes.last().id
    return if (g.activeNodeId == last) steps else steps + Step.Select(last, now, Via.TAP, g.activeNodeId)
}

internal fun planOf(id: String, spec: DecisionSpec, createdAt: Long, g: DecisionGraph, previous: RoutesDecision?) =
    RoutesDecision(id, spec, createdAt, Via.TAP, stepsOf(g, previous?.steps.orEmpty()), built = g)
