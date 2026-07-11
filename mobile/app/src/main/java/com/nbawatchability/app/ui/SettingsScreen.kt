package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

/**
 * Per-category watchability weight tuning (spec: settings gear, top app bar).
 * Purely local - these multipliers only affect what this device computes and
 * displays; they never touch the shared backend cache or any other user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    weights: RubricWeights,
    onWeightChange: (RubricCategory, Float) -> Unit,
    onReset: () -> Unit,
    showWnba: Boolean,
    onShowWnbaChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "League",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = 16.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Show WNBA", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Adds an NBA/WNBA switcher to the Games tab title.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = showWnba, onCheckedChange = onShowWnbaChange)
            }

            HorizontalDivider(modifier = Modifier.padding(top = 20.dp), color = TextMuted.copy(alpha = 0.3f))

            Text(
                text = "Watchability rating weights",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                text = "Scale how much each factor contributes to a game's score. 1.00x is the default.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            WeightSlider("Margin", weights.margin) { onWeightChange(RubricCategory.MARGIN, it) }
            WeightSlider("Clutch finish", weights.clutch) { onWeightChange(RubricCategory.CLUTCH, it) }
            WeightSlider("Buzzer-beater", weights.buzzerBeater) { onWeightChange(RubricCategory.BUZZER_BEATER, it) }
            WeightSlider("Comeback", weights.comeback) { onWeightChange(RubricCategory.COMEBACK, it) }
            WeightSlider("Lead changes", weights.leadChanges) { onWeightChange(RubricCategory.LEAD_CHANGES, it) }
            WeightSlider("Overtime", weights.overtime) { onWeightChange(RubricCategory.OVERTIME, it) }
            WeightSlider("Star performance", weights.starPerformance) { onWeightChange(RubricCategory.STAR_PERFORMANCE, it) }
            WeightSlider("Stakes", weights.stakes) { onWeightChange(RubricCategory.STAKES, it) }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            ) {
                Text("Reset to default")
            }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format("%.2fx", value),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..2f,
            modifier = Modifier.height(32.dp)
        )
    }
}
