// Derives the real start/end dates of a league group's current full-season
// browsing range - not a calendar guess, but read directly off ESPN's own
// per-game data. Backs the Games tab's full-season day-tab range/calendar
// picker: the range "start" is the first date with a regular-season-or-later
// game (excluding preseason, per product decision), and "end" is simply the
// latest date ESPN's schedule currently knows about - which is why this
// self-extends once ESPN publishes real playoff/Finals dates, rather than
// needing a guessed Finals-end date hardcoded anywhere.
import { EspnEvent, League, fetchCalendarDates, fetchScoreboard, toEspnDate } from "./espnClient";
import { BasketballLeagueGroup, LEAGUE_GROUPS } from "./gamesService";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup } from "./types";

export interface SeasonWindow {
  start: string;
  end: string;
}

function isPreseasonEvent(event: EspnEvent): boolean {
  return event.season?.slug === "preseason";
}

/**
 * Scans forward from the earliest known calendar date until it finds one
 * with a real regular-season (or later) game in ANY of the group's member
 * leagues - almost always just a handful of preseason dates to skip past,
 * so this is a small, bounded number of requests, and only ever runs once a
 * day per league group thanks to the cache below. Checking every member
 * league per date (not just one) matters for a multi-league group like
 * NBA's: Summer League dates never show up under the "nba" slug's own
 * scoreboard and vice versa, so a single-league scan would silently miss
 * whichever league isn't currently in its own season.
 */
async function findRegularSeasonStart(leagues: readonly League[], sortedCalendarDates: string[]): Promise<string | undefined> {
  for (const date of sortedCalendarDates) {
    for (const league of leagues) {
      const events = await fetchScoreboard(date.replace(/-/g, ""), league);
      if (events.some((e) => !isPreseasonEvent(e))) return date;
    }
  }
  return undefined;
}

async function getBasketballSeasonWindow(leagueGroup: BasketballLeagueGroup): Promise<SeasonWindow | null> {
  const now = new Date();
  const leagues = LEAGUE_GROUPS[leagueGroup];
  // Union every member league's own calendar rather than just one - see
  // findRegularSeasonStart's comment for why a single-league fetch isn't
  // enough for a group like NBA's that spans several separate ESPN
  // "leagues". For a single-league group (WNBA today) this is a no-op:
  // Promise.all/flat/Set over one array is identical to fetching it alone.
  const calendarsPerLeague = await Promise.all(leagues.map((league) => fetchCalendarDates(toEspnDate(now), league)));
  const allDates = [...new Set(calendarsPerLeague.flat())].sort();
  if (allDates.length === 0) return null;

  const end = allDates[allDates.length - 1];
  const start = await findRegularSeasonStart(leagues, allDates);
  if (!start) return null;

  return { start, end };
}

/** Cached once per calendar day per league group - same reasoning as standings/stats (ESPN's schedule doesn't change meaningfully more often than that). */
export async function getSeasonWindow(leagueGroup: LeagueGroup): Promise<SeasonWindow | null> {
  // MLB has no full-season browse/calendar-picker route yet (Games-tab-only
  // first pass, see mlbGamesService.ts's file comment) - null here is the
  // same "nothing to show yet" signal GameListViewModel.kt's own comment
  // says this endpoint is already expected to return gracefully, not an
  // error state. Checked before the cache/basketball fallback below, which
  // would otherwise crash indexing LEAGUE_GROUPS with a leagueGroup it
  // doesn't recognize.
  if (leagueGroup === "mlb") return null;

  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<SeasonWindow>("seasonWindow", leagueGroup, dateKey);
  if (cached) return cached;

  const window = await getBasketballSeasonWindow(leagueGroup);
  if (!window) return null;

  saveLeagueCache("seasonWindow", leagueGroup, dateKey, window);
  return window;
}
