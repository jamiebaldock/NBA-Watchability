package com.nbawatchability.app.ui.theme

import androidx.compose.runtime.Composable

/**
 * Flipping the app's chrome to light isn't enough on its own - a handful of
 * team/league crests (Toronto Tempo, the EPL crest, several other soccer
 * competition logos in LeagueGroup.kt) are only white/light-colored because
 * they were deliberately swapped to ESPN's "-dark" asset variant specifically
 * to read against this app's near-black surfaces (see the per-league
 * comments in LeagueGroup.kt, and backend/src/teamLogos.ts's
 * DARK_VARIANT_URL_PATTERNS for the team-level equivalent). On a light
 * background those same white assets would be nearly invisible.
 *
 * Every one of those overrides follows ESPN's own CDN convention - the
 * "-dark" segment lives right in the path (".../500-dark/23.png" vs the
 * plain ".../500/23.png") - so inverting it for light theme is a plain
 * string substitution, not a per-team lookup table. This covers every
 * current "-dark" logo (team or league) uniformly, including ones added
 * later, without needing its own override entry.
 */
@Composable
fun themeAwareLogoUrl(url: String?): String? {
    if (url == null || !LocalIsLightTheme.current) return url
    return url.replace("/500-dark/", "/500/")
}
