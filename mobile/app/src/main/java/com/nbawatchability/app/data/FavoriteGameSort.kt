package com.nbawatchability.app.data

/**
 * Stable partition - favorite-team games first, preserving whatever order
 * [this] is already in within each partition (not a re-sort by score/date).
 * A no-op when [enabled] is false or there are no favorite teams at all, so
 * every existing sort option (rating/date, ascending/descending) composes
 * with this rather than being replaced by it.
 */
fun List<Game>.bumpFavoriteTeamGames(enabled: Boolean, favoriteTeamNames: Set<String>): List<Game> {
    if (!enabled || favoriteTeamNames.isEmpty()) return this
    val (favored, rest) = partition { it.away in favoriteTeamNames || it.home in favoriteTeamNames }
    return favored + rest
}
