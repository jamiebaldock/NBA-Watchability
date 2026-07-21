// Lightweight per-day game COUNTS for the mobile Games tab's season-calendar
// picker - deliberately a separate, much cheaper code path from
// gamesService.ts/mlbGamesService.ts/nflGamesService.ts/nhlGamesService.ts's
// own getGamesForDate-style functions, which run the full pipeline per game
// (gameStore upsert, pregame LLM preview generation, rubric compute). The
// calendar only ever needs to know "how many games landed on this day," so
// this hits each sport's own scoreboard endpoint directly and just counts
// the (filtered) events - no LLM calls, no gameStore writes, no rubric math.
// Reuses each sport's own real-season-event filter (isRealSeasonEvent) so a
// day's count here always matches what that same day would actually show if
// browsed on the Games tab, rather than drifting out of sync with a second
// copy of the same filtering logic.
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

/** Every YYYY-MM-DD date in [year]-[month] (1-indexed month, matching the mobile client's java.time.YearMonth convention). */
function dateStringsForMonth(year: number, month: number): string[] {
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const dates: string[] = [];
  for (let day = 1; day <= daysInMonth; day++) {
    dates.push(`${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`);
  }
  return dates;
}

/** One day's real game count for [leagueGroup] - the one place this file dispatches by sport. */
async function countForDate(leagueGroup: LeagueGroup, dateIso: string): Promise<number> {
  const espnDate = toEspnDate(new Date(`${dateIso}T12:00:00Z`));
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];

  if (sport === "basketball") {
    const events = await fetchAllEvents(espnDate, leagueGroup as BasketballLeagueGroup);
    return events.length;
  }
  if (sport === "baseball") {
    return (await fetchMlbScoreboard(espnDate)).filter(isRealMlbSeasonEvent).length;
  }
  if (sport === "football") {
    return (await fetchNflScoreboard(espnDate)).filter(isRealNflSeasonEvent).length;
  }
  // hockey
  return (await fetchNhlScoreboard(espnDate)).filter(isRealNhlSeasonEvent).length;
}

/**
 * Per-day game counts for every real game-day in [year]-[month] for
 * [leagueGroup] - days with zero games are simply absent from the returned
 * map (the mobile calendar leaves those cells blank rather than showing a
 * "0"). Cached once per day per (leagueGroup, year, month) - a whole
 * month's real schedule doesn't change intra-day, and this is one cache
 * entry per month browsed rather than per single date, matching how
 * sparse a full year of browsing actually is in practice.
 */
export async function getScheduleCounts(leagueGroup: LeagueGroup, year: number, month: number): Promise<Record<string, number>> {
  const cacheKey = `${leagueGroup}-${year}-${String(month).padStart(2, "0")}`;
  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<Record<string, number>>("schedule-counts", cacheKey, dateKey);
  if (cached) return cached;

  const dates = dateStringsForMonth(year, month);
  const counts: Record<string, number> = {};
  await Promise.all(
    dates.map(async (dateIso) => {
      const count = await countForDate(leagueGroup, dateIso);
      if (count > 0) counts[dateIso] = count;
    })
  );

  saveLeagueCache("schedule-counts", cacheKey, dateKey, counts);
  return counts;
}
