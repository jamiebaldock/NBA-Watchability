// Extracts SoccerRubricInputs (soccerRubric.ts) from ESPN's raw soccer
// event + summary data - the soccer analogue of gameMapper.ts, kept as a
// separate file since the two sports' ESPN shapes and rubric inputs don't
// overlap enough to share real code beyond mapEspnState (reused as-is,
// status.type.state is "pre"/"in"/"post" for both sports).
//
// The goal-parsing logic below (largestDeficitOvercome/lateGoalChanged/
// maxGoalsByPlayer) is a direct port of the same logic already validated in
// soccerRubric.test.ts against the real 380-match EPL 2025-26 season fixture
// - this is what makes it safe to trust here instead of a fresh guess.
import { EspnSoccerBoxscoreTeam, EspnSoccerEvent, EspnSoccerKeyEvent, EspnSoccerSummary } from "./soccerEspnClient";
import { SoccerRubricInputs } from "./soccerRubric";
import { GameStatus } from "./types";

export function mapSoccerEspnState(state: "pre" | "in" | "post"): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

interface ParsedGoal {
  team: string;
  minuteValueSeconds: number;
  scorer?: string;
  ownGoal: boolean;
  freeKick: boolean;
}

// scoringPlay (not a type.type text match) is the correct general filter -
// ESPN tags goals with many different sub-types describing how they were
// scored (goal---volley, goal---free-kick, own-goal, penalty---scored,
// plain "goal", ...), so matching only the literal "goal" type would silently
// drop volleys/headers/own goals/penalties. Confirmed directly against real
// matches while building this module (a volley and an own goal both showed
// up with distinct type.type strings, both with scoringPlay: true).
function parseGoals(keyEvents: EspnSoccerKeyEvent[]): ParsedGoal[] {
  return keyEvents
    .filter((e) => e.scoringPlay)
    .map((e) => ({
      team: e.team?.displayName ?? "",
      minuteValueSeconds: e.clock.value,
      scorer: e.participants?.[0]?.athlete?.displayName,
      ownGoal: e.type.type === "own-goal",
      freeKick: e.type.type === "goal---free-kick"
    }));
}

const LATE_THRESHOLD_SECONDS = 85 * 60;

/** How far below the eventual winner (or, in a draw, either side) ever fell, at its worst point. */
function largestDeficitOvercome(goals: ParsedGoal[], homeTeam: string, homeScore: number, awayScore: number): number {
  let home = 0;
  let away = 0;
  let maxDeficitForHome = 0;
  let maxDeficitForAway = 0;
  const sorted = [...goals].sort((a, b) => a.minuteValueSeconds - b.minuteValueSeconds);
  for (const g of sorted) {
    if (g.team === homeTeam) home++;
    else away++;
    if (away - home > maxDeficitForHome) maxDeficitForHome = away - home;
    if (home - away > maxDeficitForAway) maxDeficitForAway = home - away;
  }
  if (homeScore > awayScore) return maxDeficitForHome;
  if (awayScore > homeScore) return maxDeficitForAway;
  return Math.max(maxDeficitForHome, maxDeficitForAway);
}

/** Did a goal at/after the 85th minute (including stoppage) change the score state (new lead or new tie)? */
function lateGoalChangedResult(goals: ParsedGoal[], homeTeam: string): boolean {
  const sorted = [...goals].sort((a, b) => a.minuteValueSeconds - b.minuteValueSeconds);
  let home = 0;
  let away = 0;
  let prevState: string | null = null;
  let changed = false;
  for (const g of sorted) {
    if (g.team === homeTeam) home++;
    else away++;
    const state = home === away ? "tie" : home > away ? "home" : "away";
    if (g.minuteValueSeconds >= LATE_THRESHOLD_SECONDS && prevState !== null && state !== prevState) changed = true;
    prevState = state;
  }
  return changed;
}

/** Most goals scored by a single player - own goals and unattributed scorers don't count toward this. */
function maxGoalsByPlayer(goals: ParsedGoal[]): number {
  const counts: Record<string, number> = {};
  for (const g of goals) {
    if (g.ownGoal || !g.scorer) continue;
    counts[g.scorer] = (counts[g.scorer] ?? 0) + 1;
  }
  const vals = Object.values(counts);
  return vals.length ? Math.max(...vals) : 0;
}

function statValue(team: EspnSoccerBoxscoreTeam, name: string): number {
  const stat = team.statistics.find((s) => s.name === name);
  return stat ? parseFloat(stat.displayValue) : 0;
}

export interface MappedSoccerGame {
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  awayLogo?: string;
  homeLogo?: string;
  rubricInputs: SoccerRubricInputs;
}

/** Builds SoccerRubricInputs (soccerRubric.ts) straight from ESPN's raw event + summary - only meaningful once the match has actually gone final (goals/box-score stats are only complete then). */
export function mapSoccerEventToGame(event: EspnSoccerEvent, summary: EspnSoccerSummary): MappedSoccerGame {
  const competition = event.competitions[0];
  const home = competition.competitors.find((c) => c.homeAway === "home")!;
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const homeScore = parseInt(home.score, 10) || 0;
  const awayScore = parseInt(away.score, 10) || 0;

  const keyEvents = summary.keyEvents ?? [];
  const goals = parseGoals(keyEvents);

  const boxTeams = summary.boxscore?.teams ?? [];
  const combinedShotsOnTarget = boxTeams.reduce((sum, t) => sum + statValue(t, "shotsOnTarget"), 0);
  const anyRedCard = boxTeams.some((t) => statValue(t, "redCards") > 0);
  const maxSavesByKeeper = boxTeams.length ? Math.max(...boxTeams.map((t) => statValue(t, "saves"))) : 0;

  const penaltyEvents = keyEvents.filter((e) => e.type.type.startsWith("penalty---"));
  const anyPenaltyMissed = penaltyEvents.some((e) => e.type.type !== "penalty---scored");
  const anyFreeKickGoal = goals.some((g) => g.freeKick);

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    rubricInputs: {
      margin: Math.abs(homeScore - awayScore),
      totalGoals: homeScore + awayScore,
      largestDeficitOvercome: largestDeficitOvercome(goals, home.team.displayName, homeScore, awayScore),
      lateDecisiveGoal: lateGoalChangedResult(goals, home.team.displayName),
      maxGoalsByPlayer: maxGoalsByPlayer(goals),
      combinedShotsOnTarget,
      anyRedCard,
      maxSavesByKeeper,
      anyFreeKickGoal,
      anyPenaltyMissed
    }
  };
}
