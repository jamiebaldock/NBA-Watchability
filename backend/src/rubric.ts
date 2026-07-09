// Port of nba-watchability-spec.md section 2, points 1-3. This is the single
// source of truth for scoring; the mobile client's Tier.fromScore mirrors only
// the score->tier mapping (not the full rubric, since clients don't have raw
// play-by-play) so keep that in sync if the tier thresholds below change.
import { RubricInputs, StarPerformance } from "./types";

export function marginPoints(margin: number): number {
  const m = Math.abs(margin);
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

export function comebackPoints(largestDeficitOvercome: number): number {
  if (largestDeficitOvercome >= 20) return 15;
  if (largestDeficitOvercome >= 15) return 10;
  if (largestDeficitOvercome >= 10) return 6;
  return 0;
}

export function leadChangePoints(leadChanges: number): number {
  return Math.min(10, Math.floor(leadChanges / 2));
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
export function computeWatchabilityScore(inputs: RubricInputs, stakes: number | undefined): ScoreBreakdown {
  const margin = inputs.finalMargin != null ? marginPoints(inputs.finalMargin) : 0;
  const clutch = clutchPoints(inputs);
  const buzzerBeater = inputs.buzzerBeater ? 10 : 0;
  const comeback = comebackPoints(inputs.largestDeficitOvercome ?? 0);
  const leadChanges = leadChangePoints(inputs.leadChanges ?? 0);
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
