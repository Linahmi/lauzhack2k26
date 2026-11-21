# Ripple — Research-Backed Design: Break My Plan

**Prepared:** 21 November 2026  
**Status:** Research synthesis and proposed implementation, NOT a claim of academic novelty or production-grade financial advice.  
**Product:** Two-developer Kotlin Multiplatform app (Android + JVM desktop), UBS Financial Twin + IMC Tech for Good + JetBrains.

## Executive decision

**Build a counterexample-guided financial scenario explorer** rather than a conventional financial chatbot.

- **Guardian / Break:** Exhaustively search a small declared set of possible financial conditions for a counterexample violating the user's liquidity constraint.
- **Skeptic / Question:** Determine whether a conclusion flips as one unknown is varied; ask for the most decision-relevant unknown, not a complete questionnaire.
- **Explorer / Repair:** Search a user-approved, bounded set of actions for a feasible change; then run Break again.
- **Explain:** Every result includes the precise input, uncertainty set, financial event, formula, witness trace, and its limitations.

The academic contribution is **not ours**: robust optimization, algorithmic recourse, active feature acquisition, household financial control, and time-aware recourse already exist. Our opportunity is an *accessible, transparent product integration* for people with incomplete financial information.

## 1. Critical literature review

### P1 — Gonzalez Villasanti & Passino (2017): *Feedback Controllers as Financial Advisors for Low-Income Individuals*
- Source: https://people.engineering.osu.edu/media/document/2024-03-06/finanadvisor-tcst.pdf
- Domain: low-income household economics and control theory. The paper models an economic agent as a discrete-time stochastic system with financial/credit constraints and explores MPC, PID, and stochastic dynamic programming. It explicitly considers uncertain income, shocks, and poverty traps; the proposal includes computer-assisted delivery, such as mobile.
- **Implication:** Household finances as a stateful control/optimization problem are not novel. A full MPC controller is excessive for our hackathon and may impose ungrounded assumptions about how users behave.
- **Adopt:** An explicit evolving state, cash-flow constraints, an intervention model, and a transparent horizon.
- **Do not claim:** That Ripple invents control-theory financial assistance or demonstrates real-world reduction in financial hardship.

### P2 — Bertsimas & Sim (2004): *The Price of Robustness*
- Sources: https://www.mit.edu/~dbertsim/papers/Robust%20Optimization/The%20price%20of%20Robustness.pdf ; DOI 10.1287/opre.1030.0065
- Domain: robust linear optimization; the adjustable protection parameter Gamma controls conservatism against uncertain coefficients.
- **Implication:** Assuming *all* income months are bad is excessively conservative; assuming none are bad is fragile.
- **Adopt:** A simple, explicitly stated *cardinality-constrained stress set*: among six months, up to two are lower-income months. This is **inspired by** budgeted uncertainty, not an implementation of the paper's complete robust counterpart or probabilistic guarantees.
- **Do not claim:** The Gamma chosen for a fictional person has an estimated real-world probability. It is a user-selected stress severity, not a calibrated forecast.

### P3 — Ustun, Spangher & Liu (2019): *Actionable Recourse in Linear Classification*
- Sources: https://berkustun.com/papers/actionable-recourse/ ; DOI 10.1145/3287560.3287566
- Domain: integer programming for feasible changes to an unfavorable classifier outcome; distinguishes actionable from immutable inputs and considers intervention difficulty.
- **Implication:** Advice like 'increase your income by CHF 1,000' is not acceptable merely because arithmetic makes the plan feasible.
- **Adopt:** Actions explicitly approved by the fictional user, with effect size, feasible start dates, bounded maximum, and declared cost metric. For a tiny finite set, prove optimality by exhaustive enumeration.
- **Do not claim:** Its classification algorithm directly solves household cash-flow dynamics.

