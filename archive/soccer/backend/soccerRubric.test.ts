import assert from "node:assert";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { tierForScore } from "./rubric";
import {
  chancesPoints,
  comebackPoints,
  computeSoccerWatchabilityScore,
  freeKickGoalPoints,
  lateDramaPoints,
  marginPoints,
  penaltyMissPoints,
  redCardPoints,
  savesPoints,
  starPoints,
  totalGoalsPoints,
} from "./soccerRubric";

// Point-function bucket boundaries
assert.strictEqual(marginPoints(0), 20);
assert.strictEqual(marginPoints(1), 15);
assert.strictEqual(marginPoints(2), 8);
assert.strictEqual(marginPoints(3), 0);

assert.strictEqual(totalGoalsPoints(1), 0);
assert.strictEqual(totalGoalsPoints(3), 5);
assert.strictEqual(totalGoalsPoints(5), 12);
assert.strictEqual(totalGoalsPoints(6), 20);

assert.strictEqual(comebackPoints(0), 0);
assert.strictEqual(comebackPoints(1), 10);
assert.strictEqual(comebackPoints(2), 20);
assert.strictEqual(comebackPoints(3), 30);

assert.strictEqual(lateDramaPoints(false), 0);
assert.strictEqual(lateDramaPoints(true), 15);

assert.strictEqual(starPoints(1), 0);
assert.strictEqual(starPoints(2), 5);
assert.strictEqual(starPoints(3), 15);

assert.strictEqual(chancesPoints(undefined), 0);
assert.strictEqual(chancesPoints(11), 0);
assert.strictEqual(chancesPoints(12), 8);

assert.strictEqual(redCardPoints(undefined), 0);
assert.strictEqual(redCardPoints(false), 0);
assert.strictEqual(redCardPoints(true), 5);

assert.strictEqual(savesPoints(undefined), 0);
assert.strictEqual(savesPoints(6), 0);
assert.strictEqual(savesPoints(7), 5);
assert.strictEqual(savesPoints(8), 5);
assert.strictEqual(savesPoints(9), 15);

assert.strictEqual(freeKickGoalPoints(undefined), 0);
assert.strictEqual(freeKickGoalPoints(false), 0);
assert.strictEqual(freeKickGoalPoints(true), 10);

assert.strictEqual(penaltyMissPoints(undefined), 0);
assert.strictEqual(penaltyMissPoints(false), 0);
assert.strictEqual(penaltyMissPoints(true), 10);

// Crystal Palace 3-3 Bournemouth (Oct 18, 2025) - the season's top match:
// draw (20) + 6 goals (20) + no comeback for the leveling side beyond a
// 1-goal deficit at the very end (10) + decisive 90'+7' equalizer (15) +
// no hat-trick this match (0) - stakes excluded (LLM-derived, not
// computable from historical stats alone), same as the validated design
// proposal's simulation.
const topMatch = computeSoccerWatchabilityScore(
  { margin: 0, totalGoals: 6, largestDeficitOvercome: 1, lateDecisiveGoal: true, maxGoalsByPlayer: 1 },
  undefined
);
assert.strictEqual(topMatch.total, 20 + 20 + 10 + 15 + 0 + 0 + 0);

// Full stakes-excluded score can still legitimately fall below every tier
// (e.g. a 3-0 blowout with nothing else going for it).
const blowout = computeSoccerWatchabilityScore(
  { margin: 3, totalGoals: 3, largestDeficitOvercome: 0, lateDecisiveGoal: false, maxGoalsByPlayer: 1 },
  undefined
);
assert.strictEqual(blowout.total, 0 + 5 + 0 + 0 + 0 + 0 + 0);
assert.strictEqual(tierForScore(blowout.total), "skippable");

