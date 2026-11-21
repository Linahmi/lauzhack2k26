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

    val moveAbroad = Scenario(
        name = "Move abroad",
        phases = listOf(Phase(0, 120, Chf.francs(3_700), Chf.francs(2_900))),
        oneOffCosts = listOf(OneOffCost(0, Chf.francs(6_500), "Relocation")),
    )
}
