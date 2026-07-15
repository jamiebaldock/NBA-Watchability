// One-off analysis script (not part of the build/server) - scores every
// finished WNBA regular-season/playoff game from the last two completed
// seasons through the exact same rubric.ts logic the live app uses, and
// writes the results to backend/data/historicalWatchabilityWnba.json.
// Mirrors backfillHistoricalWatchability.ts (the NBA equivalent) exactly,
// just pointed at the "wnba" league and WNBA's own season calendar (a
// single calendar year, not NBA's Oct-Jun cross-year span) - matches the
// NBA backfill's actual scope ("last two completed seasons"), which for
// WNBA lands on 2024 and 2025 since the 2026 season is still in progress
// as of this run. Excludes preseason (exhibition games, not real season
// play) and stakes/hook/pitch (LLM-derived narrative text, out of scope
// here - omitting stakes just contributes 0 to the score, same as any
// other ungraded input). No LLM or YouTube calls anywhere in this script -
// ESPN's scoreboard/summary endpoints are free and keyless, so this run
// has no token or API-quota cost.
//
// Paced at ~3 requests/sec to stay well clear of ESPN rate limits. Writes
// incrementally after every date (not just at the end) and records which
// dates are already done, so an interrupted run can be re-launched and
// picks up where it left off instead of re-fetching everything.
//
// Run with: npx tsx src/backfillHistoricalWatchabilityWnba.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { dateStringsBetween } from "./dateRange";
import { fetchScoreboard, fetchSummary } from "./espnClient";
import { mapEventToGame } from "./gameMapper";
import { computeWatchabilityScore, Tier, tierForScore } from "./rubric";

const DATA_DIR = join(__dirname, "..", "data");
const OUTPUT_PATH = join(DATA_DIR, "historicalWatchabilityWnba.json");

const REQUEST_DELAY_MS = 320; // ~3 requests/sec

interface SeasonWindow {
  label: string;
  start: string; // YYYY-MM-DD
  end: string; // YYYY-MM-DD
}

// Labeled by the single calendar year the season plays in (unlike NBA's
// "2024-25" cross-year convention) - a WNBA season never spans a year
// boundary. Window is deliberately wide (Apr 1 - Nov 30) the same way the
// NBA script's Oct1-Jun30 comfortably overshoots the real season on both
// ends - ESPN just returns no events for the empty months, at the cost of
// a handful of harmless extra scoreboard-day requests.
const SEASON_WINDOWS: SeasonWindow[] = [
  { label: "2024", start: "2024-04-01", end: "2024-11-30" },
  { label: "2025", start: "2025-04-01", end: "2025-11-30" },
];

export interface HistoricalGame {
  eventId: string;
  season: string;
  date: string; // tipoff UTC
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
  starPerformance: string | null;
  buzzerBeater: boolean;
  score: number;
  tier: Tier;
}

interface OutputFile {
  generatedAt: string;
  games: HistoricalGame[];
  completedDates: string[];
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function loadExisting(): OutputFile {
  if (!existsSync(OUTPUT_PATH)) return { generatedAt: new Date().toISOString(), games: [], completedDates: [] };
  try {
    return JSON.parse(readFileSync(OUTPUT_PATH, "utf8")) as OutputFile;
  } catch {
    return { generatedAt: new Date().toISOString(), games: [], completedDates: [] };
  }
}

function save(output: OutputFile): void {
  if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });
  output.generatedAt = new Date().toISOString();
  writeFileSync(OUTPUT_PATH, JSON.stringify(output, null, 2), "utf8");
}

/** A single flaky request shouldn't kill a long run - a few retries with backoff first. */
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

function printSummary(games: HistoricalGame[]): void {
  const tierCounts: Record<Tier, number> = { instant_classic: 0, worth_your_time: 0, solid: 0, skippable: 0 };
  for (const g of games) tierCounts[g.tier]++;

  console.log("\nTier breakdown:");
  for (const tier of Object.keys(tierCounts) as Tier[]) {
    const count = tierCounts[tier];
    const pct = games.length > 0 ? ((count / games.length) * 100).toFixed(1) : "0.0";
    console.log(`  ${tier.padEnd(16)} ${String(count).padStart(5)}  (${pct}%)`);
  }
}

async function main() {
  const output = loadExisting();
  const completedDates = new Set(output.completedDates);
  const gamesById = new Map(output.games.map((g) => [g.eventId, g]));

  const allDates = SEASON_WINDOWS.flatMap((w) =>
    dateStringsBetween(w.start, w.end).map((date) => ({ date, season: w.label }))
  );
  const totalDates = allDates.length;
  let processedDates = 0;

  for (const { date, season } of allDates) {
    if (completedDates.has(date)) {
      processedDates++;
      continue;
    }

    const yyyymmdd = date.replace(/-/g, "");
    const events = await withRetries(() => fetchScoreboard(yyyymmdd, "wnba"), `scoreboard ${date}`);
    await sleep(REQUEST_DELAY_MS);

    if (events) {
      for (const event of events) {
        if (event.season?.slug === "preseason") continue;
        if (event.competitions[0]?.status.type.state !== "post") continue;

        const summary = await withRetries(() => fetchSummary(event.id, "wnba"), `summary ${event.id}`);
        await sleep(REQUEST_DELAY_MS);
        if (!summary) continue;

        const mapped = mapEventToGame(event, "wnba", summary);
        if (mapped.status !== "final" || mapped.rubric.finalMargin == null) continue;

        const breakdown = computeWatchabilityScore(mapped.rubric, undefined, "wnba");
        const competition = event.competitions[0];
        const away = competition.competitors.find((c) => c.homeAway === "away")!;
        const home = competition.competitors.find((c) => c.homeAway === "home")!;

        gamesById.set(event.id, {
          eventId: event.id,
          season,
          date: mapped.tipoffUtc,
          away: mapped.away,
          home: mapped.home,
          awayScore: parseFloat(away.score),
          homeScore: parseFloat(home.score),
          finalMargin: mapped.rubric.finalMargin,
          largestDeficitOvercome: mapped.rubric.largestDeficitOvercome ?? 0,
          leadChanges: mapped.rubric.leadChanges ?? 0,
          overtimePeriods: mapped.rubric.overtimePeriods,
          closeInFinalTwoMin: mapped.rubric.closeInFinalTwoMin,
          leadChangeInFinalMin: mapped.rubric.leadChangeInFinalMin,
          decidedOnFinalPossession: mapped.rubric.decidedOnFinalPossession,
          starPerformance: mapped.rubric.starPerformance,
          buzzerBeater: mapped.rubric.buzzerBeater,
          score: breakdown.total,
          tier: tierForScore(breakdown.total),
        });
      }
    }

    completedDates.add(date);
    processedDates++;

    output.games = Array.from(gamesById.values());
    output.completedDates = Array.from(completedDates);
    save(output);

    if (processedDates % 10 === 0 || processedDates === totalDates) {
      console.log(`[${processedDates}/${totalDates} days] ${date} (${season}) - ${output.games.length} games scored so far`);
    }
  }

  console.log(`\nDone. ${output.games.length} games scored across ${SEASON_WINDOWS.map((w) => w.label).join(", ")}.`);
  printSummary(output.games);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
