package com.nbawatchability.app.data

/**
 * Per-category multipliers (0x-2x, default 1x) scaling each category's point
 * contribution before summing - lets a user tune how much margin, comebacks,
 * etc. matter to *their* watchability score, without touching the shared
 * server-side rubric or any other user's view.
 */
data class RubricWeights(
    val margin: Float = 1f,
    val clutch: Float = 1f,
    val buzzerBeater: Float = 1f,
    val comeback: Float = 1f,
    val leadChanges: Float = 1f,
    val overtime: Float = 1f,
    val starPerformance: Float = 1f,
    val stakes: Float = 1f
) {
    companion object {
        val DEFAULT = RubricWeights()
    }
}
