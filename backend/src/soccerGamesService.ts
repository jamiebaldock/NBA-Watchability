// Soccer's equivalent of gamesService.ts's live-schedule pipeline - kept as
// a separate file/dispatch target (see types.ts's SPORT_FOR_LEAGUE_GROUP)
// rather than branching inside gamesService.ts itself, since soccer's ESPN
// shape, rubric, and season structure (no playoffs, no Play-In, a single
// round-robin table) don't overlap enough with basketball's to share real
// logic beyond the generic gameStore setters and the LLM preview call.
//
// No highlights search yet (no soccer YouTube channel configured). History
// itself is served from the durable store (historyService.ts) rather than
// this file, but every row still needs its season_stage_label persisted
// here - a live game reaches "final" through this pipeline, not the
// backfill, so if this file never wrote that label, any soccer game that
// goes final from today onward would fall back to historyService.ts's
// plain-season-year label instead of the nicer "EPL - Regular Season" the
// backfilled rows get.
import { toEspnDate } from "./espnClient";
import {
  GameRow,
  getGame,
  setPreview,
  setSeasonStageLabel,
  setSoccerFinalRubric,
  updateStatus,
  upsertBaseEntry
} from "./gameStore";
import { mapSoccerEspnState, mapSoccerEventToGame } from "./soccerGameMapper";
import { fetchSoccerCalendarDates, fetchSoccerScoreboard, fetchSoccerSummary, SoccerLeague } from "./soccerEspnClient";
import { generateHookAndStakes } from "./llm";
import { tierForScore } from "./rubric";
import { computeSoccerWatchabilityScore } from "./soccerRubric";
import { GameJson } from "./types";

export type SoccerLeagueGroup = "epl" | "la-liga";

export const SOCCER_LEAGUE_FOR_GROUP: Record<SoccerLeagueGroup, SoccerLeague> = {
  epl: "eng.1",
  "la-liga": "esp.1"
};

// Exported for reuse by historyService.ts, which needs the same display
// name to build a competition label for any backfilled row that predates
// the season_stage_label column (mirrors gamesService.ts's own fallback
// path for basketball rows).
export const LEAGUE_DISPLAY_NAME: Record<SoccerLeagueGroup, string> = {
  epl: "EPL",
  "la-liga": "La Liga"
};

export function isSoccerLeagueGroup(leagueGroup: string): leagueGroup is SoccerLeagueGroup {
  return leagueGroup === "epl" || leagueGroup === "la-liga";
}

// Same 24h-before-tipoff gate as gamesService.ts's basketball pipeline -
// no reason for soccer previews to populate on a different schedule.
const PREVIEW_GATE_HOURS_BEFORE_TIPOFF = 24;

function hasReachedPreviewGate(tipoffUtc: string, now: Date = new Date()): boolean {
  const gateTime = new Date(tipoffUtc).getTime() - PREVIEW_GATE_HOURS_BEFORE_TIPOFF * 60 * 60 * 1000;
  return now.getTime() >= gateTime;
}

function fallbackHook(away: string, home: string): string {
  return `${away} at ${home}.`;
}

async function ensureSoccerPregamePreview(row: GameRow, leagueGroup: SoccerLeagueGroup): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(row.tipoffUtc)) return; // not time yet

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: row.away,
    home: row.home,
    league: LEAGUE_DISPLAY_NAME[leagueGroup]
  });

  setPreview(row.eventId, hook, pitch, stakes);
}

