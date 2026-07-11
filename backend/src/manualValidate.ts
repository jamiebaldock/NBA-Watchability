// Scratch script (not part of the build) to sanity-check the mapper against
// a real completed game before wiring it into the full pipeline. Run with:
//   npx tsx src/manualValidate.ts
import { fetchScoreboard, fetchSummary } from "./espnClient";
import { mapEventToGame } from "./gameMapper";
import { computeWatchabilityScore, tierForScore, isScoreVisible } from "./rubric";

async function main() {
  const events = await fetchScoreboard("20250115", "nba");
  for (const event of events) {
    const summary = await fetchSummary(event.id, "nba");
    const mapped = mapEventToGame(event, "nba", summary);
    const scoreBreakdown = computeWatchabilityScore(mapped.rubric, 5);
    const visible = isScoreVisible(mapped.status, mapped.rubric.period);
    console.log(
      `${mapped.away} @ ${mapped.home} | status=${mapped.status} margin=${mapped.rubric.finalMargin} ` +
        `deficit=${mapped.rubric.largestDeficitOvercome} leadChanges=${mapped.rubric.leadChanges} ` +
        `OT=${mapped.rubric.overtimePeriods} close2m=${mapped.rubric.closeInFinalTwoMin} ` +
        `leadChgFinalMin=${mapped.rubric.leadChangeInFinalMin} decidedFinalPoss=${mapped.rubric.decidedOnFinalPossession} ` +
        `buzzer=${mapped.rubric.buzzerBeater} star=${mapped.rubric.starPerformance} ` +
        `=> score=${scoreBreakdown.total} tier=${tierForScore(scoreBreakdown.total)} visible=${visible}`
    );
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
