import { EspnEvent, League, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { deriveCompetitionLabel, mapEspnState, mapEventToGame } from "./gameMapper";
import {
  FinalRubric,
  GameRow,
  getFinalGamesMissingHighlights,
  getGame,
  markHighlightsChecked,
  setFinalRubric,
  setHighlights,
  setPreview,
  updateStatus,
  upsertBaseEntry,
} from "./gameStore";
import { generateHookAndStakes } from "./llm";
import { computeWatchabilityScore, tierForScore } from "./rubric";
import { GameJson, LeagueGroup } from "./types";
import { HighlightsLeague, isYoutubeSearchConfigured, searchHighlightsVideo } from "./youtubeClient";

const PREVIEW_GATE_HOURS_BEFORE_TIPOFF = 24;

/** Whether it's time to generate this game's pregame preview - a flat window before its own tipoff, not tied to any venue's local clock. */
function hasReachedPreviewGate(tipoffUtc: string, now: Date = new Date()): boolean {
  const gateTime = new Date(tipoffUtc).getTime() - PREVIEW_GATE_HOURS_BEFORE_TIPOFF * 60 * 60 * 1000;
  return now.getTime() >= gateTime;
}

// NBA and WNBA are mutually-exclusive slates the client picks between (settings
// toggle + top-left dropdown) - never unioned together like the Summer League
// variants are within the "nba" group. gameStore rows are keyed by eventId
// (globally unique across ESPN sports), so both groups safely share the same
// store with no risk of collision.
const LEAGUE_GROUPS: Record<LeagueGroup, readonly League[]> = {
  nba: ["nba", "nba-summer-las-vegas", "nba-summer-utah", "nba-summer-sacramento"],
  wnba: ["wnba"],
};

function overallRecord(competitor: EspnEvent["competitions"][number]["competitors"][number]): string | undefined {
  return competitor.records?.find((r) => r.type === "total")?.summary;
}

interface LeagueEvent {
  league: League;
  event: EspnEvent;
}

/** Each league is its own ESPN "sport", so a day's full slate is the union of separate per-league fetches. */
async function fetchAllEvents(espnDate: string, leagueGroup: LeagueGroup): Promise<LeagueEvent[]> {
  const perLeague = await Promise.all(
    LEAGUE_GROUPS[leagueGroup].map(async (league) => {
      const events = await fetchScoreboard(espnDate, league);
      return events.map((event): LeagueEvent => ({ league, event }));
    })
  );
  return perLeague.flat();
}

/** Plain, spoiler-free placeholder shown on the tile until the real pregame preview generates. */
function fallbackHook(away: string, home: string): string {
  return `${away} at ${home}.`;
}

/**
 * Generates the pregame preview (hook/stakes/pitch) exactly once per game -
 * not the first time the event is seen, but starting 24 hours before its own
 * tipoff, so games populate on a staggered schedule instead of every game at
 * a venue clustering around the same wall-clock hour. Before that gate, or if
 * generation already happened, this is a no-op. Permanent once set -
 * gameStore.setPreview's own WHERE hook IS NULL guard is the actual
 * enforcement, this check is just to avoid a wasted LLM call.
 */
async function ensurePregamePreview(row: GameRow, league: League, event: EspnEvent): Promise<void> {
  if (row.hook !== null) return; // already generated
  if (!hasReachedPreviewGate(event.date)) return; // not time yet

  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: away.team.displayName,
    home: home.team.displayName,
    awayRecord: overallRecord(away),
    homeRecord: overallRecord(home),
    notes: league.startsWith("nba-summer")
      ? "NBA Summer League exhibition game — rookies/prospects, not regular-season standings, no playoff implications"
      : undefined,
  });

  setPreview(row.eventId, hook, pitch, stakes);
}

