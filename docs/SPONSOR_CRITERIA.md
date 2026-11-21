# Sponsor alignment matrix — Ripple / LauzHack 2026

**Sources:** user-provided JetBrains Cross-Platform Apps with Kotlin Multiplatform challenge text; IMC official presentation `IMC at LauzHack 2026.pdf` (pages 8, 11, 13, 14); direct inspection of the uploaded `ai-financial-twin-lauzhack-2026-main.zip` (including the UBS `faq/`, `evaluation/`, `starter-kit/` and `Data/` folders). Verify the local Git HEAD if it differs. UBS judge weights are **draft/unapproved** and official timing/submission instructions are **TBC**.

| Track | Criterion / constraint | Minimum evidence in code or live demo | Risk if missing |
|---|---|---|---|
| UBS | Fictional profile | Explicit fictional user and reproducible state | Ineligible/weak fit |
| UBS | At least one working scenario | Executed shared engine computes a changed decision | Ineligible/weak fit |
| UBS | Before/after | Baseline and alternative comparison showing why numbers differ | Ineligible/weak fit |
| UBS | Transparent assumptions | Input inspector with provided/assumed/unknown/computed provenance | Ineligible/weak fit |
| UBS | Financial correctness | Period cash reconciliation; no invented credit; liquidity treated separately from net worth | Serious credibility risk |
| UBS | Sensitivity/uncertainty (bonus) | Bounded income/cost variation and conclusion stability check | Missed differentiation |
| UBS | Conversational AI (bonus) | Extracted candidate data confirmed before simulation; explanations grounded in engine | Optional only |
| IMC | Learning / inclusion / community | User journey for people without complete financial records | Weak thematic fit |
| IMC | Real problem | Concrete access/knowledge barrier and beneficiary scenario | Weak impact argument |
| IMC | Real eligible charity | Candidate **Caritas Vaud**: verify registered nonprofit + mission AND obtain IMC confirmation on *sectarian* and *apolitical* exclusions; document backup charity; no partnership claim | Eligibility risk |
| IMC | Real demo | Working inclusion feature shown end-to-end | Presentation risk |
| IMC | Technical solution (1–10) | Functional tested prototype | Lower score |
| IMC | Impact (1–10) | Who benefits, how, realistic charity workflow and impact hypothesis; no invented results | Lower score |
| IMC | Presentation (1–10) | Clear real-time problem -> action -> outcome | Lower score |
| JetBrains | ≥2 platforms, working | Android and JVM desktop launched with core scenario | Mandatory failure |
| JetBrains | Native-feeling UX | Appropriate mobile inputs, spacious desktop exploration | Lower score |
| JetBrains | Shared technical logic | Simulation and analytics in Kotlin `commonMain`; no duplicated engines | Lower score |
| JetBrains | Creative social good | Credible accessible, uncertainty-aware decision investigation | Lower score |
| JetBrains | Compose Multiplatform (bonus) | Shared composables, adaptive layouts | Optional |
| JetBrains | Localization and themes (bonus) | English/French, light/dark if core complete | Optional |
| JetBrains | Kotlin backend / Koog AI (bonus) | Only if genuinely warranted and functional | Optional |
| JetBrains | JetBrains Air reviewed agent process (extra) | Agent tasks + human reviews/corrections + tests recorded accurately | Bonus only |
| JetBrains | Presentation | Explicit account of shared versus platform-specific code | Lower score |

## Verified UBS details from uploaded repository ZIP (2026-11-21)

- UBS minimum credible demonstration (`faq/challenge-and-scope.md`, Q12): fictional state, intervention, baseline/alternative, assumptions, explanation. AI/chatbot and Alexia persona are optional.
- `starter-kit/sample_profile.json` uses Alexia, CHF 8,000 opening cash, 120 months. `starter-kit/scenario_config.json` has three cash-only scenarios: working **CHF 152,000** closing cash; master's **CHF 144,600** closing, **CHF -18,600** minimum, first negative month index **6**; moving abroad **CHF 97,500** closing. These are test fixtures, **not forecasts**. Confirm matching implementation with independently written Kotlin tests.
- Starter kit phase intervals are `[start_month, end_month)` and zero-based; include opening cash when reporting minimum. Validate gaps, overlaps, invalid one-off costs, negative balances and exact cash reconciliation.
- `Data/synthetic-transactions.zip` **is present**; contains ~65 MB uncompressed CSV, contrary to older notes. Other synthetic datasets are also provided. Data rights and quality are not established merely by presence; do not import or redistribute datasets without permission. Treat supplied data as illustrative, not population-representative.
- No occurrence of **Exemplum** found in the archive's `.md`, `.json`, `.py`, `.csv` files. No top-level LICENSE/NOTICE file in archive, so treat code/data as reference-only pending permission.
- `evaluation/judging-rubric.md` and `evaluation/judge-scorecard.md` identify weights as *draft only*. `evaluation/demo-and-submission.md` states submission timing, platform, and demo duration are **to be confirmed**.

## Caritas Vaud — provisional IMC beneficiary, not an approved partner

**Why it fits the product hypothesis:** A person may not have an account-linked, fully recorded budget. Ripple's ability to accept ranges and unknowns, ask the most decision-relevant question, disclose uncertainty, and produce a counselor-inspectable case summary could reduce preparation friction for financial-support nonprofits.

**What is not established:** Caritas approval, partnership, interest in using Ripple, measurable impact, or acceptance under IMC eligibility rules. The IMC deck excludes *sectarian religion* and requires *apolitical* nonprofits. Caritas's Catholic history and public advocacy create **classification questions**, not automatic disqualification.

**Eligibility and impact gate before claiming IMC fit:**
1. Ask the IMC booth whether Caritas Vaud would qualify despite religious history and nonpartisan policy advocacy; record the answer and the source in `docs/CHARITY_VALIDATION.md`.
2. Confirm registered nonprofit status and finance/budget-counseling relevance via current official sources. Do not use actual client records.
3. Maintain a backup nonprofit with unambiguous eligibility.
4. Demonstrate **one synthetic beneficiary story**: incomplete inputs → decision-relevant question → bounded scenario → counselor-ready explanation.
5. Describe impact as a hypothesis until there is real evaluation. Measure only demonstrated outcomes (e.g. number of user inputs requested, reproducibility, correct flags), not unsupported charity savings or outcomes.

## Feature acceptance template

For each substantial feature, add:

```markdown
### Feature: <name>
- Real user/charity beneficiary and barrier:
- UBS: computed scenario, transparency, or validation improved:
- IMC: exact inclusion/impact mechanism, charity applicability:
- JetBrains: shared code + Android behavior + desktop behavior:
- Demo proof (live and fallback):
- Tests/acceptance criteria:
- Owner and integration interface:
- Cost / feature we can cut instead:
- Claims requiring external verification:
```

## Must-answer sponsor questions before final submission
1. **UBS:** Which result changed because of which decision? What is assumed, unknown, and mathematically calculated? Can judges reproduce it?
2. **IMC:** Which *verified eligible* nonprofit could use this (Caritas Vaud only after IMC confirms); who directly benefits; how does it concretely improve their day; what do we have evidence for versus only hypothesize?
3. **JetBrains:** Can judges see it actually running on Android AND desktop? Which business logic lives in `commonMain`? What remained native/platform-specific? Did we genuinely use/review Air agent work if claimed?

## Important non-goals
- No real private client data.
- No predictions masquerading as guaranteed future outcomes.
- No unsupported financial/legal advice.
- No fabricated nonprofit partnership, impact statistics, or technical test pass.
- No replacing actual shared KMP logic with a Python-only engine.
