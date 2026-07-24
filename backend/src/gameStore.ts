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
import { MlbRubricInputs } from "./mlbRubric";
import { NflRubricInputs } from "./nflRubric";
import { NhlRubricInputs } from "./nhlRubric";
import { GameStatus, LeagueGroup, SPORT_FOR_LEAGUE_GROUP, StandoutPerformerJson, StarPerformance } from "./types";

// A game row's own "league" column is one specific ESPN league (basketball's
// "nba"/"nba-summer-*"/"wnba", MLB's single "mlb", or NFL's single "nfl") -
// broader than the mutually-exclusive LeagueGroup a request is scoped to,
// narrower than "any string", so a typo'd league name still fails to
// compile.
export type AnyLeague = League | "mlb" | "nfl" | "nhl";

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
// JSON-encoded StandoutPerformerJson[] (types.ts) - every player on either
// team whose individual line cleared the per-league "good"-or-better bar,
// not just the single best - backs the favorite-player callout, which
// needs to check a specific person's line against a game that might not
// even be a highly-rated one overall. Set once at finalization by
// basketball (gameMapper.ts's findStandoutPerformers); never populated by
// the historical backfill scripts (predate this column), so older rows are
// simply NULL/empty rather than retroactively enriched.
ensureColumn("games", "standout_performers", "TEXT");
// JSON-encoded MlbRubricInputs (mlbRubric.ts) - the raw per-dimension facts
// (walk-off/extra-innings/total-runs/HRs/pitching-dominance/blown-save/
// errors) computeMlbWatchabilityScore was fed to produce this game's total
// score. Only score/tier survived to gameStore before this column existed
// (setMlbFinalRubric's own comment explains why: no MLB weight-adjusted
// recompute existed yet, so there was nothing that needed the inputs kept
// around) - this doesn't change that scope, it just lets the mobile client's
// game-detail "Breakdown" tab render MLB's own real per-category point
// values (mirroring the NBA/WNBA raw-facts-sent/client-recomputes pattern in
// Rubric.kt) instead of basketball's fields/labels, which is what it fell
// back to with nothing MLB-shaped to show. Null for any row scored before
// this column existed.
ensureColumn("games", "mlb_rubric_inputs", "TEXT");
// JSON-encoded NflRubricInputs (nflRubric.ts) - the NFL analogue of
// mlb_rubric_inputs above, same reasoning (lets the mobile client's
// Breakdown tab and weight sliders work off NFL's own real per-dimension
// facts instead of nothing/basketball's fields).
ensureColumn("games", "nfl_rubric_inputs", "TEXT");
// JSON-encoded NhlRubricInputs (nhlRubric.ts) - the NHL analogue of
// mlb_rubric_inputs/nfl_rubric_inputs above, same reasoning, persisted from
// day one (not retrofitted) per feedback_new_league_full_pipeline_checklist.md.
ensureColumn("games", "nhl_rubric_inputs", "TEXT");

// The one shared handle to games.db - alertStore.ts's tables live in this
// same file (same durability story: whatever disk games.db survives deploys
// on, the alert tables survive with it), and sharing the connection rather
// than opening a second one keeps every write on the single synchronous
// better-sqlite3 handle this codebase is built around - no SQLITE_BUSY
// contention to reason about.
export function rawDb(): Database.Database {
  return db;
}

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
  standoutPerformers: StandoutPerformerJson[];
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
  standoutPerformers: StandoutPerformerJson[];
  score: number | null;
  tier: string | null;
  finalAt: string | null;
  ytVideoId: string | null;
  ytLastCheckedAt: string | null;
  ytFoundAt: string | null;
  ytCheckCount: number;
  seasonStageLabel: string | null;
  mlbRubricInputs: MlbRubricInputs | null;
  nflRubricInputs: NflRubricInputs | null;
  nhlRubricInputs: NhlRubricInputs | null;
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
  standout_performers: string | null;
  score: number | null;
  tier: string | null;
  final_at: string | null;
  yt_video_id: string | null;
  yt_last_checked_at: string | null;
  yt_found_at: string | null;
  yt_check_count: number;
  season_stage_label: string | null;
  mlb_rubric_inputs: string | null;
  nfl_rubric_inputs: string | null;
  nhl_rubric_inputs: string | null;
}

