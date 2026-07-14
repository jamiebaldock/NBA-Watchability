// Backstop for games nobody's actively viewing - getGamesForDate
// (gamesService.ts) already triggers a highlights check inline for whatever
// a live request happens to touch, gated by the same learned per-league
// schedule (isDueForHighlightsCheck), so this poller only needs to catch
// games that demand-driven triggering never reaches. checkPendingHighlights
// queries gameStore for every final game still missing a match, globally -
// no date/league looping needed here, since the store isn't scoped to a
// lookback window the way the old per-day cache files were. This is also
// what makes "retry forever, never permanently give up" (the lifecycle
// spec) actually work: a game from 2 weeks ago is just as reachable as one
// from yesterday.
//
// 30 min rather than tighter: the schedule's own rungs (p50/p75/p90 per
// league, gameStore.getLagPercentiles) are what actually decide whether a
// check happens, not this tick rate - this just needs to be frequent enough
// that an unattended game doesn't sit noticeably past its due time, not
// frequent enough to drive the schedule itself.
import { checkPendingHighlights } from "./gamesService";

const POLL_INTERVAL_MS = 30 * 60 * 1000;

async function pollOnce(): Promise<void> {
  try {
    await checkPendingHighlights();
  } catch (err) {
    console.error("highlightsPoller: checkPendingHighlights failed", err);
  }
}

/** Starts the recurring poll - call once at server startup. */
export function startHighlightsPoller(): void {
  pollOnce();
  setInterval(pollOnce, POLL_INTERVAL_MS);
}
