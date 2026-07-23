// One-off: force-relabels already-stored MLB postseason games in production
// with their real per-round season_stage_label, derived from ESPN's own
// scoreboard data. MLB's analogue of relabelNflStages.ts - see that file's
// header comment for why a normal (guarded) migration can never fix an
// already-labeled row, and why this needs the unconditional
// forceSetSeasonStageLabel escape hatch.
//
// Unlike NFL, there's no MLB equivalent of nflRawStats.json carrying
// season/round metadata for already-played games (mlbRawStats.json predates
// deriveMlbCompetitionLabel), so this re-fetches ESPN's live scoreboard for
// each candidate row's own tipoff date instead - same "one real HTTP call
// per distinct date, not per game" caching approach as
// migrateStageLabels.ts. Scoped to getMlbGamesWithGenericLabel() (every MLB
// row still carrying the fixed default label) rather than every MLB row
// ever stored, so a normal regular-season game that's already correctly
// labeled is never re-fetched for nothing.
//
// Can run standalone: npx tsx src/relabelMlbStages.ts
import { toEspnDate } from "./espnClient";
import { forceSetSeasonStageLabel, getMlbGamesWithGenericLabel } from "./gameStore";
import { EspnMlbEvent, fetchMlbScoreboard } from "./mlbEspnClient";
import { COMPETITION_LABEL, deriveMlbCompetitionLabel } from "./mlbGamesService";

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const scoreboardCache = new Map<string, Promise<EspnMlbEvent[]>>();

function scoreboardFor(yyyymmdd: string): Promise<EspnMlbEvent[]> {
  let cached = scoreboardCache.get(yyyymmdd);
  if (!cached) {
    cached = fetchMlbScoreboard(yyyymmdd).finally(() => sleep(250));
    scoreboardCache.set(yyyymmdd, cached);
  }
  return cached;
}

// A day either side of the row's own recorded tipoff, same boundary-mismatch
// guard as migrateStageLabels.ts's findEvent (ESPN's scoreboard "dates"
// grouping can land on a different UTC calendar day than tipoffUtc's date).
async function findEvent(tipoffUtc: string, eventId: string): Promise<EspnMlbEvent | undefined> {
  const centerDate = new Date(tipoffUtc);
  for (const offsetDays of [0, -1, 1]) {
    const d = new Date(centerDate);
    d.setUTCDate(d.getUTCDate() + offsetDays);
    const events = await scoreboardFor(toEspnDate(d));
    const match = events.find((e) => e.id === eventId);
    if (match) return match;
  }
  return undefined;
}

export async function relabelMlbStages(): Promise<{ relabeled: number; unchanged: number; unresolved: number }> {
  const rows = getMlbGamesWithGenericLabel();
  console.log(`relabelMlbStages: ${rows.length} MLB rows still carrying the generic label`);

  let relabeled = 0;
  let unchanged = 0;
  let unresolved = 0;

  for (const row of rows) {
    const event = await findEvent(row.tipoffUtc, row.eventId);
    if (!event) {
      unresolved++;
      console.warn(`relabelMlbStages: could not resolve ${row.eventId} (${row.away} @ ${row.home}, ${row.tipoffUtc})`);
      continue;
    }
    const label = deriveMlbCompetitionLabel(event);
    if (label === COMPETITION_LABEL) {
      unchanged++;
      continue;
    }
    forceSetSeasonStageLabel(row.eventId, label);
    relabeled++;
    console.log(`relabelMlbStages: ${row.away} @ ${row.home} (${row.eventId}) -> "${label}"`);
  }

  console.log(`relabelMlbStages: done. ${relabeled} relabeled, ${unchanged} unchanged, ${unresolved} unresolved.`);
  return { relabeled, unchanged, unresolved };
}

if (require.main === module) {
  relabelMlbStages().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