function parseStandoutPerformers(raw: string | null): StandoutPerformerJson[] {
  if (!raw) return [];
  return JSON.parse(raw) as StandoutPerformerJson[];
}

function parseMlbRubricInputs(raw: string | null): MlbRubricInputs | null {
  if (!raw) return null;
  return JSON.parse(raw) as MlbRubricInputs;
}

function parseNflRubricInputs(raw: string | null): NflRubricInputs | null {
  if (!raw) return null;
  return JSON.parse(raw) as NflRubricInputs;
}

function parseNhlRubricInputs(raw: string | null): NhlRubricInputs | null {
  if (!raw) return null;
  return JSON.parse(raw) as NhlRubricInputs;
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
    standoutPerformers: parseStandoutPerformers(raw.standout_performers),
    score: raw.score,
    tier: raw.tier,
    finalAt: raw.final_at,
    ytVideoId: raw.yt_video_id,
    ytLastCheckedAt: raw.yt_last_checked_at,
    ytFoundAt: raw.yt_found_at,
    ytCheckCount: raw.yt_check_count,
    seasonStageLabel: raw.season_stage_label,
    mlbRubricInputs: parseMlbRubricInputs(raw.mlb_rubric_inputs),
    nflRubricInputs: parseNflRubricInputs(raw.nfl_rubric_inputs),
    nhlRubricInputs: parseNhlRubricInputs(raw.nhl_rubric_inputs),
  };
}

export function getGame(eventId: string): GameRow | undefined {
  const raw = db.prepare(`SELECT * FROM games WHERE event_id=?`).get(eventId) as RawGameRow | undefined;
  return raw ? mapRow(raw) : undefined;
}

export interface HeadToHeadGame {
  eventId: string;
  tipoffUtc: string;
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
}

interface RawHeadToHeadRow {
  event_id: string;
  tipoff_utc: string;
  away: string;
  home: string;
  away_score: number;
  home_score: number;
}

/**
 * Past meetings between these two exact teams within [leagueGroup], most
 * recent first - backs the game-detail popup's head-to-head context, pulled
 * straight from this same durable table rather than a fresh ESPN call
 * (every past meeting between two real teams is already sitting in this
 * table, live or backfilled). Not season-scoped - a simpler "most recent N
 * meetings regardless of season" rather than computing this app's various
 * per-league season-boundary rules a second time here, since the popup's
 * job is just "how have these two played recently," not a strict season
 * split.
 */
export function getHeadToHead(leagueGroup: LeagueGroup, teamA: string, teamB: string, excludeEventId: string, limit = 5): HeadToHeadGame[] {
  const rows = db
    .prepare(
      `SELECT event_id, tipoff_utc, away, home, away_score, home_score FROM games
       WHERE league_group=@leagueGroup AND status='final' AND event_id != @excludeEventId
         AND ((away=@teamA AND home=@teamB) OR (away=@teamB AND home=@teamA))
       ORDER BY tipoff_utc DESC
       LIMIT @limit`
    )
    .all({ leagueGroup, excludeEventId, teamA, teamB, limit }) as RawHeadToHeadRow[];

  return rows.map((r) => ({
    eventId: r.event_id,
    tipoffUtc: r.tipoff_utc,
    away: r.away,
    home: r.home,
    awayScore: r.away_score,
    homeScore: r.home_score,
  }));
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
 * One-off: force-overwrites season_stage_label regardless of its current
 * value - unlike setSeasonStageLabel (used everywhere else, including by
 * the always-safe-to-rerun migration scripts), which explicitly refuses to
 * touch a row that already has a label. That guard means a row stamped with
 * a wrong-but-non-null label *before* a labeling-logic fix shipped (e.g.
 * every NFL/MLB postseason game got the generic "Regular Season" label
 * before their own per-round derivation existed) can never self-correct
 * through the normal path - this is the escape hatch a one-off relabel
 * pass needs. Only meant to be reached from a temporary admin route,
 * removed once run once against production and confirmed.
 */
export function forceSetSeasonStageLabel(eventId: string, label: string): void {
  db.prepare(`UPDATE games SET season_stage_label=?, updated_at=? WHERE event_id=?`).run(label, now(), eventId);
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
       buzzer_beater=@buzzerBeater, star_performance=@starPerformance, standout_performers=@standoutPerformers,
       score=@score, tier=@tier,
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
    standoutPerformers: JSON.stringify(rubric.standoutPerformers),
    score: rubric.score,
    tier: rubric.tier,
    updatedAt: now(),
  });
}

