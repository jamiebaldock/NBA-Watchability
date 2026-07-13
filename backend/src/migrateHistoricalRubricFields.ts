// One-off migration: backfillHistoricalWatchability.ts's HistoricalGame
// interface originally omitted largestDeficitOvercome/leadChanges/clutch
// flags (comeback size, lead changes, close-in-final-2min, lead-change-in-
// final-min, decided-on-final-possession) - harmless for the stored score/
// tier (already baked in at backfill time), but the mobile client always
// *recomputes* score/tier client-side from these raw facts (data/Rubric.kt,
// for the customizable rubric-weights feature), so their absence silently
// showed a lower score/wrong tier for every historical game once the History
// tab shipped. Re-fetches each game's summary (not the scoreboard - already
// have date/away/home/scores, and mapEventToGame only needs summary.header
// for those) and patches the missing fields in place.
//
// Run with: npx tsx src/migrateHistoricalRubricFields.ts
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fetchSummary } from "./espnClient";
import { mapEventToGame } from "./gameMapper";
import { HistoricalGame } from "./backfillHistoricalWatchability";

const OUTPUT_PATH = join(__dirname, "..", "data", "historicalWatchability.json");
const REQUEST_DELAY_MS = 320;

interface OutputFile {
  generatedAt: string;
  games: HistoricalGame[];
  completedDates: string[];
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function withRetries<T>(fn: () => Promise<T>, label: string, attempts = 3): Promise<T | null> {
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn();
    } catch (err) {
      console.error(`  [retry ${i + 1}/${attempts}] ${label} failed: ${(err as Error).message}`);
      if (i < attempts - 1) await sleep(1500 * (i + 1));
    }
  }
  console.error(`  giving up on ${label} after ${attempts} attempts`);
  return null;
}

async function main() {
  const output = JSON.parse(readFileSync(OUTPUT_PATH, "utf8")) as OutputFile;
  const total = output.games.length;
  let patched = 0;
  let mismatches = 0;

  for (let i = 0; i < total; i++) {
    const game = output.games[i];
    if (game.leadChanges !== undefined) {
      patched++;
      continue; // already migrated (resumable, same idea as the main backfill script)
    }

    const summary = await withRetries(() => fetchSummary(game.eventId, "nba"), `summary ${game.eventId}`);
    await sleep(REQUEST_DELAY_MS);
    if (!summary) continue;

    const header = (summary as { header?: { competitions?: unknown[] } }).header;
    const competition = (header?.competitions as { date: string }[] | undefined)?.[0];
    if (!competition) continue;

    const fakeEvent = {
      id: game.eventId,
      date: (competition as unknown as { date: string }).date,
      competitions: header!.competitions,
    } as Parameters<typeof mapEventToGame>[0];

    const mapped = mapEventToGame(fakeEvent, "nba", summary);

    game.largestDeficitOvercome = mapped.rubric.largestDeficitOvercome ?? 0;
    game.leadChanges = mapped.rubric.leadChanges ?? 0;
    game.closeInFinalTwoMin = mapped.rubric.closeInFinalTwoMin;
    game.leadChangeInFinalMin = mapped.rubric.leadChangeInFinalMin;
    game.decidedOnFinalPossession = mapped.rubric.decidedOnFinalPossession;

    // Sanity check: the score/tier already stored should exactly reproduce
    // from these newly-fetched facts - if not, something about this
    // specific game's summary changed or was mis-migrated, worth a look.
    if (mapped.rubric.finalMargin !== game.finalMargin || mapped.rubric.buzzerBeater !== game.buzzerBeater) {
      mismatches++;
      console.warn(`  mismatch on ${game.eventId} (${game.away} @ ${game.home}): margin/buzzer differs from stored`);
    }

    patched++;
    if (patched % 50 === 0 || patched === total) {
      writeFileSync(OUTPUT_PATH, JSON.stringify(output, null, 2), "utf8");
      console.log(`[${patched}/${total}] saved`);
    }
  }

  writeFileSync(OUTPUT_PATH, JSON.stringify(output, null, 2), "utf8");
  console.log(`\nDone. ${patched}/${total} games patched, ${mismatches} mismatches flagged.`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
