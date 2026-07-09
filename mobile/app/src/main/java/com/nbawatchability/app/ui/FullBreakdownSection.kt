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
 */
@Composable
fun FullBreakdownSection(game: Game, modifier: Modifier = Modifier) {
    var revealed by remember(game.id) { mutableStateOf(false) }

    if (!revealed) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clickable { revealed = true }
        ) {
            if (canBlur) {
                Text(
                    text = breakdownAnnotatedText(game),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.blur(14.dp)
                )
            } else {
                RedactedBars(seed = game.id.hashCode(), lines = 2)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Full breakdown (heavy spoilers — how it played out, never the winner)",
                style = MaterialTheme.typography.bodySmall,
                color = TierInstantClassic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Hide breakdown",
            style = MaterialTheme.typography.bodySmall,
            color = TierInstantClassic,
            modifier = Modifier.clickable { revealed = false }
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = TextMuted.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = breakdownAnnotatedText(game),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

private fun marginDescriptor(margin: Int?): String? = when {
    margin == null -> null
    margin <= 3 -> "One-possession finish"
    margin <= 6 -> "Two-possession finish"
    margin <= 9 -> "Three-possession finish"
    else -> null
}

private fun breakdownFacts(game: Game): List<String> = buildList {
    marginDescriptor(game.margin)?.let { add(it) }
    if (game.buzzerBeater) add("Buzzer-beater finish")
    if (game.decidedOnFinalPossession) add("Down to the final possession")
    if (game.leadChangeInFinalMin) add("Lead changed hands late")
    if (game.closeInFinalTwoMin) add("Neck-and-neck in the final 2 minutes")
    game.comeback?.takeIf { it >= 10 }?.let { add("A $it-point comeback") }
    game.leadChanges?.takeIf { it >= 10 }?.let { add("$it lead changes") }
    if (game.overtimePeriods > 0) add(if (game.overtimePeriods == 1) "Overtime" else "${game.overtimePeriods} overtimes")
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

private fun breakdownAnnotatedText(game: Game) = buildAnnotatedString {
    val facts = breakdownFacts(game)
    append(if (facts.isEmpty()) "No standout moments logged" else facts.joinToString(" · "))
    append(" · Watchability ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) {
        append("${game.score ?: 0}/100")
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