// The official channel rarely has a game's highlights video up within
// minutes of it going final (observed same-day, but not immediately), so a
// miss is retried on this cadence rather than being permanent - not on
// every request, since search.list has its own hard 100-calls/day quota
// bucket (youtubeClient.ts). Retries forever (lifecycle point: a game is
// never permanently "unfindable") but slows down after 48h so one video
// that's genuinely never posted (rare) doesn't quietly eat the shared quota
// every 30 minutes indefinitely.
const YT_RETRY_INTERVAL_MS = 30 * 60 * 1000;
const YT_SLOWDOWN_AFTER_MS = 48 * 60 * 60 * 1000;
const YT_SLOW_RETRY_INTERVAL_MS = 24 * 60 * 60 * 1000;

let loggedMissingApiKey = false;

/**
 * Checks every final game still missing a highlights match (globally, not
 * scoped to any date range - gameStore.getFinalGamesMissingHighlights is a
 * simple query, not a per-day file scan) and searches for whichever are due
 * a retry. Called by highlightsPoller.ts on a timer - live requests
 * (getGamesForDate) no longer trigger this inline, since a game's highlights
 * have nothing to do with the ESPN data a request happens to be fetching.
 */
export async function checkPendingHighlights(): Promise<void> {
  if (!isYoutubeSearchConfigured()) {
    if (!loggedMissingApiKey) {
      loggedMissingApiKey = true;
      console.warn("checkPendingHighlights: YOUTUBE_API_KEY not set - highlights search is disabled entirely");
    }
    return;
  }

  const pending = getFinalGamesMissingHighlights();
  const now = Date.now();

  for (const row of pending) {
    const sinceTipoffMs = now - new Date(row.tipoffUtc).getTime();
    const cooldownMs = sinceTipoffMs > YT_SLOWDOWN_AFTER_MS ? YT_SLOW_RETRY_INTERVAL_MS : YT_RETRY_INTERVAL_MS;

    if (row.ytLastCheckedAt) {
      const sinceLastCheckMs = now - new Date(row.ytLastCheckedAt).getTime();
      if (sinceLastCheckMs < cooldownMs) continue;
    }

    const highlightsLeague: HighlightsLeague = row.league === "wnba" ? "wnba" : "nba";
    console.log(`checkPendingHighlights: searching for ${row.away} @ ${row.home} (${row.eventId}, ${highlightsLeague})`);
    const match = await searchHighlightsVideo(highlightsLeague, row.away, row.home, row.tipoffUtc);
    markHighlightsChecked(row.eventId, new Date(now).toISOString());
    if (match) {
      setHighlights(row.eventId, match.videoId);
      console.log(`checkPendingHighlights: matched "${match.title}" (${match.videoId})`);
    } else {
      console.log(`checkPendingHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
    }
  }
}

function toGameJson(row: GameRow, status: "upcoming" | "live", cl: string | undefined, q?: number, clk?: string): GameJson {
  const lg: GameJson["lg"] = row.league === "nba" ? "nba" : row.league === "wnba" ? "wnba" : "summer";
  return {
    a: row.away,
    h: row.home,
    al: row.awayLogo ?? undefined,
    hl: row.homeLogo ?? undefined,
    stt: status,
    utc: row.tipoffUtc,
    lg,
    cl,
    q,
    clk,
    ot: 0,
    c5: false,
    lcf: false,
    fp: false,
    bz: false,
    st: null,
    sk: row.stakes ?? undefined,
    hook: row.hook ?? fallbackHook(row.away, row.home),
    pitch: row.pitch ?? undefined,
    score_visible: false,
  };
}

/**
 * Fetches, scores, and caches one day's games, matching the mobile client's
 * JSON contract (nba-watchability-spec.md section 5).
 *
 * Bandwidth note: the one expensive ESPN call is fetchSummary (full
 * play-by-play + box score) - it's only ever made once per game, the first
 * time that game is seen as "final", and cached forever after via the
 * gameStore final-rubric fields. While a game is live, period/clock come for
 * free from the same lightweight scoreboard listing already being fetched,
 * so live games show LIVE + quarter + clock but no watchability score - the
 * score only appears once, all at once, when the game ends.
 */
export async function getGamesForDate(date: string, leagueGroup: LeagueGroup = "nba"): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const leagueEvents = await fetchAllEvents(espnDate, leagueGroup);

  const results: GameJson[] = [];

  for (const { league, event } of leagueEvents) {
    const competition = event.competitions[0];
    const away = competition.competitors.find((c) => c.homeAway === "away")!;
    const home = competition.competitors.find((c) => c.homeAway === "home")!;
    const status = mapEspnState(competition.status.type.state);

    upsertBaseEntry({
      eventId: event.id,
      league,
      leagueGroup,
      away: away.team.displayName,
      home: home.team.displayName,
      awayLogo: away.team.logo,
      homeLogo: home.team.logo,
      tipoffUtc: event.date,
      status,
    });
    updateStatus(event.id, status);

    let row = getGame(event.id)!;
    // Summer League keeps its own separate static label client-side.
    const cl = league.startsWith("nba-summer") ? undefined : deriveCompetitionLabel(event, league === "wnba" ? "wnba" : "nba");

    if (status !== "final") {
      await ensurePregamePreview(row, league, event);
      row = getGame(event.id)!;
    }

    if (status === "upcoming") {
      results.push(toGameJson(row, "upcoming", cl));
      continue;
    }

    if (status === "live") {
      results.push(toGameJson(row, "live", cl, competition.status.period, competition.status.displayClock));
      continue;
    }

    // final: compute the rubric once, ever - already-set fields are never touched again.
    if (row.score === null) {
      const summary = await fetchSummary(event.id, league);
      const mapped = mapEventToGame(event, league, summary);
      const score = computeWatchabilityScore(mapped.rubric, row.stakes ?? undefined).total;
      const rubric: FinalRubric = {
        awayScore: parseFloat(away.score),
        homeScore: parseFloat(home.score),
        finalMargin: mapped.rubric.finalMargin ?? 0,
        largestDeficitOvercome: mapped.rubric.largestDeficitOvercome ?? 0,
        leadChanges: mapped.rubric.leadChanges ?? 0,
        overtimePeriods: mapped.rubric.overtimePeriods,
        closeInFinalTwoMin: mapped.rubric.closeInFinalTwoMin,
        leadChangeInFinalMin: mapped.rubric.leadChangeInFinalMin,
        decidedOnFinalPossession: mapped.rubric.decidedOnFinalPossession,
        buzzerBeater: mapped.rubric.buzzerBeater,
        starPerformance: mapped.rubric.starPerformance,
        score,
        tier: tierForScore(score),
      };
      setFinalRubric(event.id, rubric);
      row = getGame(event.id)!;
    }

    const lg: GameJson["lg"] = row.league === "nba" ? "nba" : row.league === "wnba" ? "wnba" : "summer";
    results.push({
      a: row.away,
      h: row.home,
      al: row.awayLogo ?? undefined,
      hl: row.homeLogo ?? undefined,
      stt: "final",
      utc: row.tipoffUtc,
      lg,
      cl,
      ot: row.overtimePeriods ?? 0,
      m: row.finalMargin ?? undefined,
      cb: row.largestDeficitOvercome ?? undefined,
      lc: row.leadChanges ?? undefined,
      c5: Boolean(row.closeInFinalTwoMin),
      lcf: Boolean(row.leadChangeInFinalMin),
      fp: Boolean(row.decidedOnFinalPossession),
      bz: Boolean(row.buzzerBeater),
      st: row.starPerformance,
      sk: row.stakes ?? undefined,
      hook: row.hook ?? fallbackHook(row.away, row.home),
      pitch: row.pitch ?? undefined,
      score: row.score ?? undefined,
      score_visible: true,
      yt: row.ytVideoId ?? undefined,
    });
  }

  return results;
}
