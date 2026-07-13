// Serves the one-off NBA backfill (backfillHistoricalWatchability.ts's
// output, backend/data/historicalWatchability.json) to the mobile app's
// History tab - "which old games are actually worth going back to watch."
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { attachHistoricalHighlights } from "./historicalHighlights";
import { teamLogoUrl } from "./teamLogos";
import { GameJson, StarPerformance } from "./types";

interface HistoricalGame {
  eventId: string;
  season: string;
  date: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  overtimePeriods: number;
  starPerformance: StarPerformance;
  buzzerBeater: boolean;
  score: number;
  tier: string;
}

interface HistoricalDataFile {
  generatedAt: string;
  games: HistoricalGame[];
  completedDates: string[];
}

const DATA_PATH = join(__dirname, "..", "data", "historicalWatchability.json");

// The backfill file is a static build artifact, never mutated at runtime -
// read once per process instead of re-parsing ~1MB on every request.
let dataCache: HistoricalDataFile | undefined;

function loadData(): HistoricalDataFile {
  if (!dataCache) dataCache = JSON.parse(readFileSync(DATA_PATH, "utf8")) as HistoricalDataFile;
  return dataCache;
}

// Only these two tiers are "barn burners" worth surfacing - Solid/Skippable
// games are in the dataset (for the tier-breakdown stats) but not what this
// tab is for.
const WATCHABLE_TIERS = new Set(["worth_your_time", "instant_classic"]);

export function earliestHistoryDate(): string {
  const games = loadData().games;
  let earliest = games[0].date;
  for (const g of games) if (g.date < earliest) earliest = g.date;
  return earliest.slice(0, 10);
}

export interface HistoryResult {
  earliestDate: string;
  games: GameJson[];
}

export async function getHistory(start: string, end: string): Promise<HistoryResult> {
  const data = loadData();
  const startMs = new Date(`${start}T00:00:00Z`).getTime();
  const endMs = new Date(`${end}T23:59:59.999Z`).getTime();

  const filtered = data.games
    .filter((g) => WATCHABLE_TIERS.has(g.tier))
    .filter((g) => {
      const t = new Date(g.date).getTime();
      return t >= startMs && t <= endMs;
    })
    .sort((a, b) => b.score - a.score);

  const ytByEventId = await attachHistoricalHighlights(
    filtered.map((g) => ({ eventId: g.eventId, away: g.away, home: g.home, date: g.date }))
  );

  const games: GameJson[] = filtered.map((g) => ({
    a: g.away,
    h: g.home,
    al: teamLogoUrl(g.away),
    hl: teamLogoUrl(g.home),
    stt: "final",
    utc: g.date,
    lg: "nba",
    m: g.finalMargin,
    as: g.awayScore,
    hs: g.homeScore,
    ot: g.overtimePeriods,
    c5: false,
    lcf: false,
    fp: false,
    bz: g.buzzerBeater,
    st: g.starPerformance,
    hook: `${g.away} at ${g.home}.`,
    score: g.score,
    score_visible: true,
    yt: ytByEventId.get(g.eventId),
  }));

  return { earliestDate: earliestHistoryDate(), games };
}