export interface MlbFinalResult {
  awayScore: number;
  homeScore: number;
  score: number;
  tier: string;
  standoutPerformers: StandoutPerformerJson[];
  // Reuses the basketball-shaped final_margin/largest_deficit_overcome
  // columns directly (same concept in every sport in this codebase).
  finalMargin: number;
  largestDeficitOvercome: number;
  // The exact facts computeMlbWatchabilityScore was given to produce [score]
  // - persisted so the mobile client's Breakdown tab can recompute MLB's own
  // real per-category points (Rubric.kt's mlbRubricBreakdown, mirroring how
  // NBA/WNBA raw facts already work), not so a weight-adjusted recompute can
  // happen server-side - that's still out of scope, see this function's own
  // comment below.
  rubricInputs: MlbRubricInputs;
}

/**
 * MLB's equivalent of setFinalRubric - still deliberately narrower than
 * basketball's full column set in one respect (mlbGamesService.ts's
 * first-pass scope, see its file comment): there's no *server-side*
 * weight-adjusted recompute for MLB yet, so [rubricInputs] is stored as one
 * opaque JSON blob (mlb_rubric_inputs) rather than being broken out into its
 * own indexed/queryable columns the way basketball's rubric-input facts are
 * - nothing here queries by an individual MLB dimension, it only ever needs
 * to round-trip back out to GameJson whole. Same "never overwrite"
 * WHERE-guard as every other final-rubric setter.
 */
export function setMlbFinalRubric(eventId: string, result: MlbFinalResult, finalAt: string | null = now()): void {
  db.prepare(
    `UPDATE games SET
       status='final', final_at=@finalAt,
       away_score=@awayScore, home_score=@homeScore, score=@score, tier=@tier,
       standout_performers=@standoutPerformers,
       final_margin=@finalMargin, largest_deficit_overcome=@largestDeficitOvercome,
       mlb_rubric_inputs=@mlbRubricInputs,
       updated_at=@updatedAt
     WHERE event_id=@eventId AND score IS NULL`
  ).run({
    eventId,
    finalAt,
    awayScore: result.awayScore,
    homeScore: result.homeScore,
    score: result.score,
    tier: result.tier,
    standoutPerformers: JSON.stringify(result.standoutPerformers),
    finalMargin: result.finalMargin,
    largestDeficitOvercome: result.largestDeficitOvercome,
    mlbRubricInputs: JSON.stringify(result.rubricInputs),
    updatedAt: now()
  });
}

export interface NflFinalResult {
  awayScore: number;
  homeScore: number;
  score: number;
  tier: string;
  standoutPerformers: StandoutPerformerJson[];
  finalMargin: number;
  largestDeficitOvercome: number;
  // Same reasoning as MlbFinalResult.rubricInputs - persisted so the mobile
  // client's Breakdown tab/weight sliders can work off NFL's own real
  // per-dimension facts (Rubric.kt's nflRubricBreakdown), not so a
  // server-side weight-adjusted recompute can happen.
  rubricInputs: NflRubricInputs;
}

/** NFL's equivalent of setMlbFinalRubric - same narrower-than-basketball column shape, same "never overwrite" WHERE-guard. */
export function setNflFinalRubric(eventId: string, result: NflFinalResult, finalAt: string | null = now()): void {
  db.prepare(
    `UPDATE games SET
       status='final', final_at=@finalAt,
       away_score=@awayScore, home_score=@homeScore, score=@score, tier=@tier,
       standout_performers=@standoutPerformers,
       final_margin=@finalMargin, largest_deficit_overcome=@largestDeficitOvercome,
       nfl_rubric_inputs=@nflRubricInputs,
       updated_at=@updatedAt
     WHERE event_id=@eventId AND score IS NULL`
  ).run({
    eventId,
    finalAt,
    awayScore: result.awayScore,
    homeScore: result.homeScore,
    score: result.score,
    tier: result.tier,
    standoutPerformers: JSON.stringify(result.standoutPerformers),
    finalMargin: result.finalMargin,
    largestDeficitOvercome: result.largestDeficitOvercome,
    nflRubricInputs: JSON.stringify(result.rubricInputs),
    updatedAt: now()
  });
}

