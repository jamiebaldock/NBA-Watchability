// Derives the real start/end dates of a league's current season - not a
// calendar guess, but read directly off ESPN's own per-game data. Backs the
// Games tab's full-season day-tab range (currently WNBA only): the season
// "start" is the first date with a regular-season-or-later game (excluding
// preseason, per product decision), and "end" is simply the latest date
// ESPN's schedule currently knows about - which is why this self-extends
// once ESPN publishes real playoff/Finals dates, rather than needing a
// guessed Finals-end date hardcoded anywhere.
import { EspnEvent, League, fetchCalendarDates, fetchScoreboard, toEspnDate } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup } from "./types";

const LEAGUE_FOR_GROUP: Record<LeagueGroup, League> = { nba: "nba", wnba: "wnba" };

export interface SeasonWindow {
  start: string;
  end: string;
}

function isPreseasonEvent(event: EspnEvent): boolean {
  return event.season?.slug === "preseason";
}

/**
 * Scans forward from the earliest known calendar date until it finds one
 * with a real regular-season (or later) game - almost always just a
 * handful of preseason dates to skip past, so this is a small, bounded
 * number of requests, and only ever runs once a day per league group
 * thanks to the cache below.
 */
async function findRegularSeasonStart(league: League, sortedCalendarDates: string[]): Promise<string | undefined> {
  for (const date of sortedCalendarDates) {
    const events = await fetchScoreboard(date.replace(/-/g, ""), league);
    if (events.some((e) => !isPreseasonEvent(e))) return date;
  }
  return undefined;
}

/** Cached once per calendar day per league group - same reasoning as standings/stats (ESPN's schedule doesn't change meaningfully more often than that). */
export async function getSeasonWindow(leagueGroup: LeagueGroup): Promise<SeasonWindow | null> {
  const now = new Date();
  const dateKey = todayKey(now);
  const cached = loadLeagueCache<SeasonWindow>("seasonWindow", leagueGroup, dateKey);
  if (cached) return cached;

  const league = LEAGUE_FOR_GROUP[leagueGroup];
  const calendar = await fetchCalendarDates(toEspnDate(now), league);
  if (calendar.length === 0) return null;

  const sorted = [...calendar].sort();
  const end = sorted[sorted.length - 1];
  const start = await findRegularSeasonStart(league, sorted);
  if (!start) return null;

  const window: SeasonWindow = { start, end };
  saveLeagueCache("seasonWindow", leagueGroup, dateKey, window);
  return window;
}
