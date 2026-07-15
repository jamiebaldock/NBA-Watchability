// Durable, single-source-of-truth store for every game the app has ever
// seen - live schedule data and the historical backfill are the same thing
// now, not two systems. One row per ESPN eventId (already globally unique
// across leagues/sports - see gamesService.ts), accumulating fields
// monotonically through a game's lifecycle:
//
//   scheduled -> preview added (24h out) -> live -> final rubric added
//   -> highlights found -> "complete", never touched again
//
// Every setter below enforces "never overwrite" at the SQL layer itself
// (a `WHERE x IS NULL` guard), not just by convention at call sites - this
// is what actually prevents data loss from a re-run, a bug, or a race
// between two concurrent requests touching the same game, unlike the old
// cache.ts's whole-file read-modify-write (a known clobber risk, see the
// comment this replaced in devServer.ts).
//
// Lives on disk at DATA_DIR/games.db - DATA_DIR must point at a persistent
// Render Disk in production (set via the DATA_DIR env var) or every deploy
// wipes it exactly like the old ephemeral cache did. Defaults to backend/data
// for local dev, where losing it on a restart doesn't matter.
import Database from "better-sqlite3";
import { existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { League } from "./espnClient";
import { GameStatus, LeagueGroup, StarPerformance } from "./types";

const DATA_DIR = process.env.DATA_DIR ?? join(__dirname, "..", "data");
if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true });

const db = new Database(join(DATA_DIR, "games.db"));
db.pragma("journal_mode = WAL");

db.exec(`
  CREATE TABLE IF NOT EXISTS games (
    event_id TEXT PRIMARY KEY,
    league TEXT NOT NULL,
    league_group TEXT NOT NULL,
    away TEXT NOT NULL,
    home TEXT NOT NULL,
    away_logo TEXT,
    home_logo TEXT,
    tipoff_utc TEXT NOT NULL,
    status TEXT NOT NULL,

    hook TEXT,
    pitch TEXT,
    stakes INTEGER,

    away_score INTEGER,
    home_score INTEGER,
    final_margin INTEGER,
    largest_deficit_overcome INTEGER,
    lead_changes INTEGER,
    overtime_periods INTEGER,
    close_in_final_two_min INTEGER,
    lead_change_in_final_min INTEGER,
    decided_on_final_possession INTEGER,
    buzzer_beater INTEGER,
    star_performance TEXT,
    score INTEGER,
    tier TEXT,

    yt_video_id TEXT,
    yt_last_checked_at TEXT,

    updated_at TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_games_tipoff ON games(tipoff_utc);
  CREATE INDEX IF NOT EXISTS idx_games_tier_score ON games(tier, score);

  -- Hard ceiling on real YouTube search.list calls per day, independent of
  -- (and a backstop for) the per-game scheduling logic above - see
  -- canSpendSearchQuota below. Persisted, not in-memory, so it survives a
  -- restart mid-day instead of quietly resetting to 0 on every deploy.
  CREATE TABLE IF NOT EXISTS youtube_search_budget (
    date TEXT PRIMARY KEY,
    count INTEGER NOT NULL DEFAULT 0
  );
`);

