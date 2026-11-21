package ch.ripple.app.results

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.ripple.app.components.*
import ch.ripple.core.SampleData
import ch.ripple.core.compare

/** All financial results come from the existing core comparison, unchanged. */
@Composable
fun ComparisonScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onAssistant: () -> Unit) {
    val profile = SampleData.alexia
    val result = remember { compare(profile, SampleData.startWorking, SampleData.mastersDegree) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            BackButton(onBack)
            RippleWordmark()
            Text("Two paths, side by side", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text("An illustrative example for Alexia. Explore what each decision means for cash over time.")
            DemoDisclosure()
            HorizontalDivider()
            ResultLine("Profile", "${profile.name} (fictional)")
            ResultLine("Starting cash", profile.openingCash.format())
            ResultLine("Horizon", "${profile.horizonMonths} months")
            HorizontalDivider()
            BoxWithConstraints {
                val baseline: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Baseline", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(result.baseline.scenarioName, style = MaterialTheme.typography.headlineMedium)
                        Text(result.baseline.closingCash.format(), style = MaterialTheme.typography.titleLarge)
                        Text("Cash at the end of the horizon", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val alternative: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Alternative", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Text(result.alternative.scenarioName, style = MaterialTheme.typography.headlineMedium)
                        Text(result.alternative.closingCash.format(), style = MaterialTheme.typography.titleLarge)
                        Text("Cash at the end of the horizon", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (maxWidth >= 650.dp) Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Box(Modifier.weight(1f)) { baseline() }
                    Box(Modifier.weight(1f)) { alternative() }
                } else Column(verticalArrangement = Arrangement.spacedBy(32.dp)) { baseline(); alternative() }
            }
            HorizontalDivider()
            ResultLine("Difference (alternative − baseline)", result.closingCashDifference.format())
            ResultLine("Alternative lowest cash", result.alternative.minimumCash.format())
            ResultLine("Alternative first shortfall", result.alternative.firstNegativeMonth?.let { "Month ${it + 1}" } ?: "None")
            Text("A negative balance is an unfunded shortfall. It does not assume a loan.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("What this example assumes", style = MaterialTheme.typography.titleLarge)
            Text("Amounts are CHF. Income is treated as net monthly income. This model tracks cash only, not net worth. It excludes taxes, investments, debt, inflation and benefits.")
            listOf(SampleData.startWorking, SampleData.mastersDegree).forEach { scenario ->
                Text(scenario.name, style = MaterialTheme.typography.titleMedium)
                scenario.phases.forEach { phase ->
                    Text("Months ${phase.startMonth + 1}–${phase.endMonth}: net monthly income ${phase.monthlyIncome.format()}; monthly living expenses ${phase.monthlyExpenses.format()}.", style = MaterialTheme.typography.bodySmall)
                }
                scenario.oneOffCosts.forEach { cost ->
                    Text("Month ${cost.month + 1}: ${cost.label}, ${cost.amount.format()}.", style = MaterialTheme.typography.bodySmall)
                }
            }
            PrimaryButton("Try the voice & text companion", onAssistant, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
