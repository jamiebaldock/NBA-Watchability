// File-based daily cache for the Standings/Stats/News tabs - same idea as
// cache.ts's per-game cache, but keyed by content kind + league group + date
// rather than by event id, since these aren't per-game data.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const CACHE_DIR = join(__dirname, "..", "cache");

export function todayKey(now: Date): string {
  const y = now.getUTCFullYear();
  const m = String(now.getUTCMonth() + 1).padStart(2, "0");
  const d = String(now.getUTCDate()).padStart(2, "0");
  return `${y}${m}${d}`;
}

function cachePath(kind: string, leagueGroup: string, dateYyyyMmDd: string): string {
  return join(CACHE_DIR, `${kind}-${leagueGroup}-${dateYyyyMmDd}.json`);
}

export function loadLeagueCache<T>(kind: string, leagueGroup: string, dateYyyyMmDd: string): T | null {
  const path = cachePath(kind, leagueGroup, dateYyyyMmDd);
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, "utf8")) as T;
  } catch {
    return null;
  }
}

export function saveLeagueCache<T>(kind: string, leagueGroup: string, dateYyyyMmDd: string, data: T): void {
  if (!existsSync(CACHE_DIR)) mkdirSync(CACHE_DIR, { recursive: true });
  writeFileSync(cachePath(kind, leagueGroup, dateYyyyMmDd), JSON.stringify(data), "utf8");
}