### P4 — Li & Oliva (2021): *Active Feature Acquisition with Generative Surrogate Models*
- Sources: https://proceedings.mlr.press/v139/li21p.html ; paper PDF https://proceedings.mlr.press/v139/li21p/li21p.pdf
- Domain: active feature acquisition, balancing information value and acquisition costs through generative surrogate models and decision policies.
- **Implication:** Asking everyone every budgeting question imposes unnecessary burden.
- **Adopt:** A very small deterministic question-prioritization heuristic: identify unknowns whose bounded values flip a constraint or cause the greatest swing in safety margin. Show why that unknown was selected.
- **Do not claim:** We trained the paper's model, learned feature distributions, or proved optimal question selection.

### P5 — De Toni, Teso, Lepri & Passerini (2025): *Time Can Invalidate Algorithmic Recourse*
- Sources: https://doi.org/10.1145/3715275.3732008 ; https://facctconference.org/static/docs/facct2025-206archivalpdfs/facct2025-final48-acmpaginated.pdf
- Domain: causal algorithmic recourse under changing data distributions; studies how a previously valid intervention may fail at a future time and proposes a temporal method.
- **Implication:** 'Options disappear over time' already exists in research. The paper also emphasizes dependence on a reliable model of temporal dynamics, a limitation worth acknowledging.
- **Adopt:** Search the latest start date for a *specific* finite financial intervention that remains sufficient under the *specified* scenarios.
- **Do not claim:** That we introduced temporal recourse, use a learned causal forecasting model, or predict real action availability.

### Prior-art conclusion
The product novelty is **not** three LLM personalities, a stress test, a scenario curve, or time-aware recourse alone. It is a cohesive, inspectable **Break -> Question -> Repair -> Retest** workflow, optimized for low-information users, with actual bounded search in shared Kotlin and an accessible phone-to-desktop demo.

## 2. One fully specified, reproducible fictional demo

**Fictional case:** Alex is evaluating a move to shared housing. Six months of finances are modeled. All amounts CHF, all income net, cash recorded at month-end, no interest/taxes/new debt in this deliberately limited example. The move happens at the beginning of month 1, so its upfront cost is counted in that month. No real Swiss assistance, tax, or lease rule is represented.

### Inputs
- Starting *liquid cash*: CHF **4,000**.
- Normal net income: CHF **2,600** per month.
- Low net income: CHF **1,800** per month (CHF 800 below normal).
- After-move ongoing living expenses: CHF **2,350** per month (including new housing). Before-move expenses: CHF 2,000 per month, if displaying a no-move baseline.
- Required annual obligation: CHF **900**, in month **4**.
- Move's **known non-refundable fee**: CHF **500**, in month 1.
- Move's **refundable but inaccessible deposit**: unknown CHF **1,300–1,700**, paid month 1; therefore total upfront **cash outflow F = CHF 1,800–2,200**. Deposit is a transfer from liquid cash to a restricted asset, *not ordinary consumption*; do not treat the entire upfront cash outflow as a reduction in net worth.
- Liquidity floor: month-end cash >= CHF **500**, every month.
- Uncertainty set: **at most 2 low-income months among months 1–6**, all other months normal. No probabilities claimed. There are **22** possible low-month subsets (1 empty, 6 singletons, 15 pairs).
- Candidate user-approved action for repair: reduce *flexible/non-essential spending* by a fixed **r integer CHF/month, 0 <= r <= 100**, starting in a chosen month s, thereafter sustained through month 6. This is a **fictional stated capability**, not an assertion that real low-income users have CHF 100 of discretionary spending.

### Exact simulation equation

Let z_t = 1 if month t is a low-income month and 0 otherwise, with sum(z_t) <= 2.

For the *move* scenario:

```
C_0 = 4000
C_t = C_(t-1)
    + (2600 - 800*z_t)           // net income
    - 2350                       // ongoing expenses
    - (F if t == 1 else 0)       // upfront liquid-cash outflow
    - (900 if t == 4 else 0)     // known obligation
    + (r if t >= s else 0)       // allowed discretionary reduction
```

To separately track net worth, move the refundable deposit from liquid cash into an inaccessible deposit asset. Only the CHF 500 move fee is consumed. No debt or interest is modeled.

### The initial, deceptively comfortable result
At F = CHF 2,200, **with no low-income months and no repair**, month-end liquid cash is:

