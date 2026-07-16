import { dateStringsBetween } from "./dateRange";
import { currentSoccerSeasonStartDate, earliestGameDate, getMostRecentFinalsEnd } from "./gameStore";
import { getGameDetail } from "./gameDetailService";
import { getGamesForDate, getNextScheduledDate } from "./gamesService";
import { getHistory, HistoryResult } from "./historyService";
import { getNews } from "./newsService";
import { getSeasonWindow, SeasonWindow } from "./seasonWindowService";
import { getNextSoccerScheduledDate, getSoccerGamesForDate, isSoccerLeagueGroup } from "./soccerGamesService";
import { getRoster } from "./rosterService";
import { getStandings } from "./standingsService";
import { getStats } from "./statsService";
import { getTeams } from "./teamsService";
import {
  GameDetailResponseJson,
  GameJson,
  LeagueGroup,
  NewsResponseJson,
  RosterResponseJson,
  StandingsResponseJson,
  StatsResponseJson,
  TeamsResponseJson
} from "./types";

export interface DaySchedule {
  date: string;
  games: GameJson[];
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
// Basic abuse guard (spec section 4: "basic request throttling"). The client
// requests a couple of buffer days beyond what it displays (spec section 10:
// past/future 7 days = 15 days) to absorb ESPN's US-Eastern-based scoreboard
// date bucketing shifting by up to a day or two once re-bucketed into the
// user's own local calendar date, so this needs headroom above 15.
const MAX_RANGE_DAYS = 21;

export class BadRequestError extends Error {}

function parseLeagueGroup(raw: string): LeagueGroup {
  if (raw === "" || raw === "nba") return "nba";
  if (raw === "wnba") return "wnba";
  if (raw === "epl") return "epl";
  if (raw === "la-liga") return "la-liga";
  if (raw === "fifa-world") return "fifa-world";
  throw new BadRequestError('leagueGroup must be one of "nba", "wnba", "epl", "la-liga", "fifa-world"');
}

/** Dispatches to the basketball or soccer live-schedule pipeline (types.ts's SPORT_FOR_LEAGUE_GROUP) - the one choke point where a request's leagueGroup decides which sport's data layer actually runs. */
function getGamesForDateAnySport(date: string, leagueGroup: LeagueGroup): Promise<GameJson[]> {
  if (isSoccerLeagueGroup(leagueGroup)) return getSoccerGamesForDate(date, leagueGroup);
  return getGamesForDate(date, leagueGroup);
}

export async function getSchedule(start: string, end: string, leagueGroupRaw = "nba"): Promise<DaySchedule[]> {
  if (!DATE_RE.test(start) || !DATE_RE.test(end)) {
    throw new BadRequestError("start and end must be YYYY-MM-DD");
  }
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);
  const dates = dateStringsBetween(start, end);
  if (dates.length === 0) throw new BadRequestError("end must not be before start");
  if (dates.length > MAX_RANGE_DAYS) {
    throw new BadRequestError(`range too large: max ${MAX_RANGE_DAYS} days`);
  }

  const schedule: DaySchedule[] = [];
  for (const date of dates) {
    schedule.push({ date, games: await getGamesForDateAnySport(date, leagueGroup) });
  }
  return schedule;
}

export interface NextGameDateResult {
  date: string | null;
}

export async function getNextGameDateForLeagueGroup(afterRaw: string, leagueGroupRaw = "nba"): Promise<NextGameDateResult> {
  if (!DATE_RE.test(afterRaw)) throw new BadRequestError("after must be YYYY-MM-DD");
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);
  const date = isSoccerLeagueGroup(leagueGroup)
    ? await getNextSoccerScheduledDate(afterRaw, leagueGroup)
    : await getNextScheduledDate(afterRaw, leagueGroup);
  return { date: date ?? null };
}

export async function getSeasonWindowForLeagueGroup(
  leagueGroupRaw = "nba"
): Promise<SeasonWindow | { start: null; end: null }> {
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);
  const window = await getSeasonWindow(leagueGroup);
  return window ?? { start: null, end: null };
}

