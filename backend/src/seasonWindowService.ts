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
import { fetchMlbCalendarDates, fetchMlbScoreboard } from "./mlbEspnClient";
import { isRealSeasonEvent } from "./mlbGamesService";
import { fetchNflCalendarGroups } from "./nflEspnClient";
import { LeagueGroup, SPORT_FOR_LEAGUE_GROUP } from "./types";

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

function isoDate(d: Date): string {
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

// MLB's own scoreboard calendar (fetchMlbCalendarDates) does NOT densely
// enumerate every regular-season date the way basketball's does - confirmed
// directly against a real response: querying it mid-season returned only 48
// entries total for the whole year (spring training's first day, two
// All-Star-break dates, then every postseason date from late September
// onward), with the entire ~6-month regular season in between completely
// absent. findRegularSeasonStart's "scan the calendar day by day" approach
// above would silently skip straight from spring training to the All-Star
// break and misreport the season as starting in mid-July, so MLB instead
// scans real calendar days directly via fetchMlbScoreboard (confirmed to
// return real per-day results, e.g. 8 games on the real 2026 Opening Day of
// March 27) starting from March 1 - regular season always begins in the back
// half of March, so 60 days is a generous bound that exits early (in
// practice within ~4 weeks) once the first non-spring-training date is
// found.
async function findMlbRegularSeasonStart(seasonYear: number): Promise<string | undefined> {
  const marchFirst = Date.UTC(seasonYear, 2, 1);
  const oneDayMs = 24 * 60 * 60 * 1000;
  for (let i = 0; i < 60; i++) {
    const date = new Date(marchFirst + i * oneDayMs);
    const events = await fetchMlbScoreboard(toEspnDate(date));
    if (events.some(isRealSeasonEvent)) return isoDate(date);
  }
  return undefined;
}

/**
 * MLB analogue of getBasketballSeasonWindow - "end" is still the calendar's
 * own latest known date (same self-extending reasoning as basketball's: the
 * calendar's sparse regular-season dates don't matter for this half, since
 * its postseason tail is densely enumerated well before postseason actually
 * starts). "start" comes from findMlbRegularSeasonStart's direct scoreboard
 * scan instead, since the calendar can't be used for that half (see its
 * comment).
 */
async function getMlbSeasonWindow(): Promise<SeasonWindow | null> {
  const now = new Date();
  const allDates = (await fetchMlbCalendarDates(toEspnDate(now))).sort();
  if (allDates.length === 0) return null;

  const end = allDates[allDates.length - 1];
  const start = await findMlbRegularSeasonStart(now.getUTCFullYear());
  if (!start) return null;

  return { start, end };
}

// NFL's calendar directly labels its Regular Season/Postseason groups with
// real startDate/endDate (see nflEspnClient.ts's own comment on this shape)
// - no day-by-day scan needed the way MLB's sparse calendar requires.
// "start" is the Regular Season group's own startDate (preseason, value
// "1", deliberately excluded - same "skip preseason" rule as everywhere
// else in this build); "end" is the Postseason group's endDate if it
// exists yet, falling back to the Regular Season's own endDate otherwise
// (e.g. mid-season, before the postseason group's real dates are known).
async function getNflSeasonWindow(): Promise<SeasonWindow | null> {
  const groups = await fetchNflCalendarGroups(toEspnDate(new Date()));
  const regularSeason = groups.find((g) => g.value === "2");
  if (!regularSeason) return null;
  const postseason = groups.find((g) => g.value === "3");

  return {
    start: regularSeason.startDate.slice(0, 10),
    end: (postseason ?? regularSeason).endDate.slice(0, 10)
  };
}

/** Cached once per calendar day per league group - same reasoning as standings/stats (ESPN's schedule doesn't change meaningfully more often than that). */
export async function getSeasonWindow(leagueGroup: LeagueGroup): Promise<SeasonWindow | null> {
  // Basketball, MLB, and NFL all have a full-season browse/calendar-picker
  // route built now - NHL still has no games pipeline at all, so it still
  // falls through to null, the same "nothing to show yet" signal
  // GameListViewModel.kt's own comment says this endpoint is already
  // expected to return gracefully, not an error state. Checked before the
  // cache/basketball fallback below, which would otherwise crash indexing
  // LEAGUE_GROUPS with a leagueGroup it doesn't recognize.
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];
  if (sport !== "basketball" && sport !== "baseball" && sport !== "football") return null;

  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<SeasonWindow>("seasonWindow", leagueGroup, dateKey);
  if (cached) return cached;

  // Safe cast: the SPORT_FOR_LEAGUE_GROUP guard above already ruled out
  // every basketball-sport LeagueGroup value other than "nba" | "wnba".
  const window =
    sport === "baseball"
      ? await getMlbSeasonWindow()
      : sport === "football"
        ? await getNflSeasonWindow()
        : await getBasketballSeasonWindow(leagueGroup as BasketballLeagueGroup);
  if (!window) return null;

  saveLeagueCache("seasonWindow", leagueGroup, dateKey, window);
  return window;
}
