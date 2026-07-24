// NHL's equivalent of nflGamesService.ts/mlbGamesService.ts - a separate
// dispatch target (see types.ts's SPORT_FOR_LEAGUE_GROUP) rather than
// branching inside gamesService.ts, since hockey's ESPN shape and rubric
// don't overlap with basketball's beyond the generic gameStore setters and
// the LLM preview call.
//
// Applies every item in feedback_new_league_full_pipeline_checklist.md from
// day one (not retrofitted, the way MLB's first pass had to be):
//   1. setNhlFinalRubric persists the full nhl_rubric_inputs JSON blob so the
//      mobile client's Breakdown tab and weight sliders work off NHL's own
//      real facts immediately.
//   2. migrateToGameStore.ts seeds a historical NHL backfill so Favorites-tab
//      team schedules never cold-start timeout.
//   3. deriveNhlCompetitionLabel below derives a real per-round postseason
//      label from ESPN's own notes[].headline field (confirmed live: "East
//      1st Round", "West Final", "Stanley Cup Final", etc.) from the very
//      first game processed - NHL's season crosses the year boundary the
//      same way NFL's does, so without this every game would get one fixed
//      label regardless of round and gameStore.ts's getMostRecentFinalsEnd
//      would never find the real season-end marker, exactly the bug NFL's
//      build had to fix same-day (see
//      feedback_new_league_full_pipeline_checklist.md's "extra lesson for
//      NHL").
//
// Highlights search (checkPendingNhlHighlights below) is built and its
// title-format actually confirmed against real live uploads (unlike NFL's
// build, which had no fresh games to check during its own offseason) - see
// youtubeClient.ts's NHL_YOUTUBE_CHANNEL_ID comment for the two real
// confirmed title conventions. Still deliberately NOT called from anywhere
// yet (not highlightsPoller.ts, not processNhlEvent below) - matching MLB's/
// NFL's own staged rollout at this same point. Wiring it in later is a
// one-line change: import checkPendingNhlHighlights into
// highlightsPoller.ts's pollOnce() alongside the existing checks.
import { toEspnDate } from "./espnClient";
import {
  GameRow,
  canSpendSearchQuota,
  getFinalNhlGamesMissingHighlights,
  getGame,
  markHighlightsChecked,
  recordSearchQuotaSpend,
  setHighlights,
  setNhlFinalRubric,
  setPreview,
  setSeasonStageLabel,
  updateStatus,
  upsertBaseEntry
} from "./gameStore";
import { isDueForHighlightsCheck } from "./gamesService";
import { mapNhlEspnState, mapNhlEventToGame } from "./nhlGameMapper";
import { EspnNhlEvent, fetchNhlScoreboard, fetchNhlSummary, fetchNhlTeamSchedule } from "./nhlEspnClient";
import { generateHookAndStakes } from "./llm";
import { computeNhlWatchabilityScore, tierForNhlScore } from "./nhlRubric";
import { GameJson } from "./types";
import { isYoutubeSearchConfigured, searchHighlightsVideo } from "./youtubeClient";
import { preferDarkLogoVariant } from "./teamLogos";

export function isNhlLeagueGroup(leagueGroup: string): leagueGroup is "nhl" {
  return leagueGroup === "nhl";
}

// Same 24h-before-puck-drop gate as gamesService.ts/mlbGamesService.ts/nflGamesService.ts.
const PREVIEW_GATE_HOURS_BEFORE_TIPOFF = 24;

function hasReachedPreviewGate(tipoffUtc: string, now: Date = new Date()): boolean {
  const gateTime = new Date(tipoffUtc).getTime() - PREVIEW_GATE_HOURS_BEFORE_TIPOFF * 60 * 60 * 1000;
  return now.getTime() >= gateTime;
}

function fallbackHook(away: string, home: string): string {
  return `${away} at ${home}.`;
}

