// NFL's equivalent of soccerGamesService.ts/mlbGamesService.ts - a separate
// dispatch target (see types.ts's SPORT_FOR_LEAGUE_GROUP) rather than
// branching inside gamesService.ts, since football's ESPN shape and rubric
// don't overlap with basketball's beyond the generic gameStore setters and
// the LLM preview call.
//
// Same scope shape as MLB's own first pass: setNflFinalRubric persists the
// same shared basics (final_margin/largest_deficit_overcome/score/tier/
// standout_performers) plus the full nfl_rubric_inputs JSON blob, so the
// mobile client's Breakdown tab and weight sliders work off NFL's own real
// facts from day one (see feedback_new_league_full_pipeline_checklist.md -
// this was a gap MLB had to retrofit, not repeated here).
//
// Highlights search (checkPendingNflHighlights below) is built and follows
// the exact same pattern MLB's own did at this stage, but is deliberately
// NOT called from anywhere yet - not highlightsPoller.ts, not
// processNflEvent below - matching MLB's own staged rollout (built and
// verified against real ESPN data, YouTube title format not yet confirmed
// since the NFL is in its offseason - see youtubeClient.ts's own comment).
// Wiring it in later is a one-line change: import checkPendingNflHighlights
// into highlightsPoller.ts's pollOnce() alongside the existing
// checkPendingHighlights()/checkPendingMlbHighlights() calls.
import { toEspnDate } from "./espnClient";
import {
  GameRow,
  canSpendSearchQuota,
  getFinalNflGamesMissingHighlights,
  getGame,
  markHighlightsChecked,
  recordSearchQuotaSpend,
  setHighlights,
  setNflFinalRubric,
  setPreview,
  setSeasonStageLabel,
  updateStatus,
  upsertBaseEntry
} from "./gameStore";
import { isDueForHighlightsCheck } from "./gamesService";
import { mapNflEspnState, mapNflEventToGame } from "./nflGameMapper";
import { EspnNflEvent, fetchNflScoreboard, fetchNflSummary, fetchNflTeamSchedule } from "./nflEspnClient";
import { generateHookAndStakes } from "./llm";
import { computeNflWatchabilityScore, tierForNflScore } from "./nflRubric";
import { GameJson } from "./types";
import { isYoutubeSearchConfigured, searchHighlightsVideo } from "./youtubeClient";

export function isNflLeagueGroup(leagueGroup: string): leagueGroup is "nfl" {
  return leagueGroup === "nfl";
}

// Same 24h-before-kickoff gate as gamesService.ts/mlbGamesService.ts - no reason for NFL previews to populate on a different schedule.
const PREVIEW_GATE_HOURS_BEFORE_TIPOFF = 24;

function hasReachedPreviewGate(tipoffUtc: string, now: Date = new Date()): boolean {
  const gateTime = new Date(tipoffUtc).getTime() - PREVIEW_GATE_HOURS_BEFORE_TIPOFF * 60 * 60 * 1000;
  return now.getTime() >= gateTime;
}

function fallbackHook(away: string, home: string): string {
  return `${away} at ${home}.`;
}

// Exported so migrateToGameStore.ts's NFL historical backfill can stamp the exact same label live games get, rather than duplicating the string.
export const COMPETITION_LABEL = "NFL - Regular Season";

async function ensureNflPregamePreview(row: GameRow): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(row.tipoffUtc)) return; // not time yet

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: row.away,
    home: row.home,
    league: "NFL"
  });

  setPreview(row.eventId, hook, pitch, stakes);
}

function toNflGameJson(row: GameRow, status: "upcoming" | "live", q?: number, clk?: string): GameJson {
  return {
    id: row.eventId,
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: status,
    utc: row.tipoffUtc,
    lg: "nfl",
    cl: row.seasonStageLabel ?? COMPETITION_LABEL,
    q,
    clk,
    ot: 0,
    c5: false,
    lcf: false,
    fp: false,
    bz: false,
    st: null,
    sop: row.standoutPerformers,
    sk: row.stakes ?? undefined,
    hook: row.hook ?? fallbackHook(row.away, row.home),
    pitch: row.pitch ?? undefined,
    score_visible: false
  };
}

