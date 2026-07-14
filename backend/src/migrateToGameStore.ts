// Imports backend/data/historicalWatchability.json (the 2,650-game NBA
// backfill, already fully correct after this session's rubric-fields fix)
// into gameStore's durable games table - this is how the historical
// backfill "graduates" into the same permanent store live games use, per
// the user's lifecycle spec: a finished game from a year ago and one from
// five minutes ago are the same kind of row, not two systems.
//
// Idempotent (safe to run every time, not just once): upsertBaseEntry is
// INSERT OR IGNORE, and setFinalRubric only writes when score is still
// NULL, so running this against an already-migrated store is a harmless
// no-op. That's also why devServer.ts calls this on every startup rather
// than requiring a manual one-off run against production (which would need
// Render Shell access, a paid-tier feature) - a brand new empty disk
// self-seeds from this file automatically; an already-populated one just
// verifies nothing's missing.
//
// Can still be run standalone: npx tsx src/migrateToGameStore.ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { FinalRubric, setFinalRubric, upsertBaseEntry } from "./gameStore";
import { StarPerformance } from "./types";

interface HistoricalGame {
  eventId: string;
  season: string;
  date: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  largestDeficitOvercome: number;
  leadChanges: number;
  overtimePeriods: number;
  closeInFinalTwoMin: boolean;
  leadChangeInFinalMin: boolean;
  decidedOnFinalPossession: boolean;
  starPerformance: StarPerformance;
  buzzerBeater: boolean;
  score: number;
  tier: string;
}

const DATA_PATH = join(__dirname, "..", "data", "historicalWatchability.json");

export function migrateHistoricalBackfill(): void {
  const { games } = JSON.parse(readFileSync(DATA_PATH, "utf8")) as { games: HistoricalGame[] };

  for (const g of games) {
    upsertBaseEntry({
      eventId: g.eventId,
      league: "nba",
      leagueGroup: "nba",
      away: g.away,
      home: g.home,
      tipoffUtc: g.date,
      status: "final",
    });

    const rubric: FinalRubric = {
      awayScore: g.awayScore,
      homeScore: g.homeScore,
      finalMargin: g.finalMargin,
      largestDeficitOvercome: g.largestDeficitOvercome,
      leadChanges: g.leadChanges,
      overtimePeriods: g.overtimePeriods,
      closeInFinalTwoMin: g.closeInFinalTwoMin,
      leadChangeInFinalMin: g.leadChangeInFinalMin,
      decidedOnFinalPossession: g.decidedOnFinalPossession,
      buzzerBeater: g.buzzerBeater,
      starPerformance: g.starPerformance,
      score: g.score,
      tier: g.tier,
    };
    // finalAt=null: this game's true end time isn't recorded anywhere in
    // the backfill, and stamping "whenever this migration happened to run"
    // would corrupt the per-league upload-lag stats getLagPercentiles
    // learns from. The highlights schedule falls back to tipoff_utc as its
    // anchor for any historical game that still needs a check.
    setFinalRubric(g.eventId, rubric, null);
  }

  console.log(`migrateHistoricalBackfill: verified ${games.length} historical games are present in gameStore.`);
}

// Allows `npx tsx src/migrateToGameStore.ts` to still work standalone for
// local testing, without running twice when devServer.ts imports this module.
if (require.main === module) {
  migrateHistoricalBackfill();
}
