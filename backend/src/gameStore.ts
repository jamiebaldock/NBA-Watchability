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
import { SoccerLeague } from "./soccerEspnClient";
import { GameStatus, LeagueGroup, SPORT_FOR_LEAGUE_GROUP, StarPerformance } from "./types";

// A game row's own "league" column is one specific ESPN league (basketball's
// "nba"/"nba-summer-*"/"wnba", or soccer's "eng.1"/"esp.1") - broader than
// the mutually-exclusive LeagueGroup a request is scoped to, narrower than
// "any string", so a typo'd league name still fails to compile.
export type AnyLeague = League | SoccerLeague;

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

// final_at/yt_published_at are the two anchors the self-tuning highlights
// schedule (below) learns from: the real-world gap between them, for every
// game a match was ever found for, is what "upload lag" actually means per
// league - not a guessed constant. yt_published_at (the matched video's own
// YouTube upload timestamp, from search.list's snippet.publishedAt) is real
// ground truth independent of our own check timing - unlike yt_found_at
// (when *we* happened to look and notice), which is what the lag used to be
// measured from and turned out to be contaminated by our own scheduling
// (see getLagPercentiles). yt_found_at is still recorded and still used to
// pace the (fixed, non-learned) first/second check spacing below, just no
// longer trusted as a measurement of true upload lag. yt_check_count runs
// 0 -> 1 -> 2: the first check fires a fixed short delay after the game
// goes final, a second (final) check 30 minutes later if the first missed,
// then permanently done - not an open-ended retry ladder.
ensureColumn("games", "final_at", "TEXT");
ensureColumn("games", "yt_found_at", "TEXT");
ensureColumn("games", "yt_published_at", "TEXT");
ensureColumn("games", "yt_check_count", "INTEGER NOT NULL DEFAULT 0");
// "{LEAGUE} - {stage}" (e.g. "NBA - Playoffs: Conference Semifinals") from
// gameMapper.ts's deriveCompetitionLabel - captured once per game (any
// status, not just final) since ESPN's season/notes data for a given event
// never changes after the fact, so there's no reason to wait. Backfilled
// rows from before this column existed are enriched by the one-off
// migrateStageLabels.ts script rather than left permanently null.
ensureColumn("games", "season_stage_label", "TEXT");

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
  league: AnyLeague;
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
  seasonStageLabel: string | null;
}

