package ch.ripple.core

/**
 * Fictional example inputs. Parameter values follow the illustrative "Alexia" example of the
 * UBS LauzHack 2026 AI Financial Twin challenge (starter-kit, all rights reserved by the
 * challenge authors). They are illustrative assumptions, not salary or rent statistics.
 * See docs/UBS_REFERENCE_NOTES.md.
 */
object SampleData {
    val alexia = FinancialProfile(name = "Alexia", openingCash = Chf.francs(8_000), horizonMonths = 120)

    val startWorking = Scenario(
        name = "Start working",
        phases = listOf(Phase(0, 120, Chf.francs(4_200), Chf.francs(3_000))),
    )

    val mastersDegree = Scenario(
        name = "Master's degree",
        phases = listOf(
            Phase(0, 24, Chf.francs(1_200), Chf.francs(2_100)),
            Phase(24, 120, Chf.francs(4_800), Chf.francs(3_100)),
        ),
        oneOffCosts = listOf(
            OneOffCost(0, Chf.francs(2_500), "Tuition year 1"),
            OneOffCost(12, Chf.francs(2_500), "Tuition year 2"),
        ),
    )

    /**
     * Break My Plan demo (docs/RESEARCH_TO_IMPLEMENTATION.md §2): fictional "Alex" considers
     * moving to shared housing. 6 months, net income, month-end cash. Our own fictional
     * numbers, not UBS data. Month indexes here are zero-based (month 1 = index 0).
     */
    val alex = FinancialProfile(name = "Alex", openingCash = Chf.francs(4_000), horizonMonths = 6)
    val alexStress = StressSet(maxLowIncomeMonths = 2, lowIncomeReduction = Chf.francs(800))
    val alexFloor = Chf.francs(500)
    val alexRepairCap = Chf.francs(100)

    /** Baseline: don't move. */
    val alexStay = Scenario(
        name = "Stay",
        phases = listOf(Phase(0, 6, Chf.francs(2_600), Chf.francs(2_000))),
        oneOffCosts = listOf(OneOffCost(3, Chf.francs(900), "Annual obligation")),
    )

    /** Move with a refundable deposit (unknown, CHF 1,300–1,700); the fee is consumed, the deposit is not. */
    fun alexMove(deposit: Chf = Chf.francs(1_700)) = Scenario(
        name = "Move (deposit ${deposit.format()})",
        phases = listOf(Phase(0, 6, Chf.francs(2_600), Chf.francs(2_350))),
        oneOffCosts = listOf(
            OneOffCost(0, Chf.francs(500), "Non-refundable moving fee"),
            OneOffCost(3, Chf.francs(900), "Annual obligation"),
        ),
        transfers = listOf(AssetTransfer(0, deposit, "Refundable rental deposit")),
    )

    val alexDepositUnknown = UnknownInput(
        name = "Refundable rental deposit",
        low = Chf.francs(1_300),
        high = Chf.francs(1_700),
        step = Chf.francs(1),
        scenarioFor = ::alexMove,
    )

    val moveAbroad = Scenario(
        name = "Move abroad",
        phases = listOf(Phase(0, 120, Chf.francs(3_700), Chf.francs(2_900))),
        oneOffCosts = listOf(OneOffCost(0, Chf.francs(6_500), "Relocation")),
    )
}