export interface NhlFinalResult {
  awayScore: number;
  homeScore: number;
  score: number;
  tier: string;
  standoutPerformers: StandoutPerformerJson[];
  finalMargin: number;
  largestDeficitOvercome: number;
  // Same reasoning as MlbFinalResult.rubricInputs/NflFinalResult.rubricInputs
  // - persisted so the mobile client's Breakdown tab/weight sliders can work
  // off NHL's own real per-dimension facts (Rubric.kt's nhlRubricBreakdown).
  rubricInputs: NhlRubricInputs;
}

/** NHL's equivalent of setNflFinalRubric - same narrower-than-basketball column shape, same "never overwrite" WHERE-guard. */
export function setNhlFinalRubric(eventId: string, result: NhlFinalResult, finalAt: string | null = now()): void {
  db.prepare(
    `UPDATE games SET
       status='final', final_at=@finalAt,
       away_score=@awayScore, home_score=@homeScore, score=@score, tier=@tier,
       standout_performers=@standoutPerformers,
       final_margin=@finalMargin, largest_deficit_overcome=@largestDeficitOvercome,
       nhl_rubric_inputs=@nhlRubricInputs,
       updated_at=@updatedAt
     WHERE event_id=@eventId AND score IS NULL`
  ).run({
    eventId,
    finalAt,
    awayScore: result.awayScore,
    homeScore: result.homeScore,
    score: result.score,
    tier: result.tier,
    standoutPerformers: JSON.stringify(result.standoutPerformers),
    finalMargin: result.finalMargin,
    largestDeficitOvercome: result.largestDeficitOvercome,
    nhlRubricInputs: JSON.stringify(result.rubricInputs),
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
  // Newest tipoff first: a game whose only check attempt(s) landed on a day
  // the shared daily search budget ran dry never gets markHighlightsChecked
  // called (see checkGameHighlights's canSpendSearchQuota guard), so it stays
  // "due" indefinitely. Without this ordering, that growing backlog of
  // stuck old rows - always sorted first by rowid/insertion order - would
  // permanently eat every tick's MAX_CHECKS_PER_POLL budget ahead of
  // whatever actually just went final, starving new games of checks forever
  // (confirmed against production: 19 stuck NBA Summer League games from
  // July 9-14 were still blocking every one of yesterday's 5 WNBA finals).
  const raws = db
    .prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL ORDER BY tipoff_utc DESC`)
    .all() as RawGameRow[];
  return raws
    .map(mapRow)
    .filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime)
    // No MLB YouTube channel is configured (youtubeClient.ts's search is
    // NBA/WNBA-channel-only) - without this guard, MLB rows would sit here
    // forever "missing highlights" and gamesService.ts's demand-driven check
    // would eventually search the *NBA* channel for an MLB matchup
    // (checkGameHighlights's HighlightsLeague derivation has no MLB branch),
    // wasting real search.list quota on a near-certain miss.
    .filter((row) => SPORT_FOR_LEAGUE_GROUP[row.leagueGroup] === "basketball");
}

/**
 * MLB analogue of getFinalGamesMissingHighlights above - same recent-games-
 * only scope (RECENT_GAMES_WINDOW_MS), kept as its own separate function
 * rather than widening the basketball one so MLB highlights search stays
 * fully opt-in: nothing in the live poller (highlightsPoller.ts) or any
 * demand-driven request path calls this yet - see mlbGamesService.ts's
 * checkPendingMlbHighlights, which is built, real-data-verified, and ready
 * to wire in, but deliberately not called from anywhere yet (James's
 * explicit call - built and sitting there, not live).
 */
export function getFinalMlbGamesMissingHighlights(): GameRow[] {
  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  // Newest first - same starvation reasoning as getFinalGamesMissingHighlights above.
  const raws = db
    .prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL AND league_group='mlb' ORDER BY tipoff_utc DESC`)
    .all() as RawGameRow[];
  return raws.map(mapRow).filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime);
}