// Fallback/regular-season label - exported so migrateToGameStore.ts's NHL
// historical backfill can stamp the exact same default live games get.
export const COMPETITION_LABEL = "NHL - Regular Season";

/**
 * Real per-round postseason label, derived from ESPN's own notes[].headline
 * field (e.g. "East 1st Round - Game 2", "West Final - Game 1", "Stanley Cup
 * Final - Game 6" - confirmed directly against real 2026 playoff data) by
 * stripping the trailing "- Game N". This is what actually prevents "This
 * season" from showing stale games the way it did for NFL: the Stanley Cup
 * Final - the one game gameStore.ts's getMostRecentFinalsEnd needs to find
 * the real season boundary - gets a real, distinct label from the very first
 * game processed, never the same fixed COMPETITION_LABEL every other league
 * would fall back to regardless of round.
 */
export function deriveNhlCompetitionLabel(seasonType: number | undefined, notesHeadline: string | undefined): string {
  if (seasonType !== 3) return COMPETITION_LABEL;
  if (!notesHeadline) return "NHL - Playoffs";
  const roundName = notesHeadline.replace(/\s*-\s*Game\s*\d+\s*$/i, "").trim();
  return `NHL - Playoffs: ${roundName}`;
}

async function ensureNhlPregamePreview(row: GameRow): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(row.tipoffUtc)) return; // not time yet

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: row.away,
    home: row.home,
    league: "NHL"
  });

  setPreview(row.eventId, hook, pitch, stakes);
}

function toNhlGameJson(row: GameRow, status: "upcoming" | "live", q?: number, clk?: string): GameJson {
  return {
    id: row.eventId,
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: status,
    utc: row.tipoffUtc,
    lg: "nhl",
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
 * NHL analogue of nflGamesService.ts's processNflEvent.
 */
async function processNhlEvent(event: EspnNhlEvent): Promise<GameJson> {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;
  const status = mapNhlEspnState(competition.status.type.state);

  upsertBaseEntry({
    eventId: event.id,
    league: "nhl",
    leagueGroup: "nhl",
    away: away.team.displayName,
    home: home.team.displayName,
    awayLogo: preferDarkLogoVariant(away.team.logo),
    homeLogo: preferDarkLogoVariant(home.team.logo),
    tipoffUtc: event.date,
    status
  });
  updateStatus(event.id, status);
  setSeasonStageLabel(event.id, deriveNhlCompetitionLabel(event.season?.type, competition.notes?.[0]?.headline));

  let row = getGame(event.id)!;

  if (status !== "final") {
    await ensureNhlPregamePreview(row);
    row = getGame(event.id)!;
  }

  if (status === "upcoming") return toNhlGameJson(row, "upcoming");
  if (status === "live") return toNhlGameJson(row, "live", competition.status.period, competition.status.displayClock);

  // final: compute the rubric once, ever - already-set fields are never touched again.
  if (row.score === null) {
    const summary = await fetchNhlSummary(event.id);
    const mapped = mapNhlEventToGame(event, summary);
    const score = computeNhlWatchabilityScore(mapped.rubricInputs, row.stakes ?? undefined).total;
    setNhlFinalRubric(event.id, {
      awayScore: mapped.awayScore,
      homeScore: mapped.homeScore,
      score,
      tier: tierForNhlScore(score),
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
    lg: "nhl",
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
    nhlInputs: row.nhlRubricInputs ?? undefined
  };
}

// Regular-season and postseason only - preseason is excluded, same rule
// every other league's backfill/live pipeline already applies. Exported so
// seasonWindowService.ts's getNhlSeasonWindow can reuse the exact same
// "skip preseason" rule.
export function isRealSeasonEvent(event: EspnNhlEvent): boolean {
  return event.season?.slug !== "preseason";
}

/** Fetches, scores, and caches one day's NHL games - the NHL analogue of mlbGamesService.ts's getMlbGamesForDate/nflGamesService.ts's getNflGamesForDate. */
export async function getNhlGamesForDate(date: string): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const events = (await fetchNhlScoreboard(espnDate)).filter(isRealSeasonEvent);

  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processNhlEvent(event));
  }
  return results;
}

/**
 * A single favorited team's own real schedule (past and upcoming, current
 * season) - backs the Favorites tab's Games page, NHL analogue of
 * mlbGamesService.ts's/nflGamesService.ts's own team-schedule functions.
 */
export async function getNhlTeamSchedule(teamId: string): Promise<GameJson[]> {
  const events = (await fetchNhlTeamSchedule(teamId)).filter(isRealSeasonEvent);
  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processNhlEvent(event));
  }
  return results;
}

