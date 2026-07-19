// One-off analysis script (not part of the build/server) - pulls every
// finished MLB regular-season game from the last completed season straight
// off ESPN's free scoreboard/summary endpoints and writes RAW per-game
// facts (no rubric applied - there isn't one yet) to
// backend/data/mlbRawStats.json. This is Step 3 of
// docs/rubric-calibration-procedure.md: gathering real data to calibrate
// the MLB rubric's dimensions/brackets against, before any scoring code
// gets written.
//
// Unlike the NBA/WNBA backfill scripts, this doesn't need play-by-play
// (`plays[]`) at all - every fact below (margin, comeback, walk-off,
// extra innings, home runs, no-hitter/perfect game, errors) is derivable
// from the box score's team-level stats and per-inning linescores alone,
// confirmed directly against a real ESPN summary response before writing
// this.
//
// Paced at ~3 requests/sec. Writes incrementally after every date (not
// just at the end) and records which dates are already done, so an
// interrupted run can be re-launched and picks up where it left off.
//
// Run with: npx tsx src/backfillRawStatsMlb.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { dateStringsBetween } from "./dateRange";

const DATA_DIR = join(__dirname, "..", "data");
const OUTPUT_PATH = join(DATA_DIR, "mlbRawStats.json");
const REQUEST_DELAY_MS = 320; // ~3 requests/sec
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/baseball/mlb";

// 2025 is the most recently *completed* MLB season as of this run (2026
// season is in progress) - regular season only, window overshoots on both
// ends the same way the NBA/WNBA scripts' windows do; ESPN just returns no
// events for the empty days.
const SEASON_WINDOW = { label: "2025", start: "2025-02-15", end: "2025-11-15" };

interface EspnScoreboardEvent {
  id: string;
  date: string;
  season?: { type: number; slug: string };
  competitions: Array<{ status: { type: { state: string } } }>;
}

interface EspnLinescore {
  displayValue: string;
  hits: number;
  errors: number;
}

interface EspnStatValue {
  name: string;
  value?: number;
  displayValue: string;
}

interface EspnTeamBoxscore {
  team: { abbreviation: string; displayName: string };
  homeAway: "home" | "away";
  statistics: Array<{ name: string; stats: EspnStatValue[] }>;
}

interface EspnPlayerBattingBlock {
  type?: string;
  keys: string[];
  athletes: Array<{ stats: string[] }>;
}

interface EspnPlayerBoxscore {
  team: { abbreviation: string };
  statistics: EspnPlayerBattingBlock[];
}

interface EspnSummary {
  header: {
    competitions: Array<{
      status: { type: { completed: boolean } };
      competitors: Array<{ team: { abbreviation: string }; homeAway: "home" | "away"; winner?: boolean; linescores?: EspnLinescore[] }>;
    }>;
  };
  boxscore: {
    teams: EspnTeamBoxscore[];
    players: EspnPlayerBoxscore[];
  };
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`ESPN request failed: ${res.status} ${res.statusText} (${url})`);
  return (await res.json()) as T;
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

function statValue(block: EspnTeamBoxscore["statistics"], groupName: string, statName: string): number {
  const group = block.find((b) => b.name === groupName);
  const stat = group?.stats.find((s) => s.name === statName);
  return stat?.value ?? 0;
}

function maxHomeRunsByPlayer(players: EspnPlayerBoxscore[]): number {
  let max = 0;
  for (const team of players) {
    const battingBlock = team.statistics.find((s) => s.keys?.includes("homeRuns"));
    if (!battingBlock) continue;
    const hrIndex = battingBlock.keys.indexOf("homeRuns");
    for (const athlete of battingBlock.athletes) {
      const hr = parseInt(athlete.stats[hrIndex] ?? "0", 10) || 0;
      max = Math.max(max, hr);
    }
  }
  return max;
}

/**
 * Largest deficit the eventual winner ever faced, reconstructed from both
 * teams' per-inning linescores (cumulative running totals compared inning
 * by inning) - same "comeback" concept as the basketball/soccer rubrics,
 * just derived from box-score linescores instead of play-by-play.
 */
function largestDeficitOvercome(awayInnings: number[], homeInnings: number[], awayWon: boolean): number {
  const maxLen = Math.max(awayInnings.length, homeInnings.length);
  let awayCum = 0;
  let homeCum = 0;
  let maxDeficit = 0;
  for (let i = 0; i < maxLen; i++) {
    awayCum += awayInnings[i] ?? 0;
    homeCum += homeInnings[i] ?? 0;
    // Winner's deficit at this point, if any (positive = winner was behind).
    const winnerDeficit = awayWon ? homeCum - awayCum : awayCum - homeCum;
    if (winnerDeficit > maxDeficit) maxDeficit = winnerDeficit;
  }
  return maxDeficit;
}

/**
 * True when the home team was tied or trailing entering their final
 * plate-appearance inning and won during it - the game-ends-the-instant-
 * the-run-scores rule, so a walk-off can't be detected just from final
 * score/margin, only from comparing the score immediately before that last
 * inning to the final score.
 */
function isWalkOff(awayInnings: number[], homeInnings: number[], homeWon: boolean, awayFinal: number): boolean {
  if (!homeWon || homeInnings.length === 0) return false;
  const homeCumBeforeLastInning = homeInnings.slice(0, -1).reduce((a, b) => a + b, 0);
  return homeCumBeforeLastInning <= awayFinal;
}

