# Standard procedure: building a new league's watchability rubric

Reverse-engineered from how the NBA rubric (`backend/src/rubric.ts`), its WNBA
extension, and the standalone soccer rubric (`backend/src/soccerRubric.ts`,
EPL/La Liga) were actually built. Use this for MLB next, then NHL/NFL.

Standing rule this whole procedure exists to enforce: **a new sport gets its
own real-data calibration pass, not another league's thresholds reused
wholesale.** Different sports (and different leagues within a sport) have
different statistical distributions - a margin/comeback/whatever that's rare
in one is routine in another. Confirmed as the default approach, not a
one-off - see `[[feedback-per-league-rubric-calibration]]`.

## Step 0 - Confirm scope before writing any code

Design + calibrate + report the plan in plain text, wait for approval, only
then write scoring code. This is how the EPL rubric shipped and is the
standing default - don't skip straight to implementation.

## Step 1 - Confirm the data source

Check the free ESPN endpoints (`espnClient.ts`'s pattern - scoreboard +
summary/boxscore, no key required) actually cover the new sport:
- Does the scoreboard return real games for the league?
- Does the boxscore/summary response carry the play-by-play or per-player
  stats needed for the dimensions below (scoring plays, box score stat
  lines, key events like red cards/free kicks for soccer)?

If ESPN's coverage is missing or incomplete for something, that dimension
either gets dropped or needs a different data source - don't guess at a
value the API can't actually give you.

## Step 2 - Choose sport-native dimensions

Don't reuse another sport's dimension list. Basketball scores margin /
clutch / buzzer-beater / comeback / lead changes / overtime / star
performance. Soccer dropped "lead changes" (not a meaningful soccer concept)
and added goals-based dimensions, goalkeeper saves, red cards, free-kick
goals, missed penalties, extra time/shootout - each one chosen because it's
a real signal of *that sport's* drama, not a basketball concept relabeled.

For MLB, this means actually thinking through what makes a baseball game
memorable - walk-off hits, late-inning comebacks, extra innings, a
no-hitter/perfect game bid, a multi-home-run game, a one-run finish, a
blown save, etc. - not assuming basketball's category list translates.

`stakes` (the 0-10 LLM-judged rivalry/standings/playoff-race score) is the
one dimension that's already sport-agnostic - same mechanic
(`stakesPoints()` in `rubric.ts`, shared by both rubrics) works for any
league. Only the LLM prompt's context framing (`llm.ts`) needs adjusting per
sport, not the scoring.

## Step 3 - Pull a real historical sample

