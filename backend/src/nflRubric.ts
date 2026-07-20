// Dedicated NFL watchability rubric - a fresh design for football's own
// dynamics, not a reskin of the basketball/soccer/MLB rubrics. Dimensions
// and point brackets are grounded in real percentiles/qualification rates
// from the complete 286-game 2025 NFL season (regular season + playoffs)
// (backfillRawStatsNfl.ts collected the raw box-score/scoring-play facts
// this module's brackets are calibrated against - see
// backend/data/nflRawStats.json and docs/rubric-calibration-procedure.md for
// the full methodology). Own independent point scale and tier cutoffs
// (tierForNflScore below) - James's explicit call, matching MLB's precedent
// rather than normalized against basketball's 85/65/45 scale.
import { Tier, stakesPoints } from "./rubric";

export interface NflRubricInputs {
  // Absolute final score differential.
  finalMargin: number;
  // Largest deficit the eventual winner ever faced, reconstructed from the
  // real scoringPlays timeline (away/home running score after every scoring
  // event) - 0 if the winner was never behind, or the game ended tied.
  largestDeficitOvercome: number;
  // How many times the real (non-tied) leader actually swapped from one
  // team to the other across the scoringPlays timeline.
  leadChanges: number;
  // 0 for a regulation 4-quarter game.
  overtimePeriods: number;
  // The permanent, un-relinquished go-ahead score (the last real lead
  // change of the game) happened in the final 2 minutes of regulation or in
  // overtime - football's own "clutch finish" signal, distinct from a
  // meaningless late score by the trailing team in a blowout.
  decisiveScoreLate: boolean;
  // Combined turnovers (interceptions + fumbles lost) by both teams - a
  // chaotic-game signal, football's analog to MLB's "combined errors" bonus.
  combinedTurnovers: number;
  // Either team scored a defensive or special-teams touchdown (pick-six,
  // fumble/punt/kick-return TD) - football's own signature explosive-swing
  // play, no basketball/soccer/MLB equivalent.
  defensiveOrSpecialTeamsTd: boolean;
  // Best single-player passing yardage in the game.
  maxPassingYards: number;
  // Best single-player rushing yardage in the game.
  maxRushingYards: number;
  // Most combined passing+rushing+receiving touchdowns by one single player.
  maxTotalTdsByPlayer: number;
  // Combined final score of both teams - a shootout signal.
  totalPoints: number;
}

// Real qualification rates (286-game 2025 sample, regular season +
// playoffs): margin<=3 in 27.3% of games (one-possession-and-done), <=8
// (football's own "one-score game" convention - a TD+2pt conversion) in
// 53.5% cumulative, <=16 in 72.4% cumulative, <=24 in 89.9% cumulative.
// Anchor dimension, same role margin plays in every other rubric in this
// codebase.
export function marginPoints(margin: number): number {
  const m = Math.abs(margin);
  if (m <= 3) return 25;
  if (m <= 8) return 18;
  if (m <= 16) return 10;
  if (m <= 24) return 5;
  return 0;
}

// >=14 (overcoming 2+ scores) in 5.2% of games, >=10 in 13.3%, >=7 (a full
// one-score deficit) in 32.2%.
export function comebackPoints(largestDeficitOvercome: number): number {
  if (largestDeficitOvercome >= 14) return 18;
  if (largestDeficitOvercome >= 10) return 12;
  if (largestDeficitOvercome >= 7) return 6;
  return 0;
}

// >=4 lead changes in 9.4% of games, >=2 in 36.4%, >=1 in 60.1% (a wire-to-
// wire game with zero lead changes is actually the single most common case
// at 39.9%).
export function leadChangePoints(leadChanges: number): number {
  if (leadChanges >= 4) return 12;
  if (leadChanges >= 2) return 6;
  if (leadChanges >= 1) return 3;
  return 0;
}

// Any overtime in 5.6% of the 2025 sample - a flat bonus (not tiered by OT
// period count like basketball's overtimePoints) since a second NFL
// overtime is vanishingly rare under the current rules and never occurred
// in the real sample.
export function overtimePoints(overtimePeriods: number): number {
  return overtimePeriods >= 1 ? 12 : 0;
}

// 19.2% of the 2025 sample had their permanent go-ahead score land in the
// final 2 minutes of regulation or in overtime.
export function decisiveScoreLatePoints(decisiveScoreLate: boolean): number {
  return decisiveScoreLate ? 15 : 0;
}

