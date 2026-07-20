// One-off analysis script (not part of the build/server) - pulls every
// finished NHL regular-season + postseason game from the last completed
// season straight off ESPN's free scoreboard/summary endpoints and writes
// RAW per-game facts (no rubric applied - there isn't one yet) to
// backend/data/nhlRawStats.json. This is Step 3 of
// docs/rubric-calibration-procedure.md.
//
// NHL's summary endpoint carries goal events in a flat top-level `plays[]`
// array (type.text === "Goal"), not a separate `scoringPlays[]` the way
// basketball/MLB/NFL each do - confirmed directly against a real response
// before writing this, not assumed from precedent. Each goal play carries a
// running awayScore/homeScore, period/clock, and a `strength` field
// (Even Strength/Power Play/Short Handed) plus `participants` (scorer +
// assisters by name) - everything needed to derive lead changes,
// largest-deficit-overcome, decisive-late-goal, combined power-play goals,
// and hat tricks from one pass over the same timeline, mirroring every other
// league's backfill approach.
//
// Regulation is 3 periods (not basketball's/football's 4) - confirmed via
// the summary's `format.regulation.periods` field. `linescores.length`
// (header competitor field) counts every played period including OT/SO, so
// `linescores.length - 3` is the real overtimePeriods count; a shootout
// specifically is detected via any goal-array-adjacent play at period.number
// > format.regulation.periods with displayValue "SO" (confirmed live: a
// 2025-11-15 NJ@WSH game that went to a shootout had linescores.length===5
// and real "Start of Shootout"/"End of Shootout" plays at period 5).
//
// Postseason round labels come from the competition's own `notes[].headline`
// field (e.g. "East 1st Round - Game 2", "West Final - Game 1", "Stanley Cup
// Final - Game 6") - confirmed live across multiple 2026 playoff rounds, the
// same mechanism basketball's own NBA Cup headline already uses, NOT a
// week-number scheme like NFL's (hockey's postseason has no equivalent
// numbered-week field). This is captured here so migrateToGameStore.ts's
// historical backfill can derive the exact same real per-round
// season_stage_label the live pipeline will (nhlGamesService.ts's
// deriveNhlCompetitionLabel), rather than every backfilled game getting one
// fixed label regardless of round - the exact bug NFL's build fixed
// same-day and NHL should never hit in the first place (see
// feedback_new_league_full_pipeline_checklist.md's "extra lesson for NHL").
//
// Paced at ~3 requests/sec. Writes incrementally after every date (not just
// at the end) and records which dates are already done, so an interrupted
// run can be re-launched and picks up where it left off.
//
// Run with: npx tsx src/backfillRawStatsNhl.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { dateStringsBetween } from "./dateRange";

const DATA_DIR = join(__dirname, "..", "data");
const OUTPUT_PATH = join(DATA_DIR, "nhlRawStats.json");
const REQUEST_DELAY_MS = 320; // ~3 requests/sec
const BASE_PATH = "https://site.api.espn.com/apis/site/v2/sports/hockey/nhl";

// 2025-26 is the most recently *completed* NHL season as of this run
// (2026-27 doesn't start until October) - regular season + postseason only.
// Confirmed directly: regular season opened 2025-10-07 (2025-09-20 events
// are preseason, season.slug "preseason", excluded), Stanley Cup Final Game
// 6 played 2026-06-14.
const SEASON_WINDOW = { label: "2025-26", start: "2025-09-25", end: "2026-06-20" };

