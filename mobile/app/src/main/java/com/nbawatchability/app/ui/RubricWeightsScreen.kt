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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.MlbRubricCategory
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

private enum class RubricSport(val label: String) {
    NBA("NBA"),
    WNBA("WNBA"),
    MLB("MLB"),
    NFL("NFL"),
    NHL("NHL")
}

/**
 * Reached from Settings, same pattern as About - frees the main Settings
 * screen from these sliders' worth of scroll. Sport switcher mirrors the
 * pattern soccer used to have here (see archive/soccer/mobile/
 * RubricWeightsScreen-soccer-tab-extracted.kt.txt) before soccer support was
 * removed - now driving Basketball (real, unchanged), MLB (real, its own
 * calibrated rubric from backend/src/mlbRubric.ts), and NFL/NHL (inert
 * "coming soon" placeholders - those leagues only have team names browsable
 * today, no scoring rubric to customize yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RubricWeightsScreen(
    nbaWeights: RubricWeights,
    onNbaWeightChange: (RubricCategory, Float) -> Unit,
    onNbaReset: () -> Unit,
    wnbaWeights: RubricWeights,
    onWnbaWeightChange: (RubricCategory, Float) -> Unit,
    onWnbaReset: () -> Unit,
    mlbWeights: MlbRubricWeights,
    onMlbWeightChange: (MlbRubricCategory, Float) -> Unit,
    onMlbReset: () -> Unit,
    onBack: () -> Unit
) {
    var sport by remember { mutableStateOf(RubricSport.NBA) }

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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RubricSport.entries.forEach { entry ->
                    FilterChip(
                        selected = sport == entry,
                        onClick = { sport = entry },
                        label = { Text(entry.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TierWorthYourTime,
                            selectedLabelColor = BackgroundBase
                        )
                    )
                }
            }

            Text(
                text = "Scale how much each factor contributes to a game's score. 1.00x is the default.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )

            when (sport) {
                RubricSport.NBA -> {
                    WeightSlider("Margin", nbaWeights.margin) { onNbaWeightChange(RubricCategory.MARGIN, it) }
                    WeightSlider("Clutch finish", nbaWeights.clutch) { onNbaWeightChange(RubricCategory.CLUTCH, it) }
                    WeightSlider("Buzzer-beater", nbaWeights.buzzerBeater) { onNbaWeightChange(RubricCategory.BUZZER_BEATER, it) }
                    WeightSlider("Comeback", nbaWeights.comeback) { onNbaWeightChange(RubricCategory.COMEBACK, it) }
                    WeightSlider("Lead changes", nbaWeights.leadChanges) { onNbaWeightChange(RubricCategory.LEAD_CHANGES, it) }
                    WeightSlider("Overtime", nbaWeights.overtime) { onNbaWeightChange(RubricCategory.OVERTIME, it) }
                    WeightSlider("Star performance", nbaWeights.starPerformance) { onNbaWeightChange(RubricCategory.STAR_PERFORMANCE, it) }
                    WeightSlider("Stakes", nbaWeights.stakes) { onNbaWeightChange(RubricCategory.STAKES, it) }

                    OutlinedButton(
                        onClick = onNbaReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Text("Reset to default")
                    }
                }

                RubricSport.WNBA -> {
                    WeightSlider("Margin", wnbaWeights.margin) { onWnbaWeightChange(RubricCategory.MARGIN, it) }
                    WeightSlider("Clutch finish", wnbaWeights.clutch) { onWnbaWeightChange(RubricCategory.CLUTCH, it) }
                    WeightSlider("Buzzer-beater", wnbaWeights.buzzerBeater) { onWnbaWeightChange(RubricCategory.BUZZER_BEATER, it) }
                    WeightSlider("Comeback", wnbaWeights.comeback) { onWnbaWeightChange(RubricCategory.COMEBACK, it) }
                    WeightSlider("Lead changes", wnbaWeights.leadChanges) { onWnbaWeightChange(RubricCategory.LEAD_CHANGES, it) }
                    WeightSlider("Overtime", wnbaWeights.overtime) { onWnbaWeightChange(RubricCategory.OVERTIME, it) }
                    WeightSlider("Star performance", wnbaWeights.starPerformance) { onWnbaWeightChange(RubricCategory.STAR_PERFORMANCE, it) }
                    WeightSlider("Stakes", wnbaWeights.stakes) { onWnbaWeightChange(RubricCategory.STAKES, it) }

                    OutlinedButton(
                        onClick = onWnbaReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Text("Reset to default")
                    }
                }

                RubricSport.MLB -> {
                    WeightSlider("Margin", mlbWeights.margin) { onMlbWeightChange(MlbRubricCategory.MARGIN, it) }
                    WeightSlider("Walk-off", mlbWeights.walkOff) { onMlbWeightChange(MlbRubricCategory.WALK_OFF, it) }
                    WeightSlider("Comeback", mlbWeights.comeback) { onMlbWeightChange(MlbRubricCategory.COMEBACK, it) }
                    WeightSlider("Extra innings", mlbWeights.extraInnings) { onMlbWeightChange(MlbRubricCategory.EXTRA_INNINGS, it) }
                    WeightSlider("Total runs", mlbWeights.totalRuns) { onMlbWeightChange(MlbRubricCategory.TOTAL_RUNS, it) }
                    WeightSlider("Combined home runs", mlbWeights.combinedHomeRuns) { onMlbWeightChange(MlbRubricCategory.COMBINED_HOME_RUNS, it) }
                    WeightSlider("Star home run", mlbWeights.starHomeRun) { onMlbWeightChange(MlbRubricCategory.STAR_HOME_RUN, it) }
                    WeightSlider("Pitching dominance", mlbWeights.pitchingDominance) { onMlbWeightChange(MlbRubricCategory.PITCHING_DOMINANCE, it) }
                    WeightSlider("Blown save", mlbWeights.blownSave) { onMlbWeightChange(MlbRubricCategory.BLOWN_SAVE, it) }
                    WeightSlider("Errors", mlbWeights.errors) { onMlbWeightChange(MlbRubricCategory.ERRORS, it) }
                    WeightSlider("Stakes", mlbWeights.stakes) { onMlbWeightChange(MlbRubricCategory.STAKES, it) }

                    OutlinedButton(
                        onClick = onMlbReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Text("Reset to default")
                    }
                }

                RubricSport.NFL, RubricSport.NHL -> {
                    ComingSoonPlaceholder(sport.label)
                }
            }
        }
    }
}

@Composable
private fun ComingSoonPlaceholder(sportLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$sportLabel weight customization is coming soon.",
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.titleMedium
        )
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
