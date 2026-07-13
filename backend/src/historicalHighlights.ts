// Lazily backfills YouTube highlight links for historical games (2,650 of
// them across the backfill). Unlike gamesService.ts's ensureHighlightsVideo,
// there's no retry cooldown or 48h give-up: a historical game's video either
// exists by now or it never will, so one search per game, ever, is enough -
// cached permanently (found or not) in its own file so it survives across
// requests within the same deploy. Gitignored like backend/cache/*.json -
// Render's disk isn't persisted across deploys, so this rebuilds itself
// naturally the same way the live schedule cache does.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { isYoutubeSearchConfigured, searchHighlightsVideo } from "./youtubeClient";

const CACHE_PATH = join(__dirname, "..", "cache", "historicalHighlights.json");

interface CacheEntry {
  yt: string | null;
}

type Store = Record<string, CacheEntry>;

function load(): Store {
  if (!existsSync(CACHE_PATH)) return {};
  try {
    return JSON.parse(readFileSync(CACHE_PATH, "utf8")) as Store;
  } catch {
    return {};
  }
}

function save(store: Store): void {
  const dir = join(__dirname, "..", "cache");
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  writeFileSync(CACHE_PATH, JSON.stringify(store), "utf8");
}

// search.list shares its 100-calls/day quota with the live Summer League
// highlights feature (youtubeClient.ts) - capping new lookups per request
// keeps a single "all time" History browse from blowing the whole day's
// budget. Games beyond the cap just stay unset this request and get picked
// up on a later one, since the (score-descending) order is stable and
// already-cached games are skipped instantly.
const MAX_NEW_LOOKUPS_PER_REQUEST = 15;
const REQUEST_DELAY_MS = 320;

export interface HighlightLookupGame {
  eventId: string;
  away: string;
  home: string;
  date: string;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Returns a videoId per eventId for whichever of [games] already have (or could newly get, within budget) a cached answer. */
export async function attachHistoricalHighlights(games: HighlightLookupGame[]): Promise<Map<string, string>> {
  const result = new Map<string, string>();
  if (!isYoutubeSearchConfigured()) return result;

  const store = load();
  let newLookups = 0;
  let dirty = false;

  for (const game of games) {
    const cached = store[game.eventId];
    if (cached) {
      if (cached.yt) result.set(game.eventId, cached.yt);
      continue;
    }

    if (newLookups >= MAX_NEW_LOOKUPS_PER_REQUEST) continue;
    newLookups++;

    const match = await searchHighlightsVideo("nba", game.away, game.home, game.date);
    store[game.eventId] = { yt: match?.videoId ?? null };
    dirty = true;
    if (match) result.set(game.eventId, match.videoId);

    await sleep(REQUEST_DELAY_MS);
  }

  if (dirty) save(store);
  return result;
}
