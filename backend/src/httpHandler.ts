import { dateStringsBetween } from "./dateRange";
import { getGamesForDate } from "./gamesService";
import { GameJson, LeagueGroup } from "./types";

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