/** Creates the row if this eventId has never been seen before; a no-op otherwise - never touches an existing row's already-collected fields. */
export function upsertBaseEntry(entry: {
  eventId: string;
  league: AnyLeague;
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
  league: AnyLeague;
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
  season_stage_label: string | null;
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
    seasonStageLabel: raw.season_stage_label,
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
 * League + season-stage label ("NBA - Playoffs: Conference Semifinals") -
 * set once, permanent. Safe to call on every request that touches a game
 * (gamesService.ts does, regardless of status) since the guard makes every
 * call after the first a no-op.
 */
export function setSeasonStageLabel(eventId: string, label: string): void {
  db.prepare(`UPDATE games SET season_stage_label=?, updated_at=? WHERE event_id=? AND season_stage_label IS NULL`).run(
    label,
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

export interface SoccerFinalResult {
  awayScore: number;
  homeScore: number;
  score: number;
  tier: string;
}

/**
 * Soccer's equivalent of setFinalRubric - only touches the fields soccer
 * actually has (away/home score, the soccerRubric.ts total, and its tier).
 * Deliberately doesn't touch the basketball-shaped rubric columns (lead
 * changes, overtime periods, star performance, etc.) - those simply stay
 * NULL for a soccer row, which every reader already treats as "not
 * applicable"/"unknown" rather than a special case to guard against. Same
 * "never overwrite" WHERE-guard as setFinalRubric.
 */
export function setSoccerFinalRubric(eventId: string, result: SoccerFinalResult, finalAt: string | null = now()): void {
  db.prepare(
    `UPDATE games SET
       status='final', final_at=@finalAt,
       away_score=@awayScore, home_score=@homeScore, score=@score, tier=@tier,
       updated_at=@updatedAt
     WHERE event_id=@eventId AND score IS NULL`
  ).run({
    eventId,
    finalAt,
    awayScore: result.awayScore,
    homeScore: result.homeScore,
    score: result.score,
    tier: result.tier,
    updatedAt: now()
  });
}

/**
 * Highlights video found via a real search - set once, permanent. Records
 * yt_found_at (when *we* noticed - still used to pace check spacing) and
 * yt_published_at (the video's own real upload timestamp, real ground
 * truth - what getLagPercentiles now actually learns from). Manual/seeded
 * matches (highlightsSeed.ts) must go through setHighlightsFromSeed instead
 * - a human pasting a known-good link seconds after the game's row is
 * created isn't a real observation of upload lag, and folding it in here
 * would badly skew the learned schedule toward "videos appear almost
 * instantly," causing genuine future searches to check too early and waste
 * quota.
 */
export function setHighlights(eventId: string, videoId: string, publishedAt: string | null): void {
  const ts = now();
  db.prepare(
    `UPDATE games SET yt_video_id=?, yt_found_at=?, yt_published_at=?, updated_at=? WHERE event_id=? AND yt_video_id IS NULL`
  ).run(videoId, ts, publishedAt, ts, eventId);
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

// Sanity bounds on (final_at - tipoff_utc): a real game's whistle-to-whistle
// length rarely exceeds ~3 hours even with overtime, and final_at should
// land at or shortly after that (our own detection can lag the real buzzer
// somewhat, since it's demand-driven - see setFinalRubric). A gap outside
// this window means final_at almost certainly isn't a real per-game
// timestamp at all - it's a batch/redeploy artifact (a wide date-range
// fetch, a store migration, etc. touching a long-since-finished game for
// the first time and stamping "final now"). Confirmed directly this
// session: the WNBA full-season calendar-picker deploy and the SQLite
// store migration each produced dozens of games with final_at stamped
// within the same single minute, spanning real games from days to weeks
// apart - exactly the pattern this guard exists to exclude. A sample
// failing this check says nothing trustworthy about upload lag and must
// never enter the learned schedule.
const MIN_PLAUSIBLE_FINAL_GAP_MS = 60 * 60 * 1000; // 1h - shorter than any real game
const MAX_PLAUSIBLE_FINAL_GAP_MS = 6 * 60 * 60 * 1000; // 6h - generous margin for detection lag

function percentileOf(sortedMs: number[], p: number): number {
  const idx = Math.min(sortedMs.length - 1, Math.floor(p * sortedMs.length));
  return sortedMs[idx];
}

// Anchored to yt_published_at (the matched video's own real YouTube upload
// timestamp), not yt_found_at (when our own check schedule happened to
// look) - see the yt_published_at column comment above for why the latter
// was found to be untrustworthy. Also requires final_at to pass the
// plausibility guard above before a row counts as a sample at all.
function observedLagsMs(whereClause: string, param: string): number[] {
  const rows = db
    .prepare(
      `SELECT tipoff_utc, final_at, yt_published_at FROM games
       WHERE ${whereClause} AND final_at IS NOT NULL AND yt_published_at IS NOT NULL`
    )
    .all(param) as { tipoff_utc: string; final_at: string; yt_published_at: string }[];
  return rows
    .filter((r) => {
      const finalGapMs = new Date(r.final_at).getTime() - new Date(r.tipoff_utc).getTime();
      return finalGapMs >= MIN_PLAUSIBLE_FINAL_GAP_MS && finalGapMs <= MAX_PLAUSIBLE_FINAL_GAP_MS;
    })
    .map((r) => new Date(r.yt_published_at).getTime() - new Date(r.final_at).getTime())
    .sort((a, b) => a - b);
}

/**
 * Real, learned median upload lag for [league], falling back to
 * [leagueGroup]-wide data if that specific league doesn't have enough
 * samples yet, and to a hardcoded default if neither does. Informational
 * only as of the fixed-delay rewrite (gamesService.ts's
 * YT_FIRST_CHECK_DELAY_MS) - no longer gates when the first check fires,
 * since doing so was found to be self-reinforcing (a check that never
 * looks before its own current estimate can never learn a shorter one).
 * Kept for observability/reporting on real upload behavior per league.
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
 * row is actually due for its (fixed-delay) first or second check.
 */
export function getFinalGamesMissingHighlights(): GameRow[] {
  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  const raws = db.prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL`).all() as RawGameRow[];
  return raws
    .map(mapRow)
    .filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime)
    // No soccer YouTube channel is configured (youtubeClient.ts's search is
    // NBA/WNBA-channel-only) - without this guard, soccer rows would sit
    // here forever "missing highlights" and gamesService.ts's demand-driven
    // check would eventually search the *NBA* channel for an EPL matchup
    // (checkGameHighlights's HighlightsLeague derivation has no soccer
    // branch), wasting real search.list quota on a near-certain miss.
    .filter((row) => SPORT_FOR_LEAGUE_GROUP[row.leagueGroup] === "basketball");
}

/**
 * Rows still missing season_stage_label - backs migrateStageLabels.ts's
 * one-off enrichment pass for rows written before that column existed.
 * Summer League variants are excluded: deriveCompetitionLabel only accepts
 * league "nba"/"wnba", and Summer League tiles ignore this field entirely
 * (their own static client-side label takes over first), so there's
 * nothing to backfill for them.
 */
export function getGamesMissingStageLabel(): GameRow[] {
  const raws = db
    .prepare(`SELECT * FROM games WHERE season_stage_label IS NULL AND league IN ('nba', 'wnba')`)
    .all() as RawGameRow[];
  return raws.map(mapRow);
}

// Deliberately a raw score cutoff, not the "worth_your_time" tier's own
// >=65 boundary (rubric.ts's tierForScore) - the History tab wants a
// stricter bar than the tier badge itself uses, so a game can show a
// "Worth Your Time" tier badge elsewhere in the app without necessarily
// clearing History's own cutoff.
const HISTORY_MIN_SCORE = 70;

/**
 * Games scoring >= HISTORY_MIN_SCORE in [startDate, endDate] for
 * [leagueGroup], sorted score descending - backs the History tab.
 * Date-range filtering happens in JS on real Date objects (not a SQL
 * string comparison on tipoff_utc) - ESPN's timestamps omit
 * seconds/milliseconds when they're :00 (e.g. "T01:00Z" vs a boundary like
 * "T23:59:59.999Z"), and comparing ISO strings of different precision
 * isn't reliably correct at exact boundaries the way numeric
 * Date.getTime() comparison always is. The score/league filters alone are
 * cheap and exact in SQL, so only those are pushed down; the result set is
 * always small (a season's worth of games, at most).
 */
export function getWatchableHistory(startDate: string, endDate: string, leagueGroup: LeagueGroup): GameRow[] {
  const startTime = new Date(`${startDate}T00:00:00Z`).getTime();
  const endTime = new Date(`${endDate}T23:59:59.999Z`).getTime();
  const raws = db
    .prepare(`SELECT * FROM games WHERE score >= ? AND league_group = ?`)
    .all(HISTORY_MIN_SCORE, leagueGroup) as RawGameRow[];
  return raws
    .map(mapRow)
    .filter((row) => {
      const t = new Date(row.tipoffUtc).getTime();
      return t >= startTime && t <= endTime;
    })
    .sort((a, b) => (b.score ?? 0) - (a.score ?? 0));
}

export function earliestGameDate(leagueGroup?: LeagueGroup): string | undefined {
  const row = leagueGroup
    ? (db.prepare(`SELECT MIN(tipoff_utc) as earliest FROM games WHERE league_group = ?`).get(leagueGroup) as {
        earliest: string | null;
      })
    : (db.prepare(`SELECT MIN(tipoff_utc) as earliest FROM games`).get() as { earliest: string | null });
  return row.earliest?.slice(0, 10) ?? undefined;
}

/**
 * The most recently completed season's real Finals-end date (the last
 * "Playoffs: Finals"-stage game on record) - the season boundary per
 * James's rule: a season ends the moment its Finals conclude, and
 * everything after that (Summer League, preseason, the eventual regular
 * season) belongs to the next one, regardless of calendar date. Undefined
 * if no Finals game has been recorded yet for this league group (should
 * only happen against a brand-new, empty store).
 */
export function getMostRecentFinalsEnd(leagueGroup: LeagueGroup): string | undefined {
  const row = db
    .prepare(`SELECT MAX(tipoff_utc) as end FROM games WHERE league_group = ? AND season_stage_label LIKE '%Playoffs: Finals%'`)
    .get(leagueGroup) as { end: string | null };
  return row.end ?? undefined;
}

// The season immediately following [finalsEndTipoff]'s Finals - NBA's
// "ends in" convention labels a season by its start year (e.g. Finals in
// June 2026 conclude the "2025-26" season, so the next one is "2026-27",
// and finalsEndTipoff's own calendar year IS that next season's start
// year under this convention). WNBA never spans a year boundary, so its
// next season is simply next calendar year.
function upcomingSeasonLabel(finalsEndTipoff: string, leagueGroup: LeagueGroup): string {
  const finalsYear = new Date(finalsEndTipoff).getUTCFullYear();
  if (leagueGroup === "wnba") return String(finalsYear + 1);
  return `${finalsYear}-${String((finalsYear + 1) % 100).padStart(2, "0")}`;
}

// Soccer has no Finals/playoff stage to anchor a season boundary on (a
// domestic league season just ends when the last matchday is played, no
// separate bracket) - so unlike basketball's Finals-based rule below, this
// is a plain calendar-month cutoff, good enough since neither league's real
// fixtures ever land in the Jun-Jul off-season gap this straddles. Both
// eng.1 and esp.1 share the same Aug-May convention (confirmed against real
// ESPN data - e.g. esp.1's own season.slug reads "2024-25-laliga" for an
// August 2024 kickoff), so one function covers both leagueGroups.
const SOCCER_SEASON_START_MONTH = 7; // August, 0-indexed

function soccerSeasonStartYear(date: Date): number {
  const year = date.getUTCFullYear();
  return date.getUTCMonth() >= SOCCER_SEASON_START_MONTH ? year : year - 1;
}

function soccerSeasonLabelForTipoff(tipoffUtc: string): string {
  const startYear = soccerSeasonStartYear(new Date(tipoffUtc));
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}`;
}

/**
 * The latest already-final match on record for [leagueGroup] - soccer's
 * analogue of getMostRecentFinalsEnd. A domestic league has no Finals/
 * playoff stage, but its literal last matchday plays the exact same role:
 * the real-world marker of "the previous season is over."
 */
function getMostRecentSoccerGameEnd(leagueGroup: LeagueGroup): string | undefined {
  const row = db.prepare(`SELECT MAX(tipoff_utc) as end FROM games WHERE league_group = ? AND score IS NOT NULL`).get(
    leagueGroup
  ) as { end: string | null };
  return row.end ?? undefined;
}

/**
 * The day after the most recently completed match - soccer's equivalent of
 * getMostRecentFinalsEnd-anchored "This season", backing /current-season-start
 * for EPL/La Liga. Deliberately NOT "Aug 1 of soccerSeasonStartYear(now)" -
 * that plain calendar rule is correct for labeling a single game's own
 * season (soccerSeasonLabelForTipoff above), but wrong here: during the
 * Jun-Jul off-season gap, it would point "This season" backward at the
 * season that just finished (re-showing months of already-seen games)
 * instead of forward at the empty gap before the next one kicks off - the
 * same distinction basketball's Finals-anchored rule exists to make for
 * Summer League/preseason. Falls back to a plain Aug-1 guess only if the
 * store has no final soccer game for this leagueGroup yet (shouldn't
 * happen against a populated store).
 */
export function currentSoccerSeasonStartDate(leagueGroup: LeagueGroup, now: Date = new Date()): string {
  const lastGameEnd = getMostRecentSoccerGameEnd(leagueGroup);
  if (!lastGameEnd) return `${soccerSeasonStartYear(now)}-08-01`;
  const dayAfter = new Date(lastGameEnd);
  dayAfter.setUTCDate(dayAfter.getUTCDate() + 1);
  return dayAfter.toISOString().slice(0, 10);
}

// NBA's "ends in" convention (a season runs Oct 1 - Sep 30, labeled by its
// start year - e.g. a 2024-10-22 tipoff and a 2025-04-15 tipoff both label
// "2024-25") doesn't apply to WNBA, which never spans a year boundary - a
// WNBA season is just the calendar year it plays in, so the tipoff's own
// UTC year is the whole label. Either way, this Oct-1/plain-year math is
// only used for tipoffs at or before the most recently completed season's
// real Finals - anything strictly after that always gets the upcoming
// season's label instead, per James's rule that a season boundary is the
// moment the previous one's Finals conclude, not a fixed calendar date
// (this is what correctly puts NBA Summer League into the *next* season's
// label instead of the one that just ended). [finalsEnd] can be passed in
// by callers that already know it (e.g. looping over many rows) to avoid
// re-querying it on every call; callers that don't have it yet get a
// fresh lookup. Soccer leagueGroups are dispatched to their own
// Finals-free convention above before any of this basketball-specific
// logic runs.
export function seasonLabelForTipoff(
  tipoffUtc: string,
  leagueGroup: LeagueGroup,
  finalsEnd: string | undefined = getMostRecentFinalsEnd(leagueGroup)
): string {
  if (SPORT_FOR_LEAGUE_GROUP[leagueGroup] === "soccer") return soccerSeasonLabelForTipoff(tipoffUtc);
  if (finalsEnd && tipoffUtc > finalsEnd) {
    return upcomingSeasonLabel(finalsEnd, leagueGroup);
  }
  const date = new Date(tipoffUtc);
  const year = date.getUTCFullYear();
  if (leagueGroup === "wnba") return String(year);
  const startYear = date.getUTCMonth() >= 9 ? year : year - 1;
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}`;
}

// The nominal start date a seasonLabelForTipoff-style label itself begins
// on - the inverse of that function's own math (WNBA: Jan 1 of the label's
// year; NBA: Oct 1 of the label's start year; soccer: Aug 1 of the label's
// start year, no Finals-boundary concept to worry about).
function seasonStartDateForLabel(label: string, leagueGroup: LeagueGroup): string {
  if (leagueGroup === "wnba") return `${label}-01-01T00:00:00.000Z`;
  const startYear = label.slice(0, 4);
  if (SPORT_FOR_LEAGUE_GROUP[leagueGroup] === "soccer") return `${startYear}-08-01T00:00:00.000Z`;
  return `${startYear}-10-01T00:00:00.000Z`;
}

/**
 * Distinct season labels present in the backfill/live data for
 * [leagueGroup], newest first - backs the History tab's per-season filter
 * chips, so a newly-backfilled season shows up automatically with no code
 * change. Scoped by league_group (not the narrower "league" column, which
 * for NBA holds "nba"/"nba-summer-las-vegas"/etc separately, and for soccer
 * holds "eng.1"/"esp.1" rather than the leagueGroup string itself) - NBA
 * Summer League rows are additionally excluded by league name, since those
 * belong to the "This season" (current in-progress period) bucket the
 * client computes separately, not to a named season block. No equivalent
 * exclusion exists (or is needed) for soccer - eng.1/esp.1 never mix in a
 * non-season competition under the same league_group.
 *
 * The newest label is dropped entirely when it'd be redundant with "This
 * season": that only happens when the label represents a season that
 * genuinely started *after* the most recent Finals (i.e. real games for it
 * already exist) AND nothing real happened in the gap between Finals-end
 * and that season's own nominal start - e.g. WNBA's Finals conclude in
 * October, the next WNBA season doesn't have real games until the
 * following May, so right now "This season" (Finals+1 onward) and the
 * newest named year both cover the exact same games. This is a live check
 * against real data, not a hardcoded per-league rule, so it stops applying
 * on its own the moment something real (a Commissioner's Cup final,
 * preseason, etc.) actually lands in that gap - and it never triggers for
 * NBA today, since NBA's newest label ("2025-26") started *before* its own
 * Finals ended, not after.
 */
export function getSeasonLabels(leagueGroup: LeagueGroup): string[] {
  const rows = db
    .prepare(`SELECT tipoff_utc FROM games WHERE league_group = ? AND league NOT LIKE 'nba-summer-%'`)
    .all(leagueGroup) as {
    tipoff_utc: string;
  }[];
  const finalsEnd = getMostRecentFinalsEnd(leagueGroup);
  const labels = Array.from(new Set(rows.map((r) => seasonLabelForTipoff(r.tipoff_utc, leagueGroup, finalsEnd))))
    .sort()
    .reverse();

  if (labels.length === 0) return labels;

  // Soccer has no Finals to anchor the same dedup rule on, but its version
  // is actually simpler: there's no "gap" to check for real games in at
  // all, since a soccer leagueGroup's "This season" is just [Aug 1, today].
  // The newest label is redundant with that exactly when its own nominal
  // start hasn't arrived yet - e.g. right now, both eng.1 and esp.1 only
  // have a couple of real-but-still-upcoming 2026-27 fixtures on record
  // (touched while verifying the Games tab), which would otherwise leak in
  // as a selectable-but-permanently-empty "2026-27" chip alongside "This
  // season". Once that season's nominal start date actually arrives, this
  // stops dropping it on its own - no different from how NBA's equivalent
  // check already stops firing once real games land in its own gap.
  if (SPORT_FOR_LEAGUE_GROUP[leagueGroup] === "soccer") {
    const newestStartTime = new Date(seasonStartDateForLabel(labels[0], leagueGroup)).getTime();
    if (newestStartTime > Date.now()) labels.shift();
    return labels;
  }

  if (finalsEnd) {
    const newestStartTime = new Date(seasonStartDateForLabel(labels[0], leagueGroup)).getTime();
    const finalsEndTime = new Date(finalsEnd).getTime();
    if (newestStartTime > finalsEndTime) {
      const hasRealGapGames = rows.some((r) => {
        const t = new Date(r.tipoff_utc).getTime();
        return t > finalsEndTime && t < newestStartTime;
      });
      if (!hasRealGapGames) labels.shift();
    }
  }

  return labels;
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
