// One-off analysis script (not part of the build/server) - pulls every
// finished NFL regular-season + postseason game from the last completed
// season straight off ESPN's free scoreboard/summary endpoints and writes
// RAW per-game facts (no rubric applied - there isn't one yet) to
// backend/data/nflRawStats.json. This is Step 3 of
// docs/rubric-calibration-procedure.md: gathering real data to calibrate the
// NFL rubric's dimensions/brackets against, before any scoring code gets
// written.
//
// scoringPlays gives a running away/home score after every scoring event
// (period + clock included), which is everything needed to derive lead
// changes, largest-deficit-overcome, and "did the decisive go-ahead score
// happen late" - the same play-by-play-derived approach the NBA/soccer
// backfills use, just off football's own event stream instead of basketball
// possessions or baseball innings.
//
// Paced at ~3 requests/sec. Writes incrementally after every date (not just
// at the end) and records which dates are already done, so an interrupted
// run can be re-launched and picks up where it left off.
//
// Run with: npx tsx src/backfillRawStatsNfl.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { dateStringsBetween } from "./dateRange";

const DATA_DIR = join(__dirname, "..", "data");
const OUTPUT_PATH = join(DATA_DIR, "nflRawStats.json");
const REQUEST_DELAY_MS = 320; // ~3 requests/sec
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/football/nfl";

// 2025 is the most recently *completed* NFL season as of this run (2026
// season doesn't start until September) - regular season + postseason only,
// window overshoots on both ends the same way every other backfill script's
// does; ESPN just returns no events for the empty days. Confirmed directly:
// regular season opened 2025-09-04, Super Bowl LX played 2026-02-08.
const SEASON_WINDOW = { label: "2025", start: "2025-08-25", end: "2026-02-15" };

interface EspnScoreboardEvent {
  id: string;
  date: string;
  season?: { type: number; slug: string };
  // Only meaningful when season.type===3 (postseason) - reliably
  // distinguishes each round (1=Wild Card ... 5=Super Bowl), confirmed
  // directly against real data. Captured here so the historical backfill
  // can derive the exact same real per-round season_stage_label
  // (nflGamesService.ts's deriveNflCompetitionLabel) the live pipeline
  // does, instead of every backfilled game getting the same generic
  // "Regular Season" label regardless of postseason status - which would
  // make the Super Bowl (the one game gameStore.ts's
  // getMostRecentFinalsEnd needs to find the real season boundary)
  // indistinguishable from a Week 1 game.
  week?: { number: number };
  competitions: Array<{ status: { type: { state: string } } }>;
}

interface EspnLinescore {
  displayValue: string;
}

interface EspnHeaderCompetitor {
  team: { displayName: string };
  homeAway: "home" | "away";
  score: string;
  winner?: boolean;
  linescores?: EspnLinescore[];
}

interface EspnScoringPlay {
  awayScore: number;
  homeScore: number;
  period: { number: number };
  clock: { value: number };
}

interface EspnTeamStat {
  name: string;
  displayValue: string;
}

interface EspnTeamBoxscore {
  team: { displayName: string };
  statistics: EspnTeamStat[];
}

interface EspnPlayerStatBlock {
  name: string;
  keys: string[];
  athletes: Array<{ athlete: { displayName: string }; stats: string[] }>;
}

interface EspnPlayerBoxscore {
  team: { displayName: string };
  statistics: EspnPlayerStatBlock[];
}

