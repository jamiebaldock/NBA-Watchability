// Port of nba-watchability-spec.md section 2, points 1-3. This is the single
// source of truth for scoring; the mobile client's Tier.fromScore mirrors only
// the score->tier mapping (not the full rubric, since clients don't have raw
// play-by-play) so keep that in sync if the tier thresholds below change.
import { League } from "./espnClient";
import { RubricInputs, StarPerformance } from "./types";

// Margin/comeback/lead-change brackets are calibrated per league, not
// universal - grounded in the real backfilled game distributions (2,650 NBA
// games since 2024-25, 576 WNBA games since 2024), the same way the
// star-performance point thresholds (gameMapper.ts's starPointThresholds)
// were calibrated off real scoring history rather than a naive game-length
// ratio (40min/48min). For each NBA bracket boundary, the WNBA value here
// is whatever raw number sits at that SAME percentile of WNBA games, so a
// bracket represents the same rarity of "how close" or "how big a
// comeback" in either league, not the same absolute point value. Checked
// against real data whether clutch factor (closeInFinalTwoMin's 5-point/
// final-2-minutes threshold, below) and overtime frequency needed the same
// treatment - they don't: qualification rates already come out close
// between leagues under the existing universal thresholds (clutch: NBA
// 38.6% vs WNBA 41.5%; any overtime: NBA 4.75% vs WNBA 3.13%; 2+ OT: NBA
// 0.49% vs WNBA 0.52%), so those two stay league-agnostic.
function isWnba(league: League): boolean {
  return league === "wnba";
}

export function marginPoints(margin: number, league: League = "nba"): number {
  const m = Math.abs(margin);
  if (isWnba(league)) {
    if (m <= 4) return 25;
    if (m <= 6) return 20;
    if (m <= 9) return 13;
    if (m <= 13) return 7;
    if (m <= 17) return 3;
    return 0;
  }
  if (m <= 3) return 25;
  if (m <= 6) return 20;
  if (m <= 10) return 13;
  if (m <= 15) return 7;
  if (m <= 20) return 3;
  return 0;
}

export function clutchPoints(
  inputs: Pick<RubricInputs, "closeInFinalTwoMin" | "leadChangeInFinalMin" | "decidedOnFinalPossession">
): number {
  let pts = 0;
  if (inputs.closeInFinalTwoMin) pts += 8;
  if (inputs.leadChangeInFinalMin) pts += 6;
  if (inputs.decidedOnFinalPossession) pts += 6;
  return pts;
}

export function comebackPoints(largestDeficitOvercome: number, league: League = "nba"): number {
  if (isWnba(league)) {
    if (largestDeficitOvercome >= 17) return 15;
    if (largestDeficitOvercome >= 14) return 10;
    if (largestDeficitOvercome >= 9) return 6;
    return 0;
  }
  if (largestDeficitOvercome >= 20) return 15;
  if (largestDeficitOvercome >= 15) return 10;
  if (largestDeficitOvercome >= 10) return 6;
  return 0;
}

// The lead-changes count that earns the full 10 points - NBA's own value
// (20) is where the real NBA distribution reaches the percentile that
// WNBA's distribution reaches at 17 (see rubric.test.ts / the analysis
// behind this comment block). Same linear shape either way, just a
// different threshold, so the NBA branch below stays bit-for-bit identical
// to the pre-league-aware formula (floor(leadChanges/2), since 10/20 = 0.5).
const LEAD_CHANGES_FOR_MAX_POINTS: Record<"nba" | "wnba", number> = { nba: 20, wnba: 17 };

export function leadChangePoints(leadChanges: number, league: League = "nba"): number {
  const capThreshold = isWnba(league) ? LEAD_CHANGES_FOR_MAX_POINTS.wnba : LEAD_CHANGES_FOR_MAX_POINTS.nba;
  return Math.min(10, Math.floor((leadChanges * 10) / capThreshold));
}

export function overtimePoints(overtimePeriods: number): number {
  if (overtimePeriods <= 0) return 0;
  return overtimePeriods >= 2 ? 10 : 7;
}

export function starPoints(star: StarPerformance): number {
  switch (star) {
    case "historic":
      return 10;
    case "great":
      return 6;
    case "good":
      return 3;
    default:
      return 0;
  }
}

export function stakesPoints(stakes: number | undefined): number {
  if (stakes == null) return 0;
  return Math.max(0, Math.min(10, stakes));
}

export interface ScoreBreakdown {
  margin: number;
  clutch: number;
  buzzerBeater: number;
  comeback: number;
  leadChanges: number;
  overtime: number;
  star: number;
  stakes: number;
  total: number;
}

// Buzzer-beater bonus and stakes are excluded from `inputs: RubricInputs`
// (buzzerBeater lives there; stakes is LLM-derived and passed separately)
// since stakes is a pre-game judgment call, not a play-by-play fact.
// [league] defaults to "nba" so every existing caller that doesn't pass it
// (tests, manualValidate.ts) keeps its current NBA-calibrated behavior
// unchanged.
export function computeWatchabilityScore(
  inputs: RubricInputs,
  stakes: number | undefined,
  league: League = "nba"
): ScoreBreakdown {
  const margin = inputs.finalMargin != null ? marginPoints(inputs.finalMargin, league) : 0;
  const clutch = clutchPoints(inputs);
  const buzzerBeater = inputs.buzzerBeater ? 10 : 0;
  const comeback = comebackPoints(inputs.largestDeficitOvercome ?? 0, league);
  const leadChanges = leadChangePoints(inputs.leadChanges ?? 0, league);
  const overtime = overtimePoints(inputs.overtimePeriods);
  const star = starPoints(inputs.starPerformance);
  const stakesPts = stakesPoints(stakes);

  // No overall cap: spec explicitly allows the buzzer-beater bonus to push
  // the total over 100.
  const total = margin + clutch + buzzerBeater + comeback + leadChanges + overtime + star + stakesPts;

  return { margin, clutch, buzzerBeater, comeback, leadChanges, overtime, star, stakes: stakesPts, total };
}

export type Tier = "instant_classic" | "worth_your_time" | "solid" | "skippable";

export function tierForScore(score: number): Tier {
  if (score >= 85) return "instant_classic";
  if (score >= 65) return "worth_your_time";
  if (score >= 45) return "solid";
  return "skippable";
}

// Spoiler rule (spec section 2 point 3, section 5 notes): hidden through Q1-Q3,
// visible from the start of Q4 ("end of Q3") onward, always visible once final.
export function isScoreVisible(status: RubricInputs["status"], period: number | undefined): boolean {
  if (status === "final") return true;
  if (status === "upcoming") return false;
  if (period == null) return false;
  return period >= 4;
}
