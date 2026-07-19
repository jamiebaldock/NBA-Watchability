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
import { toEspnDate } from "./espnClient";
import { GameRow, getGame, setMlbFinalRubric, setPreview, setSeasonStageLabel, updateStatus, upsertBaseEntry } from "./gameStore";
import { mapMlbEspnState, mapMlbEventToGame } from "./mlbGameMapper";
import { EspnMlbEvent, fetchMlbCalendarDates, fetchMlbScoreboard, fetchMlbSummary, fetchMlbTeamSchedule } from "./mlbEspnClient";
import { generateHookAndStakes } from "./llm";
import { computeMlbWatchabilityScore, tierForMlbScore } from "./mlbRubric";
import { GameJson } from "./types";

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

const COMPETITION_LABEL = "MLB - Regular Season";

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
  setSeasonStageLabel(event.id, COMPETITION_LABEL);

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
      largestDeficitOvercome: mapped.rubricInputs.largestDeficitOvercome
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
    yt: row.ytVideoId ?? undefined
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
