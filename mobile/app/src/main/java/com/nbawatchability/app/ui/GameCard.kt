package com.nbawatchability.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.GameStatus
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.RubricWeights
import com.nbawatchability.app.data.StandoutPerformer
import com.nbawatchability.app.data.Team
import com.nbawatchability.app.data.Tier
import com.nbawatchability.app.data.effectiveScore
import com.nbawatchability.app.data.effectiveTier
import com.nbawatchability.app.ui.theme.FavoriteAccent
import com.nbawatchability.app.ui.theme.LiveRed
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TextSecondary
import com.nbawatchability.app.ui.theme.TierInstantClassic
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import com.nbawatchability.app.ui.theme.themeAwareLogoUrl
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val localTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val localDateFormatter = DateTimeFormatter.ofPattern("MMMM d - yyyy")
private const val TABULAR_NUMS = "tnum"

@Composable
fun GameCard(
    game: Game,
    showNumericScore: Boolean,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    modifier: Modifier = Modifier,
    isStarred: Boolean = false,
    onToggleStar: () -> Unit = {},
    onWatchHighlights: (String) -> Unit = {},
    // Only meaningful on the Starred tab, which combines games from many
    // different dates in one list - elsewhere the day-tab context already
    // makes the date obvious, so this defaults off.
    showDate: Boolean = false,
    // History tab only: these are old, already-finished games the user is
    // intentionally browsing to pick one to watch, not a live/recent game
    // that could still be spoiled - so the breakdown starts revealed instead
    // of blurred behind its own reveal tap.
    spoilerFree: Boolean = false,
    // History tab only: a "browse blind" preference - hides just the two
    // teams' final numeric score digits (TeamRow below), not the
    // watchability rating (tier badge + breakdown), which stay visible
    // either way - the rating is the point of this tab, only the literal
    // score is optional to peek at.
    showScore: Boolean = true,
    // Global favorites (not per-league) - checked against by exact team name
    // match, same identity every favorites surface in the app uses.
    favoriteTeamNames: Set<String> = emptySet(),
    // Long-press quick-add/remove shortcut, wired through from wherever this
    // card is rendered - a no-op default so tiles that don't pass a
    // FavoritesViewModel (there are none left after this phase, but keeps
    // the parameter list backward-compatible) simply don't respond to a
    // long-press rather than crashing.
    onToggleFavoriteTeam: (Team) -> Unit = {},
    // Global favorites (not per-league), same identity match as
    // favoriteTeamNames - backs the standout-performance callout below.
    favoritePlayerNames: Set<String> = emptySet(),
    // Long-press quick-add/remove shortcut on a standout-performer name,
    // mirroring onToggleFavoriteTeam above - same no-op default reasoning.
    onToggleFavoritePlayer: (FavoritePlayer) -> Unit = {},
    // Opens the game-detail popup - a no-op default so a tap on an upcoming/
    // live tile (no rubric breakdown or real top-performer stats yet) does
    // nothing, same as today's behavior; only invoked when game.hasBreakdown
    // is true (gated below, not by the caller).
    onGameClick: (Game) -> Unit = {}
) {
    val tier = game.effectiveTier(nbaWeights, wnbaWeights, mlbWeights, nflWeights)

    Card(
        onClick = { if (game.hasBreakdown) onGameClick(game) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
        shape = RoundedCornerShape(14.dp),
        border = tier?.let { BorderStroke(1.5.dp, it.color()) }
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                // Summer League keeps its own static label; every other game's
                // label (preseason/regular season/playoffs/NBA Cup) comes
                // straight from the backend, which derives it from ESPN's own
                // season + tournament data rather than calendar-date guesses.
                val competitionLabel = if (game.isSummerLeague) "NBA SUMMER LEAGUE" else game.competitionLabel
                if (competitionLabel != null || showDate) {
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (competitionLabel != null) {
                            Text(text = competitionLabel, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (showDate) {
                            if (competitionLabel != null) {
                                Text(text = " - ", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(text = localDate(game.tipoffUtc), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Badge left-justified so status ("FINAL"/live clock/tipoff
                // time) and the star toggle can share this same row on the
                // right, instead of each getting its own dedicated row -
                // keeps the tile more compact. An empty zero-size spacer
                // stands in for the badge when there's no tier yet, so
                // SpaceBetween still pushes the status+star group to the
                // right edge.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tier != null) {
                        TierBadge(
                            tier = tier,
                            numericScore = if (showNumericScore) game.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights) else null
                        )
                    } else {
                        Spacer(modifier = Modifier.size(0.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(game)
                        Spacer(modifier = Modifier.width(10.dp))
                        StarButton(isStarred = isStarred, onClick = onToggleStar)
                    }
                }

                // leagueGroupOf(game) (not the screen's own selectedLeague
                // dropdown) - correct even in Starred's "all leagues" mode,
                // where a tile's actual league can differ from whatever the
                // dropdown happens to be sitting on. Hoisted above the team
                // rows below since the standout-performer callout further
                // down also needs it to tag a long-press favorite.
                val gameLeagueGroup = leagueGroupOf(game).apiValue

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    TeamRow(
                        logoUrl = game.awayLogo,
                        name = game.away,
                        score = game.awayScore.takeIf { showScore },
                        isFavorite = game.away in favoriteTeamNames,
                        onLongPress = { onToggleFavoriteTeam(Team(name = game.away, logo = game.awayLogo, leagueGroup = gameLeagueGroup)) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TeamRow(
                        logoUrl = game.homeLogo,
                        name = game.home,
                        score = game.homeScore.takeIf { showScore },
                        isFavorite = game.home in favoriteTeamNames,
                        onLongPress = { onToggleFavoriteTeam(Team(name = game.home, logo = game.homeLogo, leagueGroup = gameLeagueGroup)) }
                    )
                }

                // Independent of tier/score - standout lines surface here
                // even on an otherwise Skippable game. Only meaningful once
                // final (box-score stats aren't complete before then). Shows
                // every standout performer, not just already-favorited ones
                // (favorited names get the accent treatment) - long-pressing
                // any name here is a quick-add/remove shortcut, same idea as
                // long-pressing a team logo above, so a player worth
                // favoriting can be spotted and favorited right from a tile
                // instead of only via Settings search.
                if (game.status == GameStatus.FINAL) {
                    val standouts = game.standoutPerformers.orEmpty()
                    if (standouts.isNotEmpty()) {
                        StandoutPerformerCallout(
                            performers = standouts,
                            favoritePlayerNames = favoritePlayerNames,
                            onTogglePlayer = { performer ->
                                onToggleFavoritePlayer(
                                    FavoritePlayer(name = performer.name, team = performer.team ?: "", leagueGroup = gameLeagueGroup)
                                )
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                // Once the game is final, the pregame preview area is fully
                // replaced by the end-game breakdown - not shown alongside it.
                if (game.status != GameStatus.FINAL) {
                    PitchSection(game = game, modifier = Modifier.padding(top = 10.dp))
                }

                if (game.hasBreakdown) {
                    val breakdownTopPadding = if (game.status == GameStatus.FINAL) 10.dp else 12.dp
                    FullBreakdownSection(
                        game = game,
                        nbaWeights = nbaWeights,
                        wnbaWeights = wnbaWeights,
                        mlbWeights = mlbWeights,
                        nflWeights = nflWeights,
                        spoilerFree = spoilerFree,
                        modifier = Modifier.padding(top = breakdownTopPadding)
                    )
                }

                // Not spoiler-sensitive like the breakdown above - the video
                // itself already shows the result, so this is a plain,
                // always-visible affordance rather than something blurred.
                if (game.status == GameStatus.FINAL && game.youtubeVideoId != null) {
                    HighlightsRow(
                        onClick = { onWatchHighlights(game.youtubeVideoId) },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * Spoiler-free hook/pitch text, always visible — unlike the full breakdown,
 * there's nothing here to hide: it's written to never reveal the score,
 * winner, or how the game played out, so blurring it added friction without
 * protecting anything. Hook and pitch are two model outputs but read as one
 * continuous preview, so they're collapsed together as a single 2-line block.
 */
@Composable
private fun PitchSection(game: Game, modifier: Modifier = Modifier) {
    // Distinct from the one-line hook: a longer spoiler-free blurb. Treated as
    // absent when blank or identical to the hook (the no-LLM-key fallback
    // emits the same plain sentence for both).
    val pitch = game.pitch?.takeIf { it.isNotBlank() && it != game.hook }
    val previewText = if (pitch != null) "${game.hook} $pitch" else game.hook

    ExpandableText(
        text = previewText,
        color = TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Collapses to 2 lines with an ellipsis by default to keep upcoming-game
 * tiles a consistent height; tapping the text toggles the full pitch open.
 * Only becomes tappable once collapsing actually truncated something -
 * a pitch that already fits in 2 lines has nothing to reveal.
 */
@Composable
private fun ExpandableText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    var expanded by remember(text) { mutableStateOf(false) }
    var isTruncated by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        style = style,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!expanded && result.hasVisualOverflow) isTruncated = true
        },
        modifier = modifier.then(
            if (isTruncated || expanded) Modifier.clickable { expanded = !expanded } else Modifier
        )
    )
}

@Composable
private fun StarButton(isStarred: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
        contentDescription = if (isStarred) "Remove from starred" else "Add to starred",
        tint = if (isStarred) TierInstantClassic else TextMuted,
        modifier = modifier.size(22.dp).clickable(onClick = onClick)
    )
}

/**
 * One line per standout performer in this game - deliberately plain text
 * (name + line), not a full card, since this can appear on any tile
 * regardless of tier, including a Skippable one that otherwise has very
 * little else drawing attention to it. Every standout shows here (not just
 * already-favorited ones), so a player worth favoriting can be spotted
 * mid-browse; a favorited name gets the same accent-tint/long-press
 * treatment TeamRow above gives a favorited team.
 */
@Composable
private fun StandoutPerformerCallout(
    performers: List<StandoutPerformer>,
    favoritePlayerNames: Set<String>,
    onTogglePlayer: (StandoutPerformer) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        performers.forEach { performer ->
            val isFavorite = performer.name in favoritePlayerNames
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isFavorite) FavoriteAccent.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .combinedClickable(onClick = {}, onLongClick = { onTogglePlayer(performer) })
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = FavoriteAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "${performer.name}: ${performer.line}",
                    color = if (isFavorite) FavoriteAccent else TextSecondary,
                    fontWeight = if (isFavorite) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HighlightsRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Default.PlayCircleFilled,
            contentDescription = null,
            tint = TierWorthYourTime,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Watch highlights",
            color = TierWorthYourTime,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "· Spoiler alert",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private val LOGO_SIZE = 24.dp

// A modest bump over the theme's own titleMedium (18sp) - applied locally
// here rather than changing titleMedium itself, since that style is shared
// by other screens (tier badges, empty states, etc.) that don't need to
// grow along with it.
private val TEAM_NAME_FONT_SIZE = 20.sp

// Away listed above home, each with its logo before the name — standard
// scoreboard convention, so no "at"/"@" separator needed between them.
// [score] is only ever non-null on the History tab (see Game.awayScore/
// homeScore) - every other tab leaves it null and this row renders exactly
// as before.
//
// [isFavorite] renders as a subtle full-row background tint rather than a
// colored border - tier already owns the border channel (Worth Your Time's
// green outline, etc.), so a second color there would compete with it
// instead of reading as a distinct signal. Long-pressing the row (anywhere
// in it, not just the logo - a larger, easier target) is this tile's
// quick-add/remove shortcut; a plain tap is intentionally a no-op here,
// since this row has never been individually tappable.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TeamRow(
    logoUrl: String?,
    name: String,
    score: Int? = null,
    isFavorite: Boolean = false,
    onLongPress: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFavorite) FavoriteAccent.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        if (logoUrl != null) {
            AsyncImage(
                model = themeAwareLogoUrl(logoUrl),
                contentDescription = null,
                modifier = Modifier.size(LOGO_SIZE)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        FitOneLineText(
            text = name,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            baseStyle = MaterialTheme.typography.titleMedium.copy(fontSize = TEAM_NAME_FONT_SIZE),
            modifier = Modifier.weight(1f, fill = false)
        )
        if (score != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = score.toString(),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = TEAM_NAME_FONT_SIZE, fontFeatureSettings = TABULAR_NUMS)
            )
        }
    }
}

private val MIN_TEAM_NAME_FONT_SIZE = 12.sp

/**
 * Shrinks [text] just enough to fit on one line instead of wrapping or
 * truncating — a long team name reads worse split across two lines or cut
 * off with an ellipsis than slightly smaller.
 */
@Composable
private fun FitOneLineText(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    var style by remember(text) { mutableStateOf(baseStyle) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        onTextLayout = { result ->
            if (result.didOverflowWidth && style.fontSize > MIN_TEAM_NAME_FONT_SIZE) {
                style = style.copy(fontSize = style.fontSize * 0.92f)
            } else {
                readyToDraw = true
            }
        }
    )
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

private fun localDate(utc: String): String =
    OffsetDateTime.parse(utc).atZoneSameInstant(ZoneId.systemDefault()).format(localDateFormatter)