interface EspnScoreboardEvent {
  id: string;
  date: string;
  season?: { type: number; slug: string };
  competitions: Array<{
    status: { type: { state: string } };
    notes?: Array<{ type: string; headline: string }>;
  }>;
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

interface EspnPlayParticipant {
  athlete: { displayName: string };
  type: string;
}

interface EspnPlay {
  type: { text: string };
  text: string;
  awayScore: number;
  homeScore: number;
  period: { number: number; displayValue: string };
  clock: { displayValue: string };
  strength?: { abbreviation: string };
  participants?: EspnPlayParticipant[];
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
  format?: { regulation?: { periods: number } };
  plays?: EspnPlay[];
  boxscore?: {
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

function teamStatValue(team: EspnTeamBoxscore, statName: string): number {
  const stat = team.statistics.find((s) => s.name === statName);
  if (!stat) return 0;
  return parseInt(stat.displayValue, 10) || 0;
}

/**
 * Walks the chronological goal-play timeline once, deriving lead changes,
 * largest-deficit-overcome, decisive-late-goal, and combined power-play
 * goals at the same time - same "single pass" approach every other league's
 * backfill in this codebase uses, off hockey's own play stream instead of
 * football/baseball's.
 */
function deriveTimelineFacts(
  goalPlays: EspnPlay[],
  awayWon: boolean,
  homeWon: boolean,
  regulationPeriods: number
): { leadChanges: number; largestDeficitOvercome: number; decisiveScoreLate: boolean; combinedPowerPlayGoals: number; maxGoalsByPlayer: number } {
  let lastRealLeader: "away" | "home" | null = null;
  let leadChanges = 0;
  let maxWinnerDeficit = 0;
  let lastFlip: { period: number; clockDisplay: string } | null = null;
  let combinedPowerPlayGoals = 0;
  const goalsByPlayer: Record<string, number> = {};

  for (const play of goalPlays) {
    const { awayScore: a, homeScore: h } = play;

    if (awayWon && h > a) maxWinnerDeficit = Math.max(maxWinnerDeficit, h - a);
    if (homeWon && a > h) maxWinnerDeficit = Math.max(maxWinnerDeficit, a - h);

    const currentLeader: "away" | "home" | null = a > h ? "away" : h > a ? "home" : null;
    if (currentLeader !== null && currentLeader !== lastRealLeader) {
      if (lastRealLeader !== null) leadChanges++;
      lastRealLeader = currentLeader;
      lastFlip = { period: play.period.number, clockDisplay: play.clock.displayValue };
    }

    if (play.strength?.abbreviation === "power-play") combinedPowerPlayGoals++;

    const scorer = play.participants?.find((p) => p.type === "scorer");
    if (scorer) goalsByPlayer[scorer.athlete.displayName] = (goalsByPlayer[scorer.athlete.displayName] ?? 0) + 1;
  }

  // "Late" = final 2 minutes of regulation's last period, or any point in OT/shootout -
  // same convention as NFL's decisiveScoreLate, adapted to hockey's mm:ss clock
  // (parsed from clock.displayValue "MM:SS" counting down to 0:00) rather than a
  // raw seconds-remaining field.
  const decisiveScoreLate =
    lastFlip !== null &&
    (lastFlip.period > regulationPeriods ||
      (lastFlip.period === regulationPeriods && clockDisplayToSeconds(lastFlip.clockDisplay) <= 120));

  const maxGoalsByPlayer = Object.values(goalsByPlayer).reduce((max, n) => Math.max(max, n), 0);

  return { leadChanges, largestDeficitOvercome: maxWinnerDeficit, decisiveScoreLate, combinedPowerPlayGoals, maxGoalsByPlayer };
}

function clockDisplayToSeconds(display: string): number {
  const [mm, ss] = display.split(":").map((n) => parseInt(n, 10) || 0);
  return mm * 60 + ss;
}

/** Best (lowest goals-against, among goalies who actually played meaningful minutes) single-goalie save total in the game - hockey's "star" signal alongside multi-goal skater games. */
function maxGoalieSaves(players: EspnPlayerBoxscore[]): number {
  let max = 0;
  for (const team of players) {
    const block = team.statistics.find((s) => s.name === "goalies");
    if (!block) continue;
    const savesIdx = block.keys.indexOf("saves");
    if (savesIdx === -1) continue;
    for (const athlete of block.athletes) {
      const saves = parseInt(athlete.stats[savesIdx] ?? "0", 10) || 0;
      max = Math.max(max, saves);
    }
  }
  return max;
}

export interface NhlRawGame {
  eventId: string;
  season: string;
  date: string;
  // ESPN's season type (2=regular, 3=postseason) plus, when postseason, the
  // real round headline (e.g. "West 1st Round", "Stanley Cup Final") stripped
  // of its "- Game N" suffix - captured so migrateToGameStore.ts's historical
  // backfill can derive the exact same real per-round season_stage_label the
  // live pipeline does.
  seasonType: number;
  playoffRoundLabel?: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  totalGoals: number;
  overtimePeriods: number;
  wentToShootout: boolean;
  leadChanges: number;
  largestDeficitOvercome: number;
  decisiveScoreLate: boolean;
  combinedPowerPlayGoals: number;
  maxGoalsByPlayer: number;
  maxGoalieSaves: number;
  teamShutout: boolean;
}

interface OutputFile {
  generatedAt: string;
  games: NhlRawGame[];
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

function derivePlayoffRoundLabel(notes: Array<{ type: string; headline: string }> | undefined): string | undefined {
  const headline = notes?.[0]?.headline;
  if (!headline) return undefined;
  return headline.replace(/\s*-\s*Game\s*\d+\s*$/i, "").trim();
}

export function mapGame(
  eventId: string,
  season: string,
  date: string,
  summary: EspnSummary,
  seasonType: number,
  playoffRoundLabel: string | undefined
): NhlRawGame | null {
  const competition = summary.header.competitions[0];
  if (!competition?.status.type.completed) return null;

  const away = competition.competitors.find((c) => c.homeAway === "away");
  const home = competition.competitors.find((c) => c.homeAway === "home");
  if (!away || !home) return null;

  const awayScore = parseFloat(away.score) || 0;
  const homeScore = parseFloat(home.score) || 0;
  const awayWon = away.winner === true;
  const homeWon = home.winner === true;
  const regulationPeriods = summary.format?.regulation?.periods ?? 3;
  const overtimePeriods = Math.max(0, (away.linescores?.length ?? regulationPeriods) - regulationPeriods);

  const allPlays = summary.plays ?? [];
  const goalPlays = allPlays.filter((p) => p.type?.text === "Goal");
  const wentToShootout = allPlays.some((p) => p.period?.displayValue === "SO");

  const { leadChanges, largestDeficitOvercome, decisiveScoreLate, combinedPowerPlayGoals, maxGoalsByPlayer } = deriveTimelineFacts(
    goalPlays,
    awayWon,
    homeWon,
    regulationPeriods
  );

  const players = summary.boxscore?.players ?? [];

  return {
    eventId,
    season,
    date,
    seasonType,
    playoffRoundLabel,
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    finalMargin: Math.abs(homeScore - awayScore),
    totalGoals: awayScore + homeScore,
    overtimePeriods,
    wentToShootout,
    leadChanges,
    largestDeficitOvercome,
    decisiveScoreLate,
    combinedPowerPlayGoals,
    maxGoalsByPlayer,
    maxGoalieSaves: maxGoalieSaves(players),
    teamShutout: awayScore === 0 || homeScore === 0
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
        if (event.season?.slug === "preseason") continue; // preseason excluded, same rule as every other league's backfill
        if (event.competitions[0]?.status.type.state !== "post") continue;

        const summary = await withRetries(() => fetchSummary(event.id), `summary ${event.id}`);
        await sleep(REQUEST_DELAY_MS);
        if (!summary) continue;

        const playoffRoundLabel = event.season?.type === 3 ? derivePlayoffRoundLabel(event.competitions[0]?.notes) : undefined;
        const mapped = mapGame(event.id, SEASON_WINDOW.label, event.date, summary, event.season?.type ?? 2, playoffRoundLabel);
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
