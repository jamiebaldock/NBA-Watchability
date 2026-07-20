package com.nbawatchability.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.NhlRubricWeights
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic

/**
 * Modifier.blur() needs RenderEffect (API 31+) to actually blur anything —
 * below that it silently no-ops, which would leak the real breakdown text in
 * plain, readable form. Only render real text-to-be-blurred when the OS can
 * actually blur it; otherwise fall back to abstract redacted bars that carry
 * no real content regardless of rendering support.
 */
private val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * "How it played out" reveal (spec section 2, point 8): comeback size, OT,
 * buzzer-beater, lead changes, clutch flags, and the watchability score
 * itself — deliberately never the winner or the actual game score. Blurred
 * (via redacted placeholder bars) by default, with its own reveal tap
 * separate from the always-visible hook above it, matching the reference
 * prototype.
 *
 * [spoilerFree] (History tab only) starts this revealed instead of blurred -
 * an old game the user is intentionally browsing to pick one to watch has
 * nothing left to spoil. Still collapsible via the same toggle, just not
 * hidden by default.
 */
@Composable
fun FullBreakdownSection(
    game: Game,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    modifier: Modifier = Modifier,
    spoilerFree: Boolean = false
) {
    var revealed by remember(game.id) { mutableStateOf(spoilerFree) }

    // The toggle text stays anchored at the bottom in both states - only its
    // label swaps - rather than moving from bottom (collapsed) to top
    // (revealed), which read as the control jumping to a different spot.
    Column(modifier = modifier.fillMaxWidth()) {
        if (revealed) {
            HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = breakdownAnnotatedText(game, nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            if (canBlur) {
                Text(
                    text = breakdownAnnotatedText(game, nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.blur(7.dp)
                )
            } else {
                RedactedBars(seed = game.id.hashCode(), lines = 2)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = if (revealed) "Hide breakdown" else "Game breakdown - no spoilers, just how it played all out.",
            style = MaterialTheme.typography.bodySmall,
            color = TierInstantClassic,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.4f,
            modifier = Modifier.fillMaxWidth().clickable { revealed = !revealed }
        )
    }
}

// Covers the full margin range (matches rubric.ts's marginPoints buckets) so
// a lopsided game still gets a descriptor instead of falling through to "No
// standout moments logged" just because it wasn't close.
private fun marginDescriptor(margin: Int?): String? = when {
    margin == null -> null
    margin <= 3 -> "One-possession finish"
    margin <= 6 -> "Two-possession finish"
    margin <= 9 -> "Three-possession finish"
    margin <= 15 -> "Comfortable double-digit win"
    margin <= 20 -> "Lopsided margin"
    else -> "Blowout margin"
}

private fun overtimeWord(periods: Int): String = when {
    periods <= 0 -> ""
    periods == 1 -> "Overtime"
    periods == 2 -> "Double overtime"
    periods == 3 -> "Triple overtime"
    else -> "$periods overtimes"
}

// Buzzer-beater, decided-on-final-possession, and the margin bucket are all
// ways of saying "how tight was the finish" - listing all three that apply
// (e.g. "One-possession finish · Buzzer-beater finish · Down to the final
// possession") just repeats the same point three times. Collapse them into
// one phrase, picking the most specific/dramatic fact that's true (buzzer-
// beater first, then final-possession, falling back to the plain margin
// descriptor), and fold the overtime count into that same phrase rather than
// listing overtime as its own disconnected bullet ("Double overtime
// buzzer-beater finish" instead of "Overtime" ... "Buzzer-beater finish").
private fun finishDescriptor(game: Game): String? {
    val ot = overtimeWord(game.overtimePeriods)
    return when {
        game.buzzerBeater -> if (ot.isEmpty()) "Buzzer-beater finish" else "$ot buzzer-beater finish"
        game.decidedOnFinalPossession -> if (ot.isEmpty()) "Down to the final possession" else "$ot, down to the final possession"
        ot.isNotEmpty() -> ot
        else -> marginDescriptor(game.margin)
    }
}

// Ordered to read like a natural recap - the shape of the game (lead changes,
// comeback), then how tense it got late, then how it actually finished, then
// any standout individual performance, with the score appended last by the
// caller.
private fun breakdownFacts(game: Game): List<String> = when (game.league) {
    "mlb" -> mlbBreakdownFacts(game)
    "nfl" -> nflBreakdownFacts(game)
    "nhl" -> nhlBreakdownFacts(game)
    else -> nbaBreakdownFacts(game)
}

private fun nbaBreakdownFacts(game: Game): List<String> = buildList {
    game.leadChanges?.takeIf { it >= 10 }?.let { add("$it lead changes") }
    game.comeback?.takeIf { it >= 10 }?.let { add("A $it-point comeback") }
    if (game.leadChangeInFinalMin) add("Lead changed hands late")
    if (game.closeInFinalTwoMin) add("Neck-and-neck in the final 2 minutes")
    finishDescriptor(game)?.let { add(it) }
    game.starPerformance?.let {
        add(
            when (it) {
                "historic" -> "A historic individual performance"
                "great" -> "A big individual stat night"
                else -> "A strong individual performance"
            }
        )
    }
}

// Baseball's own recap facts (mlbRubric.ts's dimensions) - a direct MLB
// analogue of nbaBreakdownFacts above, reading real per-dimension facts off
// Game.mlbInputs instead of basketball's top-level fields. Falls back to just
// the margin descriptor if an older cached game predates mlbInputs.
private fun mlbBreakdownFacts(game: Game): List<String> {
    val inputs = game.mlbInputs ?: return listOfNotNull(mlbMarginDescriptor(game.margin))
    return buildList {
        if (inputs.walkOff) add("Walk-off finish")
        if (inputs.extraInningsCount >= 1) {
            add(if (inputs.extraInningsCount == 1) "Extra innings" else "${inputs.extraInningsCount} extra innings")
        }
        when {
            inputs.perfectGame -> add("A perfect game")
            inputs.noHitter -> add("A no-hitter")
            inputs.teamBlanked -> add("One team was held scoreless")
        }
        if (inputs.maxHomeRunsByPlayer >= 2) add("A ${inputs.maxHomeRunsByPlayer}-homer game for one player")
        if (inputs.combinedHomeRuns >= 5) add("${inputs.combinedHomeRuns} combined home runs")
        if (inputs.blownSave) add("A blown save shook things up")
        if (inputs.totalRuns >= 15) add("A ${inputs.totalRuns}-run slugfest")
        if (inputs.combinedErrors >= 3) add("${inputs.combinedErrors} combined errors")
        if (!inputs.walkOff && inputs.extraInningsCount == 0) mlbMarginDescriptor(inputs.finalMargin)?.let { add(it) }
    }
}

// Baseball-appropriate margin language (mlbRubric.ts's marginPoints brackets)
// - basketball's "One-possession finish" wording doesn't map onto runs.
private fun mlbMarginDescriptor(margin: Int?): String? = when {
    margin == null -> null
    margin == 1 -> "One-run nailbiter"
    margin == 2 -> "Two-run finish"
    margin <= 5 -> "Comfortable margin"
    margin <= 7 -> "Lopsided margin"
    else -> "Blowout margin"
}

// Football's own recap facts (nflRubric.ts's dimensions) - a direct NFL
// analogue of mlbBreakdownFacts above, reading real per-dimension facts off
// Game.nflInputs. Falls back to just the margin descriptor if an older
// cached game predates nflInputs.
private fun nflBreakdownFacts(game: Game): List<String> {
    val inputs = game.nflInputs ?: return listOfNotNull(nflMarginDescriptor(game.margin))
    return buildList {
        if (inputs.decisiveScoreLate) add("Decisive score came late")
        if (inputs.overtimePeriods >= 1) add("Overtime")
        if (inputs.defensiveOrSpecialTeamsTd) add("A defensive or special-teams touchdown")
        if (inputs.maxTotalTdsByPlayer >= 4) add("${inputs.maxTotalTdsByPlayer} total TDs for one player")
        if (inputs.maxPassingYards >= 300) add("${inputs.maxPassingYards} passing yards")
        if (inputs.maxRushingYards >= 125) add("${inputs.maxRushingYards} rushing yards")
        if (inputs.combinedTurnovers >= 3) add("${inputs.combinedTurnovers} combined turnovers")
        if (inputs.totalPoints >= 56) add("A ${inputs.totalPoints}-point shootout")
        if (!inputs.decisiveScoreLate && inputs.overtimePeriods == 0) nflMarginDescriptor(inputs.finalMargin)?.let { add(it) }
    }
}

// Football-appropriate margin language (nflRubric.ts's marginPoints brackets,
// the "one-score game" convention at 8 points) - basketball's "One-
// possession finish" wording doesn't map onto football scoring.
private fun nflMarginDescriptor(margin: Int?): String? = when {
    margin == null -> null
    margin <= 3 -> "One-score nailbiter"
    margin <= 8 -> "One-score game"
    margin <= 16 -> "Two-score game"
    margin <= 24 -> "Comfortable margin"
    else -> "Blowout margin"
}

// Hockey's own recap facts (nhlRubric.ts's dimensions) - a direct NHL
// analogue of mlbBreakdownFacts/nflBreakdownFacts above, reading real
// per-dimension facts off Game.nhlInputs. Falls back to just the margin
// descriptor if an older cached game predates nhlInputs. A 2-goal game is
// still called out here as flavor text even though it earns no rubric
// points (real data showed it's too common - 50.1% of games - to signal a
// standout performance in the scoring itself).
private fun nhlBreakdownFacts(game: Game): List<String> {
    val inputs = game.nhlInputs ?: return listOfNotNull(nhlMarginDescriptor(game.margin))
    return buildList {
        if (inputs.teamShutout) add("One team was shut out")
        if (inputs.overtimePeriods >= 1) add(if (inputs.wentToShootout) "Decided in a shootout" else "Overtime winner")
        if (inputs.decisiveScoreLate) add("Go-ahead goal came late")
        if (inputs.maxGoalsByPlayer >= 3) add("A hat trick")
        else if (inputs.maxGoalsByPlayer >= 2) add("A multi-goal game for one player")
        if (inputs.maxGoalieSaves >= 35) add("${inputs.maxGoalieSaves} saves by one goaltender")
        if (inputs.combinedPowerPlayGoals >= 2) add("${inputs.combinedPowerPlayGoals} power-play goals")
        if (inputs.totalGoals >= 7) add("A ${inputs.totalGoals}-goal shootout-style game")
        if (!inputs.decisiveScoreLate && inputs.overtimePeriods == 0) nhlMarginDescriptor(inputs.finalMargin)?.let { add(it) }
    }
}

// Hockey-appropriate margin language (nhlRubric.ts's marginPoints brackets) -
// basketball's "One-possession finish" wording doesn't map onto goal-based
// scoring, where a 1-goal margin is the single most common outcome.
private fun nhlMarginDescriptor(margin: Int?): String? = when {
    margin == null -> null
    margin <= 1 -> "One-goal nailbiter"
    margin == 2 -> "Two-goal finish"
    margin <= 3 -> "Comfortable margin"
    margin <= 4 -> "Lopsided margin"
    else -> "Blowout margin"
}

private fun breakdownAnnotatedText(
    game: Game,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights
) = buildAnnotatedString {
    val facts = breakdownFacts(game)
    append(if (facts.isEmpty()) "No standout moments logged" else facts.joinToString(" · "))
    append(" · Watchability ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) {
        append("${game.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) ?: 0}/100")
    }
}

@Composable
private fun RedactedBars(seed: Int, lines: Int) {
    val random = remember(seed) { kotlin.random.Random(seed) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) {
            val barWidths = remember(seed, it) { List(4) { 30 + random.nextInt(70) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                barWidths.forEach { w ->
                    Box(
                        modifier = Modifier
                            .height(11.dp)
                            .width(w.dp)
                            .background(TextMuted.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}
