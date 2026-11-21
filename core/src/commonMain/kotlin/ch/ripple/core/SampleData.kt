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

    /** Ready-to-use session for the guided UI: the deposit starts Unknown (searched over 1,300–1,700). */
    val alexSession = DecisionSession(
        profile = alex,
        baseline = alexStay,
        inputs = listOf(DecisionInput("deposit", "question.deposit", Chf.francs(1_300), Chf.francs(1_700), Chf.francs(1))),
        stress = alexStress,
        floor = alexFloor,
        repairCap = alexRepairCap,
        options = listOf(DecisionOption("stay", "option.stay") { alexStay }),
        decisionLabelKey = "option.move",
    ) { v -> alexMove(v.getValue("deposit")) }

    /** Demo script for the command layer (Break → Repair → Reject → Recompute → Compare → Explain). */
    val alexDemoScript = listOf(
        "Why does this plan fail?",
        "I can't reduce my spending by that much",
        "Show me the alternative",
        "What if I stayed where I am?",
        "My deposit is actually CHF 1,500",
    )

    /**
     * Market-grounded variant (docs/MARKET_DATA.md): fictional Alex moves out into a 1-room
     * apartment in Lausanne on a NEW lease. Rent (net, excl. charges) and deposit are unknown.
     * Fictional, ASSUMED: net income 2,600, other living costs 1,200, charges 150 (not market
     * data: the FSO figures exclude charges). Only the rent benchmarks are sourced.
     */
    val alexLausanneOtherCosts = Chf.francs(1_200)
    val alexLausanneCharges = Chf.francs(150)

    fun alexMoveLausanne(rent: Chf, deposit: Chf) = Scenario(
        name = "Move to a 1-room flat in Lausanne (rent ${rent.format()}, deposit ${deposit.format()})",
        phases = listOf(Phase(0, 6, Chf.francs(2_600), alexLausanneOtherCosts + alexLausanneCharges + rent)),
        oneOffCosts = listOf(
            OneOffCost(0, Chf.francs(500), "Non-refundable moving fee"),
            OneOffCost(3, Chf.francs(900), "Annual obligation"),
        ),
        transfers = listOf(AssetTransfer(0, deposit, "Refundable rental deposit")),
    )

    /** What "rent" means for market matching: Lausanne, 1 room, new lease, net rent, decided in 2026. */
    val alexRentQuery = MarketQuery(
        category = MarketCategory.HOUSING_NET_RENT,
        unit = MarketUnit.CHF_PER_MONTH,
        scope = GeoScope.LAUSANNE,
        housing = HousingCharacteristics(rooms = 1, tenancy = TenancyDuration.UP_TO_ONE_YEAR, includesCharges = false),
        asOfYear = 2026,
    )

    /** Rent searched over CHF 600–1,500 and deposit over CHF 1,300–1,700, both every CHF 10 (ASSUMED bounds). */
    val alexLausanneSession = DecisionSession(
        profile = alex,
        baseline = alexStay,
        inputs = listOf(
            DecisionInput("rent", "question.rent", Chf.francs(600), Chf.francs(1_500), Chf.francs(10), market = alexRentQuery),
            DecisionInput("deposit", "question.deposit", Chf.francs(1_300), Chf.francs(1_700), Chf.francs(10)),
        ),
        stress = alexStress,
        floor = alexFloor,
        repairCap = alexRepairCap,
        options = listOf(DecisionOption("stay", "option.stay") { alexStay }),
        decisionLabelKey = "option.move_lausanne",
        marketData = MarketData.vaudHousing,
    ) { v -> alexMoveLausanne(v.getValue("rent"), v.getValue("deposit")) }

    /** Same fictional decision with NO market data loaded: the research flow starts from scratch. */
    val alexResearchSession = alexLausanneSession.copy(marketData = null)

    /** What Alex explicitly told us: moving out, Lausanne, a 1-room flat on a new lease, deciding in 2026. */
    val alexContext = DecisionContext(DecisionKind.MOVE_OUT, GeoScope.LAUSANNE, 2026, alexRentQuery.housing)

    val moveAbroad = Scenario(
        name = "Move abroad",
        phases = listOf(Phase(0, 120, Chf.francs(3_700), Chf.francs(2_900))),
        oneOffCosts = listOf(OneOffCost(0, Chf.francs(6_500), "Relocation")),
    )
}