// --- Validate against the real, complete 380-match EPL 2025-26 season ---
// (backend/src/testdata/eplMatches.json - the same corrected dataset used
// to design and calibrate this rubric; goals filtered via ESPN's own
// scoringPlay flag, not a type-string guess, after an earlier version of
// the collection script was found to silently drop penalty goals).
interface Goal {
  team: string;
  minuteValue: number;
  scorer?: string;
  ownGoal?: boolean;
  freeKick?: boolean;
}
interface Match {
  home: string;
  away: string;
  homeScore: number;
  awayScore: number;
  goals: Goal[];
  homeShotsOnTarget: number | null;
  awayShotsOnTarget: number | null;
  homeRedCards: number | null;
  awayRedCards: number | null;
  homeSaves: number | null;
  awaySaves: number | null;
  penaltyMissed: boolean;
}

const { matches } = JSON.parse(
  readFileSync(join(__dirname, "testdata", "eplMatches.json"), "utf8")
) as { matches: Match[] };
assert.strictEqual(matches.length, 380);

function largestDeficitOvercome(m: Match): number {
  let home = 0;
  let away = 0;
  let maxDeficitForHome = 0;
  let maxDeficitForAway = 0;
  const sorted = [...m.goals].sort((a, b) => a.minuteValue - b.minuteValue);
  for (const g of sorted) {
    if (g.team === m.home) home++;
    else away++;
    if (away - home > maxDeficitForHome) maxDeficitForHome = away - home;
    if (home - away > maxDeficitForAway) maxDeficitForAway = home - away;
  }
  if (m.homeScore > m.awayScore) return maxDeficitForHome;
  if (m.awayScore > m.homeScore) return maxDeficitForAway;
  return Math.max(maxDeficitForHome, maxDeficitForAway);
}

function lateGoalChangedResult(m: Match): boolean {
  const LATE = 85 * 60;
  const sorted = [...m.goals].sort((a, b) => a.minuteValue - b.minuteValue);
  let home = 0;
  let away = 0;
  let prevState: string | null = null;
  let changed = false;
  for (const g of sorted) {
    if (g.team === m.home) home++;
    else away++;
    const state = home === away ? "tie" : home > away ? "home" : "away";
    if (g.minuteValue >= LATE && prevState !== null && state !== prevState) changed = true;
    prevState = state;
  }
  return changed;
}

function maxGoalsByPlayer(m: Match): number {
  const counts: Record<string, number> = {};
  for (const g of m.goals) {
    if (g.ownGoal || !g.scorer) continue;
    counts[g.scorer] = (counts[g.scorer] ?? 0) + 1;
  }
  const vals = Object.values(counts);
  return vals.length ? Math.max(...vals) : 0;
}

const tierCounts = { instant_classic: 0, worth_your_time: 0, solid: 0, skippable: 0 };
for (const m of matches) {
  const score = computeSoccerWatchabilityScore(
    {
      margin: Math.abs(m.homeScore - m.awayScore),
      totalGoals: m.homeScore + m.awayScore,
      largestDeficitOvercome: largestDeficitOvercome(m),
      lateDecisiveGoal: lateGoalChangedResult(m),
      maxGoalsByPlayer: maxGoalsByPlayer(m),
      combinedShotsOnTarget: (m.homeShotsOnTarget ?? 0) + (m.awayShotsOnTarget ?? 0),
      anyRedCard: (m.homeRedCards ?? 0) + (m.awayRedCards ?? 0) > 0,
      maxSavesByKeeper: Math.max(m.homeSaves ?? 0, m.awaySaves ?? 0),
      anyFreeKickGoal: m.goals.some((g) => g.freeKick),
      anyPenaltyMissed: m.penaltyMissed,
    },
    undefined // stakes excluded - matches the validated design-proposal simulation
  );
  tierCounts[tierForScore(score.total)]++;
}

// Matches the tier breakdown reported in the design proposal for the
// saves/free-kick/penalty-miss expansion.
assert.strictEqual(tierCounts.instant_classic, 2);
assert.strictEqual(tierCounts.worth_your_time, 11);
assert.strictEqual(tierCounts.solid, 69);
assert.strictEqual(tierCounts.skippable, 298);

console.log("soccerRubric.test.ts: all assertions passed");
