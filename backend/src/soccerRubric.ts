// Dedicated soccer (EPL, to start) watchability rubric - a fresh design for
// soccer's own dynamics, not a reskin of the basketball rubric in rubric.ts.
// Dimensions and point brackets are grounded in real percentiles from the
// complete 380-match EPL 2025-26 season (soccerRubric.test.ts validates this
// module's tier output against that dataset). Backend-only for now - no
// LeagueGroup/GameJson/gameStore changes, since no soccer games are
// populated into any user-facing tab yet. There is nothing for a mobile-side
// port to recompute until soccer tiles actually get wired in.
import { stakesPoints } from "./rubric";

export interface SoccerRubricInputs {
  // Absolute goal difference - 0 is a draw.
  margin: number;
  totalGoals: number;
  // Largest deficit the eventual winner (or, in a draw, either side) ever
  // faced - 0 if the leader/eventual winner was never behind.
  largestDeficitOvercome: number;
  // Did a goal at/after the 85th minute (including stoppage) change the
  // score state (new lead or new tie)? Rarer and more decisive than just
  // "any goal that late," which is why only a state change earns points.
  lateDecisiveGoal: boolean;
  // Most goals scored by a single player in the match.
  maxGoalsByPlayer: number;
  // Optional - not every data source has shot stats.
  combinedShotsOnTarget?: number;
  anyRedCard?: boolean;
}

// Draws (27.4% of the 2025-26 EPL season) and 1-goal margins (37.6%) are the
// norm in soccer, unlike basketball where a 1-possession final margin is
// already rare - so the top two brackets cover most matches by design, and
// a 3+ goal blowout (14.7% of matches) earns nothing.
export function marginPoints(margin: number): number {
  const m = Math.abs(margin);
  if (m === 0) return 20;
  if (m === 1) return 15;
  if (m === 2) return 8;
  return 0;
}

// Mean 2.75 goals/match, median 3 - 6+ goal matches are rare (4.2%) and get
// the top bracket.
export function totalGoalsPoints(totalGoals: number): number {
  if (totalGoals <= 1) return 0;
  if (totalGoals <= 3) return 5;
  if (totalGoals <= 5) return 12;
  return 20;
}

// A multi-goal soccer comeback is much rarer than in basketball - in 67.6%
// of EPL matches this season the winner/leveler was never behind at all,
// and a 3+ goal swing didn't happen even once across the full 380-match
// season (max observed: 2). The 3+ bracket is kept for completeness even
// though it's unobserved so far.
export function comebackPoints(largestDeficitOvercome: number): number {
  if (largestDeficitOvercome <= 0) return 0;
  if (largestDeficitOvercome === 1) return 10;
  if (largestDeficitOvercome === 2) return 20;
  return 30;
}

export function lateDramaPoints(lateDecisiveGoal: boolean): number {
  return lateDecisiveGoal ? 15 : 0;
}

// Hat-tricks are genuinely rare in the EPL (1.8% of matches this season).
export function starPoints(maxGoalsByPlayer: number): number {
  if (maxGoalsByPlayer >= 3) return 15;
  if (maxGoalsByPlayer === 2) return 5;
  return 0;
}

// Optional dimension - combined shots-on-target as a tension/quality proxy
// where a data source has it (ESPN's free soccer API has no xG field).
export function chancesPoints(combinedShotsOnTarget: number | undefined): number {
  return (combinedShotsOnTarget ?? 0) >= 12 ? 8 : 0;
}

// Optional dimension, kept per explicit review call - a red card is a
// chaos/drama signal (10.5% of EPL matches this season).
export function redCardPoints(anyRedCard: boolean | undefined): number {
  return anyRedCard ? 5 : 0;
}

export interface SoccerScoreBreakdown {
  margin: number;
  totalGoals: number;
  comeback: number;
  lateDrama: number;
  star: number;
  chances: number;
  redCard: number;
  stakes: number;
  total: number;
}

// Point scale intentionally left unnormalized against the basketball
// rubric's ~100 max - soccer's own max (~113 with stakes) is its own scale,
// not meant to line up 1:1 with basketball's.
export function computeSoccerWatchabilityScore(
  inputs: SoccerRubricInputs,
  stakes: number | undefined
): SoccerScoreBreakdown {
  const margin = marginPoints(inputs.margin);
  const totalGoals = totalGoalsPoints(inputs.totalGoals);
  const comeback = comebackPoints(inputs.largestDeficitOvercome);
  const lateDrama = lateDramaPoints(inputs.lateDecisiveGoal);
  const star = starPoints(inputs.maxGoalsByPlayer);
  const chances = chancesPoints(inputs.combinedShotsOnTarget);
  const redCard = redCardPoints(inputs.anyRedCard);
  const stakesPts = stakesPoints(stakes);

  const total = margin + totalGoals + comeback + lateDrama + star + chances + redCard + stakesPts;

  return { margin, totalGoals, comeback, lateDrama, star, chances, redCard, stakes: stakesPts, total };
}
