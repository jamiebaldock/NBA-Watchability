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
    @SerialName("a") val away: String,
    @SerialName("h") val home: String,
    @SerialName("stt") val status: GameStatus,
    @SerialName("utc") val tipoffUtc: String,
    @SerialName("lg") val league: String = "nba",
    @SerialName("q") val quarter: Int? = null,
    @SerialName("clk") val clock: String? = null,
    @SerialName("m") val margin: Int? = null,
    @SerialName("cb") val comeback: Int? = null,
    @SerialName("lc") val leadChanges: Int? = null,
    @SerialName("ot") val overtimePeriods: Int = 0,
    @SerialName("c5") val closeInFinalTwoMin: Boolean = false,
    @SerialName("lcf") val leadChangeInFinalMin: Boolean = false,
    @SerialName("fp") val decidedOnFinalPossession: Boolean = false,
    @SerialName("bz") val buzzerBeater: Boolean = false,
    @SerialName("st") val starPerformance: String? = null,
    @SerialName("sk") val stakes: Int? = null,
    @SerialName("hook") val hook: String,
    @SerialName("score") val score: Int? = null,
    @SerialName("score_visible") val scoreVisible: Boolean
) {
    val id: String get() = "$away@$home@$tipoffUtc"

    val isSummerLeague: Boolean get() = league == "summer"

    val tier: Tier? get() = if (scoreVisible && score != null) Tier.fromScore(score) else null

    // Breakdown facts (comeback, lead changes, etc.) are drama already resolved
    // by the game, so gate them on the same score-visibility rule as the tier.
    val hasBreakdown: Boolean get() = tier != null
}