One-off backfill script (see `backfillHistoricalWatchabilityWnba.ts` /
`backfillHistoricalWatchabilitySoccer.ts` as templates), not part of the
build:
- Last 1-2 *completed* seasons, using that sport's real season calendar
  (WNBA's is a single calendar year; NBA's crosses a year boundary; match
  MLB's actual regular-season window).
- Exclude preseason/exhibition games.
- Paced (~3 req/sec) to stay clear of ESPN rate limits.
- Write incrementally per date with a resume marker, so an interrupted run
  doesn't have to restart from scratch.
- Capture *raw facts* per game (final margin, largest deficit overcome, a
  home-run count, extra innings, etc.) - not a score yet, since the rubric
  doesn't exist yet.
- No LLM/YouTube calls in this script - it's free, keyless ESPN data only,
  so it costs nothing to run.
- Output to `backend/data/historicalWatchability<League>.json`.

## Step 4 - Compute real percentiles for each candidate dimension

For every dimension from Step 2, look at where the real distribution's
percentiles actually fall. E.g. soccer's `marginPoints` comment documents
draws at 27.4% and 1-goal margins at 37.6% of the real 380-match sample -
that's *why* the top two brackets cover most matches by design, not an
arbitrary choice.

## Step 5 - Set bracket boundaries against those percentiles

Two cases:
1. **A dimension shared with an already-calibrated league** (e.g. margin,
   comeback size, lead changes, if MLB reuses a basketball-shaped concept):
   check first whether a universal threshold already produces similar
   qualification rates across leagues before assuming it needs its own
   bracket. `rubric.ts`'s header comment documents exactly this check for
   clutch factor and overtime frequency (NBA 38.6% vs WNBA 41.5% clutch
   qualification, close enough to stay universal) vs margin/comeback/lead
   changes (which didn't come out close, so each got its own per-league
   bracket at the *same percentile*, not the same raw number).
2. **A brand-new sport-native dimension** (no other league has it): bracket
   boundaries come straight from Step 4's percentiles - grounded in what's
   actually rare vs. common in the real sample, same as soccer's hat-trick
   (1.8% of matches) or 9+-save keeper performance (1.3%) anchors.

## Step 6 - Balance the total scale and decide on tier cutoffs

Basketball's brackets were calibrated so an *equivalent percentile* lands at
an *equivalent point value* across NBA/WNBA, which keeps the same universal
tier cutoffs (`tierForScore`: 85/65/45) meaningful on both leagues' score
scales without change.

Soccer instead left its own total scale (~113 max with stakes) deliberately
unnormalized against basketball's ~100 - its own comment says so explicitly:
"not meant to line up 1:1." Soccer doesn't currently have a shipped
tier-cutoff scheme of its own (no soccer tiles are live yet) - if it needs
one later, or MLB needs one, decide explicitly whether the new sport should
target basketball's existing 85/65/45 scale (normalize brackets to hit it)
or define its own cutoffs on its own scale, and say which up front rather
than let it fall out of whatever bracket weights get picked.

## Step 7 - Lock brackets in with regression tests

Mirror `rubric.test.ts` / `soccerRubric.test.ts`: one assertion per bracket
boundary (e.g. `marginPoints(2)` returns exactly the value the design
settled on). These are what stop a future refactor from silently drifting
the calibration.

## Step 8 - Calibrate the History "All-time" cutoff

Separate from the rubric itself, done *after* it exists: score the full
backfill sample through the new rubric, then find the score bar that
represents "genuinely rare/exceptional" for that specific league's own
distribution - not a shared number. See
`HistoryScreen.kt`'s `ALL_TIME_MIN_SCORE_NBA` (90) /
`_WNBA` (75, since WNBA's whole qualifying pool tops out at 81) /
`_SOCCER` (85, the 99.3rd percentile of the combined EPL+La Liga backfill,
which also happens to line up with the `instant_classic` tier cutoff) for
the reasoning pattern - grounded in that league's own real sample size and
score distribution, not copied from another league.

## Step 9 - Report the design, wait for approval

Before writing any scoring code: dimensions chosen (and why), bracket
boundaries with the real percentile evidence behind each one, qualification
rates, the total-scale/tier-cutoff decision from Step 6, and the proposed
All-time cutoff. Plain text, not code. Wait for a yes before Step 10.

## Step 10 - Implement

- New `<sport>Rubric.ts` (soccer's approach - a clean sport-specific module)
  or extend the league-aware pattern in `rubric.ts` (basketball's approach -
  same dimension shapes, per-league bracket tables) - pick based on how much
  the new sport's dimension *shape* actually overlaps an existing rubric,
  not by default.
- Wire into that sport's `gamesService.ts`/`gameStore.ts` equivalent, same
  as `soccerGamesService.ts` did for soccer.
- `computeWatchabilityScore`/`compute<Sport>WatchabilityScore` stays
  backend-only - the mobile client never sees play-by-play, only the
  already-computed score/breakdown/tier.

## Step 11 - Mobile-side plumbing

- If the sport's tiles actually ship to a tab (unlike soccer today), mirror
  *only* the score→tier mapping client-side (`Tier.fromScore` in Kotlin) -
  never the full rubric, since the client doesn't have play-by-play to
  recompute it from.
- Add a matching `<Sport>RubricWeights.kt` (per-dimension 0x-2x user
  multiplier, default 1x each) and settings repository/screen, mirroring
  `RubricWeights.kt`/`SoccerRubricWeights.kt` - one weight per dimension
  from Step 2, named to match.

## Step 12 - Validate end-to-end

Spot-check a handful of real recent games through the new rubric (mirror
`manualValidate.ts`'s pattern - fetch a real day's games, run them through
the new scoring function, print margin/dimension inputs alongside the
resulting score/tier) and sanity-check the output against what a human
would actually call a great vs. forgettable game from that sample.
