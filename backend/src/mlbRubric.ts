// Dedicated MLB watchability rubric - a fresh design for baseball's own
// dynamics, not a reskin of the basketball or soccer rubrics. Dimensions
// and point brackets are grounded in real percentiles/qualification rates
// from the complete 2,478-game 2025 MLB season + postseason
// (backfillRawStatsMlb.ts collected the raw box-score facts this module's
// brackets are calibrated against - see backend/data/mlbRawStats.json and
// docs/rubric-calibration-procedure.md for the full methodology). Own
// independent point scale and tier cutoffs (tierForMlbScore below) - by
// James's explicit call, not normalized against basketball's 85/65/45
// scale the way WNBA's rubric was calibrated to line up with NBA's.
// Backend-only for now - no LeagueGroup/GameJson/gameStore changes, since
// no MLB games are populated into any user-facing tab yet (same scope as
// soccerRubric.ts before EPL/La Liga shipped).
import { Tier, stakesPoints } from "./rubric";

export interface MlbRubricInputs {
  // Absolute run differential.
  finalMargin: number;
  totalRuns: number;
  // Largest deficit the eventual winner ever faced, reconstructed from
  // per-inning linescores - 0 if the winner was never behind.
  largestDeficitOvercome: number;
  // The home team was tied or trailing entering their final
  // plate-appearance inning and won during it - baseball's own signature
  // dramatic beat, no basketball/soccer equivalent.
  walkOff: boolean;
  // 0 for a regulation 9-inning game.
  extraInningsCount: number;
  combinedHomeRuns: number;
  // Most home runs hit by a single player in the game.
  maxHomeRunsByPlayer: number;
  // One team was held scoreless - the common case (13.5% of the 2025
  // sample), usually via a bullpen combo, not necessarily one pitcher going
  // the distance (see noHitter's own comment for that distinction).
  teamBlanked: boolean;
  // One team's pitching staff (any number of pitchers combined) allowed
  // zero hits all game - genuinely rare (0 occurrences in the full 2025
  // sample), real over a longer sample (some seasons have none at all).
  noHitter: boolean;
  // Extremely rare (0 occurrences in the 2025 sample) - kept for
  // completeness, same treatment soccerRubric.ts gives its unobserved 3+
  // goal comeback bracket.
  perfectGame: boolean;
  // At least one blown save (a reliever entering a save situation and
  // surrendering the lead) - a bullpen-chaos/back-and-forth-finish signal,
  // similar role to soccer's red-card bonus. 23.8% of the 2025 sample.
  blownSave: boolean;
  combinedErrors: number;
}

// Real qualification rates (2,478-game 2025 sample): m=1 in 29.3% of
// games, m<=2 in 46.3%, m<=3 in 59.0%, m>=8 (blowout) in 9.4% - a much
// wider/flatter distribution than basketball's, where a 1-possession final
// margin is already rare. Anchor dimension, same role margin plays in
// every other rubric in this codebase.
export function marginPoints(margin: number): number {
  const m = Math.abs(margin);
  if (m === 1) return 20;
  if (m === 2) return 15;
  if (m <= 5) return 9;
  if (m <= 7) return 4;
  return 0;
}

// The single heaviest non-margin dimension (James's explicit call) - an
// entire game decided in one instant, 14.0% of the 2025 sample.
export function walkOffPoints(walkOff: boolean): number {
  return walkOff ? 25 : 0;
}

// 2+ in 15.8% of games, 4+ in 2.4%, 6+ in 0.4%.
export function comebackPoints(largestDeficitOvercome: number): number {
  if (largestDeficitOvercome >= 6) return 18;
  if (largestDeficitOvercome >= 4) return 12;
  if (largestDeficitOvercome >= 2) return 6;
  return 0;
}

// Direct analog to basketball's overtimePoints - any extra innings in 8.6%
// of the 2025 sample, 2+ in 3.2%.
export function extraInningsPoints(extraInningsCount: number): number {
  if (extraInningsCount >= 2) return 10;
  if (extraInningsCount >= 1) return 5;
  return 0;
}

