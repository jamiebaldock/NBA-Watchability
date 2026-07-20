// Scratch script (not part of the build) to sanity-check the NHL mapper +
// rubric against real completed games before trusting the full pipeline -
// the NHL analogue of manualValidate.ts. Run with:
//   npx tsx src/manualValidateNhl.ts
import { fetchNhlScoreboard, fetchNhlSummary } from "./nhlEspnClient";
import { mapNhlEspnState, mapNhlEventToGame } from "./nhlGameMapper";
import { computeNhlWatchabilityScore, tierForNhlScore } from "./nhlRubric";

async function main() {
  const events = await fetchNhlScoreboard("20251115");
  for (const event of events) {
    const competition = event.competitions[0];
    const status = mapNhlEspnState(competition.status.type.state);
    if (status !== "final") continue;

    const summary = await fetchNhlSummary(event.id);
    const mapped = mapNhlEventToGame(event, summary);
    const scoreBreakdown = computeNhlWatchabilityScore(mapped.rubricInputs, 5);
    console.log(
      `${mapped.away} @ ${mapped.home} | ${mapped.awayScore}-${mapped.homeScore} margin=${mapped.rubricInputs.finalMargin} ` +
        `deficit=${mapped.rubricInputs.largestDeficitOvercome} leadChanges=${mapped.rubricInputs.leadChanges} ` +
        `OT=${mapped.rubricInputs.overtimePeriods} SO=${mapped.rubricInputs.wentToShootout} ` +
        `decisiveLate=${mapped.rubricInputs.decisiveScoreLate} PP=${mapped.rubricInputs.combinedPowerPlayGoals} ` +
        `maxGoals=${mapped.rubricInputs.maxGoalsByPlayer} maxSaves=${mapped.rubricInputs.maxGoalieSaves} ` +
        `shutout=${mapped.rubricInputs.teamShutout} standouts=${JSON.stringify(mapped.standoutPerformers)} ` +
        `=> score=${scoreBreakdown.total} tier=${tierForNhlScore(scoreBreakdown.total)}`
    );
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