/**
 * NFL analogue of getFinalMlbGamesMissingHighlights above - same
 * recent-games-only scope, same "not called from anywhere yet" status as
 * MLB's own highlights search was at its own equivalent stage (see
 * nflGamesService.ts's checkPendingNflHighlights) - built and ready, kept
 * dormant until explicitly wired in.
 */
export function getFinalNflGamesMissingHighlights(): GameRow[] {
  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  const raws = db.prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL AND league_group='nfl'`).all() as RawGameRow[];
  return raws.map(mapRow).filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime);
}

/**
 * NHL analogue of getFinalNflGamesMissingHighlights above - same
 * recent-games-only scope, same "not called from anywhere yet" status as
 * MLB's/NFL's own highlights search was at their own equivalent stage (see
 * nhlGamesService.ts's checkPendingNhlHighlights) - built and ready, kept
 * dormant until explicitly wired in.
 */
export function getFinalNhlGamesMissingHighlights(): GameRow[] {
  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  const raws = db.prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL AND league_group='nhl'`).all() as RawGameRow[];
  return raws.map(mapRow).filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime);
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

/**
 * MLB rows still stamped with the generic default label AND that fall in a
 * real postseason-plausible month (October or November - MLB's actual
 * postseason window every year, regular season always wraps up by late
 * September) - backs relabelMlbStages.ts's one-off re-derive pass. Every
 * MLB game got the fixed default label regardless of postseason status
 * before deriveMlbCompetitionLabel existed, so unlike
 * getGamesMissingStageLabel above (rows with no label at all), the target
 * set here is rows with a *present but possibly wrong* label. The Oct/Nov
 * filter is a real constraint, not a guess - it's what keeps this a
 * one-off re-check of a few weeks' worth of games instead of re-fetching
 * ESPN for every date across an entire multi-month season for nothing
 * (every March-September row is guaranteed regular season and already
 * correctly labeled).
 */
export function getMlbGamesWithGenericLabel(): GameRow[] {
  const raws = db
    .prepare(
      `SELECT * FROM games WHERE league_group = 'mlb' AND season_stage_label = 'MLB - Regular Season' AND strftime('%m', tipoff_utc) IN ('10', '11')`
    )
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
    .prepare(
      `SELECT MAX(tipoff_utc) as end FROM games
       WHERE league_group = ? AND (season_stage_label LIKE '%Playoffs: Finals%' OR season_stage_label LIKE '%Super Bowl%' OR season_stage_label LIKE '%Stanley Cup Final%' OR season_stage_label LIKE '%World Series%')`
    )
    .get(leagueGroup) as { end: string | null };
  return row.end ?? undefined;
}

