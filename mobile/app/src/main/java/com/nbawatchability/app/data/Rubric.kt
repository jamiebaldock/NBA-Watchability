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
 * Client-side port of backend/src/soccerRubric.ts's computeSoccerWatchabilityScore,
 * soccer's sibling to the private Rubric object above - a separate object
 * (not folded into Rubric) since the two sports' dimensions don't overlap
 * beyond stakes, which RubricWeights.stakes already covers for both. Point
 * values/thresholds must stay in sync with soccerRubric.ts if that ever
 * changes.
 */
private object SoccerRubric {
    fun marginPoints(margin: Int?): Int {
        val m = abs(margin ?: return 0)
        return when {
            m == 0 -> 20
            m == 1 -> 15
            m == 2 -> 8
            else -> 0
        }
    }

    fun totalGoalsPoints(totalGoals: Int?): Int = when {
        (totalGoals ?: 0) <= 1 -> 0
        totalGoals!! <= 3 -> 5
        totalGoals <= 5 -> 12
        else -> 20
    }

    fun comebackPoints(largestDeficitOvercome: Int?): Int {
        val cb = largestDeficitOvercome ?: 0
        return when {
            cb <= 0 -> 0
            cb == 1 -> 10
            cb == 2 -> 20
            else -> 30
        }
    }

    fun lateDramaPoints(lateDecisiveGoal: Boolean): Int = if (lateDecisiveGoal) 15 else 0

    fun starPoints(maxGoalsByPlayer: Int?): Int = when {
        (maxGoalsByPlayer ?: 0) >= 3 -> 15
        maxGoalsByPlayer == 2 -> 5
        else -> 0
    }

    fun chancesPoints(combinedShotsOnTarget: Int?): Int = if ((combinedShotsOnTarget ?: 0) >= 12) 8 else 0

    fun redCardPoints(anyRedCard: Boolean): Int = if (anyRedCard) 5 else 0

    fun savesPoints(maxSavesByKeeper: Int?): Int {
        val saves = maxSavesByKeeper ?: 0
        return when {
            saves >= 9 -> 15
            saves >= 7 -> 5
            else -> 0
        }
    }

    fun freeKickGoalPoints(anyFreeKickGoal: Boolean): Int = if (anyFreeKickGoal) 10 else 0

    fun penaltyMissPoints(anyPenaltyMissed: Boolean): Int = if (anyPenaltyMissed) 10 else 0

    fun stakesPoints(stakes: Int?): Int = (stakes ?: 0).coerceIn(0, 10)

    fun computeScore(game: Game, weights: RubricWeights, soccerWeights: SoccerRubricWeights): Int {
        val margin = marginPoints(game.margin) * soccerWeights.margin
        val totalGoals = totalGoalsPoints(game.totalGoals) * soccerWeights.totalGoals
        val comeback = comebackPoints(game.comeback) * soccerWeights.comeback
        val lateDrama = lateDramaPoints(game.lateDecisiveGoal) * soccerWeights.lateDrama
        val star = starPoints(game.maxGoalsByPlayer) * soccerWeights.star
        val chances = chancesPoints(game.combinedShotsOnTarget) * soccerWeights.chances
        val redCard = redCardPoints(game.anyRedCard) * soccerWeights.redCard
        val saves = savesPoints(game.maxSavesByKeeper) * soccerWeights.saves
        val freeKickGoal = freeKickGoalPoints(game.anyFreeKickGoal) * soccerWeights.freeKickGoal
        val penaltyMiss = penaltyMissPoints(game.anyPenaltyMissed) * soccerWeights.penaltyMiss
        // Stakes deliberately uses the shared RubricWeights.stakes, not a
        // soccer-only weight - see SoccerRubricWeights's doc comment.
        val stakes = stakesPoints(game.stakes) * weights.stakes

        return (margin + totalGoals + comeback + lateDrama + star + chances + redCard + saves + freeKickGoal + penaltyMiss + stakes)
            .roundToInt()
    }
}

/**
 * Weight-adjusted score, or null if the game's outcome isn't revealed yet -
 * mirrors the same scoreVisible gate the server uses, so recomputing locally
 * never leaks a score early regardless of what weights are set. Dispatches
 * to whichever sport's rubric actually applies - Rubric.computeScore for
 * basketball (margin/clutch/comeback/lead-changes/overtime/star/stakes from
 * m/cb/lc/ot/c5/lcf/fp/bz/st) or SoccerRubric.computeScore for soccer
 * (margin/totalGoals/comeback/lateDrama/star/chances/redCard/saves/
 * freeKickGoal/penaltyMiss from the sport-specific fields Phase E added to
 * GameJson).
 */
fun Game.effectiveScore(weights: RubricWeights, soccerWeights: SoccerRubricWeights = SoccerRubricWeights.DEFAULT): Int? =
    if (scoreVisible && score != null) {
        if (league == "soccer") SoccerRubric.computeScore(this, weights, soccerWeights) else Rubric.computeScore(this, weights)
    } else null

fun Game.effectiveTier(weights: RubricWeights, soccerWeights: SoccerRubricWeights = SoccerRubricWeights.DEFAULT): Tier? =
    effectiveScore(weights, soccerWeights)?.let { Tier.fromScore(it) }
