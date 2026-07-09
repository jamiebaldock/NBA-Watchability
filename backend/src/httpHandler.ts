import { dateStringsBetween } from "./dateRange";
import { getGamesForDate } from "./gamesService";
import { GameJson } from "./types";

export interface DaySchedule {
  date: string;
  games: GameJson[];
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const MAX_RANGE_DAYS = 14; // basic abuse guard (spec section 4: "basic request throttling")

export class BadRequestError extends Error {}

export async function getSchedule(start: string, end: string): Promise<DaySchedule[]> {
  if (!DATE_RE.test(start) || !DATE_RE.test(end)) {
    throw new BadRequestError("start and end must be YYYY-MM-DD");
  }
  const dates = dateStringsBetween(start, end);
  if (dates.length === 0) throw new BadRequestError("end must not be before start");
  if (dates.length > MAX_RANGE_DAYS) {
    throw new BadRequestError(`range too large: max ${MAX_RANGE_DAYS} days`);
  }

  const schedule: DaySchedule[] = [];
  for (const date of dates) {
    schedule.push({ date, games: await getGamesForDate(date) });
  }
  return schedule;
}
