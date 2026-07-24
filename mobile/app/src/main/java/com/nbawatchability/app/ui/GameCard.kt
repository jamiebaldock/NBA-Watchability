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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nbawatchability.app.data.FavoritePlayer
import com.nbawatchability.app.data.Game
import com.nbawatchability.app.data.GameStatus
import com.nbawatchability.app.data.LeagueGroup
import com.nbawatchability.app.data.MlbRubricWeights
import com.nbawatchability.app.data.NflRubricWeights
import com.nbawatchability.app.data.NhlRubricWeights
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

// Confetti-burst easter egg guard - a plain module-level Set (not DataStore-
// persisted), so "already celebrated" only lasts the life of the process,
// not forever. Deliberately simple: an unbounded persisted set of every
// Instant Classic game.id ever seen would only grow, for a purely cosmetic
// one-shot animation that doesn't need to remember across app restarts.
private val instantClassicCelebrated = mutableSetOf<String>()

@Composable
fun GameCard(
    game: Game,
    showNumericScore: Boolean,
    nbaWeights: RubricWeights,
    wnbaWeights: RubricWeights,
    mlbWeights: MlbRubricWeights,
    nflWeights: NflRubricWeights,
    nhlWeights: NhlRubricWeights,
    modifier: Modifier = Modifier,
    isStarred: Boolean = false,
    onToggleStar: () -> Unit = {},
    // The per-game push-alert bell (Alerts phase 2/3) - only wired up from
    // the Schedule tab (DayTabsScreen) so far, defaults off everywhere else
    // (Starred/History/Favorites) so those tiles don't show a bell that taps
    // through to a no-op. Also requires game.eventId (its backend identity).
    showBell: Boolean = false,
    isBelled: Boolean = false,
    onToggleBell: () -> Unit = {},
    onWatchHighlights: (String) -> Unit = {},
    // Only meaningful on the Starred tab, which combines games from many
    // different dates in one list - elsewhere the day-tab context already
    // makes the date obvious, so this defaults off.
    showDate: Boolean = false,
    // Whether the breakdown (FullBreakdownSection) starts revealed instead
    // of blurred behind its own reveal tap. False (blurred-by-default,
    // tap-to-reveal) everywhere, including History - browsing old games is
    // still "picking one to go watch," and revealing comeback size/OT on
    // sight there fought the tab's own browse-blind default (showScore
    // below), so it doesn't get a special-cased default either.
    spoilerFree: Boolean = false,
    // History tab only: a "browse blind" preference - hides just the two
    // teams' final numeric score digits (TeamRow below), not the tier badge,
    // which stays visible either way - the rating is the point of this tab,
    // only the literal score is optional to peek at. The breakdown's own
    // visibility is spoilerFree's job, independent of this.
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
    onGameClick: (Game) -> Unit = {},
    // History "All time" only: this is the single highest-scoring game in
    // that preset's currently-qualifying set - see HistoryScreen.kt's
    // topGame computation. Purely decorative (a crown next to the tier
    // badge), no other behavior change.
    isGoat: Boolean = false
) {
    val tier = game.effectiveTier(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights)

    // Confetti + haptic easter egg, once per game.id for the life of the
    // process (instantClassicCelebrated below) - a game that's already
    // Instant Classic every time this tile re-renders (scrolling it in and
    // out of view, switching tabs and back) only gets the burst the first
    // time, not on every recomposition.
    val haptic = LocalHapticFeedback.current
    var showConfetti by remember { mutableStateOf(false) }
    LaunchedEffect(game.id, tier, game.status) {
        if (tier == Tier.INSTANT_CLASSIC && game.status == GameStatus.FINAL && game.id !in instantClassicCelebrated) {
            instantClassicCelebrated += game.id
            showConfetti = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(modifier = modifier) {
    Card(
        onClick = { if (game.hasBreakdown) onGameClick(game) },
        modifier = Modifier.fillMaxWidth(),
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
                            CompetitionLabelWithLogo(competitionLabel, leagueGroupOf(game))
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
                            numericScore = if (showNumericScore) game.effectiveScore(nbaWeights, wnbaWeights, mlbWeights, nflWeights, nhlWeights) else null
                        )
                    } else {
                        Spacer(modifier = Modifier.size(0.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(game)
                        Spacer(modifier = Modifier.width(10.dp))
                        // Alerts are for games that haven't happened yet or are still
                        // live - a final game has nothing left to alert on, so the bell
                        // disappears the moment status flips, not just once a
                        // notification's actually been sent for it.
                        if (showBell && game.eventId != null && game.status != GameStatus.FINAL) {
                            BellButton(isBelled = isBelled, onClick = onToggleBell)
                            Spacer(modifier = Modifier.width(10.dp))
                        }
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

                // Which row the GOAT badge (isGoat) lands on - the winning
                // team's, per James's ask to move it off the header and
                // onto the winner's own name instead. Only ever meaningful
                // when isGoat is true, which HistoryScreen.kt already gates
                // on a final, scored game, so awayScore/homeScore are safe
                // to compare directly here.
                val awayWon = isGoat && (game.awayScore ?: 0) > (game.homeScore ?: 0)
                val homeWon = isGoat && !awayWon

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    TeamRow(
                        logoUrl = game.awayLogo,
                        name = game.away,
                        score = game.awayScore.takeIf { showScore },
                        isFavorite = game.away in favoriteTeamNames,
                        showGoatBadge = awayWon,
                        onLongPress = { onToggleFavoriteTeam(Team(name = game.away, logo = game.awayLogo, leagueGroup = gameLeagueGroup)) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TeamRow(
                        logoUrl = game.homeLogo,
                        name = game.home,
                        score = game.homeScore.takeIf { showScore },
                        isFavorite = game.home in favoriteTeamNames,
                        showGoatBadge = homeWon,
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
                        nhlWeights = nhlWeights,
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
    if (showConfetti) {
        ConfettiBurst(
            modifier = Modifier.matchParentSize(),
            onFinished = { showConfetti = false }
        )
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
/**
 * Replaces the leading league-name text in a competition label (e.g. "MLB -
 * Regular Season", "NFL - Playoffs: Super Bowl", "NBA SUMMER LEAGUE") with
 * that league's real logo, so a tile reads as a crest + context phrase
 * instead of spelling the league out in all-caps - matches how every league
 * selector elsewhere in the app (LeagueSelector.kt's TitleLeagueSelector)
 * already shows a logo instead of text. Falls back to the plain label
 * untouched if it doesn't start with the expected league prefix (a shape
 * this hasn't been verified against, safer than silently mangling it).
 */
@Composable
private fun CompetitionLabelWithLogo(label: String, league: LeagueGroup) {
    val prefix = league.shortDisplayName.uppercase()
    val remainder = if (label.startsWith(prefix, ignoreCase = true)) {
        label.substring(prefix.length).removePrefix(" - ").removePrefix(" ")
    } else {
        null
    }

    if (remainder == null) {
        Text(text = label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = themeAwareLogoUrl(league.logoUrl),
            contentDescription = league.displayName,
            modifier = Modifier.size(28.dp)
        )
        if (remainder.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = remainder, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

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

@Composable
private fun BellButton(isBelled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (isBelled) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
        contentDescription = if (isBelled) "Turn off alerts for this game" else "Get alerted for this game",
        tint = if (isBelled) TierInstantClassic else TextMuted,
        modifier = modifier.size(22.dp).clickable(onClick = onClick)
    )
}

// Player Hater Mode easter egg (LocalPlayerHaterMode, scoped down to just
// LocalHatedPlayerNames - see StandoutPerformerCallout below) - swaps the
// plain "name: line" callout for one of these instead: backhanded
// compliments, not straight insults, so it reads as "damning with faint
// praise" rather than actually mean. Only used as a fallback when
// [performer.line] doesn't parse into 2+ stats (see parseStatParts/
// playerHaterLine below) - most standout lines are a single stat (e.g. MLB's
// "2 HR", NFL's "300 pass yds", a non-triple-double NBA/WNBA "38 PTS"), so
// this bank covers the common case. Picked once per performer
// (remember-keyed on name+line, not re-rolled every recomposition) so it
// doesn't visibly flicker between jokes as the tile scrolls in and out of
// view.
private val PLAYER_HATER_SINGLE_STAT_LINES = listOf(
    "%s dropped %s - brave of them to try that hard.",
    "%s put up %s. Clean box score for someone who clearly got lucky.",
    "Respect to %s for %s, considering the competition.",
    "%s: %s. Not bad for a guy who probably practices.",
    "%s really showed up tonight with %s - shocking, honestly.",
    "%s posted %s. Even a stopped clock is right twice a day.",
    "You have to admire %s for %s - grit over talent, clearly.",
    "%s went for %s. Impressive, if you don't think about it too hard.",
    "%s: %s, and somehow still not the best player on the floor.",
    "%s managed %s - a real glow-up from whatever that was last game.",
    "%s with %s tonight. Great stats for someone who disappears in big games.",
    "Give %s credit for %s - the refs were feeling generous.",
    "%s dropped %s. Efficient, for a volume shooter.",
    "%s's %s is cute, but let's see them do it in the playoffs.",
    "%s posted %s - a career night, statistically speaking.",
    "%s: %s. Somebody finally showed up.",
    "%s put together %s - who knew they still had it in them.",
    "%s's %s looks great on paper. Paper doesn't play defense.",
    "%s racked up %s tonight - against that defense, so did everyone.",
    "%s finished with %s. A real testament to matchup luck.",
    "Oh wow, %s really showed up with %s. Groundbreaking stuff.",
    "Oh wow, %s had %s tonight. Somebody alert the historians.",
    "Big night for %s: %s. Circle the calendar.",
    "Oh wow, %s: %s. The bar was in hell and they still tripped over it.",
    "%s had %s tonight - a real 'my mom is proud' kind of stat line.",
    "Oh wow, %s pulled off %s. Someone tell their fantasy owners.",
    "%s: %s. Not the best game of their career, but sure, we'll clap.",
    "Oh wow, %s put up %s. Truly can't stop won't stop, allegedly."
)

// Spoiler-free versions: no actual stats, just gentle acknowledgment they showed up
private val PLAYER_HATER_SINGLE_STAT_LINES_SPOILER_FREE = listOf(
    "%s finally decided to play tonight - color me shocked.",
    "%s: putting in actual effort. Groundbreaking.",
    "Wow, %s showed up. Somebody alert the scoreboard.",
    "%s actually remembered they're on a basketball team.",
    "Give %s a hand for participating. Truly inspiring.",
    "%s had a game tonight - no further comment needed.",
    "Oh wow, %s decided to exist. Revolutionary.",
    "%s pulled off the impossible: being present.",
    "Credit to %s for at least trying tonight.",
    "%s: 'I'm gonna show up.' And then they did. Riveting stuff.",
    "Respect to %s for a genuine effort. Maybe.",
    "Big night for %s - and I mean that generously.",
    "Oh wow, %s actually contributed something. Let's celebrate.",
    "%s stepped up - the bar was definitely on the floor.",
    "Give it up for %s just... existing out there tonight.",
    "%s played a real game of basketball tonight.",
    "Oh wow, %s remembered the rules. How refreshing.",
    "%s: 'I can be useful.' Today was the day they tested that theory.",
    "Applause for %s, whose effort tonight was... present.",
    "Oh wow, %s didn't phone this one in. Shocking twist."
)

// Multi-stat (triple-double and good) spoiler-free
private val PLAYER_HATER_MULTI_STAT_SPOILER_FREE = listOf(
    "%s finally got it together. Only took long enough.",
    "%s showed up with their whole game tonight. Plot twist.",
    "Oh wow, %s was actually useful. In multiple ways. Historic.",
    "%s: 'I can contribute across the board.' And tonight they proved it, kinda.",
    "Oh wow, %s did multiple things well. Stop the presses.",
    "%s had one of those games where they remembered how to play."
)

private data class StatPart(val value: Int, val label: String)

// [performer.line] is always "NUMBER label" pairs, comma-separated - a
// single stat for every sport except an NBA/WNBA triple-double, which is
// always exactly "X PTS, Y REB, Z AST" (gameMapper.ts's findStandoutPerformers).
// Parses that shape generically rather than hardcoding the triple-double
// case, so it degrades gracefully (returns a 1-element list, falls through
// to the single-stat bank above) for every other sport's line format.
private fun parseStatParts(line: String): List<StatPart> =
    line.split(", ").mapNotNull { part ->
        Regex("""^(\d+)\s+(.+)$""").find(part.trim())?.let { match ->
            StatPart(match.groupValues[1].toInt(), match.groupValues[2])
        }
    }

/**
 * Spoiler-free roasts: no stat numbers mentioned, just gentle mockery
 * that the player showed up. Uses random template from the spoiler-free
 * banks that work for any game (single-stat or multi-stat).
 */
private fun playerHaterLine(name: String, line: String): String {
    val stats = parseStatParts(line)
    val bank = if (stats.size < 2) PLAYER_HATER_SINGLE_STAT_LINES_SPOILER_FREE else PLAYER_HATER_MULTI_STAT_SPOILER_FREE
    return bank.random().format(name)
}

/**
 * One line per standout performer in this game - deliberately plain text
 * (name + line), not a full card, since this can appear on any tile
 * regardless of tier, including a Skippable one that otherwise has very
 * little else drawing attention to it. Every standout shows here (not just
 * already-favorited ones), so a player worth favoriting can be spotted
 * mid-browse; a favorited name gets the same accent-tint/long-press
 * treatment TeamRow above gives a favorited team. Player Hater Mode
 * (LocalPlayerHaterMode) only roasts a performer who's also on the
 * LocalHatedPlayerNames list - being a standout here isn't enough on its
 * own, someone has to have actually marked them as hated first.
 */
@Composable
private fun StandoutPerformerCallout(
    performers: List<StandoutPerformer>,
    favoritePlayerNames: Set<String>,
    onTogglePlayer: (StandoutPerformer) -> Unit,
    modifier: Modifier = Modifier
) {
    val playerHaterMode = LocalPlayerHaterMode.current
    val hatedPlayerNames = LocalHatedPlayerNames.current
    Column(modifier = modifier) {
        performers.forEach { performer ->
            val isFavorite = performer.name in favoritePlayerNames
            val isHated = performer.name in hatedPlayerNames
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
                val text = if (playerHaterMode && isHated) {
                    remember(performer.name, performer.line) {
                        playerHaterLine(performer.name, performer.line)
                    }
                } else {
                    "${performer.name}: ${performer.line}"
                }
                Text(
                    text = text,
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
    // History "All time" only - this team is the winner of the single
    // highest-scoring game in that preset's currently-qualifying set (see
    // GameCard's isGoat param). Renders inline next to the name instead of
    // up in the header, per James's ask.
    showGoatBadge: Boolean = false,
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
        if (showGoatBadge) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "🐐 GOAT",
                color = TierInstantClassic,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
        }
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
                text = "${periodLabel(game.quarter, game.league)} ${game.clock.orEmpty()}".trim(),
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

// game.quarter is ESPN's raw status.period number for whatever sport this
// game is - basketball/football genuinely call that unit a "quarter", but
// baseball's is an inning and hockey's is a period, so a live tile was
// showing "Q3" for a hockey game's 3rd period (and a baseball game's 3rd
// inning) before this was made sport-aware. league here is Game.league
// ("nba"/"wnba"/"summer"/"mlb"/"nfl"/"nhl"), not the LeagueGroup enum -
// same raw string leagueGroupOf/isSummerLeague already dispatch on.
private fun periodLabel(quarter: Int?, league: String): String {
    if (quarter == null) return ""
    return when (league) {
        // Baseball has no overtime concept - extra innings just keep
        // counting past 9 with the same ordinal-number convention.
        "mlb" -> "${ordinal(quarter)}"
        // Regulation is 3 periods, not 4 - period 4 is overtime, 5 is a
        // shootout (nhlEspnClient.ts's own play-by-play uses the same
        // period-5-is-SO convention this mirrors).
        "nhl" -> when {
            quarter <= 3 -> "${ordinal(quarter)} Period"
            quarter == 4 -> "OT"
            else -> "SO"
        }
        // "nba"/"wnba"/"summer"/"nfl" (and any future basketball/football
        // addition) - both sports genuinely call this a quarter.
        else -> if (quarter <= 4) "Q$quarter" else "OT${quarter - 4}"
    }
}

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

// ESPN omits the seconds field when it's :00 (e.g. "19:30Z" not "19:30:00Z"),
// which Instant.parse's strict ISO_INSTANT formatter rejects; OffsetDateTime's
// default ISO_OFFSET_DATE_TIME format treats seconds as optional.
private fun localTipoff(utc: String): String =
    OffsetDateTime.parse(utc).atZoneSameInstant(ZoneId.systemDefault()).format(localTimeFormatter)

private fun localDate(utc: String): String =
    OffsetDateTime.parse(utc).atZoneSameInstant(ZoneId.systemDefault()).format(localDateFormatter)