// A modest bonus (not a major dimension), same treatment MLB's errorsPoints
// gives a chaotic-finish signal - >=5 combined turnovers in 8.4% of the
// 2025 sample, >=3 in 41.3%.
export function turnoverPoints(combinedTurnovers: number): number {
  if (combinedTurnovers >= 5) return 8;
  if (combinedTurnovers >= 3) return 4;
  return 0;
}

// 22.7% of the 2025 sample had at least one defensive/special-teams TD -
// football's own signature explosive-swing play.
export function defensiveOrSpecialTeamsTdPoints(defensiveOrSpecialTeamsTd: boolean): number {
  return defensiveOrSpecialTeamsTd ? 10 : 0;
}

// Takes the best-qualifying tier across three independent paths (passing,
// rushing, or total touchdowns), not a sum - same "highest bracket wins"
// shape as MLB's pitchingDominancePoints. Real 2025 rates: elite tier
// (400+ pass yds 2.1%, 175+ rush yds 2.8%, 5+ TDs 2.4%) is genuinely rare;
// great tier (350+/150+/4+) covers roughly 6-14% depending on path; good
// tier (300+ pass or 125+ rush - TD count alone excluded here since 3+ TDs
// by one player happens in 44.8% of games, too common on its own to signal
// a "good" individual game without real yardage to back it up).
export function starPoints(maxPassingYards: number, maxRushingYards: number, maxTotalTdsByPlayer: number): number {
  if (maxPassingYards >= 400 || maxRushingYards >= 175 || maxTotalTdsByPlayer >= 5) return 15;
  if (maxPassingYards >= 350 || maxRushingYards >= 150 || maxTotalTdsByPlayer >= 4) return 8;
  if (maxPassingYards >= 300 || maxRushingYards >= 125) return 4;
  return 0;
}

// A shootout is its own kind of drama, same role MLB's totalRunsPoints
// plays - median 45 combined points, p90=65 in the 2025 sample.
export function totalPointsBonus(totalPoints: number): number {
  if (totalPoints >= 65) return 8;
  if (totalPoints >= 56) return 4;
  return 0;
}

export interface NflScoreBreakdown {
  margin: number;
  comeback: number;
  leadChanges: number;
  overtime: number;
  decisiveScoreLate: number;
  turnovers: number;
  defensiveOrSpecialTeamsTd: number;
  star: number;
  totalPoints: number;
  stakes: number;
  total: number;
}

export function computeNflWatchabilityScore(inputs: NflRubricInputs, stakes: number | undefined): NflScoreBreakdown {
  const margin = marginPoints(inputs.finalMargin);
  const comeback = comebackPoints(inputs.largestDeficitOvercome);
  const leadChanges = leadChangePoints(inputs.leadChanges);
  const overtime = overtimePoints(inputs.overtimePeriods);
  const decisiveScoreLate = decisiveScoreLatePoints(inputs.decisiveScoreLate);
  const turnovers = turnoverPoints(inputs.combinedTurnovers);
  const defensiveOrSpecialTeamsTd = defensiveOrSpecialTeamsTdPoints(inputs.defensiveOrSpecialTeamsTd);
  const star = starPoints(inputs.maxPassingYards, inputs.maxRushingYards, inputs.maxTotalTdsByPlayer);
  const totalPointsPts = totalPointsBonus(inputs.totalPoints);
  const stakesPts = stakesPoints(stakes);

  const total = margin + comeback + leadChanges + overtime + decisiveScoreLate + turnovers + defensiveOrSpecialTeamsTd + star + totalPointsPts + stakesPts;

  return {
    margin,
    comeback,
    leadChanges,
    overtime,
    decisiveScoreLate,
    turnovers,
    defensiveOrSpecialTeamsTd,
    star,
    totalPoints: totalPointsPts,
    stakes: stakesPts,
    total
  };
}

// NFL's own independent scale (James's explicit call, matching MLB's
// precedent rather than basketball's 85/65/45) - checked against the real
// 2025 sample's actual score distribution (stakes excluded): instant_classic
// at 5.9% of real games, worth_your_time at 18.2%, solid at 51.0% (the real
// median game) - remarkably close to MLB's own 4.9%/19.4%/51.1% despite
// being derived completely independently.
export function tierForNflScore(score: number): Tier {
  if (score >= 75) return "instant_classic";
  if (score >= 54) return "worth_your_time";
  if (score >= 28) return "solid";
  return "skippable";
}
