package com.nbawatchability.app.data

/**
 * App-wide "hide anything below this tier" filter. A game with no tier yet
 * (still upcoming/live, score not visible) is never hidden by this - there's
 * nothing to judge it against yet, and hiding an unrated game would read as
 * a bug ("where did this game go?") rather than the filter working as
 * intended. Tier's ordinal order (INSTANT_CLASSIC=0 ... SKIPPABLE=3) means
 * "at least as good as the minimum" is a `<=` comparison, not `>=`.
 */
fun List<Game>.filterByMinTier(enabled: Boolean, minTier: Tier, weights: RubricWeights): List<Game> {
    if (!enabled) return this
    return filter { game ->
        val tier = game.effectiveTier(weights) ?: return@filter true
        tier.ordinal <= minTier.ordinal
    }
}
