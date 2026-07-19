package com.nbawatchability.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Client-side port of backend/src/rubric.ts's computeWatchabilityScore, so a
 * device can recompute score/tier locally from the raw facts already sent in
 * every Game (m/cb/lc/ot/c5/lcf/fp/bz/st/sk) - scaled by the user's own
 * per-category weights instead of the server's fixed 1x. Keep the point
 * values/thresholds below in sync with rubric.ts if that ever changes.
 */
private object Rubric {
    // Margin/comeback/lead-change brackets are calibrated per league, not
    // universal - grounded in the real backfilled game distributions (2,650
    // NBA games, 576 WNBA games), the same way the server's star-performance
    // point thresholds were calibrated off real scoring history rather than
    // a naive game-length ratio. Keep these three functions' WNBA branches
    // in sync with backend/src/rubric.ts if that ever changes - clutch and
    // overtime deliberately stay league-agnostic (verified against real
    // data that qualification rates already come out close between
    // leagues under the universal thresholds).
    fun marginPoints(margin: Int?, isWnba: Boolean): Int {
        val m = abs(margin ?: return 0)
        return if (isWnba) {
            when {
                m <= 4 -> 25
                m <= 6 -> 20
                m <= 9 -> 13
                m <= 13 -> 7
                m <= 17 -> 3
                else -> 0
            }
        } else {
            when {
                m <= 3 -> 25
                m <= 6 -> 20
                m <= 10 -> 13
                m <= 15 -> 7
                m <= 20 -> 3
                else -> 0
            }
        }
    }

    fun clutchPoints(closeInFinalTwoMin: Boolean, leadChangeInFinalMin: Boolean, decidedOnFinalPossession: Boolean): Int {
        var pts = 0
        if (closeInFinalTwoMin) pts += 8
        if (leadChangeInFinalMin) pts += 6
        if (decidedOnFinalPossession) pts += 6
        return pts
    }

    fun comebackPoints(largestDeficitOvercome: Int?, isWnba: Boolean): Int {
        val cb = largestDeficitOvercome ?: 0
        return if (isWnba) {
            when {
                cb >= 17 -> 15
                cb >= 14 -> 10
                cb >= 9 -> 6
                else -> 0
            }
        } else {
            when {
                cb >= 20 -> 15
                cb >= 15 -> 10
                cb >= 10 -> 6
                else -> 0
            }
        }
    }

    // WNBA reaches the 10-point cap at leadChanges=17 rather than NBA's 20
    // (the value at the same percentile of games in each league's real
    // distribution) - same linear shape, different cap threshold, so the
    // NBA branch stays bit-for-bit identical to the pre-league-aware
    // formula ((leadChanges ?: 0) / 2, since 10/20 = 0.5).
    fun leadChangePoints(leadChanges: Int?, isWnba: Boolean): Int {
        val capThreshold = if (isWnba) 17 else 20
        return minOf(10, ((leadChanges ?: 0) * 10) / capThreshold)
    }

    fun overtimePoints(overtimePeriods: Int): Int = when {
        overtimePeriods <= 0 -> 0
        overtimePeriods >= 2 -> 10
        else -> 7
    }

    fun starPoints(star: String?): Int = when (star) {
        "historic" -> 10
        "great" -> 6
        "good" -> 3
        else -> 0
    }

    fun stakesPoints(stakes: Int?): Int = (stakes ?: 0).coerceIn(0, 10)

    /** No overall cap - the buzzer-beater bonus can push the total over 100, same as the server. */
    fun computeScore(game: Game, weights: RubricWeights): Int {
        val isWnba = game.league == "wnba"
        val margin = marginPoints(game.margin, isWnba) * weights.margin
        val clutch = clutchPoints(
            game.closeInFinalTwoMin,
            game.leadChangeInFinalMin,
            game.decidedOnFinalPossession
        ) * weights.clutch
        val buzzerBeater = (if (game.buzzerBeater) 10 else 0) * weights.buzzerBeater
        val comeback = comebackPoints(game.comeback, isWnba) * weights.comeback
        val leadChanges = leadChangePoints(game.leadChanges, isWnba) * weights.leadChanges
        val overtime = overtimePoints(game.overtimePeriods) * weights.overtime
        val star = starPoints(game.starPerformance) * weights.starPerformance
        val stakes = stakesPoints(game.stakes) * weights.stakes

        return (margin + clutch + buzzerBeater + comeback + leadChanges + overtime + star + stakes).roundToInt()
    }
}

