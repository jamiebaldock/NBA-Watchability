// MLB's equivalent of soccerGamesService.ts - a separate dispatch target
// (see types.ts's SPORT_FOR_LEAGUE_GROUP) rather than branching inside
// gamesService.ts, for the same reason soccer got its own file: baseball's
// ESPN shape and rubric don't overlap with basketball's beyond the generic
// gameStore setters and the LLM preview call.
//
// Deliberately narrower than the full soccer pipeline in one remaining way:
// no per-game rubric-input persistence beyond score/tier (setMlbFinalRubric
// only ever touches the same columns setSoccerFinalRubric's shared basics
// use - final_margin/largest_deficit_overcome/score/tier/standout_performers
// - not ten new MLB-only columns), so there's no client-side weight-adjusted
// recompute for MLB yet. Team schedule (getMlbTeamSchedule) and season
// window (seasonWindowService.ts's getMlbSeasonWindow) are both wired up.
// Can be widened the same way soccer was once weight-adjusted recompute is
// actually wanted.
//
// Highlights search (checkPendingMlbHighlights below) is built and verified
// against real ESPN/YouTube data but deliberately NOT called from anywhere -
// not highlightsPoller.ts, not processMlbEvent below - per James's explicit
// call to have it ready without actually going live yet. Wiring it in later
// is a one-line change: import checkPendingMlbHighlights into
// highlightsPoller.ts's pollOnce() alongside the existing
// checkPendingHighlights() call.
import { toEspnDate } from "./espnClient";
import {
  GameRow,
  canSpendSearchQuota,
  getFinalMlbGamesMissingHighlights,
  getGame,
  markHighlightsChecked,
  recordSearchQuotaSpend,
  setHighlights,
  setMlbFinalRubric,
  setPreview,
  setSeasonStageLabel,
  updateStatus,
  upsertBaseEntry
} from "./gameStore";
import { isDueForHighlightsCheck } from "./gamesService";
import { mapMlbEspnState, mapMlbEventToGame } from "./mlbGameMapper";
import { EspnMlbEvent, fetchMlbCalendarDates, fetchMlbScoreboard, fetchMlbSummary, fetchMlbTeamSchedule } from "./mlbEspnClient";
import { generateHookAndStakes } from "./llm";
import { computeMlbWatchabilityScore, tierForMlbScore } from "./mlbRubric";
import { GameJson } from "./types";
import { isYoutubeSearchConfigured, searchHighlightsVideo } from "./youtubeClient";

export function isMlbLeagueGroup(leagueGroup: string): leagueGroup is "mlb" {
  return leagueGroup === "mlb";
}

// Same 24h-before-first-pitch gate as gamesService.ts/soccerGamesService.ts - no reason for MLB previews to populate on a different schedule.
const PREVIEW_GATE_HOURS_BEFORE_TIPOFF = 24;

function hasReachedPreviewGate(tipoffUtc: string, now: Date = new Date()): boolean {
  const gateTime = new Date(tipoffUtc).getTime() - PREVIEW_GATE_HOURS_BEFORE_TIPOFF * 60 * 60 * 1000;
  return now.getTime() >= gateTime;
}

function fallbackHook(away: string, home: string): string {
  return `${away} at ${home}.`;
}

// Fallback/regular-season label - exported so migrateToGameStore.ts's MLB
// historical backfill can stamp the exact same default live games get,
// rather than duplicating the string.
export const COMPETITION_LABEL = "MLB - Regular Season";

// Real per-round postseason labels, confirmed directly against live ESPN
// data (curl'd real scoreboard responses across the 2025 postseason, not
// assumed): competitions[0].notes[0].headline reliably distinguishes each
// round - "ALWC - Game N"/"NLWC - Game N" (wild card - ESPN uses the
// abbreviation here, not the spelled-out "Wild Card" NBA/NFL notes use
// elsewhere), "ALDS - Game N"/"NLDS - Game N" (division series, AL/NL
// collapsed into one label the same way NBA's own East/West conference
// rounds already do), "ALCS - Game N"/"NLCS - Game N" (championship series,
// same collapsing), "World Series - Game N". This is what actually lets
// "This season" close out for MLB: every game used to get the identical
// fixed COMPETITION_LABEL regardless of postseason status, so the World
// Series - the one game gameStore.ts's getMostRecentFinalsEnd searches for
// - was indistinguishable from a regular-season game.
export function deriveMlbCompetitionLabel(event: EspnMlbEvent): string {
  if (event.season?.type !== 3) return COMPETITION_LABEL;
  const headline = event.competitions[0]?.notes?.[0]?.headline?.toLowerCase() ?? "";
  if (headline.includes("alwc") || headline.includes("nlwc") || headline.includes("wild card")) return "MLB - Playoffs: Wild Card";
  if (headline.includes("alds") || headline.includes("nlds")) return "MLB - Playoffs: Division Series";
  if (headline.includes("alcs") || headline.includes("nlcs")) return "MLB - Playoffs: Championship Series";
  if (headline.includes("world series")) return "MLB - Playoffs: World Series";
  return "MLB - Playoffs";
}