// A slugfest is its own kind of drama, same role soccer's totalGoalsPoints
// plays - median 8 runs, p90=15, p95=17 in the 2025 sample.
export function totalRunsPoints(totalRuns: number): number {
  if (totalRuns >= 18) return 10;
  if (totalRuns >= 15) return 6;
  if (totalRuns >= 10) return 3;
  return 0;
}

// Combined home runs across both teams - median 2, p90=5 in the 2025
// sample.
export function combinedHomeRunsPoints(combinedHomeRuns: number): number {
  if (combinedHomeRuns >= 5) return 6;
  if (combinedHomeRuns >= 3) return 3;
  return 0;
}

// One player's own home-run count - baseball's "star performance" signal,
// same role soccer's hat-trick starPoints plays. A multi-HR (2+) game
// happens in 13.6% of the 2025 sample; 3+ by one player in just 0.8%.
export function starHomeRunPoints(maxHomeRunsByPlayer: number): number {
  if (maxHomeRunsByPlayer >= 3) return 12;
  if (maxHomeRunsByPlayer >= 2) return 5;
  return 0;
}

// Each tier implies the one below it (a perfect game is also a no-hitter is
// also a blanked team) - takes the highest bracket that applies, not a sum.
export function pitchingDominancePoints(inputs: Pick<MlbRubricInputs, "teamBlanked" | "noHitter" | "perfectGame">): number {
  if (inputs.perfectGame) return 30;
  if (inputs.noHitter) return 20;
  if (inputs.teamBlanked) return 8;
  return 0;
}

export function blownSavePoints(blownSave: boolean): number {
  return blownSave ? 6 : 0;
}

// A small bonus, not a major dimension (per James's explicit call) - an
// error-filled game isn't necessarily a more exciting one the way a soccer
// red card clearly is, but 3+ combined errors (8.6% of the 2025 sample) is
// still a real chaotic-finish signal worth a modest nod.
export function errorsPoints(combinedErrors: number): number {
  return combinedErrors >= 3 ? 4 : 0;
}

export interface MlbScoreBreakdown {
  margin: number;
  walkOff: number;
  comeback: number;
  extraInnings: number;
  totalRuns: number;
  combinedHomeRuns: number;
  starHomeRun: number;
  pitchingDominance: number;
  blownSave: number;
  errors: number;
  stakes: number;
  total: number;
}

export function computeMlbWatchabilityScore(inputs: MlbRubricInputs, stakes: number | undefined): MlbScoreBreakdown {
  const margin = marginPoints(inputs.finalMargin);
  const walkOff = walkOffPoints(inputs.walkOff);
  const comeback = comebackPoints(inputs.largestDeficitOvercome);
  const extraInnings = extraInningsPoints(inputs.extraInningsCount);
  const totalRuns = totalRunsPoints(inputs.totalRuns);
  const combinedHomeRuns = combinedHomeRunsPoints(inputs.combinedHomeRuns);
  const starHomeRun = starHomeRunPoints(inputs.maxHomeRunsByPlayer);
  const pitchingDominance = pitchingDominancePoints(inputs);
  const blownSave = blownSavePoints(inputs.blownSave);
  const errors = errorsPoints(inputs.combinedErrors);
  const stakesPts = stakesPoints(stakes);

  const total =
    margin + walkOff + comeback + extraInnings + totalRuns + combinedHomeRuns + starHomeRun + pitchingDominance + blownSave + errors + stakesPts;

  return { margin, walkOff, comeback, extraInnings, totalRuns, combinedHomeRuns, starHomeRun, pitchingDominance, blownSave, errors, stakes: stakesPts, total };
}

// MLB's own independent scale (James's explicit call, not normalized to
// basketball's 85/65/45) - checked against the real 2025 sample's actual
// score distribution (stakes excluded): instant_classic at 4.9% of real
// games, worth_your_time at 19.4%, solid at 51.1% (the median real game,
// a clean 50/50 split against skippable).
export function tierForMlbScore(score: number): Tier {
  if (score >= 60) return "instant_classic";
  if (score >= 35) return "worth_your_time";
  if (score >= 20) return "solid";
  return "skippable";
}
