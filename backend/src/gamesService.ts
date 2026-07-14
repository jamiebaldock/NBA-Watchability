import { EspnEvent, League, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { deriveCompetitionLabel, mapEspnState, mapEventToGame } from "./gameMapper";
import {
  FinalRubric,
  GameRow,
  getFinalGamesMissingHighlights,
  getGame,
  getLagPercentiles,
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

// Beyond the percentile ladder (p50 -> p75 -> p90, from gameStore's learned
// per-league lag data), a still-missing game falls back to this cadence
// forever - never permanently gives up (lifecycle point: a game is never
// stuck "unfindable"), but doesn't let one video that's genuinely never
// posted (rare) quietly eat the shared 100/day quota at a tight interval
// indefinitely.
const YT_TAIL_RETRY_INTERVAL_MS = 24 * 60 * 60 * 1000;
// Floor under the percentile ladder so a game can never be checked twice in
// quick succession even in a "catching up after downtime" burst where
// several rungs are already overdue at once.
const YT_MIN_CHECK_SPACING_MS = 5 * 60 * 1000;

let loggedMissingApiKey = false;

/**
 * Whether [row] is due for its next highlights check, per the percentile
 * ladder learned from real match data for its league (gameStore's
 * getLagPercentiles) - rung 0 fires at the league's observed p50 delay
 * since the game went final, rung 1 at p75, rung 2 at p90, then the tail
 * cadence forever after. A league with no history yet uses sane bootstrap
 * defaults and gradually switches over to its own real percentiles as
 * matches get found (see gameStore.ts).
 */
function isDueForHighlightsCheck(row: GameRow, now: number): boolean {
  if (row.ytVideoId) return false;
  if (row.ytLastCheckedAt && now - new Date(row.ytLastCheckedAt).getTime() < YT_MIN_CHECK_SPACING_MS) return false;

  const anchor = row.finalAt ?? row.tipoffUtc;
  const sinceFinalMs = now - new Date(anchor).getTime();
  const { p50Ms, p75Ms, p90Ms } = getLagPercentiles(row.league, row.leagueGroup);
  const rungs = [p50Ms, p75Ms, p90Ms];
  const nextDelayMs = row.ytCheckCount < rungs.length ? rungs[row.ytCheckCount] : YT_TAIL_RETRY_INTERVAL_MS;
  return sinceFinalMs >= nextDelayMs;
}

/** Searches for and records the result of one game's highlights check - shared by the poller and the demand-driven trigger below so there's exactly one place this happens. */
async function checkGameHighlights(row: GameRow): Promise<void> {
  const highlightsLeague: HighlightsLeague = row.league === "wnba" ? "wnba" : "nba";
  console.log(`checkGameHighlights: searching for ${row.away} @ ${row.home} (${row.eventId}, ${highlightsLeague})`);
  const match = await searchHighlightsVideo(highlightsLeague, row.away, row.home, row.tipoffUtc);
  markHighlightsChecked(row.eventId, new Date().toISOString());
  if (match) {
    setHighlights(row.eventId, match.videoId);
    console.log(`checkGameHighlights: matched "${match.title}" (${match.videoId})`);
  } else {
    console.log(`checkGameHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
  }
}

/**
 * Checks every final game still missing a highlights match (globally, not
 * scoped to any date range - gameStore.getFinalGamesMissingHighlights is a
 * simple query, not a per-day file scan) and searches for whichever are due
 * per isDueForHighlightsCheck. Called by highlightsPoller.ts on a timer, as
 * a backstop for games nobody's actively looking at - getGamesForDate below
 * also triggers this inline for whatever a live request happens to touch,
 * so someone actively viewing a game doesn't have to wait for the next
 * poller tick.
 */
export async function checkPendingHighlights(): Promise<void> {
  if (!isYoutubeSearchConfigured()) {
    if (!loggedMissingApiKey) {
      loggedMissingApiKey = true;
      console.warn("checkPendingHighlights: YOUTUBE_API_KEY not set - highlights search is disabled entirely");
    }
    return;
  }

  const now = Date.now();
  for (const row of getFinalGamesMissingHighlights()) {
    if (isDueForHighlightsCheck(row, now)) await checkGameHighlights(row);
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

    // Demand-driven: a live request touching this game is a chance to check
    // sooner than the next poller tick, if the learned per-league schedule
    // says it's actually due (isDueForHighlightsCheck) - not on every
    // request regardless, which would defeat the whole point of scheduling.
    if (!row.ytVideoId && isYoutubeSearchConfigured() && isDueForHighlightsCheck(row, Date.now())) {
      await checkGameHighlights(row);
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
