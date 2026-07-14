// Proactively triggers the highlights search shortly after a game finishes,
// instead of waiting for a user to happen to browse to that date first.
// checkPendingHighlights (gamesService.ts) queries gameStore for every final
// game still missing a match, globally - no date/league looping needed here
// anymore, since the store isn't scoped to a lookback window the way the old
// per-day cache files were. This is also what makes "retry forever, never
// permanently give up" (the lifecycle spec) actually work: a game from 2
// weeks ago is just as reachable as one from yesterday.
import { checkPendingHighlights } from "./gamesService";

const POLL_INTERVAL_MS = 15 * 60 * 1000;

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
