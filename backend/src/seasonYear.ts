import { ContentLeague } from "./espnClient";

// ESPN labels an NBA season by the calendar year it ends in (2025-26 season =
// 2026), but a WNBA season by the single calendar year it's played in (both
// start and end within the same year). This is only a starting guess - the
// caller (standingsService/statsService) always falls back a year if the
// guessed season has no data yet (offseason before the new season's first
// games), so it doesn't need to be exact about each league's cutover month.
// MLB's season (spring training through the World Series) is also entirely
// within one calendar year, same as WNBA's, so it shares that branch - "mlb"
// is accepted here (and by resolveSeasonYear below) alongside the
// basketball-only ContentLeague union rather than needing its own parallel
// copy of this try-guess-then-fallback logic in standingsService.ts. "nhl"
// is NOT added to this branch - confirmed directly against a real ESPN
// response that NHL uses the exact same "ends in" convention as NBA (the
// 2025-26 season, which crosses the year boundary like NBA's does, is
// queried as season=2026), so it falls through to the same month-based
// October cutover guess below with zero changes needed.
function guessSeasonYear(league: ContentLeague | "mlb" | "nhl", now: Date): number {
  const year = now.getUTCFullYear();
  if (league === "wnba" || league === "mlb") return year;
  const month = now.getUTCMonth(); // 0-indexed; October = 9
  return month >= 9 ? year + 1 : year;
}

/**
 * Tries the guessed "current" season year, then the year before it, returning
 * the first one [fetchFn] resolves non-null data for. Covers both the normal
 * case and "current season hasn't started yet, show the one that just ended."
 */
export async function resolveSeasonYear<T>(
  league: ContentLeague | "mlb" | "nhl",
  now: Date,
  fetchFn: (seasonYear: number) => Promise<T | null>
): Promise<{ year: number; data: T } | null> {
  const guess = guessSeasonYear(league, now);
  const guessResult = await fetchFn(guess);
  if (guessResult) return { year: guess, data: guessResult };

  const fallback = guess - 1;
  const fallbackResult = await fetchFn(fallback);
  if (fallbackResult) return { year: fallback, data: fallbackResult };

  return null;
}