export interface MlbRawGame {
  eventId: string;
  season: string;
  date: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  totalRuns: number;
  inningsPlayed: number;
  extraInningsCount: number;
  largestDeficitOvercome: number;
  walkOff: boolean;
  combinedHomeRuns: number;
  maxHomeRunsByPlayer: number;
  noHitter: boolean;
  perfectGame: boolean;
  shutout: boolean;
  blownSave: boolean;
  combinedErrors: number;
}

interface OutputFile {
  generatedAt: string;
  games: MlbRawGame[];
  completedDates: string[];
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

async function fetchScoreboard(yyyymmdd: string): Promise<EspnScoreboardEvent[]> {
  const data = await getJson<{ events: EspnScoreboardEvent[] }>(`${BASE_PATH}/scoreboard?dates=${yyyymmdd}`);
  return data.events ?? [];
}

async function fetchSummary(eventId: string): Promise<EspnSummary> {
  return getJson<EspnSummary>(`${BASE_PATH}/summary?event=${eventId}`);
}

function mapGame(eventId: string, season: string, date: string, summary: EspnSummary): MlbRawGame | null {
  const competition = summary.header.competitions[0];
  if (!competition?.status.type.completed) return null;

  const awayComp = competition.competitors.find((c) => c.homeAway === "away");
  const homeComp = competition.competitors.find((c) => c.homeAway === "home");
  const awayBox = summary.boxscore.teams.find((t) => t.homeAway === "away");
  const homeBox = summary.boxscore.teams.find((t) => t.homeAway === "home");
  if (!awayComp || !homeComp || !awayBox || !homeBox) return null;

  const awayInnings = (awayComp.linescores ?? []).map((l) => parseInt(l.displayValue, 10) || 0);
  const homeInnings = (homeComp.linescores ?? []).map((l) => parseInt(l.displayValue, 10) || 0);
  const awayScore = statValue(awayBox.statistics, "batting", "runs");
  const homeScore = statValue(homeBox.statistics, "batting", "runs");
  const awayWon = awayComp.winner === true;
  const homeWon = homeComp.winner === true;

  return {
    eventId,
    season,
    date,
    away: awayBox.team.displayName,
    home: homeBox.team.displayName,
    awayScore,
    homeScore,
    finalMargin: Math.abs(homeScore - awayScore),
    totalRuns: awayScore + homeScore,
    inningsPlayed: Math.max(awayInnings.length, homeInnings.length),
    extraInningsCount: Math.max(0, Math.max(awayInnings.length, homeInnings.length) - 9),
    largestDeficitOvercome: largestDeficitOvercome(awayInnings, homeInnings, awayWon),
    walkOff: isWalkOff(awayInnings, homeInnings, homeWon, awayScore),
    combinedHomeRuns: statValue(awayBox.statistics, "batting", "homeRuns") + statValue(homeBox.statistics, "batting", "homeRuns"),
    maxHomeRunsByPlayer: maxHomeRunsByPlayer(summary.boxscore.players),
    noHitter: statValue(awayBox.statistics, "pitching", "hits") === 0 || statValue(homeBox.statistics, "pitching", "hits") === 0,
    perfectGame: statValue(awayBox.statistics, "pitching", "perfectGames") > 0 || statValue(homeBox.statistics, "pitching", "perfectGames") > 0,
    shutout: statValue(awayBox.statistics, "pitching", "shutouts") > 0 || statValue(homeBox.statistics, "pitching", "shutouts") > 0,
    blownSave: statValue(awayBox.statistics, "pitching", "blownSaves") > 0 || statValue(homeBox.statistics, "pitching", "blownSaves") > 0,
    combinedErrors: statValue(awayBox.statistics, "fielding", "errors") + statValue(homeBox.statistics, "fielding", "errors"),
  };
}

async function main() {
  const output = loadExisting();
  const completedDates = new Set(output.completedDates);
  const gamesById = new Map(output.games.map((g) => [g.eventId, g]));

  const allDates = dateStringsBetween(SEASON_WINDOW.start, SEASON_WINDOW.end);
  const totalDates = allDates.length;
  let processedDates = 0;

  for (const date of allDates) {
    if (completedDates.has(date)) {
      processedDates++;
      continue;
    }

    const yyyymmdd = date.replace(/-/g, "");
    const events = await withRetries(() => fetchScoreboard(yyyymmdd), `scoreboard ${date}`);
    await sleep(REQUEST_DELAY_MS);

    if (events) {
      for (const event of events) {
        if (event.season?.slug === "preseason" || event.season?.type === 1) continue;
        if (event.season?.slug === "off-season") continue;
        if (event.competitions[0]?.status.type.state !== "post") continue;

        const summary = await withRetries(() => fetchSummary(event.id), `summary ${event.id}`);
        await sleep(REQUEST_DELAY_MS);
        if (!summary) continue;

        const mapped = mapGame(event.id, SEASON_WINDOW.label, event.date, summary);
        if (mapped) gamesById.set(event.id, mapped);
      }
    }

    completedDates.add(date);
    processedDates++;

    output.games = Array.from(gamesById.values());
    output.completedDates = Array.from(completedDates);
    save(output);

    if (processedDates % 10 === 0 || processedDates === totalDates) {
      console.log(`[${processedDates}/${totalDates} days] ${date} - ${output.games.length} games collected so far`);
    }
  }

  console.log(`\nDone. ${output.games.length} games collected for ${SEASON_WINDOW.label}.`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
