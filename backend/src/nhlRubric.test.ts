import assert from "node:assert";
import {
  comebackPoints,
  computeNhlWatchabilityScore,
  decisiveScoreLatePoints,
  leadChangePoints,
  marginPoints,
  NhlRubricInputs,
  overtimePoints,
  powerPlayPoints,
  shutoutPoints,
  starPoints,
  tierForNhlScore,
  totalGoalsBonus,
} from "./nhlRubric";

// Margin buckets - calibrated against the real 2025-26 season sample.
assert.strictEqual(marginPoints(0), 20);
assert.strictEqual(marginPoints(1), 20);
assert.strictEqual(marginPoints(2), 14);
assert.strictEqual(marginPoints(3), 8);
assert.strictEqual(marginPoints(4), 4);
assert.strictEqual(marginPoints(5), 0);

// Comeback buckets
assert.strictEqual(comebackPoints(0), 0);
assert.strictEqual(comebackPoints(1), 6);
assert.strictEqual(comebackPoints(2), 12);
assert.strictEqual(comebackPoints(3), 18);

// Lead changes - >=4 occurs in only 0.1% of the real sample, so the top
// bracket is >=3 (1.4%), not >=4 like the other leagues' own thresholds.
assert.strictEqual(leadChangePoints(0), 0);
assert.strictEqual(leadChangePoints(1), 3);
assert.strictEqual(leadChangePoints(2), 6);
assert.strictEqual(leadChangePoints(3), 12);
assert.strictEqual(leadChangePoints(4), 12);

// Overtime - a real 3-on-3 OT winner scores higher than a shootout finish
assert.strictEqual(overtimePoints(0, false), 0);
assert.strictEqual(overtimePoints(1, false), 15);
assert.strictEqual(overtimePoints(1, true), 8);

// Decisive late goal
assert.strictEqual(decisiveScoreLatePoints(false), 0);
assert.strictEqual(decisiveScoreLatePoints(true), 15);

// Power play goals
assert.strictEqual(powerPlayPoints(1), 0);
assert.strictEqual(powerPlayPoints(2), 4);
assert.strictEqual(powerPlayPoints(3), 8);

// Star performance - best-qualifying tier across 2 paths (hat trick / goalie
// saves), not a sum. A 2-goal game (maxGoalsByPlayer=2) earns nothing on its
// own - real data showed it's the single most common outcome (50.1% of
// games), too common to signal a standout performance.
assert.strictEqual(starPoints(1, 25), 0);
assert.strictEqual(starPoints(2, 25), 0);
assert.strictEqual(starPoints(1, 30), 4);
assert.strictEqual(starPoints(1, 35), 8);
assert.strictEqual(starPoints(3, 0), 15);
assert.strictEqual(starPoints(0, 40), 15);

// Shutout
assert.strictEqual(shutoutPoints(false), 0);
assert.strictEqual(shutoutPoints(true), 12);

// Total goals (shootout-style offense)
assert.strictEqual(totalGoalsBonus(6), 0);
assert.strictEqual(totalGoalsBonus(7), 4);
assert.strictEqual(totalGoalsBonus(8), 4);
assert.strictEqual(totalGoalsBonus(9), 8);

// Tier cutoffs - NHL's own independent scale, not basketball's 85/65/45
assert.strictEqual(tierForNhlScore(78), "instant_classic");
assert.strictEqual(tierForNhlScore(77), "worth_your_time");
assert.strictEqual(tierForNhlScore(56), "worth_your_time");
assert.strictEqual(tierForNhlScore(55), "solid");
assert.strictEqual(tierForNhlScore(29), "solid");
assert.strictEqual(tierForNhlScore(28), "skippable");

// Routine blowout: 5-goal margin, nothing else -> 0 total
const blowout: NhlRubricInputs = {
  finalMargin: 5,
  totalGoals: 6,
  largestDeficitOvercome: 0,
  leadChanges: 0,
  overtimePeriods: 0,
  wentToShootout: false,
  decisiveScoreLate: false,
  combinedPowerPlayGoals: 1,
  maxGoalsByPlayer: 1,
  maxGoalieSaves: 20,
  teamShutout: false,
};
assert.strictEqual(computeNhlWatchabilityScore(blowout, 0).total, 0);

// Instant classic: every dimension stacked (a synthetic composite for
// testing the summation math, same convention as mlbRubric.test.ts's/
// nflRubric.test.ts's own "classic" case - not meant to represent a
// realistic single real game).
const classic: NhlRubricInputs = {
  finalMargin: 1,
  totalGoals: 9,
  largestDeficitOvercome: 3,
  leadChanges: 4,
  overtimePeriods: 1,
  wentToShootout: false,
  decisiveScoreLate: true,
  combinedPowerPlayGoals: 3,
  maxGoalsByPlayer: 3,
  maxGoalieSaves: 40,
  teamShutout: true,
};
const classicScore = computeNhlWatchabilityScore(classic, 10);
assert.strictEqual(classicScore.total, 20 + 18 + 12 + 15 + 15 + 8 + 15 + 12 + 8 + 10);
assert.strictEqual(tierForNhlScore(classicScore.total), "instant_classic");
// leadChanges=4 in the "classic" fixture above still lands in the top
// bracket (>=3), so its expected total is unaffected by the >=4->>=3
// recalibration - this comment documents that deliberately, not a gap.

console.log("nhlRubric.test.ts: all assertions passed");
