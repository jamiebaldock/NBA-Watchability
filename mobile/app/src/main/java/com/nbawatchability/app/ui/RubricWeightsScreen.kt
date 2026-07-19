package com.nbawatchability.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary

/**
 * Reached from Settings, same pattern as About - frees the main Settings
 * screen from these sliders' worth of scroll. Single-sport for now
 * (basketball only - soccer support and its weight set were removed, see
 * archive/soccer/, and MLB weight customization hasn't been built yet) -
 * this used to switch between a basketball and soccer slider set via a top
 * chip pair (RubricSport enum); that switcher may need to come back once a
 * second sport has its own customizable weights again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RubricWeightsScreen(
    weights: RubricWeights,
    onWeightChange: (RubricCategory, Float) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundBase,
        topBar = {
            TopAppBar(
                title = { Text("Watchability rating weights", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                text = "Scale how much each factor contributes to a game's score. 1.00x is the default.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
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
