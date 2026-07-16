package com.nbawatchability.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierSkippable
import com.nbawatchability.app.ui.theme.TierSolid
import com.nbawatchability.app.ui.theme.TierWorthYourTime

@Composable
fun Tier.color(): Color = when (this) {
    Tier.INSTANT_CLASSIC -> TierInstantClassic
    Tier.WORTH_YOUR_TIME -> TierWorthYourTime
    Tier.SOLID -> TierSolid
    Tier.SKIPPABLE -> TierSkippable
}

/**
 * Outlined pill per the reference prototype: tier-colored border and text on
 * a transparent fill, monospace with wide tracking. numericScore is only
 * ever passed when the user's numeric toggle is on.
 */
@Composable
fun TierBadge(tier: Tier, numericScore: Int? = null, modifier: Modifier = Modifier) {
    val text = if (numericScore != null) {
        "${tier.emoji} ${tier.label} · $numericScore"
    } else {
        "${tier.emoji} ${tier.label}"
    }
    Text(
        text = text,
        color = tier.color(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp,
            fontFeatureSettings = "tnum"
        ),
        modifier = modifier
            .border(BorderStroke(1.dp, tier.color()), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
