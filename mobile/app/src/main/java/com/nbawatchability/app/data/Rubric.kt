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
 * Client-side port of backend/src/mlbRubric.ts's computeMlbWatchabilityScore
 * - the MLB analogue of basketball's Rubric object above, scaled by the
 * user's own MlbRubricWeights instead of the server's fixed 1x, the same way
 * NBA/WNBA already work. Keep every bracket in sync with mlbRubric.ts if
 * that ever changes.
 */
private object MlbRubric {
    fun marginPoints(margin: Int): Int {
        val m = abs(margin)
        return when {
            m == 1 -> 20
            m == 2 -> 15
            m <= 5 -> 9
            m <= 7 -> 4
            else -> 0
        }
    }

    fun walkOffPoints(walkOff: Boolean): Int = if (walkOff) 25 else 0

    fun comebackPoints(largestDeficitOvercome: Int): Int = when {
        largestDeficitOvercome >= 6 -> 18
        largestDeficitOvercome >= 4 -> 12
        largestDeficitOvercome >= 2 -> 6
        else -> 0
    }

    fun extraInningsPoints(extraInningsCount: Int): Int = when {
        extraInningsCount >= 2 -> 10
        extraInningsCount >= 1 -> 5
        else -> 0
    }

    fun totalRunsPoints(totalRuns: Int): Int = when {
        totalRuns >= 18 -> 10
        totalRuns >= 15 -> 6
        totalRuns >= 10 -> 3
        else -> 0
    }

    fun combinedHomeRunsPoints(combinedHomeRuns: Int): Int = when {
        combinedHomeRuns >= 5 -> 6
        combinedHomeRuns >= 3 -> 3
        else -> 0
    }

    fun starHomeRunPoints(maxHomeRunsByPlayer: Int): Int = when {
        maxHomeRunsByPlayer >= 3 -> 12
        maxHomeRunsByPlayer >= 2 -> 5
        else -> 0
    }

    // Each tier implies the one below it - takes the highest bracket that applies, not a sum.
    fun pitchingDominancePoints(perfectGame: Boolean, noHitter: Boolean, teamBlanked: Boolean): Int = when {
        perfectGame -> 30
        noHitter -> 20
        teamBlanked -> 8
        else -> 0
    }

    fun blownSavePoints(blownSave: Boolean): Int = if (blownSave) 6 else 0

    fun errorsPoints(combinedErrors: Int): Int = if (combinedErrors >= 3) 4 else 0

    /** No overall cap - mirrors basketball's Rubric.computeScore, same reasoning (a big pitching-dominance bonus can push the total over 100, same as the server). */
    fun computeScore(inputs: MlbRubricInputs, stakes: Int?, weights: MlbRubricWeights): Int {
        val margin = marginPoints(inputs.finalMargin) * weights.margin
        val walkOff = walkOffPoints(inputs.walkOff) * weights.walkOff
        val comeback = comebackPoints(inputs.largestDeficitOvercome) * weights.comeback
        val extraInnings = extraInningsPoints(inputs.extraInningsCount) * weights.extraInnings
        val totalRuns = totalRunsPoints(inputs.totalRuns) * weights.totalRuns
        val combinedHomeRuns = combinedHomeRunsPoints(inputs.combinedHomeRuns) * weights.combinedHomeRuns
        val starHomeRun = starHomeRunPoints(inputs.maxHomeRunsByPlayer) * weights.starHomeRun
        val pitchingDominance = pitchingDominancePoints(inputs.perfectGame, inputs.noHitter, inputs.teamBlanked) * weights.pitchingDominance
        val blownSave = blownSavePoints(inputs.blownSave) * weights.blownSave
        val errors = errorsPoints(inputs.combinedErrors) * weights.errors
        val stakesPts = Rubric.stakesPoints(stakes) * weights.stakes

        return (margin + walkOff + comeback + extraInnings + totalRuns + combinedHomeRuns + starHomeRun + pitchingDominance + blownSave + errors + stakesPts)
            .roundToInt()
    }
}

/**
 * Weight-adjusted score, or null if the game's outcome isn't revealed yet -
 * mirrors the same scoreVisible gate the server uses, so recomputing locally
 * never leaks a score early regardless of what weights are set. Dispatches
 * to whichever sport's rubric actually applies - Rubric.computeScore for
 * basketball (margin/clutch/comeback/lead-changes/overtime/star/stakes from
 * m/cb/lc/ot/c5/lcf/fp/bz/st), MlbRubric.computeScore for MLB (from
 * mlbInputs). Soccer support (EPL/La Liga/FIFA World Cup) was removed from
 * the live app - see archive/soccer/.
 */