interface EspnSummary {
  header: {
    competitions: Array<{
      status: { type: { completed: boolean } };
      competitors: EspnHeaderCompetitor[];
    }>;
  };
  scoringPlays?: EspnScoringPlay[];
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

// Always reads displayValue (parsed), never the parallel `value` field -
// confirmed directly against a real response that `value` is the literal
// string "-" for several simple single-number stats (turnovers included,
// the one this script actually needs), not just for the compound "X-Y"
// fields where that'd be expected. Same "don't trust the field name/shape
// at face value" lesson as MLB's stats-leader displayValue bug.
function teamStatValue(team: EspnTeamBoxscore, statName: string): number {
  const stat = team.statistics.find((s) => s.name === statName);
  if (!stat) return 0;
  return parseInt(stat.displayValue, 10) || 0;
}

/** Sum of a single stat key across every athlete in one team's stat block (e.g. every rushing back's touchdowns). */
function sumPlayerStat(block: EspnPlayerStatBlock | undefined, keyName: string): Record<string, number> {
  const result: Record<string, number> = {};
  if (!block) return result;
  const idx = block.keys.indexOf(keyName);
  if (idx === -1) return result;
  for (const athlete of block.athletes) {
    const raw = athlete.stats[idx];
    const n = parseInt(raw ?? "0", 10) || 0;
    if (n > 0) result[athlete.athlete.displayName] = (result[athlete.athlete.displayName] ?? 0) + n;
  }
  return result;
}

function maxPassingOrRushingYards(players: EspnPlayerBoxscore[], blockName: "passing" | "rushing", yardsKey: string): number {
  let max = 0;
  for (const team of players) {
    const block = team.statistics.find((s) => s.name === blockName);
    if (!block) continue;
    const idx = block.keys.indexOf(yardsKey);
    if (idx === -1) continue;
    for (const athlete of block.athletes) {
      const yards = parseInt(athlete.stats[idx] ?? "0", 10) || 0;
      max = Math.max(max, yards);
    }
  }
  return max;
}

/** Most combined TDs (passing+rushing+receiving) by one single player, matching by display name across all three stat blocks. */
function maxTotalTdsByPlayer(players: EspnPlayerBoxscore[]): number {
  let max = 0;
  for (const team of players) {
    const passingTds = sumPlayerStat(
      team.statistics.find((s) => s.name === "passing"),
      "passingTouchdowns"
    );
    const rushingTds = sumPlayerStat(
      team.statistics.find((s) => s.name === "rushing"),
      "rushingTouchdowns"
    );
    const receivingTds = sumPlayerStat(
      team.statistics.find((s) => s.name === "receiving"),
      "receivingTouchdowns"
    );
    const byPlayer: Record<string, number> = {};
    for (const [name, n] of Object.entries(passingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const [name, n] of Object.entries(rushingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const [name, n] of Object.entries(receivingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const n of Object.values(byPlayer)) {
      if (n > max) max = n;
    }
  }
  return max;
}

/**
 * Walks the chronological scoringPlays timeline once, deriving three facts
 * at the same time (same "single pass over the timeline" approach the
 * MLB/soccer backfills use for their own comeback/lead-change math):
 * - leadChanges: how many times the real (non-tied) leader actually swaps
 *   from one team to the other - a play that only ties the game doesn't
 *   count by itself, only the next play that actually puts one team ahead
 *   again counts, and only if it's a *different* team than the last real
 *   leader.
 * - largestDeficitOvercome: the eventual winner's own worst deficit at any
 *   point in the timeline (0 if they were never behind, or if the game
 *   ended tied - a real, if rare, NFL outcome).
 * - decisiveScoreLate: true only if the *last* real lead-change of the
 *   entire game (the one that turned out to be permanent, since nothing
 *   overturns it afterward) happened in the final 2 minutes of regulation
 *   or in overtime - deliberately not just "was the last point of the game
 *   scored late," which would wrongly flag a garbage-time field goal by the
 *   trailing team in a blowout.
 */
function deriveTimelineFacts(
  scoringPlays: EspnScoringPlay[],
  awayWon: boolean,
  homeWon: boolean
): { leadChanges: number; largestDeficitOvercome: number; decisiveScoreLate: boolean } {
  let lastRealLeader: "away" | "home" | null = null;
  let leadChanges = 0;
  let maxWinnerDeficit = 0;
  let lastFlip: { period: number; clockValue: number } | null = null;

  for (const play of scoringPlays) {
    const { awayScore: a, homeScore: h } = play;

    if (awayWon && h > a) maxWinnerDeficit = Math.max(maxWinnerDeficit, h - a);
    if (homeWon && a > h) maxWinnerDeficit = Math.max(maxWinnerDeficit, a - h);

    const currentLeader: "away" | "home" | null = a > h ? "away" : h > a ? "home" : null;
    if (currentLeader !== null && currentLeader !== lastRealLeader) {
      if (lastRealLeader !== null) leadChanges++;
      lastRealLeader = currentLeader;
      lastFlip = { period: play.period.number, clockValue: play.clock.value };
    }
  }

  const decisiveScoreLate = lastFlip !== null && (lastFlip.period > 4 || (lastFlip.period === 4 && lastFlip.clockValue <= 120));

  return { leadChanges, largestDeficitOvercome: maxWinnerDeficit, decisiveScoreLate };
}

export interface NflRawGame {
  eventId: string;
  season: string;
  date: string;
  // ESPN's season type (2=regular, 3=postseason) and, when postseason, the
  // week number that distinguishes each round - captured so
  // migrateToGameStore.ts's historical backfill can derive the exact same
  // real per-round season_stage_label the live pipeline does
  // (nflGamesService.ts's deriveNflCompetitionLabel), rather than every
  // backfilled game getting the same generic label regardless of
  // postseason status.
  seasonType: number;
  weekNumber?: number;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  totalPoints: number;
  overtimePeriods: number;
  leadChanges: number;
  largestDeficitOvercome: number;
  decisiveScoreLate: boolean;
  combinedTurnovers: number;
  defensiveOrSpecialTeamsTd: boolean;
  maxPassingYards: number;
  maxRushingYards: number;
  maxTotalTdsByPlayer: number;
}

interface OutputFile {
  generatedAt: string;
  games: NflRawGame[];
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

export function mapGame(
  eventId: string,
  season: string,
  date: string,
  summary: EspnSummary,
  seasonType: number,
  weekNumber: number | undefined
): NflRawGame | null {
  const competition = summary.header.competitions[0];
  if (!competition?.status.type.completed) return null;

  const away = competition.competitors.find((c) => c.homeAway === "away");
  const home = competition.competitors.find((c) => c.homeAway === "home");
  if (!away || !home) return null;

  const awayScore = parseFloat(away.score) || 0;
  const homeScore = parseFloat(home.score) || 0;
  const awayWon = away.winner === true;
  const homeWon = home.winner === true;
  const overtimePeriods = Math.max(0, (away.linescores?.length ?? 4) - 4);

  const scoringPlays = summary.scoringPlays ?? [];
  const { leadChanges, largestDeficitOvercome, decisiveScoreLate } = deriveTimelineFacts(scoringPlays, awayWon, homeWon);

  const boxTeams = summary.boxscore?.teams ?? [];
  const awayBox = boxTeams.find((t) => t.team.displayName === away.team.displayName);
  const homeBox = boxTeams.find((t) => t.team.displayName === home.team.displayName);
  const combinedTurnovers = (awayBox ? teamStatValue(awayBox, "turnovers") : 0) + (homeBox ? teamStatValue(homeBox, "turnovers") : 0);
  const defensiveOrSpecialTeamsTd =
    (awayBox ? teamStatValue(awayBox, "defensiveTouchdowns") : 0) > 0 || (homeBox ? teamStatValue(homeBox, "defensiveTouchdowns") : 0) > 0;

  const players = summary.boxscore?.players ?? [];

  return {
    eventId,
    season,
    date,
    seasonType,
    weekNumber,
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    finalMargin: Math.abs(homeScore - awayScore),
    totalPoints: awayScore + homeScore,
    overtimePeriods,
    leadChanges,
    largestDeficitOvercome,
    decisiveScoreLate,
    combinedTurnovers,
    defensiveOrSpecialTeamsTd,
    maxPassingYards: maxPassingOrRushingYards(players, "passing", "passingYards"),
    maxRushingYards: maxPassingOrRushingYards(players, "rushing", "rushingYards"),
    maxTotalTdsByPlayer: maxTotalTdsByPlayer(players)
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
        if (event.season?.type === 1) continue; // preseason excluded, same rule as every other league's backfill
        if (event.competitions[0]?.status.type.state !== "post") continue;

        const summary = await withRetries(() => fetchSummary(event.id), `summary ${event.id}`);
        await sleep(REQUEST_DELAY_MS);
        if (!summary) continue;

        const mapped = mapGame(event.id, SEASON_WINDOW.label, event.date, summary, event.season?.type ?? 2, event.week?.number);
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
