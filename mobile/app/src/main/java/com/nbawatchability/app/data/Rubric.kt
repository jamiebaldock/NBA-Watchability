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
    fun marginPoints(margin: Int?): Int {
        val m = abs(margin ?: return 0)
        return when {
            m <= 3 -> 25
            m <= 6 -> 20
            m <= 10 -> 13
            m <= 15 -> 7
            m <= 20 -> 3
            else -> 0
        }
    }

    fun clutchPoints(closeInFinalTwoMin: Boolean, leadChangeInFinalMin: Boolean, decidedOnFinalPossession: Boolean): Int {
        var pts = 0
        if (closeInFinalTwoMin) pts += 8
        if (leadChangeInFinalMin) pts += 6
        if (decidedOnFinalPossession) pts += 6
        return pts
    }

    fun comebackPoints(largestDeficitOvercome: Int?): Int {
        val cb = largestDeficitOvercome ?: 0
        return when {
            cb >= 20 -> 15
            cb >= 15 -> 10
            cb >= 10 -> 6
            else -> 0
        }
    }

    fun leadChangePoints(leadChanges: Int?): Int = minOf(10, (leadChanges ?: 0) / 2)

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
        val margin = marginPoints(game.margin) * weights.margin
        val clutch = clutchPoints(
            game.closeInFinalTwoMin,
            game.leadChangeInFinalMin,
            game.decidedOnFinalPossession
        ) * weights.clutch
        val buzzerBeater = (if (game.buzzerBeater) 10 else 0) * weights.buzzerBeater
        val comeback = comebackPoints(game.comeback) * weights.comeback
        val leadChanges = leadChangePoints(game.leadChanges) * weights.leadChanges
        val overtime = overtimePoints(game.overtimePeriods) * weights.overtime
        val star = starPoints(game.starPerformance) * weights.starPerformance
        val stakes = stakesPoints(game.stakes) * weights.stakes

        return (margin + clutch + buzzerBeater + comeback + leadChanges + overtime + star + stakes).roundToInt()
    }
}

/**
 * Weight-adjusted score, or null if the game's outcome isn't revealed yet -
 * mirrors the same scoreVisible gate the server uses, so recomputing locally
 * never leaks a score early regardless of what weights are set.
 */
fun Game.effectiveScore(weights: RubricWeights): Int? =
    if (scoreVisible && score != null) Rubric.computeScore(this, weights) else null

fun Game.effectiveTier(weights: RubricWeights): Tier? =
    effectiveScore(weights)?.let { Tier.fromScore(it) }