// Falls back to the server's own [score] for MLB only when mlbInputs is
// missing (an older cached game that predates that field) - every game
// scored after that field shipped gets the real weight-adjusted recompute,
// same as NBA/WNBA.
fun Game.effectiveScore(nbaWeights: RubricWeights, wnbaWeights: RubricWeights, mlbWeights: MlbRubricWeights): Int? =
    if (scoreVisible && score != null) {
        when (league) {
            "mlb" -> mlbInputs?.let { MlbRubric.computeScore(it, stakes, mlbWeights) } ?: score
            "wnba" -> Rubric.computeScore(this, wnbaWeights)
            else -> Rubric.computeScore(this, nbaWeights) // "nba" and "summer" - Summer League is NBA-affiliated, not a separate scoring path
        }
    } else null

fun Game.effectiveTier(nbaWeights: RubricWeights, wnbaWeights: RubricWeights, mlbWeights: MlbRubricWeights): Tier? =
    effectiveScore(nbaWeights, wnbaWeights, mlbWeights)?.let { Tier.fromScore(it, league) }

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
 * effectiveScore). Dispatches to MLB's own dimensions/labels for MLB games
 * (mlbRubricBreakdown below) - basketball's fields (margin/comeback aside,
 * which MLB reuses for its own raw facts) don't apply to baseball at all.
 */
fun Game.rubricBreakdown(nbaWeights: RubricWeights, wnbaWeights: RubricWeights, mlbWeights: MlbRubricWeights): List<RubricBreakdownEntry> {
    if (!scoreVisible || score == null) return emptyList()
    if (league == "mlb") return mlbRubricBreakdown(mlbWeights)
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

/**
 * MLB's own dimensions (backend/src/mlbRubric.ts's MlbScoreBreakdown),
 * scaled by [weights] - mirrors basketball's rubricBreakdown above, so a
 * customized weight still shows a correct "out of" via [maxPoints]. Empty
 * (not a fallback basketball-shaped list) if an older cached game predates
 * the mlbInputs field.
 */
private fun Game.mlbRubricBreakdown(weights: MlbRubricWeights): List<RubricBreakdownEntry> {
    val inputs = mlbInputs ?: return emptyList()
    return listOf(
        RubricBreakdownEntry("Margin", MlbRubric.marginPoints(inputs.finalMargin) * weights.margin, 20f * weights.margin),
        RubricBreakdownEntry("Walk-off", MlbRubric.walkOffPoints(inputs.walkOff) * weights.walkOff, 25f * weights.walkOff),
        RubricBreakdownEntry("Comeback", MlbRubric.comebackPoints(inputs.largestDeficitOvercome) * weights.comeback, 18f * weights.comeback),
        RubricBreakdownEntry(
            "Extra innings",
            MlbRubric.extraInningsPoints(inputs.extraInningsCount) * weights.extraInnings,
            10f * weights.extraInnings
        ),
        RubricBreakdownEntry("Total runs", MlbRubric.totalRunsPoints(inputs.totalRuns) * weights.totalRuns, 10f * weights.totalRuns),
        RubricBreakdownEntry(
            "Combined home runs",
            MlbRubric.combinedHomeRunsPoints(inputs.combinedHomeRuns) * weights.combinedHomeRuns,
            6f * weights.combinedHomeRuns
        ),
        RubricBreakdownEntry(
            "Star home run",
            MlbRubric.starHomeRunPoints(inputs.maxHomeRunsByPlayer) * weights.starHomeRun,
            12f * weights.starHomeRun
        ),
        RubricBreakdownEntry(
            "Pitching dominance",
            MlbRubric.pitchingDominancePoints(inputs.perfectGame, inputs.noHitter, inputs.teamBlanked) * weights.pitchingDominance,
            30f * weights.pitchingDominance
        ),
        RubricBreakdownEntry("Blown save", MlbRubric.blownSavePoints(inputs.blownSave) * weights.blownSave, 6f * weights.blownSave),
        RubricBreakdownEntry("Errors", MlbRubric.errorsPoints(inputs.combinedErrors) * weights.errors, 4f * weights.errors),
        RubricBreakdownEntry("Stakes", Rubric.stakesPoints(stakes) * weights.stakes, 10f * weights.stakes)
    )
}
