# Ripple — Permanent project instructions (LauzHack 2026)

> Put this file at the **root of the Ripple repository**. AI agents must read it before planning, coding, reviewing, and preparing the demo. These constraints apply to **every milestone**, not just the final pitch.

## Mission
Build one technically credible, accessible financial-decision explorer eligible for **UBS AI Financial Twin**, **IMC IMChange Tech for Good**, and **JetBrains Kotlin Multiplatform**. The team has **exactly two CS developers**. Optimize for **correctness × genuine impact × native cross-platform delivery × memorable live demo**. Do not add features just to tick boxes.

**Product:** A fictional user's question becomes an inspectable, uncertainty-aware financial model. Guardian investigates modeled vulnerabilities and feasible intervention timing; Skeptic challenges assumptions and seeks decision-relevant missing information; Explorer compares user-approved counterfactuals. These are distinct analytical procedures, **not** LLM personalities with arbitrary prompts. Mixing their attention weights must never weaken risk disclosure, accounting correctness, or safeguards. Phone-first exploration and a desktop evidence/visualization view are complementary experiences.

## Challenge gate — apply before accepting ANY new feature
For each proposal/PR, write a brief note (in the PR or `docs/DECISIONS.md`):
1. **Problem and beneficiary:** which real user friction, especially a financial-information or access barrier, does this resolve?
2. **UBS evidence:** which scenario/counterfactual, verified math, assumption, uncertainty, or understandable conclusion improves?
3. **IMC evidence:** what *specific* benefit for an eligible charity/nonprofit or its beneficiaries would this demonstrate? Do not substitute “it might help everyone.”
4. **JetBrains evidence:** which logic is shared in KMP `commonMain`, and how will it run and feel appropriate on Android **and** JVM desktop?
5. **Demo proof:** how will judges SEE this working in a short live demonstration, and what is the fallback?
6. **Feasibility:** owner, interface, tests, integration risk, and feature that gets cut to make room.

If a feature has no defensible effect on at least one criterion or makes the core demo less reliable, defer it. Verify completion with tests and real runnable evidence, not assumptions or screenshots of mock behavior.

## UBS — financial twin (reference: official local UBS challenge repository)
**Required minimum** (confirmed in repository analysis; re-check official files): fictional client profile; at least one computed scenario; baseline/before-versus-after comparison; transparent assumptions. Motto: **“Don't predict the future. Make it explorable.”** Optional: conversational input, sensitivity and uncertainty.

**Priorities:** financially correct deterministic calculations, meaningful scenario exploration, reproducible tests, comprehensible explanations, and responsible design. Reported **draft, unconfirmed** scorecard weights from `evaluation/judge-scorecard.md`: financial correctness 25%; scenario/counterfactuals 20%; technical validation 15%; UX/communication 15%; innovation 15%; responsible design 10%. Do not claim official finalized percentages without re-checking.

**Hard financial invariants:** cash reconciliation every period; distinction between liquidity and net worth; transfers are not expenses; proper debt principal/interest treatment *if modeled*; income gross/net explicit; no invented loan and no silent clamping of negative balances; show violated constraints as infeasible; explicit currency/horizon; bounded and labeled assumptions; calculations repeatable. For range inputs, show outcomes conditional on specified bounds—**not empirical probabilities** without calibrated distributions.

**AI boundary:** LLM may parse language into *proposed* structured fields, ask clarifying questions and explain **actual** engine outputs. User confirms inputs. LLM must never invent numerical financial facts, financial rules, sources, or investment advice. Distinguish `user-stated`, `assumed`, `unknown`, `computed`, and `external-source` evidence. If the model cannot justify a conclusion, say so.

**Reference inspection (verified against team-uploaded ZIP `ai-financial-twin-lauzhack-2026-main.zip` on 2026-11-21):** local Git repository on developer's Windows machine: `C:\Users\linah\Downloads\ai-financial-twin-lauzhack-2026`. It may have an empty working tree, so inspect `git -C "..." ls-tree -r --name-only HEAD` and `git -C "..." show "HEAD:<path>"`. Read at least `starter-kit/financial_twin.py`, sample/scenario JSON, `docs/scenario-modelling.md`, `evaluation/technical-validation.md`, `evaluation/responsible-ai-and-data.md`, and `evaluation/judge-scorecard.md`. **License handling:** the supplied ZIP contains no top-level LICENSE or NOTICE file (despite a starter-kit README reference to NOTICE); do not infer open-source permission. Treat it as reference-only until specific reuse rights are confirmed. Do not copy/commit its Python code, CSV datasets, or original assets into Ripple. Independently implement financial requirements; document provenance. Verify against the local Git checkout if it differs from the uploaded ZIP. The search of all `.md`, `.json`, `.py`, and `.csv` files in the uploaded archive found **no “Exemplum”**: don't present fictional delayed-benefit rules as UBS-provided.

