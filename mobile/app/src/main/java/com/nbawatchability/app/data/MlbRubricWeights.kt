package com.nbawatchability.app.data

/**
 * MLB's own per-category multipliers - a sibling to RubricWeights, not a
 * shared type with it, since MLB's rubric dimensions (backend/src/
 * mlbRubric.ts) are their own independent scale (James's explicit call, not
 * normalized against basketball's). Adding a third sport's rubric later
 * means adding a third sibling file in this same shape - deliberately not a
 * generic "one weights class with a rubric-type enum," which would force
 * every sport's dimensions into one bag of optional fields.
 */
data class MlbRubricWeights(
    val margin: Float = 1f,
    val walkOff: Float = 1f,
    val comeback: Float = 1f,
    val extraInnings: Float = 1f,
    val totalRuns: Float = 1f,
    val combinedHomeRuns: Float = 1f,
    val starHomeRun: Float = 1f,
    val pitchingDominance: Float = 1f,
    val blownSave: Float = 1f,
    val errors: Float = 1f,
    val stakes: Float = 1f
) {
    companion object {
        val DEFAULT = MlbRubricWeights()
    }
}
