// Backstop for games nobody's actively viewing - getGamesForDate
// (gamesService.ts) already triggers a highlights check inline for whatever
// a live request happens to touch, gated by the same learned per-league
// delay (isDueForHighlightsCheck), so this poller only needs to catch games
// that demand-driven triggering never reaches. checkPendingHighlights
// queries gameStore for final games still missing a match, scoped to the
// recent window only (gameStore.getFinalGamesMissingHighlights) - a
// historical/backfill game is never a candidate here, by design.
//
// 30 min rather than tighter: the learned per-league p50 delay and the
// fixed 30-min gap before the second attempt (gamesService.ts's
// isDueForHighlightsCheck) are what actually decide whether a game's next
// (of at most 2) checks happens, not this tick rate - this just needs to be
// frequent enough that an unattended game doesn't sit noticeably past its
// due time, not frequent enough to drive the schedule itself.
import { checkPendingHighlights } from "./gamesService";
import { checkPendingMlbHighlights } from "./mlbGamesService";

const POLL_INTERVAL_MS = 30 * 60 * 1000;

async function pollOnce(): Promise<void> {
  try {
    await checkPendingHighlights();
  } catch (err) {
    console.error("highlightsPoller: checkPendingHighlights failed", err);
  }
  try {
    await checkPendingMlbHighlights();
  } catch (err) {
    console.error("highlightsPoller: checkPendingMlbHighlights failed", err);
  }
}

/** Starts the recurring poll - call once at server startup. */
export function startHighlightsPoller(): void {
  pollOnce();
  setInterval(pollOnce, POLL_INTERVAL_MS);
}