// The season immediately following [finalsEndTipoff]'s Finals - NBA's
// "ends in" convention labels a season by its start year (e.g. Finals in
// June 2026 conclude the "2025-26" season, so the next one is "2026-27",
// and finalsEndTipoff's own calendar year IS that next season's start
// year under this convention). WNBA never spans a year boundary, so its
// next season is simply next calendar year. NFL is labeled by its own start
// year too (a "2025" season runs Sept 2025 - Feb 2026, Super Bowl LX played
// in calendar year 2026) - finalsEndTipoff's own calendar year IS already
// the next season's label (the Super Bowl happens in the year *after* the
// season's own label year), unlike WNBA where the label year and the
// Finals-end year are the same and the next season needs +1.
function upcomingSeasonLabel(finalsEndTipoff: string, leagueGroup: LeagueGroup): string {
  const finalsYear = new Date(finalsEndTipoff).getUTCFullYear();
  if (leagueGroup === "wnba") return String(finalsYear + 1);
  if (leagueGroup === "nfl") return String(finalsYear);
  return `${finalsYear}-${String((finalsYear + 1) % 100).padStart(2, "0")}`;
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
// fresh lookup.
export function seasonLabelForTipoff(
  tipoffUtc: string,
  leagueGroup: LeagueGroup,
  finalsEnd: string | undefined = getMostRecentFinalsEnd(leagueGroup)
): string {
  if (finalsEnd && tipoffUtc > finalsEnd) {
    return upcomingSeasonLabel(finalsEnd, leagueGroup);
  }
  const date = new Date(tipoffUtc);
  const year = date.getUTCFullYear();
  if (leagueGroup === "wnba") return String(year);
  // NFL: single-year label like WNBA (not hyphenated), but the season DOES
  // cross the year boundary the way NBA's does (a "2025" season's real
  // games run Sept 2025 - Feb 2026) - September (real regular-season start
  // month, confirmed live) is the cutover, mirroring NBA's own
  // "use the real season-start month" convention below rather than an
  // arbitrary buffer month.
  if (leagueGroup === "nfl") return String(date.getUTCMonth() >= 8 ? year : year - 1);
  const startYear = date.getUTCMonth() >= 9 ? year : year - 1;
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}`;
}

// The nominal start date a seasonLabelForTipoff-style label itself begins
// on - the inverse of that function's own math (WNBA: Jan 1 of the label's
// year; NBA: Oct 1 of the label's start year; NFL: Sept 1 of the label's
// year, matching seasonLabelForTipoff's own September cutover).
function seasonStartDateForLabel(label: string, leagueGroup: LeagueGroup): string {
  if (leagueGroup === "wnba") return `${label}-01-01T00:00:00.000Z`;
  if (leagueGroup === "nfl") return `${label}-09-01T00:00:00.000Z`;
  const startYear = label.slice(0, 4);
  return `${startYear}-10-01T00:00:00.000Z`;
}

/**
 * Distinct season labels present in the backfill/live data for
 * [leagueGroup], newest first - backs the History tab's per-season filter
 * chips, so a newly-backfilled season shows up automatically with no code
 * change. Scoped by league_group (not the narrower "league" column, which
 * for NBA holds "nba"/"nba-summer-las-vegas"/etc separately) - NBA Summer
 * League rows are additionally excluded by league name, since those belong
 * to the "This season" (current in-progress period) bucket the client
 * computes separately, not to a named season block.
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

// TEMPORARY diagnostic (devServer.ts's /admin/highlights-diagnostics) -
// investigating James's report that recent/current games aren't reliably
// getting highlights. Pulls real production state (search budget spend
// history, learned upload-lag percentiles per league, and a breakdown of
// currently-missing rows by check count) rather than guessing from code
// alone. Remove once the investigation is resolved.
export function getHighlightsDiagnostics(): {
  budgetHistory: { date: string; count: number }[];
  lagPercentiles: Record<string, LagPercentiles>;
  missingByLeague: Record<string, { checkCount0: number; checkCount1: number; abandoned: number; total: number }>;
} {
  const budgetHistory = db.prepare(`SELECT date, count FROM youtube_search_budget ORDER BY date DESC LIMIT 10`).all() as {
    date: string;
    count: number;
  }[];

  const lagPercentiles = {
    nba: getLagPercentiles("nba", "nba"),
    wnba: getLagPercentiles("wnba", "wnba"),
    mlb: getLagPercentiles("mlb", "mlb"),
  };

  const cutoffTime = Date.now() - RECENT_GAMES_WINDOW_MS;
  const raws = db
    .prepare(`SELECT * FROM games WHERE status='final' AND yt_video_id IS NULL ORDER BY tipoff_utc DESC`)
    .all() as RawGameRow[];
  const rows = raws.map(mapRow).filter((row) => new Date(row.tipoffUtc).getTime() >= cutoffTime);

  const missingByLeague: Record<string, { checkCount0: number; checkCount1: number; abandoned: number; total: number }> = {};
  for (const row of rows) {
    const bucket = (missingByLeague[row.leagueGroup] ??= { checkCount0: 0, checkCount1: 0, abandoned: 0, total: 0 });
    bucket.total++;
    if (row.ytCheckCount === 0) bucket.checkCount0++;
    else if (row.ytCheckCount === 1) bucket.checkCount1++;
    else bucket.abandoned++;
  }

  return { budgetHistory, lagPercentiles, missingByLeague };
}

export function closeDb(): void {
  db.close();
}
