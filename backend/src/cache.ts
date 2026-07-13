// File-based daily cache (spec section 4: "Cache/DB (JSON store) - whichever
// is simpler to wire up"). Swap this module for a Postgres/Supabase-backed
// one later without touching gamesService.ts's call sites.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { League } from "./espnClient";
import { StarPerformance } from "./types";

const CACHE_DIR = join(__dirname, "..", "cache");

export interface CachedGame {
  eventId: string;
  away: string;
  home: string;
  // Optional because cache files written before logo support don't have them.
  awayLogo?: string;
  homeLogo?: string;
  tipoffUtc: string;
  // Optional because cache files written before Summer League support don't
  // have it; callers should treat a missing value as "nba".
  league?: League;
  // stakes/hook/pitch are undefined until the pregame preview is actually
  // generated - no longer on first sight, but at/after 5am local time at the
  // venue (gamesService.ts). Also optional for the old reason: cache files
  // written before the pitch blurb existed don't have it.
  stakes?: number;
  hook?: string;
  pitch?: string;
  // Matched YouTube video ID for the official full-game-highlights video.
  // ytCheckedAt (ISO timestamp of the most recent search attempt) lets a
  // miss be retried periodically instead of being cached as permanent - the
  // official channel usually doesn't have the video up within minutes of a
  // game going final, so a single check-and-never-again (the old ytChecked
  // boolean) silently locked in "no highlights" for most games, forever.
  // Both are optional for the old reason: cache files written before this
  // feature existed have neither, and files written by the old boolean
  // scheme have ytChecked but not ytCheckedAt, which reads as "never
  // checked" under the new scheme and self-heals on the next request.
  yt?: string;
  ytCheckedAt?: string;
  finalRubric?: {
    finalMargin: number;
    largestDeficitOvercome: number;
    leadChanges: number;
    overtimePeriods: number;
    closeInFinalTwoMin: boolean;
    leadChangeInFinalMin: boolean;
    decidedOnFinalPossession: boolean;
    buzzerBeater: boolean;
    starPerformance: StarPerformance;
    score: number;
  };
}

export interface CachedDay {
  date: string;
  games: Record<string, CachedGame>;
}

function dayPath(date: string): string {
  return join(CACHE_DIR, `${date}.json`);
}

export function loadDay(date: string): CachedDay {
  const path = dayPath(date);
  if (!existsSync(path)) return { date, games: {} };
  try {
    return JSON.parse(readFileSync(path, "utf8")) as CachedDay;
  } catch {
    return { date, games: {} };
  }
}

export function saveDay(day: CachedDay): void {
  if (!existsSync(CACHE_DIR)) mkdirSync(CACHE_DIR, { recursive: true });
  writeFileSync(dayPath(day.date), JSON.stringify(day, null, 2), "utf8");
}