| Month | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| Cash (CHF) | 2050 | 2300 | 2550 | 1900 | 2150 | 2400 |

Every month is comfortably above CHF 500. A single nominal forecast could make moving appear straightforward under these assumptions.

### Guardian finds a counterexample
Search the 22 stress sequences, F = CHF 2,200, no repair. A witness is **low income in months 1 and 2**:

| Month | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| Income | 1800 | 1800 | 2600 | 2600 | 2600 | 2600 |
| End cash | **1250** | **700** | **950** | **300** | **550** | **800** |

The low point is month 4: **CHF 300**, which violates the CHF 500 liquidity floor by **CHF 200**.

The financial engine **finds** this scenario by exhaustive search; no LLM invents the numbers.

### Explorer finds two provable repair results
**Definition A — Minimum monthly reduction if action begins in month 1:** Exhaustively test integer r from 0 to 100. The least r that maintains C_t >= 500 across **all 22 stress sequences** at F = 2200 is **CHF 50 per month**.

One stress-witness trace after r=50 beginning in month 1:

| Month | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| End cash | **1300** | **800** | **1100** | **500** | **800** | **1100** |

At r=49, the month-4 worst balance is 496, so the minimum **within this exact action set and CHF 1 granularity** is CHF 50. This is not 'the optimal life decision.'

**Definition B — Latest allowed start if reduction capped at CHF 100 per month:**

| Start month | Smallest CHF/month that passes all 22 stresses | Feasible within cap? |
|---|---:|---|
| 1 | 50 | Yes |
| 2 | 67 | Yes |
| 3 | 100 | Yes |
| 4 | Requires 200 | **No** |

Thus, **month 3 is the last modeled start date** if the person can reduce optional expenses by at most CHF 100/month. Starting in month 4 at CHF 100 yields worst month-4 cash **CHF 400**, still below the floor.

This is a statement about this explicitly bounded model, **not** a factual claim about the user's real-world opportunities or a recommendation to cut essentials.

### Skeptic finds a decision-changing missing input
The *deposit* is not known. It is constrained to [1300,1700], and the known fee is 500, so F is [1800,2200]. Under the same 22 stress sequences, without any repair:

- Deposit **CHF 1,300** (F=1800) -> worst monthly cash **CHF 700**, feasible.
- Deposit **CHF 1,500** (F=2000) -> worst monthly cash **CHF 500**, exactly feasible.
- Deposit **CHF 1,700** (F=2200) -> worst monthly cash **CHF 300**, infeasible.

**Exact threshold: refundable deposit <= CHF 1,500** gives a feasible move without repair *within this specified uncertainty set*.

Skeptic's next question is therefore:

> “Do you know the required refundable housing deposit? If it's CHF 1,500 or less, this modeled plan meets your minimum cash buffer even across the two low-income months we tested. Above CHF 1,500, the conclusion can change.”

A second fictional unknown that changes only a small expense after month 5 should not be prioritized above the deposit when assessing the first liquidity breach. **Do not claim globally optimal question selection** without a defined questioning cost/objective.

### Transparent formula showing the threshold
At month 4, under the worst two low-income months, without repair:

```
C_4 = 4000 + 4*(2600 - 2350) - 900 - F - 2*800
    = 2500 - F
    = 2000 - deposit
```

For C_4 >= 500, deposit <= 1500.

A UI may visually highlight this algebra next to the graph. This is a stronger explainability moment than having a chatbot say it is 'confident.'

## 3. Concrete Kotlin domain design

Implement financial arithmetic in the shared Kotlin `core/src/commonMain` module. All monetary values should be integer **CHF cents** (`Long`) or another exact decimal representation, never floating-point currency math.

Suggested immutable structures (adapt to the current repository rather than forcibly refactoring):

```kotlin
// Conceptual signatures, adapt to existing code.
data class CashFlowEvent(val month: Int, val amountCents: Long, val category: EventCategory)
data class StressSet(val monthCount: Int, val maxLowIncomeMonths: Int, val lowIncomeDeltaCents: Long)
data class LiquidityConstraint(val floorCents: Long)
data class ScenarioResult(val monthlyCashCents: List<Long>, val minimumCashCents: Long,
                          val firstViolationMonth: Int?)
data class StressWitness(val lowIncomeMonths: Set<Int>, val result: ScenarioResult)
data class RepairOption(val startMonth: Int, val monthlyReductionCents: Long)
```

