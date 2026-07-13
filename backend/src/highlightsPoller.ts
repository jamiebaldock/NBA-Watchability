// Proactively triggers the highlights search shortly after a game finishes,
// instead of waiting for a user to happen to browse to that date first.
// getGamesForDate (gamesService.ts) already does the actual search+cache
// work via ensureHighlightsVideo, gated so a found match is never re-searched
// and a miss is retried only on its own cooldown (YT_RETRY_INTERVAL_MS) -
// this just calls it on a timer instead of relying on an HTTP request to
// trigger it, so a finished game's link shows up for whoever's already
// looking at the app, not just the next person to load that date.
import { getGamesForDate } from "./gamesService";
import { LeagueGroup } from "./types";

const POLL_INTERVAL_MS = 15 * 60 * 1000;
// Covers a game that finished late (crossing midnight UTC) or was missed
// by a prior tick - cheap to re-check since an already-found match is a
// no-op and a still-unmatched game is skipped until its own retry cooldown
// elapses (both in gamesService.ts's ensureHighlightsVideo), so a small
// buffer costs nothing extra.
const LOOKBACK_DAYS = 2;
const LEAGUE_GROUPS: LeagueGroup[] = ["nba", "wnba"];

function recentDateStrings(now: Date, days: number): string[] {
  const dates: string[] = [];
  for (let i = 0; i < days; i++) {
    dates.push(new Date(now.getTime() - i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
  }
  return dates;
}

async function pollOnce(): Promise<void> {
  const dates = recentDateStrings(new Date(), LOOKBACK_DAYS);
  for (const leagueGroup of LEAGUE_GROUPS) {
    for (const date of dates) {
      try {
        await getGamesForDate(date, leagueGroup);
      } catch (err) {
        console.error(`highlightsPoller: failed for ${leagueGroup} ${date}`, err);
      }
    }
  }
}

/** Starts the recurring poll - call once at server startup. */
export function startHighlightsPoller(): void {
  pollOnce();
  setInterval(pollOnce, POLL_INTERVAL_MS);
}
