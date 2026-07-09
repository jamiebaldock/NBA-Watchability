package com.nbawatchability.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.GameStatus
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.ui.theme.LiveRed
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassicAccent
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val localTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private const val TABULAR_NUMS = "tnum"

@Composable
fun GameCard(
    game: Game,
    showNumericScore: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (game.tier == Tier.INSTANT_CLASSIC) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(TierInstantClassicAccent)
                )
            }

            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                if (game.isSummerLeague) {
                    Text(
                        text = "NBA SUMMER LEAGUE",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = matchupText(game.away, game.home),
                        inlineContent = mapOf(
                            AWAY_LOGO_ID to teamLogoInlineContent(game.awayLogo),
                            HOME_LOGO_ID to teamLogoInlineContent(game.homeLogo)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    Column(horizontalAlignment = Alignment.End) {
                        StatusIndicator(game)
                        val tier = game.tier
                        if (tier != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            TierBadge(tier = tier, numericScore = if (showNumericScore) game.score else null)
                        }
                    }
                }

                Text(
                    text = game.hook,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )

                if (game.hasBreakdown) {
                    FullBreakdownSection(game = game, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

private const val AWAY_LOGO_ID = "awayLogo"
private const val HOME_LOGO_ID = "homeLogo"
private val LOGO_SIZE = 20.sp

private fun matchupText(away: String, home: String): AnnotatedString = buildAnnotatedString {
    appendInlineContent(AWAY_LOGO_ID, "[logo]")
    append(" ")
    withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold)) { append(away) }
    withStyle(SpanStyle(color = TextSecondary, fontWeight = FontWeight.Normal)) { append(" at ") }
    appendInlineContent(HOME_LOGO_ID, "[logo]")
    append(" ")
    withStyle(SpanStyle(color = TextPrimary, fontWeight = FontWeight.Bold)) { append(home) }
}

private fun teamLogoInlineContent(logoUrl: String?): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(width = LOGO_SIZE, height = LOGO_SIZE, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

@Composable
private fun StatusIndicator(game: Game) {
    when (game.status) {
        GameStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = LiveRed)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "LIVE", color = LiveRed, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${periodLabel(game.quarter)} ${game.clock.orEmpty()}".trim(),
                color = TextSecondary,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_NUMS)
            )
        }
        GameStatus.UPCOMING -> Text(
            text = localTipoff(game.tipoffUtc),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_NUMS)
        )
        GameStatus.FINAL -> Text(
            text = "FINAL",
            color = TextMuted,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "livePulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "liveDotAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

private fun periodLabel(quarter: Int?): String = when {
    quarter == null -> ""
    quarter <= 4 -> "Q$quarter"
    else -> "OT${quarter - 4}"
}

// ESPN omits the seconds field when it's :00 (e.g. "19:30Z" not "19:30:00Z"),
// which Instant.parse's strict ISO_INSTANT formatter rejects; OffsetDateTime's
// default ISO_OFFSET_DATE_TIME format treats seconds as optional.
private fun localTipoff(utc: String): String =
    OffsetDateTime.parse(utc).atZoneSameInstant(ZoneId.systemDefault()).format(localTimeFormatter)