Recommended pure methods:

```text
simulate(profile, plan, stressSequence, repair) -> ScenarioResult
findCounterexample(profile, plan, stressSet, constraint) -> StressWitness?
findMinimalMonthlyRepair(..., fixedStartMonth, maxReduction) -> RepairResult
findLatestFeasibleStart(..., fixedReductionCap) -> StartWindowResult
findDecisionChangingUnknown(..., missingInputRanges) -> QuestionCandidate?
```

**Guarantee scope:** Exhaustive search proves a statement *only* about the explicitly enumerated finite uncertainty/action sets. Never label it real-world safety.

A simple 6-month, Γ=2 adversarial search has only 22 stress sequences, so no solver or Monte Carlo is necessary. For MVP, exhaustive enumeration is more trustworthy and easier to demo than an opaque optimizer.

## 4. Responsible-AI guardrails

- Use a clearly labeled fictional profile and synthetic assumptions. No UBS client data.
- Keep the LLM outside financial computation; its output must reference actual result IDs, trace values, and assumption IDs.
- Label all inputs: known, user estimate, assumption, computed. Do not silently infer missing expenses, income patterns, support payments, or real probabilities.
- **Do not model income as 'two low months are likely'.** The Gamma stress budget is a hypothetical scenario-set restriction.
- Negative cash is a funding gap, not automatically zero or a new loan.
- Don't optimize net worth as if it were the sole goal.
- Don't call a deposit a consumption expense; transfer it to a restricted asset if reporting net worth.
- Never recommend reduction of essential expenses; only evaluate explicitly feasible user-specified actions.
- If no feasible repair is found, say so within the search scope and offer transparent assumptions or a human-counselor workflow.
- For IMC, Caritas Vaud remains a **provisional** eligible beneficiary; no partnership or eligibility approval should be claimed without confirmation.

## 5. Two-developer split and incremental integration

**Developer A — `core` ownership:** Cash-flow engine, state/events, stress enumeration, counterexample search, exhaustive repair search, uncertainty threshold, assertions/tests, example fixture. Output stable pure Kotlin APIs with README examples.

**Developer B — `composeApp` ownership:** Android guided story with exact/range inputs; desktop side-by-side nominal/witness/repaired timeline; source/assumption inspector; responsive UX; optional 3-mode labels on analytical operations. Use fixture objects while API is frozen, then integrate.

**Shared checkpoint 1:** Both agree on typed public interfaces and example fixture.  
**Shared checkpoint 2:** Core test vectors pass; desktop can render actual results.  
**Shared checkpoint 3:** On Android, selecting a fictional deposit and stress budget triggers real core outputs.  
**Shared checkpoint 4:** Desktop runs same shared Kotlin engine and reproduces the witness and repair without hard-coded graphs.  
**Cut first if behind:** voice, LLM multi-agent orchestration, weights/personality mixer, real-time sync, authentication, complicated domain rules, advanced Monte Carlo.

## 6. Demo storyboard (90 seconds)

1. **Android (15s):** “I want to move out. I don't know my deposit, and income varies.” Show structured ranges, not bank authentication.
2. **Nominal (10s):** Normal months look fine: month 4 CHF 1,900 with F=2200.
3. **Break (20s):** Tap **Break My Plan**. Worst modeled month becomes CHF 300. Highlight two low-income months + insurance obligation. The exact six-month witness is visible.
4. **Question (15s):** Skeptic highlights missing deposit, showing robust threshold CHF 1,500. Enter 1,300 and witness becomes feasible; enter 1,700 and it fails.
5. **Repair (15s):** With 1,700 deposit, find minimum CHF 50/month reduction from month 1; drag start date to month 4 and see it fail even at CHF 100.
6. **Desktop (15s):** Show comparative timeline, evidence panel with equation, and live Kotlin validation test. Explain: same core module on both platforms.

