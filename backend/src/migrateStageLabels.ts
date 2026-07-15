// One-off enrichment pass: backfills season_stage_label for every game
// written to gameStore before that column existed (the entire pre-existing
// NBA/WNBA historical backfill, plus any live-collected game seen before
// this feature shipped). Not idempotent-by-necessity like
// migrateToGameStore.ts's re-verification, but safe to re-run any number of
// times - getGamesMissingStageLabel only ever returns rows still missing
// the label, and setSeasonStageLabel's own guard means a row that already
// got a label from a previous partial run is never touched again.
//
// Re-fetches ESPN's scoreboard (not summary - season.slug/notes are present
// on the lightweight scoreboard listing already) once per distinct
// (league, date) pair actually needed, not once per game - the historical
// backfill spans a few hundred distinct game dates across both leagues, not
// thousands of individual event lookups.
//
// Can run standalone: npx tsx src/migrateStageLabels.ts
import { EspnEvent, League, fetchScoreboard, toEspnDate } from "./espnClient";
import { deriveCompetitionLabel } from "./gameMapper";
import { getGamesMissingStageLabel, setSeasonStageLabel } from "./gameStore";

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Cache of one scoreboard fetch per (league, yyyymmdd) actually requested,
// so rows sharing a date only cost one real HTTP call between them.
const scoreboardCache = new Map<string, Promise<EspnEvent[]>>();

function scoreboardFor(league: League, yyyymmdd: string): Promise<EspnEvent[]> {
  const key = `${league}|${yyyymmdd}`;
  let cached = scoreboardCache.get(key);
  if (!cached) {
    cached = fetchScoreboard(yyyymmdd, league).finally(() => sleep(250));
    scoreboardCache.set(key, cached);
  }
  return cached;
}

// A day either side of the row's own recorded tipoff, in case ESPN's
// scoreboard "dates" grouping (which uses the event's own local/scheduling
// day) lands on a different UTC calendar day than tipoffUtc's date - the
// same boundary mismatch already seen elsewhere in this codebase's date
// handling. Checked in order; the first day whose scoreboard actually
// contains this eventId wins. Each candidate day is still only ever fetched
// once overall, via scoreboardFor's cache, even though many rows probe it.
async function findEvent(league: League, tipoffUtc: string, eventId: string): Promise<EspnEvent | undefined> {
  const centerDate = new Date(tipoffUtc);
  for (const offsetDays of [0, -1, 1]) {
    const d = new Date(centerDate);
    d.setUTCDate(d.getUTCDate() + offsetDays);
    const events = await scoreboardFor(league, toEspnDate(d));
    const match = events.find((e) => e.id === eventId);
    if (match) return match;
  }
  return undefined;
}

export async function migrateStageLabels(): Promise<{ updated: number; unresolved: number }> {
  const rows = getGamesMissingStageLabel();
  console.log(`migrateStageLabels: ${rows.length} rows missing season_stage_label`);

  let updated = 0;
  let unresolved = 0;
  let done = 0;

  for (const row of rows) {
    const event = await findEvent(row.league, row.tipoffUtc, row.eventId);
    const label = event ? deriveCompetitionLabel(event, row.leagueGroup) : undefined;
    if (label) {
      setSeasonStageLabel(row.eventId, label);
      updated++;
    } else {
      unresolved++;
      console.warn(`migrateStageLabels: could not resolve stage for ${row.eventId} (${row.away} @ ${row.home}, ${row.tipoffUtc})`);
    }

    done++;
    if (done % 200 === 0) {
      console.log(`migrateStageLabels: ${done}/${rows.length} rows done (${updated} updated, ${unresolved} unresolved so far)`);
    }
  }

  console.log(`migrateStageLabels: done. ${updated} updated, ${unresolved} unresolved.`);
  return { updated, unresolved };
}

if (require.main === module) {
  migrateStageLabels().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
