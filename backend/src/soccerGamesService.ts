// Soccer's equivalent of gamesService.ts's live-schedule pipeline - kept as
// a separate file/dispatch target (see types.ts's SPORT_FOR_LEAGUE_GROUP)
// rather than branching inside gamesService.ts itself, since soccer's ESPN
// shape and rubric don't overlap enough with basketball's to share real
// logic beyond the generic gameStore setters and the LLM preview call.
// Domestic leagues (EPL/La Liga) are a flat round-robin table; "fifa-world"
// (the FIFA World Cup) is a group-stage-then-knockout-bracket tournament
// instead - that difference doesn't actually matter to this file, since
// getSoccerGamesForDate just asks ESPN "what's scheduled on date X" and
// scores whatever comes back, day by day, with no notion of season/bracket
// structure baked in. It only surfaces in the two places a tournament
// genuinely differs from a league: the competitionLabel (a named knockout
// stage like "Semifinals", not a flat "Regular Season") and the stakes
// prompt (win-or-go-home elimination, not a mid-table fixture).
//
// No highlights search yet (no soccer YouTube channel configured). History
// itself is served from the durable store (historyService.ts) rather than
// this file, but every row still needs its season_stage_label persisted
// here - a live game reaches "final" through this pipeline, not the
// backfill, so if this file never wrote that label, any soccer game that
// goes final from today onward would fall back to historyService.ts's
// plain-season-year label instead of the nicer "EPL - Regular Season" the
// backfilled rows get. ("fifa-world" has no backfill/History route at all -
// Games-tab-only, James's explicit call given the tournament's ~2-day
// remaining shelf life when this was added - so this doesn't matter for it.)
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
import { decidedByShootout, mapSoccerEspnState, mapSoccerEventToGame, wentToExtraTime } from "./soccerGameMapper";
import { fetchSoccerCalendarDates, fetchSoccerScoreboard, fetchSoccerSummary, SoccerLeague } from "./soccerEspnClient";
import { generateHookAndStakes } from "./llm";
import { tierForScore } from "./rubric";
import { computeSoccerWatchabilityScore } from "./soccerRubric";
import { GameJson } from "./types";

export type SoccerLeagueGroup = "epl" | "la-liga" | "fifa-world";

export const SOCCER_LEAGUE_FOR_GROUP: Record<SoccerLeagueGroup, SoccerLeague> = {
  epl: "eng.1",
  "la-liga": "esp.1",
  "fifa-world": "fifa.world"
};

// Exported for reuse by historyService.ts, which needs the same display
// name to build a competition label for any backfilled row that predates
// the season_stage_label column (mirrors gamesService.ts's own fallback
// path for basketball rows). historyService.ts never actually sees a
// "fifa-world" row today (no backfill for this leagueGroup), but the entry
// is kept here anyway so this map stays total over SoccerLeagueGroup.
export const LEAGUE_DISPLAY_NAME: Record<SoccerLeagueGroup, string> = {
  epl: "EPL",
  "la-liga": "La Liga",
  "fifa-world": "FIFA World Cup"
};

export function isSoccerLeagueGroup(leagueGroup: string): leagueGroup is SoccerLeagueGroup {
  return leagueGroup === "epl" || leagueGroup === "la-liga" || leagueGroup === "fifa-world";
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

// ESPN's per-event season.slug for fifa-world - NOT the scoreboard
// response's top-level leagues[0].season.type.name, which was confirmed
// directly against real data to lag exactly at the tournament's last two
// stages (it still reads "Semifinals" on the 3rd-Place Match and Final's
// own dates). Each event's own slug is accurate for every stage checked
// (group-stage/round-of-32/round-of-16/quarterfinals/semifinals/
// 3rd-place-match/final).
const WORLD_CUP_STAGE_LABEL: Record<string, string> = {
  "group-stage": "Group Stage",
  "round-of-32": "Round of 32",
  "round-of-16": "Round of 16",
  quarterfinals: "Quarterfinals",
  semifinals: "Semifinals",
  "3rd-place-match": "3rd-Place Match",
  final: "Final"
};

function worldCupStageLabel(slug: string | undefined): string | undefined {
  if (!slug) return undefined;
  return WORLD_CUP_STAGE_LABEL[slug] ?? slug.split("-").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ");
}

// Only fifa-world has genuinely elimination-or-not stakes worth flagging
// explicitly - a domestic league fixture's stakes (relegation battle, title
// race) are already conveyed well enough by team names/form alone, which
// generateHookAndStakes already sees. A World Cup knockout match is a
// fundamentally different kind of high-stakes (lose and the tournament is
// over for you) than a group-stage game where both sides may have already
// secured/lost advancement - worth telling the LLM apart rather than
// leaving it to guess from team names alone.
function tournamentStakesNote(leagueGroup: SoccerLeagueGroup, stageLabel: string | undefined): string | undefined {
  if (leagueGroup !== "fifa-world" || !stageLabel) return undefined;
  return stageLabel === "Group Stage"
    ? "FIFA World Cup group stage - group standing and advancement are on the line"
    : `FIFA World Cup ${stageLabel} - single-elimination knockout match, loser is out of the tournament`;
}

// EPL/La Liga's competition label has always been a flat "- Regular
// Season" suffix (accurate for a round-robin table with no named stages).
// fifa-world instead uses the per-event stage label - a knockout match
// tagged "Regular Season" would read as simply wrong, not just
// uninformative the way it would for a league.
function competitionLabelFor(leagueGroup: SoccerLeagueGroup, stageLabel: string | undefined): string {
  const displayName = LEAGUE_DISPLAY_NAME[leagueGroup];
  if (leagueGroup === "fifa-world") return stageLabel ? `${displayName} - ${stageLabel}` : displayName;
  return `${displayName} - Regular Season`;
}

async function ensureSoccerPregamePreview(row: GameRow, leagueGroup: SoccerLeagueGroup, stageLabel?: string): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(row.tipoffUtc)) return; // not time yet

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: row.away,
    home: row.home,
    league: LEAGUE_DISPLAY_NAME[leagueGroup],
    notes: tournamentStakesNote(leagueGroup, stageLabel)
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

  const results: GameJson[] = [];

  for (const event of events) {
    const competition = event.competitions[0];
    const away = competition.competitors.find((c) => c.homeAway === "away")!;
    const home = competition.competitors.find((c) => c.homeAway === "home")!;
    const status = mapSoccerEspnState(competition.status.type.state);
    // Computed per-event, not once per date - a knockout tournament can (and
    // for the World Cup's 3rd-Place Match/Final, does) have a date whose
    // real stage differs from the scoreboard's own top-level "current
    // phase" pointer. Irrelevant for domestic leagues (every event on a
    // date shares the same flat "Regular Season" label either way).
    const stageLabel = leagueGroup === "fifa-world" ? worldCupStageLabel(event.season?.slug) : undefined;
    const competitionLabel = competitionLabelFor(leagueGroup, stageLabel);

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
      await ensureSoccerPregamePreview(row, leagueGroup, stageLabel);
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
        anyPenaltyMissed: mapped.rubricInputs.anyPenaltyMissed,
        wentToExtraTime: mapped.rubricInputs.wentToExtraTime,
        decidedByShootout: mapped.rubricInputs.decidedByShootout
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
      et: Boolean(row.wentToExtraTime),
      pk: Boolean(row.decidedByShootout),
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
