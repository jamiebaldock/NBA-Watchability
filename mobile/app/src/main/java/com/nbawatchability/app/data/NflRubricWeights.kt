package com.nbawatchability.app.data

/**
 * NFL's own per-category multipliers - a sibling to RubricWeights/
 * MlbRubricWeights, not a shared type with them, since NFL's rubric
 * dimensions (backend/src/nflRubric.ts) are their own independent scale
 * (James's explicit call, not normalized against basketball's or MLB's).
 */
data class NflRubricWeights(
    val margin: Float = 1f,
    val comeback: Float = 1f,
    val leadChanges: Float = 1f,
    val overtime: Float = 1f,
    val decisiveScoreLate: Float = 1f,
    val turnovers: Float = 1f,
    val defensiveOrSpecialTeamsTd: Float = 1f,
    val star: Float = 1f,
    val totalPoints: Float = 1f,
    val stakes: Float = 1f
) {
    companion object {
        val DEFAULT = NflRubricWeights()
    }
}
