import assert from "node:assert";
import {
  blownSavePoints,
  combinedHomeRunsPoints,
  comebackPoints,
  computeMlbWatchabilityScore,
  errorsPoints,
  extraInningsPoints,
  marginPoints,
  MlbRubricInputs,
  pitchingDominancePoints,
  starHomeRunPoints,
  tierForMlbScore,
  totalRunsPoints,
  walkOffPoints,
} from "./mlbRubric";

// Margin buckets
assert.strictEqual(marginPoints(1), 20);
assert.strictEqual(marginPoints(2), 15);
assert.strictEqual(marginPoints(3), 9);
assert.strictEqual(marginPoints(5), 9);
assert.strictEqual(marginPoints(6), 4);
assert.strictEqual(marginPoints(7), 4);
assert.strictEqual(marginPoints(8), 0);

// Walk-off
assert.strictEqual(walkOffPoints(false), 0);
assert.strictEqual(walkOffPoints(true), 25);

// Comeback buckets
assert.strictEqual(comebackPoints(1), 0);
assert.strictEqual(comebackPoints(2), 6);
assert.strictEqual(comebackPoints(3), 6);
assert.strictEqual(comebackPoints(4), 12);
assert.strictEqual(comebackPoints(5), 12);
assert.strictEqual(comebackPoints(6), 18);

// Extra innings
assert.strictEqual(extraInningsPoints(0), 0);
assert.strictEqual(extraInningsPoints(1), 5);
assert.strictEqual(extraInningsPoints(2), 10);
assert.strictEqual(extraInningsPoints(3), 10);

// Total runs buckets
assert.strictEqual(totalRunsPoints(9), 0);
assert.strictEqual(totalRunsPoints(10), 3);
assert.strictEqual(totalRunsPoints(14), 3);
assert.strictEqual(totalRunsPoints(15), 6);
assert.strictEqual(totalRunsPoints(17), 6);
assert.strictEqual(totalRunsPoints(18), 10);

// Home runs - combined and single-player
assert.strictEqual(combinedHomeRunsPoints(2), 0);
assert.strictEqual(combinedHomeRunsPoints(3), 3);
assert.strictEqual(combinedHomeRunsPoints(4), 3);
assert.strictEqual(combinedHomeRunsPoints(5), 6);
assert.strictEqual(starHomeRunPoints(1), 0);
assert.strictEqual(starHomeRunPoints(2), 5);
assert.strictEqual(starHomeRunPoints(3), 12);

// Pitching dominance - highest applicable tier wins, not a sum
assert.strictEqual(pitchingDominancePoints({ teamBlanked: false, noHitter: false, perfectGame: false }), 0);
assert.strictEqual(pitchingDominancePoints({ teamBlanked: true, noHitter: false, perfectGame: false }), 8);
assert.strictEqual(pitchingDominancePoints({ teamBlanked: true, noHitter: true, perfectGame: false }), 20);
assert.strictEqual(pitchingDominancePoints({ teamBlanked: true, noHitter: true, perfectGame: true }), 30);
// A caller passing only the top flag (without the implied lower ones) still resolves correctly.
assert.strictEqual(pitchingDominancePoints({ teamBlanked: false, noHitter: false, perfectGame: true }), 30);

// Blown save / errors
assert.strictEqual(blownSavePoints(false), 0);
assert.strictEqual(blownSavePoints(true), 6);
assert.strictEqual(errorsPoints(2), 0);
assert.strictEqual(errorsPoints(3), 4);

// Tier cutoffs - MLB's own independent scale, not basketball's 85/65/45
assert.strictEqual(tierForMlbScore(60), "instant_classic");
assert.strictEqual(tierForMlbScore(59), "worth_your_time");
assert.strictEqual(tierForMlbScore(35), "worth_your_time");
assert.strictEqual(tierForMlbScore(34), "solid");
assert.strictEqual(tierForMlbScore(20), "solid");
assert.strictEqual(tierForMlbScore(19), "skippable");

// Routine blowout: 15pt margin, nothing else -> 0 total
const blowout: MlbRubricInputs = {
  finalMargin: 15,
  totalRuns: 9,
  largestDeficitOvercome: 0,
  walkOff: false,
  extraInningsCount: 0,
  combinedHomeRuns: 1,
  maxHomeRunsByPlayer: 1,
  teamBlanked: false,
  noHitter: false,
  perfectGame: false,
  blownSave: false,
  combinedErrors: 0,
};
assert.strictEqual(computeMlbWatchabilityScore(blowout, 0).total, 0);

// Instant classic: every dimension stacked (a synthetic composite for
// testing the summation math, same convention as rubric.test.ts's own
// "classic" case - not meant to represent a realistic single real game).
const classic: MlbRubricInputs = {
  finalMargin: 1,
  totalRuns: 18,
  largestDeficitOvercome: 6,
  walkOff: true,
  extraInningsCount: 2,
  combinedHomeRuns: 5,
  maxHomeRunsByPlayer: 3,
  teamBlanked: true,
  noHitter: true,
  perfectGame: true,
  blownSave: true,
  combinedErrors: 3,
};
const classicScore = computeMlbWatchabilityScore(classic, 10);
assert.strictEqual(classicScore.total, 20 + 25 + 18 + 10 + 10 + 6 + 12 + 30 + 6 + 4 + 10);
assert.strictEqual(tierForMlbScore(classicScore.total), "instant_classic");

console.log("mlbRubric.test.ts: all assertions passed");
