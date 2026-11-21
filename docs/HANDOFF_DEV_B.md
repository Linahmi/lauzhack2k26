# Handoff for Developer B — engine now on `main`

All Developer A work (Break My Plan, `brief()`, personal constraints, decision graph,
trade-offs, command layer) is on `main` as of 2026-11-21. Verified from a fresh clone:
128 tests pass (65 JVM + 63 Android host, incl. UBS golden tests), desktop compiles, APK builds.
`composeApp/` and `androidApp/` were not changed.

## Update your branch

```
git fetch origin
git switch <your-branch>
git merge origin/main        # or: git rebase origin/main (only if you haven't pushed your branch)
```

No conflicts are expected in your folders. `composeApp` already depends on `core`
(`api(projects.core)`), so `import ch.ripple.core.*` works in `commonMain`.

## What to call (all in `ch.ripple.core`)

| Need | Call |
|---|---|
| Start the demo twin | `startGraph(SampleData.alexSession): DecisionGraph` |
| Current screen content | `graph.briefing(): Briefing` (primary, followUp, actions, moreInsights, disclosureKeys, scenarios, evidence) |
| Text → action | `graph.parseCommand(text): TwinCommand?` (null = show buttons) |
| Run an action | `graph.dispatch(cmd, confirmedByUser = false): CommandResult` |
| Answer / correct / reject | `graph.correct(Correction…): GraphUpdate` (or via `dispatch` with confirmation) |
| What-if branch | `graph.explore(Hypothesis.TryOption("stay") / WhatIfInput / ApplyCut): GraphUpdate` |
| Switch / revert branch | `graph.select(nodeId)` then `graph.briefing()` |
| Branch list | `graph.nodes` → `id`, `parentId`, `labelKey`, `hypothetical`, `summary.status` |
| Compare branches | `graph.compareBranches(aId, bId): BranchComparison` |
| Options side by side | `graph.tradeOffs(): List<TradeOff>` |
| Old node under today's limits | `graph.reevaluate(nodeId): NodeSummary` |
| Timelines for charts | `briefing.scenarios["baseline" / "decision-nominal" / "decision-worst" / "decision-repaired"].cashByMonth` |

Full field lists, text keys and wording examples: `docs/ARCHITECTURE.md` → "API for
Developer B", "Self-correcting twin", "Interactive twin loop".

## Minimal end-to-end example (shared Compose code)

```kotlin
import androidx.compose.runtime.*
import ch.ripple.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TwinScreen() {
    var graph by remember { mutableStateOf<DecisionGraph?>(null) }
    var briefing by remember { mutableStateOf<Briefing?>(null) }
    var pending by remember { mutableStateOf<TwinCommand.Correct?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {                       // engine work off the UI thread (~<1 s per call)
        val g = withContext(Dispatchers.Default) { startGraph(SampleData.alexSession) }
        graph = g
        briefing = withContext(Dispatchers.Default) { g.briefing() }
    }

    fun run(cmd: TwinCommand, confirmed: Boolean = false) = scope.launch {
        val g = graph ?: return@launch
        when (val r = withContext(Dispatchers.Default) { g.dispatch(cmd, confirmed) }) {
            is CommandResult.NeedsConfirmation -> pending = r.command          // show "Confirm?" sheet
            is CommandResult.Updated -> { graph = r.update.graph; briefing = r.update.briefing; pending = null }
            is CommandResult.Selected -> { graph = r.graph; briefing = r.briefing }
            else -> { /* Explanation / TradeOffs / Compared: show in a detail panel */ }
        }
    }

    // UI: render briefing?.primary.headlineKey + facts + disclosureKeys (always visible).
    // Text box: graph?.parseCommand(text)?.let { run(it) } ?: showButtons()
    // Confirm sheet: pending?.let { run(it, confirmed = true) }
}
```

Demo phrases (same as `SampleData.alexDemoScript`): "Why does this plan fail?" →
"I can't reduce my spending by that much" (confirm) → "Show me the alternative" →
"What if I stayed where I am?" → "My deposit is actually CHF 1,500" (confirm).

## Rules

- No money arithmetic in Composables; display engine fields only, format with `Chf.format()`.
- Months in facts are zero-based → show `index + 1`. No calendar names yet ("month 4", not "April").
- Always show `disclosureKeys` and each insight's `limitationKeys`.
- Never call `dispatch(..., confirmedByUser = true)` without the user tapping Confirm.
- Need a different contract? Ask Developer A; don't edit `core/`.
