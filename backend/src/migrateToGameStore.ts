// One-time migration: imports backend/data/historicalWatchability.json (the
// 2,650-game NBA backfill, already fully correct after this session's
// rubric-fields fix) into gameStore's durable games table - this is how the
// historical backfill "graduates" into the same permanent store live games
// use, per the user's lifecycle spec: a finished game from a year ago and
// one from five minutes ago are the same kind of row, not two systems.
//
// Idempotent (safe to re-run): upsertBaseEntry is INSERT OR IGNORE, and
// setFinalRubric only writes when score is still NULL, so re-running this
// against an already-migrated store is a harmless no-op.
//
// Run with: npx tsx src/migrateToGameStore.ts
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

function main(): void {
  const { games } = JSON.parse(readFileSync(DATA_PATH, "utf8")) as { games: HistoricalGame[] };
  let migrated = 0;

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
    setFinalRubric(g.eventId, rubric);
    migrated++;
  }

  console.log(`Migrated ${migrated}/${games.length} historical games into gameStore.`);
}

main();
