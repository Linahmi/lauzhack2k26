# UBS reference notes

Source: UBS LauzHack 2026 "AI Financial Twin" repository (local clone, read from Git HEAD
`fbaf35d`, 2026-11-21). The working tree there is empty; files exist only in HEAD.

**Local Git HEAD vs. team ZIP (`ai-financial-twin-lauzhack-2026-main.zip`), compared
2026-11-21:** all 58 files in HEAD are byte-identical in the ZIP (line endings normalised).
The ZIP has 3 extra files: `Data/life-events.csv`, `Data/synthetic-transactions.zip`,
`evaluation/judging-rubric.md` (marked "Draft only"). Starter kit identical → golden values
unaffected. `starter-kit/README.md` refers to a `NOTICE.md` that exists in neither copy.

## Licensing (confirmed — `README.md`)

No open-source or open-data licence is granted; materials are "all rights reserved" and
provided for participation in the challenge. **We copy no UBS code, JSON or datasets.**
Our engine is an independent Kotlin implementation. `SampleData.kt` reuses the *numeric
example parameters* of the Alexia scenarios (with attribution) so our results can be
checked against theirs. If the organisers object, replace them with our own profile.
`resources/data-usage-and-licenses.md`: check each third-party data source's terms; link
rather than copy when unclear.

## Confirmed requirements

From `README.md`, `evaluation/technical-validation.md`, `evaluation/judge-scorecard.md`:
- Fictional financial state; at least one meaningful what-if; recompute; compare; explain
  trade-offs. "Don't predict the future. Make it explorable."
- Evidence for judges: input profile, one intervention, assumptions, calculated comparison,
  one sanity test/validation result, limitations.
- Draft scorecard weights: modelling & correctness 25, scenario/counterfactual exploration 20,
  technical execution & validation 15, UX & communication 15, innovation 15,
  responsible design & transparency 10.

## Accounting rules (confirmed — `docs/financial-concepts.md` / `faq/financial-modelling.md` / `evaluation/technical-validation.md`)

- Cash identity `C(t+1) = C(t) + Σ cash flows`; example 8000 + 1200 − 1800 = 7400.
- Net worth = assets − liabilities; differs from liquidity.
- Investment transfers reduce cash but are not consumption; a CHF 500 transfer must not
  reduce net worth by 500 (before fees/market moves).
- Debt principal repayment reduces cash and debt; interest is an expense.
- Negative cash must be flagged as financing need or infeasibility, not ignored.
- Missing input → ask for clarification or apply a *labelled* assumption.
- Deterministic inputs must reproduce identical results; stochastic methods must document
  seeds/distributions and never present probabilities as known.
- Boundary cases: zero income, zero interest, higher tuition must not raise cash, loan
  payoff never makes principal negative, consistent time steps and currencies.
- LLM output must agree with the calculation engine.

## Starter kit (`starter-kit/financial_twin.py`)

Cash-only monthly simulator: profile `{opening_cash_chf, horizon_months}`, scenario
`{phases[start_month, end_month, monthly_income_chf, monthly_expenses_chf], one_off_costs[month, amount_chf]}`.
Validates gaps/overlaps/ranges, rejects negative expenses and one-off costs, rounds to 2 dp,
minimum includes opening cash, first negative month is zero-indexed.

### Golden values — verified

Ran `run_example.py` and the starter-kit tests (5/5 pass) in a scratch copy, plus hand
calculation. Alexia: opening CHF 8,000, horizon 120 months.

| Scenario | Closing | Minimum | First negative (0-indexed) |
|---|---:|---:|---:|
| Start working | 152,000 | 8,000 | none |
| Master's degree | 144,600 | −18,600 | 6 (printed "Month 7") |
| Move abroad | 97,500 | 2,300 | none |

All five values from earlier notes were correct; the only nuance is that "index 6" is
zero-based while the script prints it as month 7. These are encoded in `UbsGoldenTest`.

### Deviations from the starter kit

- Exact integer Rappen instead of float rounded to 2 dp (same results for these inputs).
- We also reject **negative monthly income** (the starter kit allows it).
- Each month keeps an itemised `MonthRecord` for future traceability.

## "Exemplum" / benefits rules

`git grep -i "exemplum|benefit"` over HEAD finds **no matches**. "Canton" appears only as a
column in `Data/synthetic-profiles.csv`. Exemplum and benefits-cliff simulation are **not**
part of the UBS materials; they came from a separate concept and are out of scope unless the
team decides otherwise.

## Other useful material (not yet used)

`Data/*.csv` (synthetic profiles, accounts, liabilities, goals, scenario catalog/assumptions),
`docs/scenario-library-120.csv`, `examples/*`. Same licensing caveat applies — read, don't copy.
