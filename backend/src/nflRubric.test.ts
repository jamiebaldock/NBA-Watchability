import assert from "node:assert";
import {
  comebackPoints,
  computeNflWatchabilityScore,
  decisiveScoreLatePoints,
  defensiveOrSpecialTeamsTdPoints,
  leadChangePoints,
  marginPoints,
  NflRubricInputs,
  overtimePoints,
  starPoints,
  tierForNflScore,
  totalPointsBonus,
  turnoverPoints,
} from "./nflRubric";

// Margin buckets
assert.strictEqual(marginPoints(1), 25);
assert.strictEqual(marginPoints(3), 25);
assert.strictEqual(marginPoints(4), 18);
assert.strictEqual(marginPoints(8), 18);
assert.strictEqual(marginPoints(9), 10);
assert.strictEqual(marginPoints(16), 10);
assert.strictEqual(marginPoints(17), 5);
assert.strictEqual(marginPoints(24), 5);
assert.strictEqual(marginPoints(25), 0);

// Comeback buckets
assert.strictEqual(comebackPoints(6), 0);
assert.strictEqual(comebackPoints(7), 6);
assert.strictEqual(comebackPoints(9), 6);
assert.strictEqual(comebackPoints(10), 12);
assert.strictEqual(comebackPoints(13), 12);
assert.strictEqual(comebackPoints(14), 18);

// Lead changes
assert.strictEqual(leadChangePoints(0), 0);
assert.strictEqual(leadChangePoints(1), 3);
assert.strictEqual(leadChangePoints(2), 6);
assert.strictEqual(leadChangePoints(3), 6);
assert.strictEqual(leadChangePoints(4), 12);

// Overtime - flat bonus, not tiered by period count
assert.strictEqual(overtimePoints(0), 0);
assert.strictEqual(overtimePoints(1), 12);
assert.strictEqual(overtimePoints(2), 12);

// Decisive late score
assert.strictEqual(decisiveScoreLatePoints(false), 0);
assert.strictEqual(decisiveScoreLatePoints(true), 15);

// Turnovers
assert.strictEqual(turnoverPoints(2), 0);
assert.strictEqual(turnoverPoints(3), 4);
assert.strictEqual(turnoverPoints(4), 4);
assert.strictEqual(turnoverPoints(5), 8);

// Defensive/special-teams TD
assert.strictEqual(defensiveOrSpecialTeamsTdPoints(false), 0);
assert.strictEqual(defensiveOrSpecialTeamsTdPoints(true), 10);

// Star performance - best-qualifying tier across 3 paths, not a sum
assert.strictEqual(starPoints(250, 60, 2), 0);
assert.strictEqual(starPoints(300, 60, 2), 4);
assert.strictEqual(starPoints(0, 125, 2), 4);
assert.strictEqual(starPoints(350, 60, 2), 8);
assert.strictEqual(starPoints(0, 0, 4), 8);
assert.strictEqual(starPoints(400, 60, 2), 15);
assert.strictEqual(starPoints(0, 175, 2), 15);
assert.strictEqual(starPoints(0, 0, 5), 15);
// A caller passing only the top-tier flag (without the implied lower path) still resolves correctly.
assert.strictEqual(starPoints(0, 0, 6), 15);

// Total points (shootout)
assert.strictEqual(totalPointsBonus(45), 0);
assert.strictEqual(totalPointsBonus(56), 4);
assert.strictEqual(totalPointsBonus(64), 4);
assert.strictEqual(totalPointsBonus(65), 8);

// Tier cutoffs - NFL's own independent scale, not basketball's 85/65/45
assert.strictEqual(tierForNflScore(75), "instant_classic");
assert.strictEqual(tierForNflScore(74), "worth_your_time");
assert.strictEqual(tierForNflScore(54), "worth_your_time");
assert.strictEqual(tierForNflScore(53), "solid");
assert.strictEqual(tierForNflScore(28), "solid");
assert.strictEqual(tierForNflScore(27), "skippable");

// Routine blowout: 30pt margin, nothing else -> 0 total
const blowout: NflRubricInputs = {
  finalMargin: 30,
  largestDeficitOvercome: 0,
  leadChanges: 0,
  overtimePeriods: 0,
  decisiveScoreLate: false,
  combinedTurnovers: 1,
  defensiveOrSpecialTeamsTd: false,
  maxPassingYards: 200,
  maxRushingYards: 60,
  maxTotalTdsByPlayer: 2,
  totalPoints: 40,
};
assert.strictEqual(computeNflWatchabilityScore(blowout, 0).total, 0);

// Instant classic: every dimension stacked (a synthetic composite for
// testing the summation math, same convention as mlbRubric.test.ts's own
// "classic" case - not meant to represent a realistic single real game).
const classic: NflRubricInputs = {
  finalMargin: 1,
  largestDeficitOvercome: 14,
  leadChanges: 4,
  overtimePeriods: 1,
  decisiveScoreLate: true,
  combinedTurnovers: 5,
  defensiveOrSpecialTeamsTd: true,
  maxPassingYards: 400,
  maxRushingYards: 0,
  maxTotalTdsByPlayer: 5,
  totalPoints: 65,
};
const classicScore = computeNflWatchabilityScore(classic, 10);
assert.strictEqual(classicScore.total, 25 + 18 + 12 + 12 + 15 + 8 + 10 + 15 + 8 + 10);
assert.strictEqual(tierForNflScore(classicScore.total), "instant_classic");

console.log("nflRubric.test.ts: all assertions passed");
