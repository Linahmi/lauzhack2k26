package ch.ripple.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.ripple.core.SampleData
import ch.ripple.core.compare

/** Milestone 1 screen: every number shown comes from ch.ripple.core.compare(). */
@Composable
fun App() {
    val profile = SampleData.alexia
    val result = remember { compare(profile, SampleData.startWorking, SampleData.mastersDegree) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Ripple", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Fictional profile · illustrative assumptions · not a prediction",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Line("Profile", "${profile.name} (fictional)")
                Line("Starting cash", profile.openingCash.format())
                Line("Horizon", "${profile.horizonMonths} months")
                HorizontalDivider()
                Line("Baseline: ${result.baseline.scenarioName}", result.baseline.closingCash.format())
                Line("Alternative: ${result.alternative.scenarioName}", result.alternative.closingCash.format())
                Line("Difference (alt − base)", result.closingCashDifference.format())
                HorizontalDivider()
                Line("Alternative lowest cash", result.alternative.minimumCash.format())
                Line(
                    "Alternative first shortfall",
                    result.alternative.firstNegativeMonth?.let { "Month ${it + 1}" } ?: "None",
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
