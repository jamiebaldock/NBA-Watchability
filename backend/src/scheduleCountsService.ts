// Lightweight per-day game DATA for the mobile Games tab's season-calendar
// picker - deliberately a much cheaper code path from gamesService.ts/
// mlbGamesService.ts/nflGamesService.ts/nhlGamesService.ts's own
// getGamesForDate-style functions, which run the full pipeline per game
// (gameStore upsert, pregame LLM preview generation, rubric compute). The
// calendar only ever needs to know "how many games landed on this day," so
// this hits each sport's own scoreboard endpoint directly and just collects
// each game's real UTC tipoff - no LLM calls, no gameStore writes, no rubric
// math. Reuses each sport's own real-season-event filter (isRealSeasonEvent)
// so this always matches what that same day would actually show if browsed
// on the Games tab.
//
// Returns raw tipoff timestamps, NOT pre-grouped per-day counts - grouping
// happens client-side instead, by the viewer's own local calendar date
// (mirroring GameListViewModel.kt's rebucketByLocalDate/localDateOf exactly).
// ESPN's own scoreboard "day" is anchored to US Eastern time (confirmed
// directly: an NBA game listed under ESPN's 2026-04-12 scoreboard day tips
// off at 2026-04-13T00:30Z - 8:30pm Eastern, still "the 12th" there, but
// already the 13th in UTC and any timezone at or ahead of it, the UK
// included). Grouping by ESPN's own day server-side - what an earlier
// version of this file did - silently disagreed with the real Games tab's
// tile count for any viewer outside the Americas, the same class of bug
// already documented elsewhere in this codebase (gamesService.ts's own
// QUERY_BUFFER_DAYS comment). Sending raw timestamps and letting the device
// that actually knows its own timezone do the bucketing avoids needing to
// thread a timezone/UTC-offset parameter through this endpoint at all.
import { toEspnDate } from "./espnClient";
import { BasketballLeagueGroup, fetchAllEvents } from "./gamesService";
import { fetchMlbScoreboard } from "./mlbEspnClient";
import { isRealSeasonEvent as isRealMlbSeasonEvent } from "./mlbGamesService";
import { fetchNflScoreboard } from "./nflEspnClient";
import { isRealSeasonEvent as isRealNflSeasonEvent } from "./nflGamesService";
import { fetchNhlScoreboard } from "./nhlEspnClient";
import { isRealSeasonEvent as isRealNhlSeasonEvent } from "./nhlGamesService";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, SPORT_FOR_LEAGUE_GROUP } from "./types";

/**
 * Every YYYY-MM-DD date in [year]-[month] (1-indexed month, matching the
 * mobile client's java.time.YearMonth convention), plus one real calendar
 * day of buffer on each side - a game ESPN buckets under the last/first day
 * of the adjacent month can still rebucket into this month once the client
 * regroups by local date (a late-night ESPN-Eastern-day game near a month
 * boundary, viewed from a timezone ahead of Eastern, is exactly the
 * boundary case this whole file exists to get right).
 */
function dateStringsForMonthWithBuffer(year: number, month: number): string[] {
  const firstOfMonth = new Date(Date.UTC(year, month - 1, 1));
  const bufferStart = new Date(firstOfMonth);
  bufferStart.setUTCDate(bufferStart.getUTCDate() - 1);

  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const bufferEnd = new Date(Date.UTC(year, month - 1, daysInMonth));
  bufferEnd.setUTCDate(bufferEnd.getUTCDate() + 1);

  const dates: string[] = [];
  for (let d = new Date(bufferStart); d <= bufferEnd; d.setUTCDate(d.getUTCDate() + 1)) {
    dates.push(d.toISOString().slice(0, 10));
  }
  return dates;
}

/** One ESPN-scoreboard-day's real game tipoffs (UTC ISO timestamps) for [leagueGroup]. */
async function tipoffsForDate(leagueGroup: LeagueGroup, dateIso: string): Promise<string[]> {
  const espnDate = toEspnDate(new Date(`${dateIso}T12:00:00Z`));
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];

  if (sport === "basketball") {
    const events = await fetchAllEvents(espnDate, leagueGroup as BasketballLeagueGroup);
    return events.map(({ event }) => event.date);
  }
  if (sport === "baseball") {
    return (await fetchMlbScoreboard(espnDate)).filter(isRealMlbSeasonEvent).map((e) => e.date);
  }
  if (sport === "football") {
    return (await fetchNflScoreboard(espnDate)).filter(isRealNflSeasonEvent).map((e) => e.date);
  }
  // hockey
  return (await fetchNhlScoreboard(espnDate)).filter(isRealNhlSeasonEvent).map((e) => e.date);
}

/**
 * Every real game's UTC tipoff timestamp in [year]-[month] (plus a 1-day
 * buffer either side, see dateStringsForMonthWithBuffer) for [leagueGroup] -
 * the client groups these into per-local-date counts itself. Cached once
 * per day per (leagueGroup, year, month) - a whole month's real schedule
 * doesn't change intra-day.
 */
export async function getScheduleCounts(leagueGroup: LeagueGroup, year: number, month: number): Promise<string[]> {
  const cacheKey = `${leagueGroup}-${year}-${String(month).padStart(2, "0")}`;
  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<string[]>("schedule-tipoffs", cacheKey, dateKey);
  if (cached) return cached;

  const dates = dateStringsForMonthWithBuffer(year, month);
  const perDate = await Promise.all(dates.map((dateIso) => tipoffsForDate(leagueGroup, dateIso)));
  const tipoffs = perDate.flat();

  saveLeagueCache("schedule-tipoffs", cacheKey, dateKey, tipoffs);
  return tipoffs;
}