**Verified UBS sample fixture** (independently derive values from `starter-kit/sample_profile.json` and `scenario_config.json`; validate via tests): Alexia starts with CHF 8,000 and a 120-month horizon. Start working: closing cash CHF 152,000. Master's: closing cash CHF 144,600, minimum cash CHF -18,600, first negative month index 6. Move abroad: closing cash CHF 97,500. These outputs apply **only** to the documented cash-only fictional assumptions; do not claim Swiss realism or full net-worth accounting. Match the starter-kit's zero-based monthly timeline and `[start_month, end_month)` phase intervals; explicitly test gaps, overlaps, invalid one-off costs, and initial-balance-inclusive minimum.

**UBS data inventory correction:** ZIP includes `Data/synthetic-transactions.zip` containing `synthetic-transactions.csv` (about 65 MB uncompressed), plus profiles, accounts, assets, liabilities, goals, cash balances, life events and scenario assumptions. Prior notes saying transactions were missing are inaccurate. Presence does not grant redistribution rights, establish statistical representativeness, or imply that all files join cleanly. The ZIP also includes `docs/scenario-library-120.csv` and `Data/scenario-catalog.csv` for idea discovery; neither is mandatory. Prefer independently authored small fictional fixtures for the prototype.

**UBS scoring status:** `evaluation/judging-rubric.md` explicitly says **Draft only**, and `evaluation/judge-scorecard.md` says to use the weights only after organizer approval. Their 25/20/15/15/15/10 allocation is useful for planning, **not a confirmed official score**. `evaluation/technical-validation.md` and `evaluation/demo-and-submission.md` are suggested guidance; the latter leaves final deadline, submission platform and demo duration **to be confirmed**. Never present a three-minute demo as a confirmed UBS requirement.

## IMC — IMChange Tech for Good (official 16-page sponsor deck)
Theme: **Learning · Inclusion · Community**. Sponsor framing: **“Real problem · Real charity · Real demo”** (slide 11). Judging (slide 14): **Technical solution — does it work? 1–10; Impact — who it helps and how much? 1–10; Presentation — live demo 1–10.** Do not invent weighting beyond the slide's three listed ratings.

**Important eligibility rule from slide 13:** focus on a **registered nonprofit**, **apolitical**, with **youth/education/inclusion** relevance. The slide excludes **sectarian religion**, **business and alumni groups**, and **personal benefit** as charity categories. We must identify a real, eligible charity or nonprofit beneficiary, verify eligibility, and define a plausible workflow; **do not claim a partnership, impact, or endorsement** without direct evidence. Ask the IMC booth to confirm eligibility if ambiguous.

**Candidate charity — Caritas Vaud (provisional, unverified by IMC):** Caritas Vaud appears directly relevant to financial inclusion, budget support and hardship prevention; the proposed beneficiary is **people assisted by a Caritas-type nonprofit**, with a privacy-conscious optional counselor handoff. Do not claim Caritas collaboration, sponsorship, endorsement, access to their client data, or confirmed IMC eligibility. **Two open eligibility questions:** Catholic heritage versus IMC's exclusion of *sectarian religion*, and nonpartisan advocacy versus the *apolitical* requirement. Ask IMC at the booth; record response in `docs/CHARITY_VALIDATION.md` (date, person/role if volunteered, precise wording, conclusion). Identify a backup registered, clearly apolitical education/inclusion nonprofit before pitching. Financial coaching software is only a proposed fit until a nonprofit confirms the workflow.

**Our strongest aligned use case:** make financial decision exploration accessible to people **without complete transaction records, bank linkage, or precise budgets**. Allow exact/range/unknown inputs; ask a small number of high-value questions; show robust findings and limits; offer a privacy-conscious counselor handoff if it serves an identified charity's work. This remains a **hypothesis** until we validate a target charity, their needs, and that this workflow actually helps. Build one plausible fictional beneficiary story and demonstrate the before/after **without fabricating quantitative impact**.

**Impact check for every major feature:** WHO specifically benefits? What currently blocks them? How does the feature reduce that barrier? What is demonstrable now? What would need user/charity validation later? Prioritize simple language, optional French, mobile accessibility, offline/sample mode, no mandatory banking access, and explicit uncertainty when feasible.