// NHL's calendar is dense/flat (confirmed directly, ~226 real dates spanning
// the full season), so a scoreboard scan only ever needs to cover the real
// off-season gap between the just-completed season's last game and the next
// one's first - same order of magnitude as NFL's own gap, generous bound.
const NEXT_GAME_SCAN_DAYS = 150;

function isoDate(d: Date): string {
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** The next date, strictly after [afterDate], that NHL actually has a scheduled game - NHL analogue of mlbGamesService.ts's/nflGamesService.ts's own next-scheduled-date functions. */
export async function getNextNhlScheduledDate(afterDate: string): Promise<string | undefined> {
  const afterTime = new Date(`${afterDate}T12:00:00Z`).getTime();
  const oneDayMs = 24 * 60 * 60 * 1000;

  for (let i = 1; i <= NEXT_GAME_SCAN_DAYS; i++) {
    const dateStr = isoDate(new Date(afterTime + i * oneDayMs));
    const games = await getNhlGamesForDate(dateStr);
    if (games.length > 0) return dateStr;
  }
  return undefined;
}

/**
 * Searches for and records one NHL game's highlights match - the NHL
 * analogue of mlbGamesService.ts's checkMlbGameHighlights/nflGamesService.ts's
 * checkNflGameHighlights, sharing every piece of that function's design.
 * Not called from anywhere yet - see this file's header comment.
 */
async function checkNhlGameHighlights(row: GameRow): Promise<void> {
  if (!canSpendSearchQuota()) {
    console.warn(`checkNhlGameHighlights: daily search budget spent, deferring ${row.away} @ ${row.home}`);
    return;
  }

  console.log(`checkNhlGameHighlights: searching for ${row.away} @ ${row.home} (${row.eventId})`);
  recordSearchQuotaSpend();
  const match = await searchHighlightsVideo("nhl", row.away, row.home, row.tipoffUtc);
  markHighlightsChecked(row.eventId, new Date().toISOString());
  if (match) {
    setHighlights(row.eventId, match.videoId, match.publishedAt);
    console.log(`checkNhlGameHighlights: matched "${match.title}" (${match.videoId})`);
  } else {
    console.log(`checkNhlGameHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
  }
}

// Same bound as gamesService.ts's MAX_CHECKS_PER_POLL / mlbGamesService.ts's/nflGamesService.ts's own constants.
const MAX_NHL_CHECKS_PER_POLL = 20;

/**
 * NHL analogue of mlbGamesService.ts's/nflGamesService.ts's own
 * checkPending*Highlights - reuses the exact same isDueForHighlightsCheck so
 * the fixed 15-min-then-30-min two-check schedule is identical across every
 * league. Not called from highlightsPoller.ts or anywhere else yet - built,
 * real-title-format-verified (unlike NFL's own build), ready to wire in
 * whenever wanted.
 */
export async function checkPendingNhlHighlights(): Promise<void> {
  if (!isYoutubeSearchConfigured()) return;

  const now = Date.now();
  let checksThisPoll = 0;
  for (const row of getFinalNhlGamesMissingHighlights()) {
    if (checksThisPoll >= MAX_NHL_CHECKS_PER_POLL) break;
    if (isDueForHighlightsCheck(row, now)) {
      await checkNhlGameHighlights(row);
      checksThisPoll++;
    }
  }
}
