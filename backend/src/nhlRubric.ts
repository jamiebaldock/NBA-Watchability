// Dedicated NHL watchability rubric - a fresh design for hockey's own
// dynamics, not a reskin of the basketball/soccer/MLB/NFL rubrics. Dimensions
// are grounded in what ESPN's free NHL endpoints actually carry (confirmed
// live while building backfillRawStatsNhl.ts - see nhlEspnClient.ts's own
// comments for the endpoint-shape details), following
// docs/rubric-calibration-procedure.md.
//
// Calibrated against the real, complete 2025-26 season (1,394 games -
// 1,312 regular season + 82 postseason, backend/data/nhlRawStats.json,
// analyzeNhlPercentiles.ts's real percentile pass) - every bracket below cites
// its own real qualification rate. Two dimensions were corrected from an
// initial general-knowledge-only first pass once the real numbers came in:
// lead changes >=4 turned out to occur in only 0.1% of real games (2 of
// 1,394) - functionally unreachable as a top bracket, so the tiers shifted
// down one notch (>=3/>=2/>=1 instead of >=4/>=2/>=1). A skater's 2-goal game
// turned out to be the single most common outcome in the sample (50.1% of
// games) - far too common to signal a "good" individual performance on its
// own, so it was dropped from starPoints entirely (only a real hat trick or a
// goaltender's save total now qualify, mirroring NFL's own "300+ passing
// alone, not just 3+ TDs, since 3+ TDs by one player happens in 44.8% of
// games" lesson).
import { Tier, stakesPoints } from "./rubric";

export interface NhlRubricInputs {
  // Absolute final goal differential.
  finalMargin: number;
  // Combined final score of both teams - a high-event-game signal.
  totalGoals: number;
  // Largest deficit the eventual winner ever faced, reconstructed from the
  // real goal-play timeline - 0 if the winner was never behind.
  largestDeficitOvercome: number;
  // How many times the real (non-tied) leader actually swapped from one team
  // to the other across the goal-play timeline.
  leadChanges: number;
  // 0 for a game decided in the 3 regulation periods.
  overtimePeriods: number;
  // True if the game specifically reached a shootout (as opposed to being
  // decided in the 3-on-3 overtime period itself) - hockey's own two-tier
  // "extra time" structure, distinct from football's/baseball's single
  // overtime concept.
  wentToShootout: boolean;
  // The permanent, un-relinquished go-ahead goal (the last real lead change
  // of the game) happened in the final 2 minutes of the 3rd period or in
  // overtime - hockey's own "clutch finish" signal, same convention as
  // nflRubric.ts's decisiveScoreLate.
  decisiveScoreLate: boolean;
  // Combined power-play goals by both teams - a special-teams drama signal.
  combinedPowerPlayGoals: number;
  // Most goals by one single skater in the game (2 = a "brace", 3+ = a hat
  // trick, hockey's clearest individual-star signal alongside a goalie's
  // save total).
  maxGoalsByPlayer: number;
  // Best single-goalie save total in the game - a busy, standout
  // goaltending performance is its own kind of drama distinct from the
  // skater-scoring side.
  maxGoalieSaves: number;
  // Either team was blanked (0 goals) - a full team shutout, hockey's
  // equivalent of MLB's "team blanked"/pitching-dominance signal.
  teamShutout: boolean;
}

// Real qualification rates (1,394-game 2025-26 sample): margin<=1 in 43.3%
// of games, <=2 in 61.1%, <=3 in 83.9%, <=4 in 94.5% - one-goal final
// margins are the single most common outcome in the NHL, so the anchor
// dimension's top bracket is deliberately not the outsized share
// basketball's/MLB's own top brackets are - a one-goal hockey game is
// closer to "the normal case" than "the rare, special case" the way it is
// in other sports.
export function marginPoints(margin: number): number {
  const m = Math.abs(margin);
  if (m <= 1) return 20;
  if (m <= 2) return 14;
  if (m <= 3) return 8;
  if (m <= 4) return 4;
  return 0;
}

// Real qualification rates (1,394-game 2025-26 sample): a winner overcoming
// a 3+-goal deficit happened in only 1.9% of games, 2+ in 10.4%, 1+ (any
// deficit at all) in 43.0% - hockey's per-goal deficits are inherently
// smaller-magnitude than football's/baseball's (goals are far rarer than
// points/runs), so the bracket boundaries are tighter than nflRubric.ts's
// comebackPoints.
export function comebackPoints(largestDeficitOvercome: number): number {
  if (largestDeficitOvercome >= 3) return 18;
  if (largestDeficitOvercome >= 2) return 12;
  if (largestDeficitOvercome >= 1) return 6;
  return 0;
}

// Real qualification rates: >=4 lead changes occurred in just 0.1% of the
// real sample (2 of 1,394 games) - functionally unreachable as a top
// bracket, unlike NFL's own >=4 threshold (9.4% there). Shifted down one
// notch from the initial pre-calibration guess: >=3 (1.4%), >=2 (11.8%),
// >=1 (43.0%, tied with a wire-to-wire 0-lead-change game at 57.0% for the
// single most common outcome).
export function leadChangePoints(leadChanges: number): number {
  if (leadChanges >= 3) return 12;
  if (leadChanges >= 2) return 6;
  if (leadChanges >= 1) return 3;
  return 0;
}

