package ch.ripple.app.assistant

import ch.ripple.core.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class FinancialTwinControllerTest {
    @Test fun ambiguousNarrativesNeverBecomeAmounts() {
        listOf("I'm 70 years old and my deposit is CHF 1,500", "CHF -1,500", "1500 and 70", "1e3", "99999999999999999999999999999", "1.500.00").forEach {
            assertNull(DepositInput.parse(it, "en"), it)
        }
        assertFalse(MovingDemoIntent.supports("I'm 70 years old and my deposit is CHF 1,500"))
    }

    @Test fun exactAmountsHaveExplicitLocaleAndCurrency() {
        assertEquals(InputState.Exact(Chf(150_050)), DepositInput.parse("CHF 1,500.50", "en"))
        assertEquals(InputState.Exact(Chf(150_050)), DepositInput.parse("1 500,50", "fr"))
        assertNull(DepositInput.parse("1,500.50", "fr"))
        assertNull(DepositInput.parse("1500,50", "en"))
        assertEquals(InputState.Unknown, DepositInput.parse("I'm not sure", "en"))
        assertEquals(InputState.Unknown, DepositInput.parse("Je ne sais pas", "fr"))
    }

    @Test fun preparingOrDiscardingAnAnswerCannotChangeTheBriefing() = runBlocking {
        val controller = FinancialTwinController()
        controller.initialize()
        val before = assertNotNull(controller.briefing)
        assertTrue(controller.proposeDeposit(InputState.Exact(Chf.francs(1_500))))
        assertEquals(before, controller.briefing)
        controller.discardPending()
        assertFalse(controller.confirmDeposit())
        assertEquals(before, controller.briefing)
    }

    @Test fun confirmedContextualDepositRecomputesVerifiedInsight() = runBlocking {
        val saved = mutableListOf<String>()
        val controller = FinancialTwinController { saved += it }
        controller.initialize()
        assertNotNull(controller.briefing?.followUp)
        assertTrue(controller.proposeDeposit(InputState.Exact(Chf.francs(1_500))))
        assertTrue(saved.isEmpty())
        assertTrue(controller.confirmDeposit())
        val briefing = assertNotNull(controller.briefing)
        assertEquals(InsightType.ROBUST_CONCLUSION, briefing.primary.type)
        assertEquals(Chf.francs(500), briefing.evidence.worstStressTest.worstMinimumCash)
        assertNull(briefing.followUp)
        assertEquals(listOf("exact:150000"), saved)
        assertTrue(BriefingPresenter.insight(briefing, false).contains(Chf.francs(500).format()))
        assertTrue(BriefingPresenter.qualifications(briefing, false).contains("Only the cases tested are covered."))
    }

    @Test fun uncertaintyIsPreservedAndConfirmedAnswersCanBeRestored() = runBlocking {
        val controller = FinancialTwinController()
        controller.initialize()
        controller.proposeDeposit(InputState.Unknown)
        assertTrue(controller.confirmDeposit())
        val briefing = assertNotNull(controller.briefing)
        assertNull(briefing.followUp)
        assertTrue(Keys.DISCLOSURE_UNKNOWN_BOUNDED in briefing.disclosureKeys)
        val restored = FinancialTwinController()
        restored.initialize("exact:150000")
        assertEquals(InsightType.ROBUST_CONCLUSION, restored.briefing?.primary?.type)
        assertNull(DepositInput.restore("arbitrary speech 1500"))
    }
}
