// One-off analysis script (not part of the build) - computes real
// percentiles/qualification rates for each nhlRubric.ts dimension against
// the full 2025-26 season backfill, per Step 4 of
// docs/rubric-calibration-procedure.md. Run with:
//   npx tsx src/analyzeNhlPercentiles.ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { NhlRawGame } from "./backfillRawStatsNhl";

const path = join(__dirname, "..", "data", "nhlRawStats.json");
const { games } = JSON.parse(readFileSync(path, "utf8")) as { games: NhlRawGame[] };

const n = games.length;
function pct(pred: (g: NhlRawGame) => boolean): string {
  const count = games.filter(pred).length;
  return `${count} (${((count / n) * 100).toFixed(1)}%)`;
}

console.log(`Total games: ${n}`);
console.log(`Regular season: ${games.filter((g) => g.seasonType === 2).length}, Postseason: ${games.filter((g) => g.seasonType === 3).length}`);

console.log("\n--- Margin ---");
console.log(`margin<=1: ${pct((g) => g.finalMargin <= 1)}`);
console.log(`margin<=2: ${pct((g) => g.finalMargin <= 2)}`);
console.log(`margin<=3: ${pct((g) => g.finalMargin <= 3)}`);
console.log(`margin<=4: ${pct((g) => g.finalMargin <= 4)}`);
console.log(`margin<=5: ${pct((g) => g.finalMargin <= 5)}`);
console.log(`margin>=6: ${pct((g) => g.finalMargin >= 6)}`);

console.log("\n--- Comeback (largest deficit overcome) ---");
console.log(`>=1: ${pct((g) => g.largestDeficitOvercome >= 1)}`);
console.log(`>=2: ${pct((g) => g.largestDeficitOvercome >= 2)}`);
console.log(`>=3: ${pct((g) => g.largestDeficitOvercome >= 3)}`);
console.log(`>=4: ${pct((g) => g.largestDeficitOvercome >= 4)}`);
console.log(`>=5: ${pct((g) => g.largestDeficitOvercome >= 5)}`);

console.log("\n--- Lead changes ---");
console.log(`>=1: ${pct((g) => g.leadChanges >= 1)}`);
console.log(`>=2: ${pct((g) => g.leadChanges >= 2)}`);
console.log(`>=3: ${pct((g) => g.leadChanges >= 3)}`);
console.log(`>=4: ${pct((g) => g.leadChanges >= 4)}`);
console.log(`0 (wire-to-wire): ${pct((g) => g.leadChanges === 0)}`);

console.log("\n--- Overtime/Shootout ---");
console.log(`Any OT/SO: ${pct((g) => g.overtimePeriods >= 1)}`);
console.log(`Went to shootout: ${pct((g) => g.wentToShootout)}`);
console.log(`OT decided (not SO): ${pct((g) => g.overtimePeriods >= 1 && !g.wentToShootout)}`);

console.log("\n--- Decisive late goal ---");
console.log(`decisiveScoreLate: ${pct((g) => g.decisiveScoreLate)}`);

console.log("\n--- Combined power-play goals ---");
console.log(`>=1: ${pct((g) => g.combinedPowerPlayGoals >= 1)}`);
console.log(`>=2: ${pct((g) => g.combinedPowerPlayGoals >= 2)}`);
console.log(`>=3: ${pct((g) => g.combinedPowerPlayGoals >= 3)}`);
console.log(`>=4: ${pct((g) => g.combinedPowerPlayGoals >= 4)}`);

console.log("\n--- Max goals by one player (star) ---");
console.log(`>=2 (multi-goal): ${pct((g) => g.maxGoalsByPlayer >= 2)}`);
console.log(`>=3 (hat trick): ${pct((g) => g.maxGoalsByPlayer >= 3)}`);
console.log(`>=4: ${pct((g) => g.maxGoalsByPlayer >= 4)}`);

console.log("\n--- Max goalie saves ---");
console.log(`>=25: ${pct((g) => g.maxGoalieSaves >= 25)}`);
console.log(`>=30: ${pct((g) => g.maxGoalieSaves >= 30)}`);
console.log(`>=35: ${pct((g) => g.maxGoalieSaves >= 35)}`);
console.log(`>=40: ${pct((g) => g.maxGoalieSaves >= 40)}`);
console.log(`>=45: ${pct((g) => g.maxGoalieSaves >= 45)}`);

console.log("\n--- Team shutout ---");
console.log(`teamShutout: ${pct((g) => g.teamShutout)}`);

console.log("\n--- Total goals ---");
console.log(`>=5: ${pct((g) => g.totalGoals >= 5)}`);
console.log(`>=6: ${pct((g) => g.totalGoals >= 6)}`);
console.log(`>=7: ${pct((g) => g.totalGoals >= 7)}`);
console.log(`>=8: ${pct((g) => g.totalGoals >= 8)}`);
console.log(`>=9: ${pct((g) => g.totalGoals >= 9)}`);
console.log(`>=10: ${pct((g) => g.totalGoals >= 10)}`);
const avgGoals = games.reduce((s, g) => s + g.totalGoals, 0) / n;
console.log(`mean total goals: ${avgGoals.toFixed(2)}`);

// Rough composite score using current provisional brackets, to find real
// tier-cutoff percentiles (Step 6/8) - imported directly rather than
// re-deriving so this stays in sync with the shipped rubric.
import("./nhlRubric").then(({ computeNhlWatchabilityScore }) => {
  const scores = games
    .map((g) =>
      computeNhlWatchabilityScore(
        {
          finalMargin: g.finalMargin,
          totalGoals: g.totalGoals,
          largestDeficitOvercome: g.largestDeficitOvercome,
          leadChanges: g.leadChanges,
          overtimePeriods: g.overtimePeriods,
          wentToShootout: g.wentToShootout,
          decisiveScoreLate: g.decisiveScoreLate,
          combinedPowerPlayGoals: g.combinedPowerPlayGoals,
          maxGoalsByPlayer: g.maxGoalsByPlayer,
          maxGoalieSaves: g.maxGoalieSaves,
          teamShutout: g.teamShutout
        },
        undefined
      ).total
    )
    .sort((a, b) => a - b);

  function scoreAtPercentile(p: number): number {
    return scores[Math.floor(p * scores.length)];
  }

  console.log("\n--- Real score distribution (stakes excluded, current provisional brackets) ---");
  console.log(`min=${scores[0]} p10=${scoreAtPercentile(0.1)} p25=${scoreAtPercentile(0.25)} median=${scoreAtPercentile(0.5)} p75=${scoreAtPercentile(0.75)} p90=${scoreAtPercentile(0.9)} p95=${scoreAtPercentile(0.95)} p99=${scoreAtPercentile(0.99)} max=${scores[scores.length - 1]}`);
  console.log(`% scoring >=78 (instant_classic): ${((scores.filter((s) => s >= 78).length / n) * 100).toFixed(1)}%`);
  console.log(`% scoring >=56 (worth_your_time): ${((scores.filter((s) => s >= 56).length / n) * 100).toFixed(1)}%`);
  console.log(`% scoring >=29 (solid): ${((scores.filter((s) => s >= 29).length / n) * 100).toFixed(1)}%`);
});
