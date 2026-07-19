package com.nbawatchability.app.data

/**
 * Which mutually-exclusive slate the Games tab is currently showing - never
 * both at once, unlike NBA + Summer League which are already unioned
 * server-side within the "nba" group. Mirrors backend/src/types.ts's
 * LeagueGroup; [apiValue] is the exact query-string value the backend expects
 * - only meaningful for [isSupported] leagues, since the backend has no
 * route logic for the placeholder ones at all yet.
 *
 * [isSupported] gates every tab (Games/Starred/History/Leaders/News) to a
 * shared "coming soon" state instead of firing a network call the backend
 * doesn't recognize - see AppRoot.kt's ComingSoonTab. These four are
 * placeholders only: no game tiles, scoring, or backend data exists for
 * them yet. Logo URLs verified directly against ESPN's site API per league
 * (not guessed), same as every other logo in this codebase.
 */
enum class LeagueGroup(
    val apiValue: String,
    val displayName: String,
    val logoUrl: String,
    val isSupported: Boolean = true,
    // The big top-bar title (LeagueSelector.kt's TitleLeagueSelector) uses
    // this instead of [displayName] - most leagues' full names are already
    // this short (NBA, WNBA, NBL, UFC), so only the ones with a genuinely
    // long official name need a distinct value here. The dropdown list and
    // Settings' "Selected Sports" toggle both keep using [displayName] in
    // full, since there's room for it there.
    val shortDisplayName: String = displayName
) {
    NBA("nba", "NBA", "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"),
    WNBA("wnba", "WNBA", "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png"),
    NBL("nbl", "NBL", "https://a.espncdn.com/i/teamlogos/leagues/500/nbl.png", isSupported = false),
    UFC("ufc", "UFC", "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", isSupported = false),
    // Same dark-crest-on-dark-background problem as several other soccer
    // placeholders below - ESPN's default logo (id 2) is solid black,
    // illegible here, so the "-dark" (white)
    // variant is used instead. ESPN's own API slug for this competition is
    // "uefa.champions" (for whenever it's actually wired up) - apiValue
    // here is just a short display code like the other placeholders, not
    // that slug, since there's no backend route to match it against yet.
    UEFA_CHAMPIONS_LEAGUE(
        "ucl",
        "UEFA Champions League",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/2.png",
        isSupported = false,
        shortDisplayName = "UCL"
    ),

    // --- Batch 2 placeholders (all isSupported = false) ---
    // Every ESPN-covered entry below was confirmed live against
    // site.api.espn.com before adding (a scoreboard request that returns a
    // real league object), not assumed from the slug alone. Each logo was
    // downloaded and visually checked against this app's near-black
    // surfaces; "-dark" is used only where it actually swaps illegible
    // black content for white/transparent, not by default.

    // ESPN's cricket vertical is keyed by numeric league id, not a slug -
    // "8048" confirmed live (site.api.espn.com/.../cricket/8048/scoreboard
    // returns real IPL fixtures/teams). No league-level logo field in the
    // API response, but this competition-logo asset exists directly on
    // ESPN's CDN and is real (verified, not the generic cricket-ball
    // fallback below) - blue-on-transparent, reads fine on dark surfaces
    // without needing a dark variant.
    IPL(
        "ipl",
        "Indian Premier League",
        "https://a.espncdn.com/i/leaguelogos/cricket/500/8048.png",
        isSupported = false,
        shortDisplayName = "IPL"
    ),
    // Default crest's red/white shapes are fine, but its black "BUNDESLIGA"
    // wordmark disappears - the "-dark" variant drops the wordmark's dark
    // background cleanly, keeping the red panel and player silhouette.
    BUNDESLIGA(
        "bundesliga",
        "Bundesliga",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/10.png",
        isSupported = false
    ),
    NFL("nfl", "NFL", "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png", isSupported = false),
    // Navy wordmark is dim but legible against dark surfaces (not solid
    // black) - "-dark" is pixel-identical to the default here, so no real
    // choice to make.
    SERIE_A("serie-a", "Serie A", "https://a.espncdn.com/i/leaguelogos/soccer/500/12.png", isSupported = false),
    FORMULA_1(
        "f1",
        "Formula 1",
        "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/f1.png",
        isSupported = false,
        shortDisplayName = "F1"
    ),
    // isSupported = true: Games is live (real ESPN fixtures, scored via
    // mlbRubric.ts's own independent scale) as of the MLB Games-tab-only
    // first pass - Standings/Team-schedule (Favorites)/season-window/History
    // aren't wired up yet (backend/src/mlbGamesService.ts's file comment)
    // but degrade gracefully (empty results, not crashes) rather than
    // needing their own isSupported flag.
    MLB("mlb", "MLB", "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png"),
    // Default crest's grey/slate mark and wordmark are low-contrast on dark
    // surfaces - the "-dark" variant recolors both white, confirmed by
    // direct visual check (not just assumed from the "-dark" name).
    LIGUE_1("ligue-1", "Ligue 1", "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/9.png", isSupported = false),
    // Default crest's black star/wordmark disappear - "-dark" keeps the
    // orange rings and turns the star/wordmark white.
    UEFA_EUROPA_LEAGUE(
        "uel",
        "UEFA Europa League",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/2310.png",
        isSupported = false,
        shortDisplayName = "UEL"
    ),
    // Crest is mostly-black-on-white in both variants (not a light/dark
    // swap situation like the soccer placeholders above) - kept as the
    // default since its bright silver border and white wordmark still read
    // as a recognizable outline against dark surfaces even though the
    // shield's black fill blends in; the "-dark" variant's border is a
    // duller grey, worse.
    NHL("nhl", "NHL", "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png", isSupported = false),
    // Navy wordmark is dim but legible (not solid black) - "-dark" here is
    // an older sponsor-name variant of the same crest, no real contrast
    // difference, so the current default is kept.
    LIGA_MX("liga-mx", "Liga MX", "https://a.espncdn.com/i/leaguelogos/soccer/500/22.png", isSupported = false),
    // Default crest's black wordmark disappears - "-dark" drops the
    // wordmark entirely but keeps the colorful shield, which alone is
    // still clearly recognizable.
    PRIMEIRA_LIGA(
        "primeira-liga",
        "Primeira Liga",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/14.png",
        isSupported = false
    ),
    // ESPN has zero coverage for Japan's Nippon Professional Baseball - no
    // recognized slug under the baseball namespace (every guess returned
    // HTTP 400) and no matching asset on ESPN's CDN (checked directly, not
    // assumed). Real ESPN data for this league does not exist today. Tried
    // Wikimedia Commons' actual NPB crest first, but it failed to load in
    // the app (external non-ESPN host, likely a hotlink/User-Agent block
    // Coil's default client hits that a browser doesn't) - uses ESPN's own
    // generic baseball glyph instead, same reasoning as CBA China below: a
    // neutral stand-in from the one CDN already proven reliable everywhere
    // else in this app, not a guessed league-specific asset. If this league
    // ever gets wired up for real, it'll need an entirely different data
    // source, not just a new backend route.
    MLB_JAPAN(
        "npb",
        "MLB Japan (NPB)",
        "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon-baseball.png",
        isSupported = false,
        shortDisplayName = "NPB"
    ),
    // Default crest's black wordmark/sponsor line disappear - "-dark" drops
    // them, keeping the gold trophy graphic alone, still recognizable.
    COPA_LIBERTADORES(
        "libertadores",
        "Copa Libertadores",
        "https://a.espncdn.com/i/leaguelogos/soccer/500-dark/58.png",
        isSupported = false
    ),
    // Same story as MLB Japan - ESPN has zero coverage for the Chinese
    // Basketball Association (every basketball-namespace slug guess
    // returned HTTP 400, no CDN asset exists, no espn.com page exists).
    // No official crest could be sourced from anywhere verifiable, so this
    // uses ESPN's own generic basketball glyph as a neutral stand-in rather
    // than a specific (and unverifiable) league logo - a real data source
    // for this league doesn't exist yet, same caveat as MLB Japan above.
    CBA_CHINA(
        "cba",
        "CBA China",
        "https://a.espncdn.com/redesign/assets/img/icons/ESPN-icon-basketball.png",
        isSupported = false,
        shortDisplayName = "CBA"
    ),
    // Crest is already colorful/outlined with no solid-black fill in
    // either variant - kept as the default, no real difference either way.
    EFL_CHAMPIONSHIP(
        "efl-championship",
        "EFL Championship",
        "https://a.espncdn.com/i/leaguelogos/soccer/500/24.png",
        isSupported = false,
        shortDisplayName = "EFL"
    )
}
