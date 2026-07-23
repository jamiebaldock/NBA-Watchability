// One-off: force-relabels every already-stored NFL game in production with
// its real per-round season_stage_label, derived from nflRawStats.json's
// own seasonType/weekNumber fields via deriveNflCompetitionLabel.
//
// Why this is needed even though cf4299b already shipped
// deriveNflCompetitionLabel and re-ran this same backfill: every NFL game
// (including the Super Bowl) had already been upserted with the generic
// "NFL - Regular Season" label by the live pipeline *before* that fix
// shipped. setSeasonStageLabel's own guard
// (`... WHERE season_stage_label IS NULL`) means the normal, always-safe-
// to-rerun migration path (migrateToGameStore.ts, run automatically on
// every server startup) can never touch a row that already has a label -
// so the already-wrong label on production's copy of these rows was never
// actually replaced, even though the fix was live and correct in code and
// even though this same script ran successfully against a local dev
// database. gameStore.ts's forceSetSeasonStageLabel is the escape hatch:
// an unconditional UPDATE, no guard.
//
// Reads the same backend/data/nflRawStats.json already deployed via git
// (unlike games.db, tracked and pushed with every deploy) - no live ESPN
// re-fetch needed, since every field deriveNflCompetitionLabel needs is
// already captured there.
//
// Can run standalone: npx tsx src/relabelNflStages.ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { forceSetSeasonStageLabel } from "./gameStore";
import { deriveNflCompetitionLabel } from "./nflGamesService";

interface NflHistoricalGame {
  eventId: string;
  seasonType: number;
  weekNumber?: number;
}

export function relabelNflStages(): { relabeled: number } {
  const path = join(__dirname, "..", "data", "nflRawStats.json");
  const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: NflHistoricalGame[] };

  for (const g of games) {
    forceSetSeasonStageLabel(g.eventId, deriveNflCompetitionLabel(g.seasonType, g.weekNumber));
  }

  console.log(`relabelNflStages: force-relabeled ${games.length} NFL games.`);
  return { relabeled: games.length };
}

if (require.main === module) {
  relabelNflStages();
}
