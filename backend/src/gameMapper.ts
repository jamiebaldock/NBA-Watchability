import { EspnBoxscoreTeamPlayers, EspnEvent, EspnPlay, EspnSummary, League } from "./espnClient";
import { GameStatus, RubricInputs, StarPerformance } from "./types";

/** ESPN clock is "mm:ss" above 1 minute, plain seconds ("4.0", "27.1") under it. */
export function parseClockSeconds(display: string): number {
  if (display.includes(":")) {
    const [m, s] = display.split(":").map(Number);
    return m * 60 + s;
  }
  return parseFloat(display);
}

export function mapEspnState(state: EspnEvent["competitions"][number]["status"]["type"]["state"]): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

type LeadState = "home" | "away" | "tie";

function leadStateAt(homeScore: number, awayScore: number): LeadState {
  if (homeScore > awayScore) return "home";
  if (awayScore > homeScore) return "away";
  return "tie";
}

export function countLeadChangesAndTies(plays: EspnPlay[]): number {
  let count = 0;
  let prev: LeadState | null = null;
  for (const play of plays) {
    const state = leadStateAt(play.homeScore, play.awayScore);
    if (prev !== null && state !== prev) count++;
    prev = state;
  }
  return count;
}

/** How far below the eventual winner ever fell, at its worst point. */
export function largestDeficitOvercome(plays: EspnPlay[], winnerHomeAway: "home" | "away"): number {
  let worst = 0;
  for (const play of plays) {
    const winnerScore = winnerHomeAway === "home" ? play.homeScore : play.awayScore;
    const loserScore = winnerHomeAway === "home" ? play.awayScore : play.homeScore;
    const deficit = loserScore - winnerScore;
    if (deficit > worst) worst = deficit;
  }
  return worst;
}

function finalPeriodOf(plays: EspnPlay[]): number {
  return plays.reduce((max, p) => Math.max(max, p.period.number), 0);
}

export function closeInFinalTwoMin(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  return plays.some(
    (p) =>
      p.period.number === finalPeriod &&
      parseClockSeconds(p.clock.displayValue) <= 120 &&
      Math.abs(p.homeScore - p.awayScore) <= 5
  );
}

export function leadChangeInFinalMin(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  let prev: LeadState | null = null;
  let changedInWindow = false;
  for (const play of plays) {
    const state = leadStateAt(play.homeScore, play.awayScore);
    const inWindow = play.period.number === finalPeriod && parseClockSeconds(play.clock.displayValue) <= 60;
    if (prev !== null && state !== prev && inWindow) changedInWindow = true;
    prev = state;
  }
  return changedInWindow;
}

/**
 * Heuristic (ESPN has no explicit "game-deciding play" flag): the last scoring
 * play, made within the final 24 seconds (one shot-clock) of regulation/OT,
 * itself flipped who was ahead (tie->lead or lead->lead-change) or broke a tie.
 * This also catches buzzer-beaters, since a game-winning shot by definition
 * changes the leader.
 */
export function decidedOnFinalPossession(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  const scoringPlays = plays.filter((p) => p.scoringPlay);
  if (scoringPlays.length === 0) return false;
  const last = scoringPlays[scoringPlays.length - 1];
  if (last.period.number !== finalPeriod) return false;
  if (parseClockSeconds(last.clock.displayValue) > 24) return false;

  const idx = plays.indexOf(last);
  const before = idx > 0 ? plays[idx - 1] : null;
  const stateBefore = before ? leadStateAt(before.homeScore, before.awayScore) : "tie";
  const stateAfter = leadStateAt(last.homeScore, last.awayScore);
  return stateBefore !== stateAfter;
}

/**
 * Game-winning field goal (not a free throw) in the final 3 seconds. A made
 * shot that late only counts if it actually flipped (or broke a tied) lead —
 * a make that merely cuts the final margin without changing the winner is
 * not a "game-winner" (e.g. a garbage-time three at the buzzer of a loss).
 */
export function isBuzzerBeater(plays: EspnPlay[]): boolean {
  const finalPeriod = finalPeriodOf(plays);
  const scoringPlays = plays.filter((p) => p.scoringPlay);
  if (scoringPlays.length === 0) return false;
  const last = scoringPlays[scoringPlays.length - 1];
  if (last.period.number !== finalPeriod || !last.shootingPlay) return false;
  if (parseClockSeconds(last.clock.displayValue) > 3) return false;

  const idx = plays.indexOf(last);
  const before = idx > 0 ? plays[idx - 1] : null;
  const stateBefore = before ? leadStateAt(before.homeScore, before.awayScore) : "tie";
  const stateAfter = leadStateAt(last.homeScore, last.awayScore);
  return stateBefore !== stateAfter;
}

