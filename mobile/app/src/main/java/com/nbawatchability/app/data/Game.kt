package com.nbawatchability.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

data class DayGames(
    val date: LocalDate,
    val games: List<Game>
)

@Serializable
enum class GameStatus {
    @SerialName("final") FINAL,
    @SerialName("live") LIVE,
    @SerialName("upcoming") UPCOMING
}

@Serializable
data class StandoutPerformer(
    val name: String,
    val line: String,
    // Which of the game's two teams this player is on (matches Game.away/
    // Game.home) - null on any row persisted before this field existed.
    // Lets a tile's long-press quick-add (GameCard.kt) tag a favorited
    // player with a team, same as every other favorite-player entry point.
    val team: String? = null
)

enum class Tier(val label: String, val emoji: String) {
    INSTANT_CLASSIC("INSTANT CLASSIC", "🔥"),
    WORTH_YOUR_TIME("WORTH YOUR TIME", "⭐"),
    SOLID("SOLID", "👍"),
    SKIPPABLE("SKIPPABLE", "😴");

    companion object {
        // Same rubric-to-tier mapping as the backend (spec section 2, point 2) — kept
        // identical so client and server never disagree on badge/tier for a given score.
        fun fromScore(score: Int): Tier = when {
            score >= 85 -> INSTANT_CLASSIC
            score >= 65 -> WORTH_YOUR_TIME
            score >= 45 -> SOLID
            else -> SKIPPABLE
        }
    }
}

/** Mirrors the backend JSON contract in nba-watchability-spec.md section 5. */
@Serializable
data class Game(
    // ESPN's own event id - distinct from this class's own computed [id]
    // property below (the away@home@utc composite key every existing
    // screen already uses for LazyColumn/starred/favorite identity, which
    // stays exactly as-is). Only used as the lookup key for the game-detail
    // popup's /game-detail?eventId= endpoint (Phase G) - null on any Game
    // instance that predates this field or was never round-tripped through
    // the backend (there are none in practice, but this keeps decoding an
    // older cached response harmless rather than a hard failure).
    @SerialName("id") val eventId: String? = null,
    @SerialName("a") val away: String,
    @SerialName("h") val home: String,
    @SerialName("al") val awayLogo: String? = null,
    @SerialName("hl") val homeLogo: String? = null,
    @SerialName("stt") val status: GameStatus,
    @SerialName("utc") val tipoffUtc: String,
    @SerialName("lg") val league: String = "nba",
    @SerialName("cl") val competitionLabel: String? = null,
    @SerialName("q") val quarter: Int? = null,
    @SerialName("clk") val clock: String? = null,
    @SerialName("m") val margin: Int? = null,
    // Absolute final score - only ever sent by the History tab's endpoint.
    // Every other tab deliberately never reveals the winner or exact score
    // (spec section 2 point 8), but a game the user is intentionally
    // browsing in History has nothing left to spoil.
    @SerialName("as") val awayScore: Int? = null,
    @SerialName("hs") val homeScore: Int? = null,
    @SerialName("cb") val comeback: Int? = null,
    @SerialName("lc") val leadChanges: Int? = null,
    @SerialName("ot") val overtimePeriods: Int = 0,
    @SerialName("c5") val closeInFinalTwoMin: Boolean = false,
    @SerialName("lcf") val leadChangeInFinalMin: Boolean = false,
    @SerialName("fp") val decidedOnFinalPossession: Boolean = false,
    @SerialName("bz") val buzzerBeater: Boolean = false,
    @SerialName("st") val starPerformance: String? = null,
    @SerialName("sop") val standoutPerformers: List<StandoutPerformer>? = null,
    @SerialName("sk") val stakes: Int? = null,
    // Soccer-only rubric-input facts - m/cb above are reused as-is (same
    // concept as basketball's final margin/largest deficit overcome), these
    // 8 have no basketball equivalent. Absent/null on every basketball Game.
    @SerialName("tg") val totalGoals: Int? = null,
    @SerialName("ldg") val lateDecisiveGoal: Boolean = false,
    @SerialName("mgp") val maxGoalsByPlayer: Int? = null,
    @SerialName("cst") val combinedShotsOnTarget: Int? = null,
    @SerialName("rc") val anyRedCard: Boolean = false,
    @SerialName("sv") val maxSavesByKeeper: Int? = null,
    @SerialName("fkg") val anyFreeKickGoal: Boolean = false,
    @SerialName("pm") val anyPenaltyMissed: Boolean = false,
    // Knockout-tournament-only (World Cup) - absent/false on every EPL/La
    // Liga game, which never has extra time or a shootout.
    @SerialName("et") val wentToExtraTime: Boolean = false,
    @SerialName("pk") val decidedByShootout: Boolean = false,
    @SerialName("hook") val hook: String,
    @SerialName("pitch") val pitch: String? = null,
    @SerialName("score") val score: Int? = null,
    @SerialName("score_visible") val scoreVisible: Boolean,
    @SerialName("yt") val youtubeVideoId: String? = null
) {
    val id: String get() = "$away@$home@$tipoffUtc"

    val isSummerLeague: Boolean get() = league == "summer"

    val tier: Tier? get() = if (scoreVisible && score != null) Tier.fromScore(score) else null

    // Breakdown facts (comeback, lead changes, etc.) are drama already resolved
    // by the game, so gate them on the same score-visibility rule as the tier.
    val hasBreakdown: Boolean get() = tier != null
}
