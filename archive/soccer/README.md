# Archived soccer backend (extracted 2026-07-20)

This directory holds the EPL / La Liga / FIFA World Cup ("soccer") support that
was extracted from the NBA Watchability app on 2026-07-20. The product decision
was made to keep NBA Watchability focused on the 5 major US leagues (NBA, WNBA,
NFL, MLB, NHL) and to spin soccer off into its own future "Soccer Watchability"
app. This code is kept here so that future app can bootstrap from working,
previously-shipped logic instead of rebuilding the ESPN integration and rubric
from scratch.

## Architecture it used

The live app dispatches per-sport behavior through a `LeagueGroup` / `Sport`
type pair defined in `backend/src/types.ts`. Each league group (e.g. `"epl"`,
`"la-liga"`, `"fifa-world"`) maps to a `Sport` (`"soccer"`) via
`SPORT_FOR_LEAGUE_GROUP`, and the sport determines which per-sport files handle
fetching, mapping, scoring, and serving games. For soccer that was:

- an ESPN client (`soccerEspnClient.ts`) — talks to ESPN's soccer endpoints
- a game mapper (`soccerGameMapper.ts`) — turns raw ESPN payloads into the
  app's internal game shape, including parsing goals/events
- a rubric (`soccerRubric.ts`) — the watchability-scoring formula, calibrated
  against real match data
- a games service (`soccerGamesService.ts`) — the per-league-group dispatch
  layer (`isSoccerLeagueGroup`, `SOCCER_LEAGUE_FOR_GROUP`, schedule/date
  lookups) that the rest of the backend (httpHandler, gameDetailService,
  rosterService, teamsService, seasonWindowService, historyService) called
  into

This same dispatch pattern is mirrored today by the MLB implementation —
`mlbEspnClient.ts` / `mlbGameMapper.ts` / `mlbGamesService.ts` /
`mlbRubric.ts` in the live `backend/src/` — which is the cleanest currently-
live reference for how to wire a new sport into this pattern, since it was
just added and follows the same shape.

## What's in this folder

- `backend/soccerEspnClient.ts` — ESPN API client for EPL/La Liga/FIFA World
  Cup: fetches scoreboards, team rosters, team lists, calendar/season-window
  dates, and match summaries (for key events like goals/cards).
- `backend/soccerGameMapper.ts` — maps raw ESPN soccer scoreboard/summary JSON
  into the app's internal game representation, including goal-event parsing
  (`parseGoals`).
- `backend/soccerGamesService.ts` — the soccer dispatch layer: league-group
  helpers (`isSoccerLeagueGroup`, `SOCCER_LEAGUE_FOR_GROUP`,
  `LEAGUE_DISPLAY_NAME`), and functions to get games for a date, team
  schedules, and the next scheduled date for a soccer league group.
- `backend/soccerRubric.ts` — the watchability scoring rubric for soccer
  matches (goals, late/decisive goals, red cards, shots on target, saves,
  free-kick goals, missed penalties, extra time, shootouts, etc.).
- `backend/soccerRubric.test.ts` — unit tests for the soccer rubric.
- `backend/backfillHistoricalWatchabilitySoccer.ts` — one-off script used to
  backfill historical watchability scores for past EPL/La Liga seasons from
  ESPN data.
- `backend/testdata/eplMatches.json` — fixture data (sample EPL matches) used
  by the rubric/mapper tests.
- `backend/data/historicalWatchabilityEpl.json` — historical (season-by-season)
  computed watchability scores for EPL matches, used for rubric calibration
  and percentile lookups.
- `backend/data/historicalWatchabilityLaLiga.json` — same, for La Liga.

Note: FIFA World Cup historical data was not separately archived here (it
shared the EPL/La Liga rubric and dispatch code but had no dedicated
historical-watchability file of its own in the live app).

## Mobile-side archival (also 2026-07-20)

The mobile (Android/Kotlin) app's soccer support was removed in the same
pass, following the same product decision. It mirrored the backend split
above: a client-side rubric port, a client-side weight-customization system
(sliders for tuning each rubric category's contribution), and its own
Compose UI. What's archived here, in `mobile/`:

- `mobile/SoccerRubricWeights.kt` - a data class holding a per-category
  weight multiplier (0-2x) for each of soccer's 12 rubric dimensions
  (margin, total goals, comeback, late drama, star performance, chances
  created, red card, goalkeeper saves, free-kick goal, penalty miss, extra
  time, penalty shootout), plus a `SoccerRubricCategory` enum and a
  `DEFAULT` (all 1.0x) instance.
- `mobile/SoccerRubricSettingsRepository.kt` - DataStore-backed persistence
  for the user's customized soccer weights, so a slider change survives an
  app restart.
- `mobile/SoccerRubricSettingsViewModel.kt` - the ViewModel wrapping that
  repository for RubricWeightsScreen.kt's soccer tab (update/reset actions,
  exposed as Compose state).
- `mobile/SoccerRubric-extracted-from-RubricKt.kt.txt` - the client-side
  soccer scoring rubric (a `private object SoccerRubric`), a plain-text
  extract of what was embedded directly inside the live app's `Rubric.kt`
  (alongside the basketball-only `private object Rubric`) - not
  standalone-compilable as extracted; see the header comment in that file
  for what scaffolding it needs rebuilt around it.
- `mobile/RubricWeightsScreen-soccer-tab-extracted.kt.txt` - the "Soccer"
  segmented-button tab and its ~12 weight sliders from the Rubric Weights
  settings screen, a plain-text extract of what was embedded inside the
  live app's `RubricWeightsScreen.kt` - also not standalone-compilable as
  extracted; same caveat as above.

The live app's `LeagueGroup.kt` enum, `Game.kt`'s JSON contract, `Rubric.kt`,
`MinTierFilter.kt`, and every screen/ViewModel that threaded soccer weights
through (AppRoot.kt, GameCard.kt, DayTabsScreen.kt, FavoritesScreen.kt,
StarredScreen.kt, HistoryScreen.kt, HistoryViewModel.kt, GameDetailScreen.kt,
FullBreakdownSection.kt, StarredGamesViewModel.kt) had their soccer-specific
parameters, fields, and branches removed in place rather than archived,
since they're shared basketball/MLB files - only the soccer-only pieces of
each were cut.