/**
 * Weight-adjusted score, or null if the game's outcome isn't revealed yet -
 * mirrors the same scoreVisible gate the server uses, so recomputing locally
 * never leaks a score early regardless of what weights are set. Dispatches
 * to whichever sport's rubric actually applies - Rubric.computeScore for
 * basketball (margin/clutch/comeback/lead-changes/overtime/star/stakes from
 * m/cb/lc/ot/c5/lcf/fp/bz/st). Soccer support (EPL/La Liga/FIFA World Cup)
 * was removed from the live app - see archive/soccer/.
 */
// MLB has no client-side weight-adjusted recompute yet (mlbGamesService.ts's
// first pass deliberately doesn't persist the extra rubric-input facts a
// local recompute would need - see its file comment) - the user's weight
// sliders simply don't affect MLB tiles yet, and the server's real score is
// used as-is, same as every other league before soccer's weight system
// existed.
fun Game.effectiveScore(nbaWeights: RubricWeights, wnbaWeights: RubricWeights): Int? =
    if (scoreVisible && score != null) {
        when (league) {
            "mlb" -> score
            "wnba" -> Rubric.computeScore(this, wnbaWeights)
            else -> Rubric.computeScore(this, nbaWeights) // "nba" and "summer" - Summer League is NBA-affiliated, not a separate scoring path
        }
    } else null

fun Game.effectiveTier(nbaWeights: RubricWeights, wnbaWeights: RubricWeights): Tier? =
    effectiveScore(nbaWeights, wnbaWeights)?.let { Tier.fromScore(it, league) }

/**
 * One line of the game-detail popup's rubric-breakdown tab - a labeled
 * category, the weighted points it actually contributed, and that same
 * category's weighted max (base rubric cap * the user's own weight
 * multiplier, so a customized weight still shows a correct "out of").
 * [maxPoints] exists so the UI can render "13/20 pts" instead of a bare "13
 * pts" - James flagged the bare number as misreadable as a real-world stat
 * (e.g. the actual final score margin) rather than a rating contribution.
 */
data class RubricBreakdownEntry(val label: String, val points: Float, val maxPoints: Float)

/**
 * Per-category breakdown backing the game-detail popup's "why did this score
 * what it scored" tab - reuses the exact same point functions
 * effectiveScore/effectiveTier already call, just surfaced individually
 * instead of summed, so this can never drift out of sync with the actual
 * total. Empty if the outcome isn't revealed yet (same scoreVisible gate as
 * effectiveScore).
 */
fun Game.rubricBreakdown(nbaWeights: RubricWeights, wnbaWeights: RubricWeights): List<RubricBreakdownEntry> {
    if (!scoreVisible || score == null) return emptyList()
    val isWnba = league == "wnba"
    val weights = if (isWnba) wnbaWeights else nbaWeights
    return listOf(
        RubricBreakdownEntry("Margin", Rubric.marginPoints(margin, isWnba) * weights.margin, 25f * weights.margin),
        RubricBreakdownEntry(
            "Clutch finish",
            Rubric.clutchPoints(closeInFinalTwoMin, leadChangeInFinalMin, decidedOnFinalPossession) * weights.clutch,
            20f * weights.clutch
        ),
        RubricBreakdownEntry("Buzzer-beater", (if (buzzerBeater) 10 else 0) * weights.buzzerBeater, 10f * weights.buzzerBeater),
        RubricBreakdownEntry("Comeback", Rubric.comebackPoints(comeback, isWnba) * weights.comeback, 15f * weights.comeback),
        RubricBreakdownEntry("Lead changes", Rubric.leadChangePoints(leadChanges, isWnba) * weights.leadChanges, 10f * weights.leadChanges),
        RubricBreakdownEntry("Overtime", Rubric.overtimePoints(overtimePeriods) * weights.overtime, 10f * weights.overtime),
        RubricBreakdownEntry("Star performance", Rubric.starPoints(starPerformance) * weights.starPerformance, 10f * weights.starPerformance),
        RubricBreakdownEntry("Stakes", Rubric.stakesPoints(stakes) * weights.stakes, 10f * weights.stakes)
    )
}