function toSoccerGameJson(row: GameRow, competitionLabel: string, status: "upcoming" | "live", q?: number, clk?: string): GameJson {
  return {
    id: row.eventId,
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: status,
    utc: row.tipoffUtc,
    lg: "soccer",
    cl: competitionLabel,
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
 * Fetches, scores, and caches one day's soccer games - the soccer analogue
 * of gamesService.ts's getGamesForDate. The one expensive ESPN call
 * (fetchSoccerSummary - keyEvents + box score) only ever runs once per game,
 * the first time it's seen final, exactly like the basketball pipeline.
 */
export async function getSoccerGamesForDate(date: string, leagueGroup: SoccerLeagueGroup): Promise<GameJson[]> {
  const league = SOCCER_LEAGUE_FOR_GROUP[leagueGroup];
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const events = await fetchSoccerScoreboard(espnDate, league);
  const competitionLabel = `${LEAGUE_DISPLAY_NAME[leagueGroup]} - Regular Season`;

  const results: GameJson[] = [];

  for (const event of events) {
    const competition = event.competitions[0];
    const away = competition.competitors.find((c) => c.homeAway === "away")!;
    const home = competition.competitors.find((c) => c.homeAway === "home")!;
    const status = mapSoccerEspnState(competition.status.type.state);

    upsertBaseEntry({
      eventId: event.id,
      league,
      leagueGroup,
      away: away.team.displayName,
      home: home.team.displayName,
      awayLogo: away.team.logo,
      homeLogo: home.team.logo,
      tipoffUtc: event.date,
      status
    });
    updateStatus(event.id, status);
    setSeasonStageLabel(event.id, competitionLabel);

    let row = getGame(event.id)!;

    if (status !== "final") {
      await ensureSoccerPregamePreview(row, leagueGroup);
      row = getGame(event.id)!;
    }

    if (status === "upcoming") {
      results.push(toSoccerGameJson(row, competitionLabel, "upcoming"));
      continue;
    }

    if (status === "live") {
      results.push(toSoccerGameJson(row, competitionLabel, "live", competition.status.period, competition.status.displayClock));
      continue;
    }

    // final: compute the rubric once, ever - already-set fields are never touched again.
    if (row.score === null) {
      const summary = await fetchSoccerSummary(event.id, league);
      const mapped = mapSoccerEventToGame(event, summary);
      const score = computeSoccerWatchabilityScore(mapped.rubricInputs, row.stakes ?? undefined).total;
      setSoccerFinalRubric(event.id, {
        awayScore: mapped.awayScore,
        homeScore: mapped.homeScore,
        score,
        tier: tierForScore(score),
        standoutPerformers: mapped.standoutPerformers,
        finalMargin: mapped.rubricInputs.margin,
        largestDeficitOvercome: mapped.rubricInputs.largestDeficitOvercome,
        totalGoals: mapped.rubricInputs.totalGoals,
        lateDecisiveGoal: mapped.rubricInputs.lateDecisiveGoal,
        maxGoalsByPlayer: mapped.rubricInputs.maxGoalsByPlayer,
        combinedShotsOnTarget: mapped.rubricInputs.combinedShotsOnTarget,
        anyRedCard: mapped.rubricInputs.anyRedCard,
        maxSavesByKeeper: mapped.rubricInputs.maxSavesByKeeper,
        anyFreeKickGoal: mapped.rubricInputs.anyFreeKickGoal,
        anyPenaltyMissed: mapped.rubricInputs.anyPenaltyMissed
      });
      row = getGame(event.id)!;
    }

    results.push({
      id: row.eventId,
      a: row.away,
      h: row.home,
      al: row.awayLogo ?? undefined,
      hl: row.homeLogo ?? undefined,
      stt: "final",
      utc: row.tipoffUtc,
      lg: "soccer",
      cl: competitionLabel,
      ot: 0,
      c5: false,
      lcf: false,
      fp: false,
      bz: false,
      st: null,
      sop: row.standoutPerformers,
      m: row.finalMargin ?? undefined,
      cb: row.largestDeficitOvercome ?? undefined,
      tg: row.totalGoals ?? undefined,
      ldg: Boolean(row.lateDecisiveGoal),
      mgp: row.maxGoalsByPlayer ?? undefined,
      cst: row.combinedShotsOnTarget ?? undefined,
      rc: Boolean(row.anyRedCard),
      sv: row.maxSavesByKeeper ?? undefined,
      fkg: Boolean(row.anyFreeKickGoal),
      pm: Boolean(row.anyPenaltyMissed),
      sk: row.stakes ?? undefined,
      hook: row.hook ?? fallbackHook(row.away, row.home),
      pitch: row.pitch ?? undefined,
      score: row.score ?? undefined,
      score_visible: true,
      yt: row.ytVideoId ?? undefined
    });
  }

  return results;
}

/**
 * The next date, strictly after [afterDate], that [leagueGroup] actually has
 * a scheduled match - soccer analogue of gamesService.ts's
 * getNextScheduledDate, backing the same "jump to next game" action.
 */
export async function getNextSoccerScheduledDate(afterDate: string, leagueGroup: SoccerLeagueGroup): Promise<string | undefined> {
  const league = SOCCER_LEAGUE_FOR_GROUP[leagueGroup];
  const anchorEspnDate = toEspnDate(new Date(`${afterDate}T12:00:00Z`));
  const calendarDates = await fetchSoccerCalendarDates(anchorEspnDate, league);
  const candidateDates = calendarDates.filter((d) => d > afterDate).sort();

  for (const date of candidateDates) {
    const games = await getSoccerGamesForDate(date, leagueGroup);
    if (games.length > 0) return date;
  }
  return undefined;
}