async function ensureMlbPregamePreview(row: GameRow): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(row.tipoffUtc)) return; // not time yet

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: row.away,
    home: row.home,
    league: "MLB"
  });

  setPreview(row.eventId, hook, pitch, stakes);
}

function toMlbGameJson(row: GameRow, status: "upcoming" | "live", q?: number, clk?: string): GameJson {
  return {
    id: row.eventId,
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: status,
    utc: row.tipoffUtc,
    lg: "mlb",
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
 * MLB analogue of soccerGamesService.ts's processSoccerEvent.
 */
async function processMlbEvent(event: EspnMlbEvent): Promise<GameJson> {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;
  const status = mapMlbEspnState(competition.status.type.state);

  upsertBaseEntry({
    eventId: event.id,
    league: "mlb",
    leagueGroup: "mlb",
    away: away.team.displayName,
    home: home.team.displayName,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    tipoffUtc: event.date,
    status
  });
  updateStatus(event.id, status);
  setSeasonStageLabel(event.id, deriveMlbCompetitionLabel(event));

  let row = getGame(event.id)!;

  if (status !== "final") {
    await ensureMlbPregamePreview(row);
    row = getGame(event.id)!;
  }

  if (status === "upcoming") return toMlbGameJson(row, "upcoming");
  if (status === "live") return toMlbGameJson(row, "live", competition.status.period, competition.status.displayClock);

  // final: compute the rubric once, ever - already-set fields are never touched again.
  if (row.score === null) {
    const summary = await fetchMlbSummary(event.id);
    const mapped = mapMlbEventToGame(event, summary);
    const score = computeMlbWatchabilityScore(mapped.rubricInputs, row.stakes ?? undefined).total;
    setMlbFinalRubric(event.id, {
      awayScore: mapped.awayScore,
      homeScore: mapped.homeScore,
      score,
      tier: tierForMlbScore(score),
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
    lg: "mlb",
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
    mlbInputs: row.mlbRubricInputs ?? undefined
  };
}

// Regular-season and postseason only - spring training (type 1) is
// deliberately excluded, same rule backfillRawStatsMlb.ts already validated
// against real data (a spring-training final has no real stakes and pads
// the Games tab with exhibition noise). Exported so seasonWindowService.ts's
// getMlbSeasonWindow can reuse the exact same "skip spring training" rule
// when scanning for the real regular-season start date, rather than
// re-deriving it.
export function isRealSeasonEvent(event: EspnMlbEvent): boolean {
  return event.season?.type !== 1 && event.season?.slug !== "preseason" && event.season?.slug !== "off-season";
}

/** Fetches, scores, and caches one day's MLB games - the MLB analogue of soccerGamesService.ts's getSoccerGamesForDate. */
export async function getMlbGamesForDate(date: string): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const events = (await fetchMlbScoreboard(espnDate)).filter(isRealSeasonEvent);

  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processMlbEvent(event));
  }
  return results;
}

/**
 * A single favorited team's own real schedule (past and upcoming, current
 * season) - backs the Favorites tab's Games page, MLB analogue of
 * gamesService.ts's getTeamSchedule / soccerGamesService.ts's
 * getSoccerTeamSchedule. Reuses the same processMlbEvent pipeline every
 * other MLB event (day-based or team-based) goes through, so a favorited
 * team's games get the same preview/final-rubric treatment as the Games
 * tab's own slate.
 */
export async function getMlbTeamSchedule(teamId: string): Promise<GameJson[]> {
  const events = (await fetchMlbTeamSchedule(teamId)).filter(isRealSeasonEvent);
  const results: GameJson[] = [];
  for (const event of events) {
    results.push(await processMlbEvent(event));
  }
  return results;
}

/** The next date, strictly after [afterDate], that MLB actually has a scheduled game - MLB analogue of gamesService.ts's getNextScheduledDate. */
export async function getNextMlbScheduledDate(afterDate: string): Promise<string | undefined> {
  const anchorEspnDate = toEspnDate(new Date(`${afterDate}T12:00:00Z`));
  const calendarDates = await fetchMlbCalendarDates(anchorEspnDate);
  const candidateDates = calendarDates.filter((d) => d > afterDate).sort();

  for (const date of candidateDates) {
    const games = await getMlbGamesForDate(date);
    if (games.length > 0) return date;
  }
  return undefined;
}

/**
 * Searches for and records one MLB game's highlights match - the MLB
 * analogue of gamesService.ts's checkGameHighlights, sharing every piece of
 * that function's design (the same daily-budget guard via
 * canSpendSearchQuota, the same "always mark checked, win or miss" bookkeeping
 * via markHighlightsChecked, the same real-upload-timestamp persistence via
 * setHighlights) - only the channel searched (youtubeClient.ts's
 * HighlightsLeague "mlb" branch, MLB's real channel id UCoLrcjPV5PbUrUyXq5mjc_A,
 * confirmed directly against youtube.com/@MLB) differs. Not called from
 * anywhere yet - see this file's header comment.
 */
async function checkMlbGameHighlights(row: GameRow): Promise<void> {
  if (!canSpendSearchQuota()) {
    console.warn(`checkMlbGameHighlights: daily search budget spent, deferring ${row.away} @ ${row.home}`);
    return;
  }

  console.log(`checkMlbGameHighlights: searching for ${row.away} @ ${row.home} (${row.eventId})`);
  recordSearchQuotaSpend();
  const match = await searchHighlightsVideo("mlb", row.away, row.home, row.tipoffUtc);
  markHighlightsChecked(row.eventId, new Date().toISOString());
  if (match) {
    setHighlights(row.eventId, match.videoId, match.publishedAt);
    console.log(`checkMlbGameHighlights: matched "${match.title}" (${match.videoId})`);
  } else {
    console.log(`checkMlbGameHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
  }
}

// Same bound as gamesService.ts's MAX_CHECKS_PER_POLL, same reasoning
// (defense in depth against a big backlog spending the daily budget in one
// tick) - kept as MLB's own constant rather than importing basketball's so
// this file doesn't reach into gamesService.ts's private module state for a
// value that's allowed to diverge later even though it starts identical.
const MAX_MLB_CHECKS_PER_POLL = 20;

/**
 * MLB analogue of gamesService.ts's checkPendingHighlights - reuses the
 * exact same isDueForHighlightsCheck (imported from gamesService.ts) so the
 * fixed 15-min-then-30-min two-check schedule is identical to basketball's,
 * per James's explicit call to keep the algorithm the same rather than
 * tuning a separate one for MLB.
 *
 * The "learning" half of that request is already satisfied for free, with
 * no MLB-specific code needed: gameStore.ts's getLagPercentiles(league,
 * leagueGroup) is fully generic and will compute a real per-league median
 * upload lag for MLB the moment 5+ real matches exist (MIN_LAG_SAMPLES),
 * fed by the same yt_published_at/final_at columns setHighlights/
 * setMlbFinalRubric already populate. It's informational-only rather than
 * gating when checks fire - exactly like basketball's own copy - because an
 * earlier version of this idea for basketball was found to be
 * self-reinforcing (a check that never looks before its own current
 * estimate can mechanically never discover a shorter true delay, only ever
 * confirm or raise its guess - see gamesService.ts's YT_FIRST_CHECK_DELAY_MS
 * comment for the full explanation). Narrowing the estimate over time still
 * happens automatically; it just isn't trusted to decide *when* to look,
 * for the same reason it isn't trusted for basketball.
 *
 * Not called from highlightsPoller.ts or anywhere else yet - built,
 * verified against real ESPN/YouTube data, ready to wire in.
 */
export async function checkPendingMlbHighlights(): Promise<void> {
  if (!isYoutubeSearchConfigured()) return;

  const now = Date.now();
  let checksThisPoll = 0;
  for (const row of getFinalMlbGamesMissingHighlights()) {
    if (checksThisPoll >= MAX_MLB_CHECKS_PER_POLL) break;
    if (isDueForHighlightsCheck(row, now)) {
      await checkMlbGameHighlights(row);
      checksThisPoll++;
    }
  }
}