/**
 * Processes exactly one event through the full pipeline (upsert, status,
 * pregame preview, final-rubric compute-once) and returns its GameJson - the
 * NFL analogue of mlbGamesService.ts's processMlbEvent.
 */
async function processNflEvent(event: EspnNflEvent): Promise<GameJson> {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;
  const status = mapNflEspnState(competition.status.type.state);

  upsertBaseEntry({
    eventId: event.id,
    league: "nfl",
    leagueGroup: "nfl",
    away: away.team.displayName,
    home: home.team.displayName,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    tipoffUtc: event.date,
    status
  });
  updateStatus(event.id, status);
  setSeasonStageLabel(event.id, COMPETITION_LABEL);

  let row = getGame(event.id)!;

  if (status !== "final") {
    await ensureNflPregamePreview(row);
    row = getGame(event.id)!;
  }

  if (status === "upcoming") return toNflGameJson(row, "upcoming");
  if (status === "live") return toNflGameJson(row, "live", competition.status.period, competition.status.displayClock);

  // final: compute the rubric once, ever - already-set fields are never touched again.
  if (row.score === null) {
    const summary = await fetchNflSummary(event.id);
    const mapped = mapNflEventToGame(event, summary);
    const score = computeNflWatchabilityScore(mapped.rubricInputs, row.stakes ?? undefined).total;
    setNflFinalRubric(event.id, {
      awayScore: mapped.awayScore,
      homeScore: mapped.homeScore,
      score,
      tier: tierForNflScore(score),
      standoutPerformers: mapped.standoutPerformers,
      finalMargin: mapped.rubricInputs.finalMargin,
      largestDeficitOvercome: mapped.rubricInputs.largestDeficitOvercome,
      rubricInputs: mapped.rubricInputs
    });
    row = getGame(event.id)!;
  }

  return {
    id: row.eventId,
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: "final",
    utc: row.tipoffUtc,
    lg: "nfl",
    cl: row.seasonStageLabel ?? COMPETITION_LABEL,
    ot: 0,
    c5: false,
    lcf: false,
    fp: false,
    bz: false,
    st: null,
    sop: row.standoutPerformers,
    m: row.finalMargin ?? undefined,
    cb: row.largestDeficitOvercome ?? undefined,
    sk: row.stakes ?? undefined,
    hook: row.hook ?? fallbackHook(row.away, row.home),
    pitch: row.pitch ?? undefined,
    score: row.score ?? undefined,
    score_visible: true,
    yt: row.ytVideoId ?? undefined,
    nflInputs: row.nflRubricInputs ?? undefined
  };
}

// Regular-season and postseason only - preseason (type 1) is deliberately
// excluded, same rule backfillRawStatsNfl.ts already validated against real
// data (a preseason final has no real stakes and pads the Games tab with
// exhibition noise, same reasoning as MLB's spring-training exclusion).
// Exported so seasonWindowService.ts's getNflSeasonWindow can reuse the
// exact same "skip preseason" rule when scanning for the real regular-season
// start date, rather than re-deriving it.
export function isRealSeasonEvent(event: EspnNflEvent): boolean {
  return event.season?.type !== 1 && event.season?.slug !== "preseason";
}

/** Fetches, scores, and caches one day's NFL games - the NFL analogue of mlbGamesService.ts's getMlbGamesForDate. */
export async function getNflGamesForDate(date: string): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const events = (await fetchNflScoreboard(espnDate)).filter(isRealSeasonEvent);

  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processNflEvent(event));
  }
  return results;
}

/**
 * A single favorited team's own real schedule (past and upcoming, current
 * season) - backs the Favorites tab's Games page, NFL analogue of
 * mlbGamesService.ts's getMlbTeamSchedule. Reuses the same processNflEvent
 * pipeline every other NFL event (day-based or team-based) goes through, so
 * a favorited team's games get the same preview/final-rubric treatment as
 * the Games tab's own slate.
 */