export function overtimePeriodsFrom(plays: EspnPlay[]): number {
  return Math.max(0, finalPeriodOf(plays) - 4);
}

const STAT_CATEGORIES = ["PTS", "REB", "AST", "STL", "BLK"] as const;

// Point thresholds are calibrated per league, not universal: a 40-point game
// is unremarkable in the NBA but has happened only ~30 times in WNBA history
// (28 seasons) - using NBA thresholds on WNBA data would make "historic"/
// "great" nearly unreachable. Triple-double thresholds are left as-is (a
// 10-10-10 line is the same fixed statistical achievement in either league).
function starPointThresholds(league: League): { historic: number; great: number; good: number } {
  return league === "wnba"
    ? { historic: 40, great: 30, good: 25 }
    : { historic: 50, great: 40, good: 35 };
}

export function classifyStarPerformance(
  playersBoxscore: EspnBoxscoreTeamPlayers[] | undefined,
  league: League
): StarPerformance {
  if (!playersBoxscore || playersBoxscore.length === 0) return null;

  let maxPoints = 0;
  let anyTripleDouble = false;
  let anyNearTripleDouble = false;

  for (const team of playersBoxscore) {
    for (const statBlock of team.statistics) {
      const indices = STAT_CATEGORIES.map((cat) => statBlock.labels.indexOf(cat));
      for (const athlete of statBlock.athletes) {
        const values = indices.map((i) => (i >= 0 ? parseFloat(athlete.stats[i]) || 0 : 0));
        const [pts, reb, ast, stl, blk] = values;
        maxPoints = Math.max(maxPoints, pts);

        const doubleDigitCats = values.filter((v) => v >= 10).length;
        if (doubleDigitCats >= 3) anyTripleDouble = true;
        else if (doubleDigitCats >= 2 && values.some((v) => v >= 8 && v < 10)) anyNearTripleDouble = true;
      }
    }
  }

  const { historic, great, good } = starPointThresholds(league);
  if (maxPoints >= historic) return "historic";
  if (maxPoints >= great || anyTripleDouble) return "great";
  if (maxPoints >= good || anyNearTripleDouble) return "good";
  return null;
}

export interface MappedGame {
  away: string;
  home: string;
  status: GameStatus;
  tipoffUtc: string;
  rubric: RubricInputs;
}

export function mapEventToGame(event: EspnEvent, league: League, summary?: EspnSummary): MappedGame {
  const competition = event.competitions[0];
  const status = mapEspnState(competition.status.type.state);
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const plays = summary?.plays ?? [];
  const hasPlays = plays.length > 0;

  let rubric: RubricInputs;

  if (!hasPlays) {
    rubric = {
      status,
      period: status === "live" ? competition.status.period : undefined,
      clock: status === "live" ? competition.status.displayClock : undefined,
      overtimePeriods: 0,
      closeInFinalTwoMin: false,
      leadChangeInFinalMin: false,
      decidedOnFinalPossession: false,
      buzzerBeater: false,
      starPerformance: null,
    };
  } else {
    const winnerHomeAway: "home" | "away" =
      parseFloat(home.score) >= parseFloat(away.score) ? "home" : "away";

    rubric = {
      status,
      period: status === "live" ? competition.status.period : undefined,
      clock: status === "live" ? competition.status.displayClock : undefined,
      finalMargin: Math.abs(parseFloat(home.score) - parseFloat(away.score)),
      largestDeficitOvercome: largestDeficitOvercome(plays, winnerHomeAway),
      leadChanges: countLeadChangesAndTies(plays),
      overtimePeriods: overtimePeriodsFrom(plays),
      closeInFinalTwoMin: closeInFinalTwoMin(plays),
      leadChangeInFinalMin: leadChangeInFinalMin(plays),
      decidedOnFinalPossession: decidedOnFinalPossession(plays),
      buzzerBeater: isBuzzerBeater(plays),
      starPerformance: classifyStarPerformance(summary?.boxscore?.players, league),
    };
  }

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    status,
    tipoffUtc: event.date,
    rubric,
  };
}
