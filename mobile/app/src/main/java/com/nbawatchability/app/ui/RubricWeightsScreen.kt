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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.RubricCategory
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.SoccerRubricCategory
import com.nbawatchability.app.data.SoccerRubricWeights
import com.nbawatchability.app.ui.theme.BackgroundBase
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierWorthYourTime

private enum class RubricSport { BASKETBALL, SOCCER }

/**
 * Reached from Settings, same pattern as About - frees the main Settings
 * screen from these sliders' worth of scroll. Basketball and soccer each
 * get their own independent weight set (their rubric dimensions don't
 * overlap - see RubricWeights vs SoccerRubricWeights), switched via a
 * top chip pair rather than two separate screens, since only one sport's
 * sliders are ever relevant to look at together. Stakes is the one
 * dimension both sports share conceptually, so it's shown once, below
 * whichever sport-specific set is currently selected, rather than
 * duplicated per sport.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RubricWeightsScreen(
    weights: RubricWeights,
    onWeightChange: (RubricCategory, Float) -> Unit,
    onReset: () -> Unit,
    soccerWeights: SoccerRubricWeights,
    onSoccerWeightChange: (SoccerRubricCategory, Float) -> Unit,
    onSoccerReset: () -> Unit,
    onBack: () -> Unit
) {
    var sport by rememberSaveable { mutableStateOf(RubricSport.BASKETBALL) }

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
                FilterChip(
                    selected = sport == RubricSport.BASKETBALL,
                    onClick = { sport = RubricSport.BASKETBALL },
                    label = { Text("Basketball") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TierWorthYourTime,
                        selectedLabelColor = BackgroundBase
                    )
                )
                FilterChip(
                    selected = sport == RubricSport.SOCCER,
                    onClick = { sport = RubricSport.SOCCER },
                    label = { Text("Soccer") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TierWorthYourTime,
                        selectedLabelColor = BackgroundBase
                    )
                )
            }

            Text(
                text = "Scale how much each factor contributes to a game's score. 1.00x is the default.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )

            if (sport == RubricSport.BASKETBALL) {
                WeightSlider("Margin", weights.margin) { onWeightChange(RubricCategory.MARGIN, it) }
                WeightSlider("Clutch finish", weights.clutch) { onWeightChange(RubricCategory.CLUTCH, it) }
                WeightSlider("Buzzer-beater", weights.buzzerBeater) { onWeightChange(RubricCategory.BUZZER_BEATER, it) }
                WeightSlider("Comeback", weights.comeback) { onWeightChange(RubricCategory.COMEBACK, it) }
                WeightSlider("Lead changes", weights.leadChanges) { onWeightChange(RubricCategory.LEAD_CHANGES, it) }
                WeightSlider("Overtime", weights.overtime) { onWeightChange(RubricCategory.OVERTIME, it) }
                WeightSlider("Star performance", weights.starPerformance) { onWeightChange(RubricCategory.STAR_PERFORMANCE, it) }
            } else {
                WeightSlider("Margin", soccerWeights.margin) { onSoccerWeightChange(SoccerRubricCategory.MARGIN, it) }
                WeightSlider("Total goals", soccerWeights.totalGoals) { onSoccerWeightChange(SoccerRubricCategory.TOTAL_GOALS, it) }
                WeightSlider("Comeback", soccerWeights.comeback) { onSoccerWeightChange(SoccerRubricCategory.COMEBACK, it) }
                WeightSlider("Late drama", soccerWeights.lateDrama) { onSoccerWeightChange(SoccerRubricCategory.LATE_DRAMA, it) }
                WeightSlider("Star performance", soccerWeights.star) { onSoccerWeightChange(SoccerRubricCategory.STAR, it) }
                WeightSlider("Chances created", soccerWeights.chances) { onSoccerWeightChange(SoccerRubricCategory.CHANCES, it) }
                WeightSlider("Red card", soccerWeights.redCard) { onSoccerWeightChange(SoccerRubricCategory.RED_CARD, it) }
                WeightSlider("Goalkeeper saves", soccerWeights.saves) { onSoccerWeightChange(SoccerRubricCategory.SAVES, it) }
                WeightSlider("Free-kick goal", soccerWeights.freeKickGoal) { onSoccerWeightChange(SoccerRubricCategory.FREE_KICK_GOAL, it) }
                WeightSlider("Penalty miss", soccerWeights.penaltyMiss) { onSoccerWeightChange(SoccerRubricCategory.PENALTY_MISS, it) }
                WeightSlider("Extra time", soccerWeights.extraTime) { onSoccerWeightChange(SoccerRubricCategory.EXTRA_TIME, it) }
                WeightSlider("Penalty shootout", soccerWeights.shootout) { onSoccerWeightChange(SoccerRubricCategory.SHOOTOUT, it) }
            }

            // Shared across both sports (RubricWeights.stakes, not
            // duplicated per sport) - shown once regardless of which sport
            // chip is selected.
            WeightSlider("Stakes", weights.stakes) { onWeightChange(RubricCategory.STAKES, it) }

            OutlinedButton(
                onClick = { if (sport == RubricSport.BASKETBALL) onReset() else onSoccerReset() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            ) {
                Text(if (sport == RubricSport.BASKETBALL) "Reset basketball to default" else "Reset soccer to default")
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