## JetBrains — Cross-Platform Apps with Kotlin Multiplatform (official sponsor text)
**Mandatory outcome:** working app with meaningful core functionality on **at least two platforms**. We choose **Android + JVM desktop**. Prove both run; a successful JVM build alone does not prove Android runtime. Use KMP shared computational logic in `commonMain`; prefer Compose Multiplatform; offer polished **native-feeling** screen layouts instead of a tiny desktop-stretched phone UI. In the demo explain precisely what code is shared and what is platform-specific.

**Judging categories:** (1) Implementation/proof of concept; (2) UX and interface; (3) genuinely shared technical features in `commonMain`; (4) innovation/creativity, especially social good; (5) agentic development process **extra points, optional**; (6) presentation/clear shared-versus-platform explanation.

**Potential bonuses** (not requirements): dark/light mode, localization, Compose Multiplatform, Kotlin backend, shared AI/Koog logic if genuinely useful. Do not risk the working prototype to force bonuses.

**JetBrains Air extra credit:** If available, run meaningful Claude/Codex/Junie tasks through Air, compare/review generated changes, run tests, record human corrections. Capture a truthful `docs/AGENT_REVIEW_LOG.md` with task, agent, review finding, human modification, and verification. Never claim use of Air unless actually used. Human review is mandatory for finance logic and generated explanations.

## Two-developer work split
**Developer A — shared financial core:** models, accounting, scenario engine, Guardian/Skeptic/Explorer computations, bounded uncertainty, financial unit/golden tests. Own `core/` (or shared core area) and public APIs.

**Developer B — cross-platform product:** Android conversational/text onboarding, profile confirmation, Compose mobile/desktop layouts, results, evidence inspector, scenario controls and handoff. Own `composeApp/`. 

Agree on types + fixture JSON and interface signatures before independent work. Keep PRs small; integrate a tested vertical slice early. Both must review the boundary between core outputs and AI/UI explanations. No LLM-derived calculations in UI.

## Implementation order & cut line
1. **Proof:** Android + desktop launch; shared Kotlin simulation; fictional profile; baseline/alternative; tests and explicit assumptions.
2. **Decision insight:** Guardian shortfall, Skeptic key uncertainty, Explorer counterfactual, one validated intervention/threshold.
3. **Accessible journey:** text-first onboarding; exact/range/unknown inputs; confirmation; simplified results; desktop inspectability.
4. **Cross-device journey:** reliable session export/import or code-based handoff. QR/cloud sync only if core demo already stable.
5. **Delight (optional):** perspective mixer, voice, future-self narration, translation, themes, Air extras.

**Cut first:** bank integrations, real personal data, payments, investment trading, unverified legal/benefits calculations, complex agent swarms, backend and live sync, cosmetic animations. Never cut UBS financial truthfulness, IMC beneficiary clarity, or JetBrains both-platform proof.

## Release/demo gate
- [ ] Verified UBS official sample/reproducible calculation or documented discrepancy.
- [ ] At least one fictional profile + scenario + baseline comparison + assumptions.
- [ ] Financial reconciliation and negative-cash/constraint tests pass.
- [ ] Range/unknown inputs labeled; no unsupported predictions or advice.
- [ ] A real eligible IMC charity confirmed against sponsor criteria; Caritas Vaud remains **provisional** until the booth resolves eligibility; backup documented; no fabricated partnership/impact.
- [ ] A live beneficiary-focused demo shows real functionality.
- [ ] Android **actually launched** and JVM desktop **actually launched**; record proof. If not, honestly report blocker.
- [ ] Substantive `commonMain` logic; documented shared/platform-specific split.
- [ ] User can trace a result to inputs and math; external sources cited only when used.
- [ ] Agent review evidence truthful if claiming Air bonus.
- [ ] Short demo path with duration adapted to **confirmed organizer limit** and fallback prepared; no invented metrics.

**Source hierarchy:** sponsor slides/docs and verified local UBS repo > tested code/results > user interviews / validated nonprofit needs > market claims / hypotheses. If a source cannot be accessed, label that requirement unverified and request inspection; do not improvise or silently downgrade it.

See `docs/SPONSOR_CRITERIA.md` for a sponsor-by-sponsor acceptance matrix and `docs/CHARITY_VALIDATION.md` for the nonprofit eligibility and impact evidence gate. Never make a product claim stronger than its cited source or completed test.
