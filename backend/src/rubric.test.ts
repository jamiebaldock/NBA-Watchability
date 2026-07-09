import assert from "node:assert";
import {
  computeWatchabilityScore,
  isScoreVisible,
  leadChangePoints,
  marginPoints,
  overtimePoints,
  tierForScore,
} from "./rubric";
import { RubricInputs } from "./types";

// Margin buckets
assert.strictEqual(marginPoints(1), 25);
assert.strictEqual(marginPoints(3), 25);
assert.strictEqual(marginPoints(4), 20);
assert.strictEqual(marginPoints(10), 13);
assert.strictEqual(marginPoints(15), 7);
assert.strictEqual(marginPoints(20), 3);
assert.strictEqual(marginPoints(21), 0);

// Lead changes cap at 10 (1 pt per 2)
assert.strictEqual(leadChangePoints(0), 0);
assert.strictEqual(leadChangePoints(19), 9);
assert.strictEqual(leadChangePoints(20), 10);
assert.strictEqual(leadChangePoints(40), 10);

// Overtime
assert.strictEqual(overtimePoints(0), 0);
assert.strictEqual(overtimePoints(1), 7);
assert.strictEqual(overtimePoints(2), 10);
assert.strictEqual(overtimePoints(3), 10);

// Tiers
assert.strictEqual(tierForScore(85), "instant_classic");
assert.strictEqual(tierForScore(84), "worth_your_time");
assert.strictEqual(tierForScore(65), "worth_your_time");
assert.strictEqual(tierForScore(64), "solid");
assert.strictEqual(tierForScore(45), "solid");
assert.strictEqual(tierForScore(44), "skippable");

// Full blowout: 24pt margin, nothing else -> 0 total
const blowout: RubricInputs = {
  status: "final",
  finalMargin: 24,
  largestDeficitOvercome: 0,
  leadChanges: 1,
  overtimePeriods: 0,
  closeInFinalTwoMin: false,
  leadChangeInFinalMin: false,
  decidedOnFinalPossession: false,
  buzzerBeater: false,
  starPerformance: null,
};
assert.strictEqual(computeWatchabilityScore(blowout, 0).total, 0);

// Instant classic: 1pt margin (25) + full clutch (20) + buzzer beater (10)
// + 22pt comeback (15) + 20 lead changes (10) + 2OT (10) + historic star (10) + stakes 8
const classic: RubricInputs = {
  status: "final",
  finalMargin: 1,
  largestDeficitOvercome: 22,
  leadChanges: 20,
  overtimePeriods: 2,
  closeInFinalTwoMin: true,
  leadChangeInFinalMin: true,
  decidedOnFinalPossession: true,
  buzzerBeater: true,
  starPerformance: "historic",
};
const classicScore = computeWatchabilityScore(classic, 8);
assert.strictEqual(classicScore.total, 25 + 20 + 10 + 15 + 10 + 10 + 10 + 8);
assert.strictEqual(tierForScore(classicScore.total), "instant_classic");
// buzzer-beater bonus can legitimately push the total over 100
assert.ok(classicScore.total > 100, `expected >100, got ${classicScore.total}`);

// score_visible gating
assert.strictEqual(isScoreVisible("upcoming", undefined), false);
assert.strictEqual(isScoreVisible("final", 4), true);
assert.strictEqual(isScoreVisible("live", 1), false);
assert.strictEqual(isScoreVisible("live", 2), false);
assert.strictEqual(isScoreVisible("live", 3), false);
assert.strictEqual(isScoreVisible("live", 4), true);
assert.strictEqual(isScoreVisible("live", 5), true); // OT1

console.log("rubric.test.ts: all assertions passed");
