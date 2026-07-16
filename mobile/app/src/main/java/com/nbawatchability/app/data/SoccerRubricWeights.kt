package com.nbawatchability.app.data

/**
 * Soccer's own per-category multipliers - a sibling to RubricWeights, not a
 * shared type with it, since soccer's rubric dimensions (backend/src/
 * soccerRubric.ts) genuinely don't overlap with basketball's beyond the
 * shared "stakes" concept (RubricWeights.stakes is reused for both sports
 * rather than duplicated, since a playoffs/rivalry bonus means the same
 * thing regardless of sport). Adding a third sport's rubric later means
 * adding a third sibling file in this same shape - deliberately not a
 * generic "one weights class with a rubric-type enum," which would force
 * every sport's dimensions into one bag of optional fields.
 */
data class SoccerRubricWeights(
    val margin: Float = 1f,
    val totalGoals: Float = 1f,
    val comeback: Float = 1f,
    val lateDrama: Float = 1f,
    val star: Float = 1f,
    val chances: Float = 1f,
    val redCard: Float = 1f,
    val saves: Float = 1f,
    val freeKickGoal: Float = 1f,
    val penaltyMiss: Float = 1f,
    // Knockout-tournament-only (World Cup) - a no-op multiplier for every
    // EPL/La Liga game, which never has extra time or a shootout.
    val extraTime: Float = 1f,
    val shootout: Float = 1f
) {
    companion object {
        val DEFAULT = SoccerRubricWeights()
    }
}