// Added after the initial release - ensureColumn (not a second CREATE TABLE
// migration) so this is safe to run against both a brand-new DB (columns
// just won't already exist) and an already-populated one from before these
// existed, without needing a separate versioned-migration system.
function ensureColumn(table: string, column: string, definition: string): void {
  const existing = db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[];
  if (!existing.some((c) => c.name === column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
  }
}

// final_at/yt_found_at are the two anchors the self-tuning highlights
// schedule (below) learns from: the real-world gap between them, for every
// game a match was ever found for, is what "upload lag" actually means per
// league - not a guessed constant. yt_check_count runs 0 -> 1 -> 2: the
// first check fires at the league's learned p50 delay, a second (final)
// check 30 minutes later if the first missed, then permanently done - not
// an open-ended retry ladder.
ensureColumn("games", "final_at", "TEXT");
ensureColumn("games", "yt_found_at", "TEXT");
ensureColumn("games", "yt_check_count", "INTEGER NOT NULL DEFAULT 0");

function now(): string {
  return new Date().toISOString();
}

export interface FinalRubric {
  awayScore: number;
  homeScore: number;
  finalMargin: number;
  largestDeficitOvercome: number;
  leadChanges: number;
  overtimePeriods: number;
  closeInFinalTwoMin: boolean;
  leadChangeInFinalMin: boolean;
  decidedOnFinalPossession: boolean;
  buzzerBeater: boolean;
  starPerformance: StarPerformance;
  score: number;
  tier: string;
}

export interface GameRow {
  eventId: string;
  league: League;
  leagueGroup: LeagueGroup;
  away: string;
  home: string;
  awayLogo: string | null;
  homeLogo: string | null;
  tipoffUtc: string;
  status: GameStatus;
  hook: string | null;
  pitch: string | null;
  stakes: number | null;
  awayScore: number | null;
  homeScore: number | null;
  finalMargin: number | null;
  largestDeficitOvercome: number | null;
  leadChanges: number | null;
  overtimePeriods: number | null;
  closeInFinalTwoMin: number | null;
  leadChangeInFinalMin: number | null;
  decidedOnFinalPossession: number | null;
  buzzerBeater: number | null;
  starPerformance: StarPerformance;
  score: number | null;
  tier: string | null;
  finalAt: string | null;
  ytVideoId: string | null;
  ytLastCheckedAt: string | null;
  ytFoundAt: string | null;
  ytCheckCount: number;
}

/** Creates the row if this eventId has never been seen before; a no-op otherwise - never touches an existing row's already-collected fields. */
export function upsertBaseEntry(entry: {
  eventId: string;
  league: League;
  leagueGroup: LeagueGroup;
  away: string;
  home: string;
  awayLogo?: string;
  homeLogo?: string;
  tipoffUtc: string;
  status: GameStatus;
}): void {
  db.prepare(
    `INSERT OR IGNORE INTO games
       (event_id, league, league_group, away, home, away_logo, home_logo, tipoff_utc, status, updated_at)
     VALUES (@eventId, @league, @leagueGroup, @away, @home, @awayLogo, @homeLogo, @tipoffUtc, @status, @updatedAt)`
  ).run({
    eventId: entry.eventId,
    league: entry.league,
    leagueGroup: entry.leagueGroup,
    away: entry.away,
    home: entry.home,
    awayLogo: entry.awayLogo ?? null,
    homeLogo: entry.homeLogo ?? null,
    tipoffUtc: entry.tipoffUtc,
    status: entry.status,
    updatedAt: now(),
  });
}

/** Status changes freely (upcoming -> live -> final) until final, which is sticky - a completed game's status is never downgraded. */
export function updateStatus(eventId: string, status: GameStatus): void {
  db.prepare(`UPDATE games SET status=?, updated_at=? WHERE event_id=? AND status != 'final'`).run(
    status,
    now(),
    eventId
  );
}

// better-sqlite3 returns raw column names as-is (snake_case) - it does not
// auto-convert to camelCase, so every read has to go through this mapper
// rather than a bare `as GameRow` cast (a cast doesn't validate anything at
// runtime; without this, every field silently read as undefined).
interface RawGameRow {
  event_id: string;
  league: League;
  league_group: LeagueGroup;
  away: string;
  home: string;
  away_logo: string | null;
  home_logo: string | null;
  tipoff_utc: string;
  status: GameStatus;
  hook: string | null;
  pitch: string | null;
  stakes: number | null;
  away_score: number | null;
  home_score: number | null;
  final_margin: number | null;
  largest_deficit_overcome: number | null;
  lead_changes: number | null;
  overtime_periods: number | null;
  close_in_final_two_min: number | null;
  lead_change_in_final_min: number | null;
  decided_on_final_possession: number | null;
  buzzer_beater: number | null;
  star_performance: StarPerformance;
  score: number | null;
  tier: string | null;
  final_at: string | null;
  yt_video_id: string | null;
  yt_last_checked_at: string | null;
  yt_found_at: string | null;
  yt_check_count: number;
}

function mapRow(raw: RawGameRow): GameRow {
  return {
    eventId: raw.event_id,
    league: raw.league,
    leagueGroup: raw.league_group,
    away: raw.away,
    home: raw.home,
    awayLogo: raw.away_logo,
    homeLogo: raw.home_logo,
    tipoffUtc: raw.tipoff_utc,
    status: raw.status,
    hook: raw.hook,
    pitch: raw.pitch,
    stakes: raw.stakes,
    awayScore: raw.away_score,
    homeScore: raw.home_score,
    finalMargin: raw.final_margin,
    largestDeficitOvercome: raw.largest_deficit_overcome,
    leadChanges: raw.lead_changes,
    overtimePeriods: raw.overtime_periods,
    closeInFinalTwoMin: raw.close_in_final_two_min,
    leadChangeInFinalMin: raw.lead_change_in_final_min,
    decidedOnFinalPossession: raw.decided_on_final_possession,
    buzzerBeater: raw.buzzer_beater,
    starPerformance: raw.star_performance,
    score: raw.score,
    tier: raw.tier,
    finalAt: raw.final_at,
    ytVideoId: raw.yt_video_id,
    ytLastCheckedAt: raw.yt_last_checked_at,
    ytFoundAt: raw.yt_found_at,
    ytCheckCount: raw.yt_check_count,
  };
}

export function getGame(eventId: string): GameRow | undefined {
  const raw = db.prepare(`SELECT * FROM games WHERE event_id=?`).get(eventId) as RawGameRow | undefined;
  return raw ? mapRow(raw) : undefined;
}

/** Pregame preview (hook/pitch/stakes) - set once, 24h before tipoff, permanent. */
export function setPreview(eventId: string, hook: string, pitch: string, stakes: number): void {
  db.prepare(`UPDATE games SET hook=?, pitch=?, stakes=?, updated_at=? WHERE event_id=? AND hook IS NULL`).run(
    hook,
    pitch,
    stakes,
    now(),
    eventId
  );
}

/**
 * Final rubric + score/tier - set once, when the game goes final, permanent.
 * [finalAt] defaults to right now (a live game just went final) - the
 * migration script passes `null` explicitly instead, since a backfilled
 * historical game's true end time isn't known, and recording "whenever the
 * migration happened to run" as its anchor would silently corrupt the
 * per-league upload-lag stats (getLagPercentiles below) for any historical
 * game that later gets a highlights match found.
 */
export function setFinalRubric(eventId: string, rubric: FinalRubric, finalAt: string | null = now()): void {
  db.prepare(
    `UPDATE games SET
       status='final', final_at=@finalAt,
       away_score=@awayScore, home_score=@homeScore, final_margin=@finalMargin,
       largest_deficit_overcome=@largestDeficitOvercome, lead_changes=@leadChanges,
       overtime_periods=@overtimePeriods, close_in_final_two_min=@closeInFinalTwoMin,
       lead_change_in_final_min=@leadChangeInFinalMin, decided_on_final_possession=@decidedOnFinalPossession,
       buzzer_beater=@buzzerBeater, star_performance=@starPerformance, score=@score, tier=@tier,
       updated_at=@updatedAt
     WHERE event_id=@eventId AND score IS NULL`
  ).run({
    eventId,
    finalAt,
    awayScore: rubric.awayScore,
    homeScore: rubric.homeScore,
    finalMargin: rubric.finalMargin,
    largestDeficitOvercome: rubric.largestDeficitOvercome,
    leadChanges: rubric.leadChanges,
    overtimePeriods: rubric.overtimePeriods,
    closeInFinalTwoMin: rubric.closeInFinalTwoMin ? 1 : 0,
    leadChangeInFinalMin: rubric.leadChangeInFinalMin ? 1 : 0,
    decidedOnFinalPossession: rubric.decidedOnFinalPossession ? 1 : 0,
    buzzerBeater: rubric.buzzerBeater ? 1 : 0,
    starPerformance: rubric.starPerformance,
    score: rubric.score,
    tier: rubric.tier,
    updatedAt: now(),
  });
}

/**
 * Highlights video found via a real search - set once, permanent. Also
 * records yt_found_at, the raw data point getLagPercentiles learns from.
 * Manual/seeded matches (highlightsSeed.ts) must go through
 * setHighlightsFromSeed instead - a human pasting a known-good link seconds
 * after the game's row is created isn't a real observation of upload lag,
 * and folding it in here would badly skew the learned schedule toward
 * "videos appear almost instantly," causing genuine future searches to
 * check too early and waste quota.
 */
export function setHighlights(eventId: string, videoId: string): void {
  const ts = now();
  db.prepare(`UPDATE games SET yt_video_id=?, yt_found_at=?, updated_at=? WHERE event_id=? AND yt_video_id IS NULL`).run(
    videoId,
    ts,
    ts,
    eventId
  );
}

/** Manually-confirmed video (highlightsSeed.ts) - sets yt_video_id only, deliberately not yt_found_at (see setHighlights). */
export function setHighlightsFromSeed(eventId: string, videoId: string): void {
  db.prepare(`UPDATE games SET yt_video_id=?, updated_at=? WHERE event_id=? AND yt_video_id IS NULL`).run(
    videoId,
    now(),
    eventId
  );
}

/**
 * Records the single search attempt's timestamp and bumps the check count
 * regardless of outcome - this is what makes isDueForHighlightsCheck's
 * "check exactly once" rule stick (ytCheckCount > 0 blocks any further
 * attempt), never gates the precious yt_video_id itself.
 */
export function markHighlightsChecked(eventId: string, when: string): void {
  db.prepare(`UPDATE games SET yt_last_checked_at=?, yt_check_count = yt_check_count + 1, updated_at=? WHERE event_id=?`).run(
    when,
    now(),
    eventId
  );
}

export interface LagPercentiles {
  p50Ms: number;
  sampleCount: number;
  fromRealData: boolean;
}

// Bootstrap default for a league with no (or too little) real data yet -
// roughly matches the actual NBA-channel upload timings observed directly
// this session (most within an hour of game end). Every league starts here
// and graduates to its own real median as MIN_LAG_SAMPLES worth of matches
// get found.
const DEFAULT_P50_MS = 45 * 60 * 1000;
const MIN_LAG_SAMPLES = 5;

function percentileOf(sortedMs: number[], p: number): number {
  const idx = Math.min(sortedMs.length - 1, Math.floor(p * sortedMs.length));
  return sortedMs[idx];
}

function observedLagsMs(whereClause: string, param: string): number[] {
  const rows = db
    .prepare(`SELECT final_at, yt_found_at FROM games WHERE ${whereClause} AND final_at IS NOT NULL AND yt_found_at IS NOT NULL`)
    .all(param) as { final_at: string; yt_found_at: string }[];
  return rows.map((r) => new Date(r.yt_found_at).getTime() - new Date(r.final_at).getTime()).sort((a, b) => a - b);
}

/**
 * Real, learned median upload lag for [league], falling back to
 * [leagueGroup]-wide data if that specific league doesn't have enough
 * samples yet, and to a hardcoded default if neither does. This is what
 * lets the single highlights check land around each league's actual
 * observed delay instead of a guessed constant applied everywhere - and
 * scale to new leagues for free, since each one just starts on the default
 * and learns its own profile as it accumulates real matches. Only p50 is
 * tracked - the check is single-shot (isDueForHighlightsCheck), so there's
 * no retry ladder left to pace with p75/p90.
 */
export function getLagPercentiles(league: string, leagueGroup: string): LagPercentiles {
  let lags = observedLagsMs("league = ?", league);
  if (lags.length < MIN_LAG_SAMPLES) {
    lags = observedLagsMs("league_group = ?", leagueGroup);
  }
  if (lags.length < MIN_LAG_SAMPLES) {
    return { p50Ms: DEFAULT_P50_MS, sampleCount: lags.length, fromRealData: false };
  }
  return {
    p50Ms: percentileOf(lags, 0.5),
    sampleCount: lags.length,
    fromRealData: true,
  };
}

// A highlights link is only ever searched for recent games - the live app's
// realistic display window (Games/Starred tabs, roughly +/-9 days), with 14
// for margin. Historical/backfill games are deliberately never searched,
// regardless of tier or score: per product decision, the History tab is
// fine showing older Worth Your Time/Instant Classic games without a
// highlights link for now. An earlier version of this filter also included
// watchable-tier historical games (any age), which combined with a since-
// fixed persistent-disk gap to burn the entire shared 100/day YouTube quota
// in a single afternoon re-scanning the 2,650-game backfill on repeated
// server restarts - excluding historical games entirely removes that
// exposure completely rather than just shrinking it. Date comparison
// happens in JS (matching getWatchableHistory below), not a SQL string
// comparison on tipoff_utc, for the same mixed-ISO-precision reason.
const RECENT_GAMES_WINDOW_MS = 14 * 24 * 60 * 60 * 1000;

/**
 * Final games missing a highlights match that could actually be shown one -
 * always a small set in practice, so it's simplest and safest to
 * filter/decide scheduling in JS (matching the rest of the codebase's
 * style) rather than encode retry-interval math in SQL date functions. The
 * caller (gamesService.ts's isDueForHighlightsCheck) decides whether each
 * row is actually due for a check, against getLagPercentiles' learned
 * per-league schedule.
 */
export function getFinalGamesMissingHighlights(): GameRow[] {
  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  const raws = db.prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL`).all() as RawGameRow[];
  return raws.map(mapRow).filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime);
}

// Deliberately a raw score cutoff, not the "worth_your_time" tier's own
// >=65 boundary (rubric.ts's tierForScore) - the History tab wants a
// stricter bar than the tier badge itself uses, so a game can show a
// "Worth Your Time" tier badge elsewhere in the app without necessarily
// clearing History's own cutoff.
const HISTORY_MIN_SCORE = 70;

/**
 * Games scoring >= HISTORY_MIN_SCORE in [startDate, endDate], sorted score
 * descending - backs the History tab. Date-range filtering happens in JS on
 * real Date objects (not a SQL string comparison on tipoff_utc) - ESPN's
 * timestamps omit seconds/milliseconds when they're :00 (e.g. "T01:00Z" vs
 * a boundary like "T23:59:59.999Z"), and comparing ISO strings of different
 * precision isn't reliably correct at exact boundaries the way numeric
 * Date.getTime() comparison always is. The score filter alone is cheap and
 * exact in SQL, so only that's pushed down; the result set is always small
 * (a season's worth of games, at most).
 */
export function getWatchableHistory(startDate: string, endDate: string): GameRow[] {
  const startTime = new Date(`${startDate}T00:00:00Z`).getTime();
  const endTime = new Date(`${endDate}T23:59:59.999Z`).getTime();
  const raws = db.prepare(`SELECT * FROM games WHERE score >= ?`).all(HISTORY_MIN_SCORE) as RawGameRow[];
  return raws
    .map(mapRow)
    .filter((row) => {
      const t = new Date(row.tipoffUtc).getTime();
      return t >= startTime && t <= endTime;
    })
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
}

export function earliestGameDate(): string | undefined {
  const row = db.prepare(`SELECT MIN(tipoff_utc) as earliest FROM games`).get() as { earliest: string | null };
  return row.earliest?.slice(0, 10) ?? undefined;
}

// Same "ends in" convention as historicalWatchability.json's own season
// strings (a season runs Oct 1 - Sep 30, labeled by its start year) - e.g.
// a 2024-10-22 tipoff and a 2025-04-15 tipoff both label "2024-25".
function seasonLabelForTipoff(tipoffUtc: string): string {
  const date = new Date(tipoffUtc);
  const year = date.getUTCFullYear();
  const startYear = date.getUTCMonth() >= 9 ? year : year - 1;
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}`;
}

/**
 * Distinct season labels present in the backfill/live data, newest first -
 * backs the History tab's per-season filter chips, so a newly-backfilled
 * season shows up automatically with no code change. Only real NBA
 * season/playoff games count toward a season label (league='nba') - Summer
 * League and preseason games are deliberately excluded, since those belong
 * to the "This season" (current in-progress period) bucket the client
 * computes separately, not to a named season block.
 */
export function getSeasonLabels(): string[] {
  const rows = db.prepare(`SELECT tipoff_utc FROM games WHERE league = 'nba'`).all() as { tipoff_utc: string }[];
  const labels = new Set(rows.map((r) => seasonLabelForTipoff(r.tipoff_utc)));
  return Array.from(labels).sort().reverse();
}

// Conservative margin under YouTube's real 100/day search.list cap - not a
// precise mirror of it (this counter's day boundary is UTC midnight, not
// YouTube's actual midnight-Pacific reset), just enough headroom that
// clock-boundary drift between the two can never itself cause a real
// overage. This is deliberately a second, independent layer on top of the
// per-game scheduling in gamesService.ts (learned delay, 2-check cap,
// recent-games-only scope) - the thing that turned an earlier scope bug
// into a 22K-request storm was having no ceiling at all on total daily
// volume, only per-game pacing that assumed the scope was already correct.
const DAILY_SEARCH_BUDGET = 90;

function todayUtcKey(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Whether a real search.list call is still within today's budget - check before spending, not after. */
export function canSpendSearchQuota(): boolean {
  const row = db.prepare(`SELECT count FROM youtube_search_budget WHERE date = ?`).get(todayUtcKey()) as
    | { count: number }
    | undefined;
  return (row?.count ?? 0) < DAILY_SEARCH_BUDGET;
}

/** Records one spent search.list call against today's budget - call once per real request actually made, success or failure alike (a quota-exceeded response still counts against Google's own daily limit). */
export function recordSearchQuotaSpend(): void {
  db.prepare(
    `INSERT INTO youtube_search_budget (date, count) VALUES (?, 1)
     ON CONFLICT(date) DO UPDATE SET count = count + 1`
  ).run(todayUtcKey());
}

export function closeDb(): void {
  db.close();
}
