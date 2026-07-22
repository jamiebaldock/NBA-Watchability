// Alerts phase 4: the detection poller. Phases 1-3 built storage, the FCM
// send wrapper, and the mobile client (registration, the bell, receiving a
// push) - nothing decided *when* to actually call sendPush until this file.
//
// Close-swing detection has no calibrated rubric behind it the way
// mlbRubric.ts/nflRubric.ts/nhlRubric.ts do (those are fit against real
// season data - see the rubric-calibration project notes). A live game has
// none of the inputs those rubrics need (comeback size, lead changes, final
// margin) until it's over, so this uses a simple, openly-approximate proxy
// instead: current margin vs. a per-sport threshold, gated on being late in
// the game (final period/inning, or overtime). Tunable later; not meant to
// match the final-score tiers exactly, just to be in the right neighborhood.
import { claimAlertSend, clearDeadToken, getAlertableDevices, getDeviceFavorites, getGameSubDeviceIds, type AlertDeviceRow } from "./alertStore";
import { fetchScoreboard } from "./espnClient";
import { DEAD_TOKEN_CODES, sendPush } from "./fcm";
import { getGamesForDateAnySport } from "./httpHandler";
import { fetchMlbScoreboard } from "./mlbEspnClient";
import { fetchNflScoreboard } from "./nflEspnClient";
import { fetchNhlScoreboard } from "./nhlEspnClient";
import { GameJson, LeagueGroup } from "./types";

// Close-swing alerts are time-sensitive in a way highlights checks (30 min)
// aren't - a 3-minute tick still leaves real crunch-time margin for a push
// to arrive before the game's decided.
const POLL_INTERVAL_MS = 3 * 60 * 1000;

const LEAGUE_GROUPS: LeagueGroup[] = ["nba", "wnba", "mlb", "nfl", "nhl"];

type ExcitementLevel = 0 | 1 | 2 | 3;
const TIER_RANK: Record<string, ExcitementLevel> = { solid: 1, worth_your_time: 2, instant_classic: 3 };

// Absolute-margin cutoffs (points/runs/goals) for each excitement level, per
// sport - loosely picked, not fit against real data. game.lg's "summer"
// value (NBA Summer League) reuses the nba row.
const CLOSE_MARGIN: Record<string, { solid: number; worthYourTime: number; instantClassic: number }> = {
  nba: { solid: 8, worthYourTime: 5, instantClassic: 3 },
  wnba: { solid: 8, worthYourTime: 5, instantClassic: 3 },
  summer: { solid: 8, worthYourTime: 5, instantClassic: 3 },
  mlb: { solid: 3, worthYourTime: 2, instantClassic: 1 },
  nfl: { solid: 10, worthYourTime: 7, instantClassic: 4 },
  nhl: { solid: 2, worthYourTime: 1, instantClassic: 1 }
};

/** "Late enough to matter" - final period/inning or overtime, per sport. game.q carries quarter/period/inning depending on lg. */
function isLateGame(game: GameJson): boolean {
  if (game.q == null) return false;
  switch (game.lg) {
    case "nba":
    case "wnba":
    case "summer":
    case "nfl":
      return game.q >= 4;
    case "nhl":
      return game.q >= 3;
    case "mlb":
      return game.q >= 7;
    default:
      return false;
  }
}

/**
 * [margin] comes from fetchLiveMargins below, NOT GameJson.m - m is only
 * ever populated from the final rubric (row.finalMargin), by spoiler-free
 * design, so it's always undefined while a game is live. The original
 * version of this detector read game.m and therefore could never fire;
 * live margins have to come from a separate server-side-only scoreboard
 * read that never touches the client-facing payload.
 */
function excitementLevel(game: GameJson, margin: number | null): ExcitementLevel {
  if (game.stt !== "live" || margin == null || !isLateGame(game)) return 0;
  const thresholds = CLOSE_MARGIN[game.lg];
  if (!thresholds) return 0;
  if (margin <= thresholds.instantClassic) return 3;
  if (margin <= thresholds.worthYourTime) return 2;
  if (margin <= thresholds.solid) return 1;
  return 0;
}

// Minimal structural slice of what every sport's scoreboard event looks
// like - all four clients' event interfaces satisfy it (ESPN's site API
// uses the same competitions/competitors/score shape across sports).
interface ScoreboardEventLike {
  id: string;
  competitions: Array<{ competitors: Array<{ score?: string }> }>;
}

function collectMargins(events: ScoreboardEventLike[], into: Map<string, number>): void {
  for (const event of events) {
    const competitors = event.competitions?.[0]?.competitors;
    if (!competitors || competitors.length !== 2) continue;
    const a = Number(competitors[0]?.score);
    const b = Number(competitors[1]?.score);
    if (!Number.isFinite(a) || !Number.isFinite(b)) continue;
    into.set(event.id, Math.abs(a - b));
  }
}

/**
 * Live margins straight off each sport's ESPN scoreboard, server-side only.
 * Fetched per (needed league, date) pair - "needed" meaning a league group
 * that actually has a live game this tick, so idle leagues cost nothing.
 * Every failure is per-fetch and non-fatal: a missing margin just means no
 * alert for that game this tick, and the next tick retries naturally.
 */
