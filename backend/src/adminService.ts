// Backs the mobile app's hidden Admin page - a pre-launch, James-only
// operational view into the highlights-search pipeline (built after
// investigating why recent games weren't reliably getting highlights; see
// that investigation's findings for the two real bugs found - a too-tight
// retry window and an MLB team-nickname mismatch - which this page exists
// to make visible without needing another one-off diagnostic route next time).
//
// PIN check is server-side, not client-side, deliberately: a client-side-
// only check would ship the PIN inside the APK, trivially recoverable by
// decompiling it once this is on the Play Store. Real login can later
// replace exactly the pin/token issuance below without touching how the
// client calls the other endpoints - they already only care about a bearer
// token, not how it was obtained.
import { randomBytes } from "node:crypto";
import {
  DAILY_SEARCH_BUDGET,
  GameRow,
  LagPercentiles,
  canSpendSearchQuota,
  getFinalGamesMissingHighlights,
  getFinalMlbGamesMissingHighlights,
  getGame,
  getLagPercentiles,
  getSearchBudgetHistory,
  getSearchOutcomeCounts,
  getTodaySearchBudgetCount,
  recordHighlightsSearchLog,
  recordSearchQuotaSpend,
  setHighlights,
} from "./gameStore";
import { HighlightsLeague, searchHighlightsVideo } from "./youtubeClient";

export class AdminUnauthorizedError extends Error {}
export class AdminBadRequestError extends Error {}

// In-memory only - a Render restart (deploy, or the free tier spinning down
// an idle instance) invalidates every outstanding session, which just means
// re-entering the PIN once. No persistence needed for a single-operator,
// low-frequency admin page; not worth a DB table for this.
const ADMIN_TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
const tokens = new Map<string, number>();

export function adminLogin(pin: string): { token: string } {
  const expected = process.env.ADMIN_PIN;
  if (!expected) throw new AdminBadRequestError("ADMIN_PIN is not configured on the server");
  if (pin !== expected) throw new AdminUnauthorizedError("incorrect PIN");

  const token = randomBytes(24).toString("hex");
  tokens.set(token, Date.now() + ADMIN_TOKEN_TTL_MS);
  return { token };
}

export function isValidAdminToken(token: string | undefined): boolean {
  if (!token) return false;
  const expiry = tokens.get(token);
  if (!expiry || expiry < Date.now()) {
    tokens.delete(token);
    return false;
  }
  return true;
}

export interface AdminStats {
  todayCount: number;
  dailyCap: number;
  budgetHistory: { date: string; count: number }[];
  lagPercentiles: Record<string, LagPercentiles>;
  outcomeCounts: Record<string, number>;
}

export function getAdminStats(): AdminStats {
  return {
    todayCount: getTodaySearchBudgetCount(),
    dailyCap: DAILY_SEARCH_BUDGET,
    budgetHistory: getSearchBudgetHistory(14),
    // Basketball/MLB only - the three leagues with highlights search
    // actually wired in live (NFL/NHL's own search is built but dormant,
    // see nflGamesService.ts/nhlGamesService.ts).
    lagPercentiles: {
      nba: getLagPercentiles("nba", "nba"),
      wnba: getLagPercentiles("wnba", "wnba"),
      mlb: getLagPercentiles("mlb", "mlb"),
    },
    outcomeCounts: getSearchOutcomeCounts(7),
  };
}

export interface AdminMissingHighlightsGame {
  eventId: string;
  league: string;
  leagueGroup: string;
  away: string;
  home: string;
  tipoffUtc: string;
  ytCheckCount: number;
  ytLastCheckedAt: string | null;
}

function toAdminRow(row: GameRow): AdminMissingHighlightsGame {
  return {
    eventId: row.eventId,
    league: row.league,
    leagueGroup: row.leagueGroup,
    away: row.away,
    home: row.home,
    tipoffUtc: row.tipoffUtc,
    ytCheckCount: row.ytCheckCount,
    ytLastCheckedAt: row.ytLastCheckedAt,
  };
}

/** Every currently-missing recent game across every league with live search - newest first, same as the underlying gameStore queries. */
export function getAdminMissingHighlights(): AdminMissingHighlightsGame[] {
  return [...getFinalGamesMissingHighlights(), ...getFinalMlbGamesMissingHighlights()]
    .sort((a, b) => (a.tipoffUtc < b.tipoffUtc ? 1 : -1))
    .map(toAdminRow);
}

const SUPPORTED_RESEND_LEAGUES: ReadonlySet<string> = new Set(["nba", "wnba", "mlb"]);

export interface AdminResendResult {
  matched: boolean;
  videoId?: string;
  title?: string;
}

/**
 * Manual, on-demand re-search for one game - the Admin page's "Re-search"
 * button. Deliberately bypasses the normal scheduling gates (the 15/45-min
 * timing window, MAX_HIGHLIGHTS_CHECKS's 2-attempt abandonment) since the
 * whole point is retrying a game that path already gave up on. Still spends
 * a real search.list unit against the same shared daily budget every other
 * caller uses - there's only one real quota, Google's, and this can't
 * bypass that even if it bypasses this app's own internal scheduling.
 */
export async function resendHighlightsSearch(eventId: string): Promise<AdminResendResult> {
  const row = getGame(eventId);
  if (!row) throw new AdminBadRequestError("no such game");
  if (!SUPPORTED_RESEND_LEAGUES.has(row.leagueGroup)) {
    throw new AdminBadRequestError(`highlights search isn't live for ${row.leagueGroup} yet`);
  }
  if (!canSpendSearchQuota()) {
    throw new AdminBadRequestError("today's search quota is already spent");
  }

  const league = row.leagueGroup as HighlightsLeague;
  recordSearchQuotaSpend();

  let match;
  try {
    match = await searchHighlightsVideo(league, row.away, row.home, row.tipoffUtc);
  } catch (err) {
    recordHighlightsSearchLog(row.eventId, row.leagueGroup, "api_error", String(err));
    throw err;
  }

  if (match) {
    setHighlights(row.eventId, match.videoId, match.publishedAt);
    recordHighlightsSearchLog(row.eventId, row.leagueGroup, "matched");
    return { matched: true, videoId: match.videoId, title: match.title };
  }
  recordHighlightsSearchLog(row.eventId, row.leagueGroup, "no_match");
  return { matched: false };
}