export interface CurrentSeasonStartResult {
  date: string;
}

/**
 * The real start of "This season" for [leagueGroup] - the day immediately
 * after the most recently completed Finals game, per the rule that a
 * season begins the moment the previous one's Finals conclude (so Summer
 * League/preseason games fall under the *new* season instead of a fixed
 * Oct 1/Apr 1 calendar cutoff, which would otherwise keep counting them as
 * part of the season that already ended). Falls back to Jan 1 of the
 * current year if no Finals game is on record yet - shouldn't happen
 * against a populated store, but degrades gracefully rather than erroring.
 *
 * Soccer has no Finals/playoff stage at all, so this whole Finals-anchored
 * rule doesn't apply - dispatched to currentSoccerSeasonStartDate's own
 * Aug-1 convention before any of it runs, rather than falling through to
 * the Jan-1 fallback (which would be wrong for a sport whose season
 * genuinely crosses a calendar-year boundary starting mid-year).
 */
export function getCurrentSeasonStartForLeagueGroup(leagueGroupRaw = "nba"): CurrentSeasonStartResult {
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);
  if (isSoccerLeagueGroup(leagueGroup)) {
    return { date: currentSoccerSeasonStartDate(leagueGroup) };
  }
  const finalsEnd = getMostRecentFinalsEnd(leagueGroup);
  if (!finalsEnd) {
    return { date: `${new Date().getUTCFullYear()}-01-01` };
  }
  const dayAfter = new Date(finalsEnd);
  dayAfter.setUTCDate(dayAfter.getUTCDate() + 1);
  return { date: dayAfter.toISOString().slice(0, 10) };
}

export async function getStandingsForLeagueGroup(leagueGroupRaw = "nba"): Promise<StandingsResponseJson> {
  return getStandings(parseLeagueGroup(leagueGroupRaw));
}

export async function getStatsForLeagueGroup(leagueGroupRaw = "nba"): Promise<StatsResponseJson> {
  return getStats(parseLeagueGroup(leagueGroupRaw));
}

export async function getNewsForLeagueGroup(leagueGroupRaw = "nba"): Promise<NewsResponseJson> {
  return getNews(parseLeagueGroup(leagueGroupRaw));
}

export async function getTeamsForLeagueGroup(leagueGroupRaw = "nba"): Promise<TeamsResponseJson> {
  return getTeams(parseLeagueGroup(leagueGroupRaw));
}

export async function getRosterForTeam(leagueGroupRaw: string, teamId: string): Promise<RosterResponseJson> {
  if (!teamId) throw new BadRequestError("team is required");
  return getRoster(parseLeagueGroup(leagueGroupRaw), teamId);
}

export async function getGameDetailForEvent(eventId: string): Promise<GameDetailResponseJson> {
  if (!eventId) throw new BadRequestError("eventId is required");
  return getGameDetail(eventId);
}

/**
 * Silently clamps rather than rejecting (spec latitude: "grey out or just
 * clamp, whichever is simpler") - a client-side preset like "All Time" is
 * built from today's date and the dataset's actual earliest date can shift
 * as future backfills extend it, so the client shouldn't need to know that
 * boundary precisely to ask for it.
 */
export async function getHistoryForRange(startRaw: string, endRaw: string, leagueGroupRaw = "nba"): Promise<HistoryResult> {
  if (!DATE_RE.test(startRaw) || !DATE_RE.test(endRaw)) {
    throw new BadRequestError("start and end must be YYYY-MM-DD");
  }
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);

  const earliest = earliestGameDate(leagueGroup) ?? startRaw;
  const today = new Date().toISOString().slice(0, 10);
  const start = startRaw < earliest ? earliest : startRaw;
  const end = endRaw > today ? today : endRaw;
  if (end < start) throw new BadRequestError("end must not be before start");

  return getHistory(start, end, leagueGroup);
}