**Optional future-self voice:** decorative narrative only; never replace numbers and validation. **No promise** of actual future affordability.

## 7. Judge evidence

**UBS:** fictional profile, independently implemented state transition, explicit baseline, intervention, comparative traces, cash reconciliation, negative cash handling, transparent assumptions, bound-specific conclusions, deterministic tests. UBS documents are references only, not copied code.

**IMC:** help a user with incomplete records identify a decision-changing piece of information; state a plausible nonprofit counseling workflow; identify a real eligible charity subject to sponsor confirmation; demonstrate a tangible interaction rather than claim measured societal impact.

**JetBrains:** Android + JVM desktop both demonstrably running; `commonMain` carries financial state, stress search, repair, and question selection; platform-native UX; review evidence of agent work in JetBrains Air when available. Voice/Koog are optional.

## 8. Unit-test / acceptance vectors

For F=2200 and no cuts:
- Nominal no-low income trace: `[2050, 2300, 2550, 1900, 2150, 2400]` CHF.
- Stress with low income in months 1 & 2: `[1250, 700, 950, 300, 550, 800]` CHF.
- Exhaustive stress subset count: **22**.
- Minimum cash over all stress cases: **CHF 300**.
- Minimum monthly reduction with start=1 and cap=100: **CHF 50**.
- Minimum monthly reduction for start=2: **CHF 67** (integer CHF granularity; 66 is insufficient).
- Minimum monthly reduction for start=3: **CHF 100**.
- No feasible reduction <=CHF 100 beginning at month 4.
- With start=1, r=50, low months 1 & 2: `[1300, 800, 1100, 500, 800, 1100]` CHF.
- With start=3, r=100, low months 1 & 2: `[1250, 700, 1050, 500, 850, 1200]` CHF.
- With start=4, r=100, low months 1 & 2: minimum CHF 400.
- With F=1800, no cut, worst minimum CHF 700.
- With F=1900, no cut, worst minimum CHF 600.
- With F=2000, no cut, worst minimum CHF 500.
- With F=2200, no cut, worst minimum CHF 300.

Add boundary tests: F=0, zero income, two vs three low months, no move, missing deposit, event at month6, constraint exactly equal floor, duplicate deposit accounting, deterministic ordering of witnesses, idempotent recomputation, large balances/cents overflow guard, consistent period timing.

## 9. Honest novelty claim and anti-claims

**Defensible:** “We combine finite robust financial stress testing, actionable constrained repair, and decision-relevant questions into one transparent, cross-platform decision workflow for people with incomplete financial information.”

**Not defensible:** “No one has used robust optimization in household finances,” “we invented time-aware recourse,” “the AI predicts when the user will run out of money,” “our stress scenarios have calibrated probabilities,” “Caritas has adopted our technology,” or “our solution is financially safe in the real world.”

## Sources to cite in pitch / README

1. Gonzalez Villasanti, H. & Passino, K. M. (2017). *Feedback Controllers as Financial Advisors for Low-Income Individuals*. IEEE TCST. https://people.engineering.osu.edu/media/document/2024-03-06/finanadvisor-tcst.pdf
2. Bertsimas, D. & Sim, M. (2004). *The Price of Robustness*. Operations Research 52(1), 35–53. https://doi.org/10.1287/opre.1030.0065
3. Ustun, B., Spangher, A., & Liu, Y. (2019). *Actionable Recourse in Linear Classification*. FAccT. https://doi.org/10.1145/3287560.3287566
4. Li, Y. & Oliva, J. (2021). *Active Feature Acquisition with Generative Surrogate Models*. ICML. https://proceedings.mlr.press/v139/li21p.html
5. De Toni, G., Teso, S., Lepri, B., & Passerini, A. (2025). *Time Can Invalidate Algorithmic Recourse*. FAccT. https://doi.org/10.1145/3715275.3732008
6. UBS LauzHack 2026 official repository (locally supplied by team): `docs/scenario-modelling.md`, `evaluation/technical-validation.md`, `evaluation/responsible-ai-and-data.md`. Obey licensing restrictions.
