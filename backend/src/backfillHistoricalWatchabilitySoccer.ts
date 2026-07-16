// One-off analysis script (not part of the build/server) - scores every
// finished EPL (eng.1) and La Liga (esp.1) match from the last two
// completed seasons through the exact same soccerRubric.ts logic the live
// app uses, and writes the results to backend/data/historicalWatchabilityEpl.json
// and historicalWatchabilityLaLiga.json. Mirrors
// backfillHistoricalWatchabilityWnba.ts's shape (season windows, tier
// summary, resumable incremental writes), adapted for soccer:
//
// - Unlike the basketball backfills (which page through one scoreboard
//   request per day), a single wide-date-range scoreboard request with
//   &limit=500 returns an entire ~380-match season in one call (confirmed
//   directly against ESPN) - so this script only spends its request budget
//   on the per-match summary fetch (goals/box-score stats), not iterating
//   calendar days.
// - No preseason/playoff filtering needed - eng.1/esp.1 are each league's
//   own domestic-competition endpoint, which never mixes in friendlies or
//   cup matches (those live under separate ESPN competition ids), and
//   neither league has a playoff stage to exclude.
// - No stakes (LLM-derived, out of scope here - omitted stakes contributes
//   0 to the score, same convention as the basketball backfills).
// - Tier boundaries are reused as-is from rubric.ts's tierForScore (85/65/45)
//   rather than a soccer-specific scale - this is the same choice already
//   validated in soccerRubric.test.ts against the full 380-match 2025-26 EPL
//   season during the rubric design/calibration phase, not a new decision.
//
// Paced at ~3 summary requests/sec to stay well clear of ESPN rate limits.
// Writes incrementally (not just at the end), keyed by eventId, so an
// interrupted run can be re-launched and skips matches already scored.
//
// Run with: npx tsx src/backfillHistoricalWatchabilitySoccer.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Tier, tierForScore } from "./rubric";
import { fetchSoccerScoreboardRange, fetchSoccerSummary, SoccerLeague } from "./soccerEspnClient";
import { mapSoccerEventToGame } from "./soccerGameMapper";
import { computeSoccerWatchabilityScore } from "./soccerRubric";

const DATA_DIR = join(__dirname, "..", "data");
const REQUEST_DELAY_MS = 320; // ~3 requests/sec

interface SeasonWindow {
  label: string;
  start: string; // YYYY-MM-DD
  end: string; // YYYY-MM-DD
}

// Wide enough to comfortably cover either league's real Aug-May season on
// both ends (the actual matches are all well inside these bounds) - the
// wide-range scoreboard query costs nothing extra for the padding, unlike
// the basketball backfills' day-by-day iteration where padding means extra
// requests.
const SEASON_WINDOWS: SeasonWindow[] = [
  { label: "2024-25", start: "2024-08-01", end: "2025-06-30" },
  { label: "2025-26", start: "2025-08-01", end: "2026-06-30" },
];

interface LeagueConfig {
  league: SoccerLeague;
  outputFile: string;
}

const LEAGUE_CONFIGS: LeagueConfig[] = [
  { league: "eng.1", outputFile: "historicalWatchabilityEpl.json" },
  { league: "esp.1", outputFile: "historicalWatchabilityLaLiga.json" },
];

export interface HistoricalSoccerGame {
  eventId: string;
  season: string;
  date: string; // kickoff UTC
  away: string;
  home: string;
  awayLogo?: string;
  homeLogo?: string;
  awayScore: number;
  homeScore: number;
  score: number;
  tier: Tier;
}

interface OutputFile {
  generatedAt: string;
  games: HistoricalSoccerGame[];
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function toEspnDate(d: string): string {
  return d.replace(/-/g, "");
}

function loadExisting(outputPath: string): OutputFile {
  if (!existsSync(outputPath)) return { generatedAt: new Date().toISOString(), games: [] };
  try {
    return JSON.parse(readFileSync(outputPath, "utf8")) as OutputFile;
  } catch {
    return { generatedAt: new Date().toISOString(), games: [] };
  }
}

function save(outputPath: string, output: OutputFile): void {
  if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });
  output.generatedAt = new Date().toISOString();
  writeFileSync(outputPath, JSON.stringify(output, null, 2), "utf8");
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

function printSummary(label: string, games: HistoricalSoccerGame[]): void {
  const tierCounts: Record<Tier, number> = { instant_classic: 0, worth_your_time: 0, solid: 0, skippable: 0 };
  for (const g of games) tierCounts[g.tier]++;

  console.log(`\n${label} tier breakdown (${games.length} games):`);
  for (const tier of Object.keys(tierCounts) as Tier[]) {
    const count = tierCounts[tier];
    const pct = games.length > 0 ? ((count / games.length) * 100).toFixed(1) : "0.0";
    console.log(`  ${tier.padEnd(16)} ${String(count).padStart(5)}  (${pct}%)`);
  }
}

async function backfillLeague(config: LeagueConfig): Promise<HistoricalSoccerGame[]> {
  const outputPath = join(DATA_DIR, config.outputFile);
  const output = loadExisting(outputPath);
  const gamesById = new Map(output.games.map((g) => [g.eventId, g]));

  for (const window of SEASON_WINDOWS) {
    console.log(`\n[${config.league}] fetching ${window.label} scoreboard (${window.start} to ${window.end})...`);
    const events = await withRetries(
      () => fetchSoccerScoreboardRange(toEspnDate(window.start), toEspnDate(window.end), config.league, 500),
      `${config.league} ${window.label} scoreboard`
    );
    await sleep(REQUEST_DELAY_MS);

    if (!events) {
      console.error(`[${config.league}] could not fetch ${window.label} scoreboard, skipping season`);
      continue;
    }

    const finished = events.filter((e) => e.competitions[0]?.status.type.state === "post");
    console.log(`[${config.league}] ${window.label}: ${events.length} events, ${finished.length} finished`);

    let processed = 0;
    for (const event of finished) {
      processed++;
      if (gamesById.has(event.id)) continue;

      const summary = await withRetries(() => fetchSoccerSummary(event.id, config.league), `${config.league} summary ${event.id}`);
      await sleep(REQUEST_DELAY_MS);
      if (!summary) continue;

      const mapped = mapSoccerEventToGame(event, summary);
      const breakdown = computeSoccerWatchabilityScore(mapped.rubricInputs, undefined);

      gamesById.set(event.id, {
        eventId: event.id,
        season: window.label,
        date: event.date,
        away: mapped.away,
        home: mapped.home,
        awayLogo: mapped.awayLogo,
        homeLogo: mapped.homeLogo,
        awayScore: mapped.awayScore,
        homeScore: mapped.homeScore,
        score: breakdown.total,
        tier: tierForScore(breakdown.total),
      });

      if (processed % 20 === 0 || processed === finished.length) {
        output.games = Array.from(gamesById.values());
        save(outputPath, output);
        console.log(`[${config.league}] ${window.label}: ${processed}/${finished.length} matches scored so far`);
      }
    }

    output.games = Array.from(gamesById.values());
    save(outputPath, output);
  }

  return Array.from(gamesById.values());
}

async function main() {
  const allGames: HistoricalSoccerGame[] = [];
  for (const config of LEAGUE_CONFIGS) {
    const games = await backfillLeague(config);
    printSummary(config.league, games);
    allGames.push(...games);
  }
  printSummary("Combined (EPL + La Liga)", allGames);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
