import { dateStringsBetween } from "./dateRange";
import { earliestGameDate } from "./gameStore";
import { getGamesForDate, getNextScheduledDate } from "./gamesService";
import { getHistory, HistoryResult } from "./historyService";
import { getNews } from "./newsService";
import { getStandings } from "./standingsService";
import { getStats } from "./statsService";
import { GameJson, LeagueGroup, NewsResponseJson, StandingsResponseJson, StatsResponseJson } from "./types";

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
  throw new BadRequestError('leagueGroup must be "nba" or "wnba"');
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
    schedule.push({ date, games: await getGamesForDate(date, leagueGroup) });
  }
  return schedule;
}

export interface NextGameDateResult {
  date: string | null;
}

export async function getNextGameDateForLeagueGroup(afterRaw: string, leagueGroupRaw = "nba"): Promise<NextGameDateResult> {
  if (!DATE_RE.test(afterRaw)) throw new BadRequestError("after must be YYYY-MM-DD");
  const leagueGroup = parseLeagueGroup(leagueGroupRaw);
  const date = await getNextScheduledDate(afterRaw, leagueGroup);
  return { date: date ?? null };
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