export async function getNflTeamSchedule(teamId: string): Promise<GameJson[]> {
  const events = (await fetchNflTeamSchedule(teamId)).filter(isRealSeasonEvent);
  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processNflEvent(event));
  }
  return results;
}

// NFL games happen roughly weekly (not daily like basketball, nor even
// MLB's near-daily cadence), and the off-season gap between seasons runs
// ~5.5 months - a bounded direct day-by-day scoreboard scan (same fallback
// shape as seasonWindowService.ts's own findMlbRegularSeasonStart) needs a
// much wider bound than MLB's 60 days to always find a real answer from any
// point in the calendar, including the deepest point of the off-season.
// NFL's calendar endpoint can't supply a flat candidate-date list the way
// basketball's/MLB's do (see nflEspnClient.ts's own comment on its grouped
// shape), so this scans directly rather than filtering a candidate list.
const NEXT_GAME_SCAN_DAYS = 250;

function isoDate(d: Date): string {
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** The next date, strictly after [afterDate], that NFL actually has a scheduled game - NFL analogue of mlbGamesService.ts's getNextMlbScheduledDate. */
export async function getNextNflScheduledDate(afterDate: string): Promise<string | undefined> {
  const afterTime = new Date(`${afterDate}T12:00:00Z`).getTime();
  const oneDayMs = 24 * 60 * 60 * 1000;

  for (let i = 1; i <= NEXT_GAME_SCAN_DAYS; i++) {
    const dateStr = isoDate(new Date(afterTime + i * oneDayMs));
    const games = await getNflGamesForDate(dateStr);
    if (games.length > 0) return dateStr;
  }
  return undefined;
}

/**
 * Searches for and records one NFL game's highlights match - the NFL
 * analogue of mlbGamesService.ts's checkMlbGameHighlights, sharing every
 * piece of that function's design. Not called from anywhere yet - see this
 * file's header comment.
 */
async function checkNflGameHighlights(row: GameRow): Promise<void> {
  if (!canSpendSearchQuota()) {
    console.warn(`checkNflGameHighlights: daily search budget spent, deferring ${row.away} @ ${row.home}`);
    return;
  }

  console.log(`checkNflGameHighlights: searching for ${row.away} @ ${row.home} (${row.eventId})`);
  recordSearchQuotaSpend();
  const match = await searchHighlightsVideo("nfl", row.away, row.home, row.tipoffUtc);
  markHighlightsChecked(row.eventId, new Date().toISOString());
  if (match) {
    setHighlights(row.eventId, match.videoId, match.publishedAt);
    console.log(`checkNflGameHighlights: matched "${match.title}" (${match.videoId})`);
  } else {
    console.log(`checkNflGameHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
  }
}

// Same bound as gamesService.ts's MAX_CHECKS_PER_POLL / mlbGamesService.ts's own constant.
const MAX_NFL_CHECKS_PER_POLL = 20;

/**
 * NFL analogue of mlbGamesService.ts's checkPendingMlbHighlights - reuses
 * the exact same isDueForHighlightsCheck so the fixed 15-min-then-30-min
 * two-check schedule is identical across every league. Not called from
 * highlightsPoller.ts or anywhere else yet - built, ready to wire in once
 * the title-format caveat in youtubeClient.ts's own comment is resolved
 * against a real live NFL upload.
 */
export async function checkPendingNflHighlights(): Promise<void> {
  if (!isYoutubeSearchConfigured()) return;

  const now = Date.now();
  let checksThisPoll = 0;
  for (const row of getFinalNflGamesMissingHighlights()) {
    if (checksThisPoll >= MAX_NFL_CHECKS_PER_POLL) break;
    if (isDueForHighlightsCheck(row, now)) {
      await checkNflGameHighlights(row);
      checksThisPoll++;
    }
  }
}