async function fetchLiveMargins(neededLgs: Set<string>, dates: string[]): Promise<Map<string, number>> {
  const margins = new Map<string, number>();
  for (const date of dates) {
    const espnDate = date.replace(/-/g, "");
    for (const lg of neededLgs) {
      try {
        switch (lg) {
          case "nba":
            collectMargins(await fetchScoreboard(espnDate, "nba"), margins);
            break;
          case "summer":
            // GameJson bundles all three summer-league variants under "summer".
            collectMargins(await fetchScoreboard(espnDate, "nba-summer-las-vegas"), margins);
            collectMargins(await fetchScoreboard(espnDate, "nba-summer-utah"), margins);
            collectMargins(await fetchScoreboard(espnDate, "nba-summer-sacramento"), margins);
            break;
          case "wnba":
            collectMargins(await fetchScoreboard(espnDate, "wnba"), margins);
            break;
          case "mlb":
            collectMargins(await fetchMlbScoreboard(espnDate), margins);
            break;
          case "nfl":
            collectMargins(await fetchNflScoreboard(espnDate), margins);
            break;
          case "nhl":
            collectMargins(await fetchNhlScoreboard(espnDate), margins);
            break;
        }
      } catch (err) {
        console.error(`alertsPoller: live-margin fetch failed for ${lg} ${date}`, err);
      }
    }
  }
  return margins;
}

function utcDateString(offsetDays: number): string {
  return new Date(Date.now() + offsetDays * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

/**
 * Live games across all 5 leagues, +/-1 UTC day around now - a live game
 * can briefly sit in yesterday's or tomorrow's date bucket depending on
 * ESPN's US-Eastern bucketing vs. UTC (same reasoning as httpHandler.ts's
 * MAX_RANGE_DAYS buffer), so this over-fetches by a day on each side rather
 * than risk missing one. Every one of these calls hits data gamesService.ts
 * already caches, so the redundancy is cheap.
 */
async function fetchLiveGames(): Promise<GameJson[]> {
  const dates = [utcDateString(-1), utcDateString(0), utcDateString(1)];
  const results: GameJson[] = [];
  for (const leagueGroup of LEAGUE_GROUPS) {
    for (const date of dates) {
      try {
        const games = await getGamesForDateAnySport(date, leagueGroup);
        for (const game of games) if (game.stt === "live") results.push(game);
      } catch (err) {
        console.error(`alertsPoller: failed fetching ${leagueGroup} ${date}`, err);
      }
    }
  }
  const seen = new Set<string>();
  return results.filter((game) => {
    if (!game.id || seen.has(game.id)) return false;
    seen.add(game.id);
    return true;
  });
}

async function notifyDevice(device: AlertDeviceRow, game: GameJson, body: string): Promise<void> {
  if (!device.fcm_token || !game.id) return;
  // Fire-once guard: a device already notified for this game this session
  // (any earlier tick that also crossed its bar) never gets a second push.
  if (!claimAlertSend(device.device_id, game.id, "close_swing")) return;

  const results = await sendPush([device.fcm_token], {
    title: `${game.a} @ ${game.h}`,
    body,
    eventId: game.id
  });
  for (const result of results) {
    if (!result.ok && result.errorCode && DEAD_TOKEN_CODES.has(result.errorCode)) {
      clearDeadToken(result.token);
    }
  }
}

async function pollOnce(): Promise<void> {
  const devices = getAlertableDevices();
  if (devices.length === 0) return; // nobody to notify - skip the live-game fetch entirely

  let liveGames: GameJson[];
  try {
    liveGames = await fetchLiveGames();
  } catch (err) {
    console.error("alertsPoller: fetchLiveGames failed", err);
    return;
  }
  if (liveGames.length === 0) return;

  const dates = [utcDateString(-1), utcDateString(0), utcDateString(1)];
  const liveMargins = await fetchLiveMargins(new Set(liveGames.map((g) => g.lg)), dates);

  for (const game of liveGames) {
    if (!game.id) continue;
    const level = excitementLevel(game, liveMargins.get(game.id) ?? null);
    if (level === 0) continue;

    const belledDeviceIds = getGameSubDeviceIds(game.id);
    const belledSet = new Set(belledDeviceIds);
    // "nba" covers NBA Summer League too (device.leagues stores enabledLeagues'
    // apiValue, which has no separate "summer" entry - mirrors the app's own bundling).
    const normalizedLeague = game.lg === "summer" ? "nba" : game.lg;

    for (const device of devices) {
      const deviceLeagues = device.leagues.split(",").filter(Boolean);
      if (deviceLeagues.length > 0 && !deviceLeagues.includes(normalizedLeague)) continue;

      const isBelled = belledSet.has(device.device_id);
      const isFavoriteGame = getDeviceFavorites(device.device_id).some(
        (fav) => fav.team_name === game.a || fav.team_name === game.h
      );

      if (isBelled) {
        await notifyDevice(device, game, "You belled this one - it's getting close.");
        continue;
      }
      if (isFavoriteGame) {
        await notifyDevice(device, game, "Your team's game is getting close.");
        continue;
      }
      if (device.favorites_only) continue;
      if (!device.tier_threshold) continue; // "off" for non-favorite games
      const requiredRank = TIER_RANK[device.tier_threshold] ?? 99;
      if (level < requiredRank) continue;
      await notifyDevice(device, game, "A close game worth checking out.");
    }
  }
}

/**
 * Starts the recurring poll - call once at server startup, same pattern as
 * highlightsPoller.ts. Every invocation is .catch-wrapped: pollOnce can
 * reject outside its internal try/catches (an FCM network failure in
 * sendPush, a DB error in a store call), and an unhandled rejection would
 * take down the whole server process on modern Node, not just skip a tick.
 */
export function startAlertsPoller(): void {
  const safePoll = () => pollOnce().catch((err) => console.error("alertsPoller: tick failed", err));
  safePoll();
  setInterval(safePoll, POLL_INTERVAL_MS);
}
