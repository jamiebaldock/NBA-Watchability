package com.nbawatchability.app.data

/**
 * NHL's own per-category multipliers - a sibling to RubricWeights/
 * MlbRubricWeights/NflRubricWeights, not a shared type with them, since
 * NHL's rubric dimensions (backend/src/nhlRubric.ts) are their own
 * independent scale (James's explicit call, matching MLB's/NFL's precedent).
 */
data class NhlRubricWeights(
    val margin: Float = 1f,
    val comeback: Float = 1f,
    val leadChanges: Float = 1f,
    val overtime: Float = 1f,
    val decisiveScoreLate: Float = 1f,
    val powerPlay: Float = 1f,
    val star: Float = 1f,
    val shutout: Float = 1f,
    val totalGoals: Float = 1f,
    val stakes: Float = 1f
) {
    companion object {
        val DEFAULT = NhlRubricWeights()
    }
}