// Real rate: any OT/shootout in 25.0% of the sample, split roughly 2:1
// between a real OT winner (16.4%) and a shootout (8.5%). A real 3-on-3
// overtime winner is treated as more dramatic than a shootout finish
// (hockey's own two-tier "extra time" structure - once a shootout starts,
// the preceding OT period already ended scoreless and the shootout itself
// is a skills-contest tiebreaker rather than continuous play), so a
// shootout gets a smaller bonus than an OT winner.
export function overtimePoints(overtimePeriods: number, wentToShootout: boolean): number {
  if (overtimePeriods < 1) return 0;
  return wentToShootout ? 8 : 15;
}

// Real rate: 13.8% of the sample had their permanent go-ahead goal land in
// the final 2 minutes of the 3rd period or in overtime.
export function decisiveScoreLatePoints(decisiveScoreLate: boolean): number {
  return decisiveScoreLate ? 15 : 0;
}

// Real rate: >=3 combined power-play goals in 12.5% of the sample, >=2 in
// 34.1% - a modest bonus (not a major dimension), same treatment MLB's
// errorsPoints/NFL's turnoverPoints give a chaotic/special-teams drama
// signal.
export function powerPlayPoints(combinedPowerPlayGoals: number): number {
  if (combinedPowerPlayGoals >= 3) return 8;
  if (combinedPowerPlayGoals >= 2) return 4;
  return 0;
}

// Takes the best-qualifying tier across two independent paths (a skater's
// hat trick, or a goalie's big save total), not a sum, same "highest
// bracket wins" shape as nflRubric.ts's starPoints/mlbRubric.ts's
// pitchingDominancePoints. Real rates: a hat trick (3+ goals by one skater)
// is genuinely rare at 7.2% - but a 2-goal game turned out to be the single
// most common outcome in the whole sample at 50.1%, far too common to
// signal a "good" individual performance on its own, so (unlike the initial
// pre-calibration guess) it does NOT earn any bonus here - same lesson as
// NFL's own starPoints excluding "3+ TDs alone" (44.8% of games) without
// real yardage backing it up. Goaltending fills the "good"/"great" tiers
// instead, off its own real distribution: >=40 saves (3.1%), >=35 (13.7%),
// >=30 (41.5%).
export function starPoints(maxGoalsByPlayer: number, maxGoalieSaves: number): number {
  if (maxGoalsByPlayer >= 3 || maxGoalieSaves >= 40) return 15;
  if (maxGoalieSaves >= 35) return 8;
  if (maxGoalieSaves >= 30) return 4;
  return 0;
}

// Real rate: 8.5% of the sample had one team held scoreless - a goalie
// posting a full shutout is a genuinely notable single-game feat in
// hockey, same role MLB's teamBlanked/pitching-dominance signal plays.
export function shutoutPoints(teamShutout: boolean): number {
  return teamShutout ? 12 : 0;
}

// Real rate: >=9 combined goals in 17.5% of the sample, >=7 in 46.3% (mean
// 6.23 total goals/game) - a high-event/shootout-style offensive game, same
// role MLB's totalRunsPoints/NFL's totalPointsBonus play.
export function totalGoalsBonus(totalGoals: number): number {
  if (totalGoals >= 9) return 8;
  if (totalGoals >= 7) return 4;
  return 0;
}

export interface NhlScoreBreakdown {
  margin: number;
  comeback: number;
  leadChanges: number;
  overtime: number;
  decisiveScoreLate: number;
  powerPlay: number;
  star: number;
  shutout: number;
  totalGoals: number;
  stakes: number;
  total: number;
}

export function computeNhlWatchabilityScore(inputs: NhlRubricInputs, stakes: number | undefined): NhlScoreBreakdown {
  const margin = marginPoints(inputs.finalMargin);
  const comeback = comebackPoints(inputs.largestDeficitOvercome);
  const leadChanges = leadChangePoints(inputs.leadChanges);
  const overtime = overtimePoints(inputs.overtimePeriods, inputs.wentToShootout);
  const decisiveScoreLate = decisiveScoreLatePoints(inputs.decisiveScoreLate);
  const powerPlay = powerPlayPoints(inputs.combinedPowerPlayGoals);
  const star = starPoints(inputs.maxGoalsByPlayer, inputs.maxGoalieSaves);
  const shutout = shutoutPoints(inputs.teamShutout);
  const totalGoalsPts = totalGoalsBonus(inputs.totalGoals);
  const stakesPts = stakesPoints(stakes);

  const total = margin + comeback + leadChanges + overtime + decisiveScoreLate + powerPlay + star + shutout + totalGoalsPts + stakesPts;

  return {
    margin,
    comeback,
    leadChanges,
    overtime,
    decisiveScoreLate,
    powerPlay,
    star,
    shutout,
    totalGoals: totalGoalsPts,
    stakes: stakesPts,
    total
  };
}

// NHL's own independent scale (matching MLB's/NFL's precedent of a
// sport-specific scale rather than basketball's 85/65/45) - checked against
// the real 2025-26 sample's actual score distribution (stakes excluded):
// instant_classic at 4.0% of real games, worth_your_time at 16.2%, solid at
// 53.7% (the real median game, score 30) - remarkably close to MLB's own
// 4.9%/19.4%/51.1% and NFL's 5.9%/18.2%/51.0%, despite being derived
// completely independently (same sanity-check pattern nflRubric.ts's own
// header comment notes).
export function tierForNhlScore(score: number): Tier {
  if (score >= 78) return "instant_classic";
  if (score >= 56) return "worth_your_time";
  if (score >= 29) return "solid";
  return "skippable";
}
