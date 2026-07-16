import { EspnEvent, League, fetchCalendarDates, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { deriveCompetitionLabel, mapEspnState, mapEventToGame } from "./gameMapper";
import {
  FinalRubric,
  GameRow,
  canSpendSearchQuota,
  getFinalGamesMissingHighlights,
  getGame,
  markHighlightsChecked,
  recordSearchQuotaSpend,
  setFinalRubric,
  setHighlights,
  setPreview,
  setSeasonStageLabel,
  updateStatus,
  upsertBaseEntry,
} from "./gameStore";
import { generateHookAndStakes } from "./llm";
import { computeWatchabilityScore, tierForScore } from "./rubric";
import { preferDarkLogoVariant } from "./teamLogos";
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
//
// The two basketball-sport LeagueGroup values - narrower than LeagueGroup
// itself now that "epl"/"la-liga" (soccer, an entirely separate ESPN client/
// mapper/rubric) are members of that type too. Every function in this file
// only ever handles basketball, so they're typed against this narrower alias
// rather than LeagueGroup - the sport dispatch (SPORT_FOR_LEAGUE_GROUP,
// types.ts) happens one layer up, in httpHandler.ts, before a soccer
// leagueGroup would ever reach here.
export type BasketballLeagueGroup = "nba" | "wnba";

// Exported so seasonWindowService.ts can derive a group's full-season
// browsing range from the same set of member leagues the live schedule
// itself unions - NBA's group spans 4 separate ESPN "leagues" (the real
// season plus 3 Summer League sites), and a season-window fetch scoped to
// only one of them would miss whichever isn't currently in its own season.
export const LEAGUE_GROUPS: Record<BasketballLeagueGroup, readonly League[]> = {
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
async function fetchAllEvents(espnDate: string, leagueGroup: BasketballLeagueGroup): Promise<LeagueEvent[]> {
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

// Floor so a game can never be checked twice in quick succession even if
// the poller and a demand-driven request both find it due at nearly the
// same moment.
const YT_MIN_CHECK_SPACING_MS = 5 * 60 * 1000;

// Gap before the first check, counted from final_at/tipoff. Deliberately a
// short fixed constant, not the league's learned p50 - gating check 0
// behind a *learned* delay was tried and reverted: since the system never
// looks before its own current p50 estimate, it can mechanically never
// discover a shorter true delay than whatever it already believes (any
// match found by check 0 is found at-or-after p50 by construction, which
// only ever reinforces or raises the estimate, never lowers it). A fixed,
// modest floor breaks that circularity - it costs an occasional wasted
// search on a near-certain miss, but that's the price of being able to
// observe reality instead of just confirming a prior belief.
const YT_FIRST_CHECK_DELAY_MS = 15 * 60 * 1000;

// Gap before the second (and final) attempt, counted from the first
// check's own timestamp - not from the league's learned delay, since the
// first check may itself have fired late (30-min poller granularity).
const YT_SECOND_CHECK_DELAY_MS = 30 * 60 * 1000;

// Two attempts total, never more: a game that misses both is not retried
// again (no daily-forever tail) - if it's later found to be missing its
// link, that's a manual fix (James pastes the real link), not something
// the app keeps polling for.
const MAX_HIGHLIGHTS_CHECKS = 2;

let loggedMissingApiKey = false;

/**
 * Whether [row] is due for its next (first or second) highlights check.
 * Check 0 waits a short fixed delay (YT_FIRST_CHECK_DELAY_MS) since the game
 * went final - not the league's learned p50 (see that constant's comment
 * for why). If that misses, check 1 fires a flat 30 minutes later - late
 * enough that a video posted just after the first look has a real chance of
 * being up, without turning into an open-ended retry schedule. After 2
 * checks, permanently stops regardless of outcome. Historical/backfill
 * games never reach this at all (gameStore's recent-only scope). A match
 * found on either attempt feeds gameStore's learned-lag tracking via its
 * own yt_published_at (the video's real upload time), not via when this
 * check happened to fire.
 */
function isDueForHighlightsCheck(row: GameRow, now: number): boolean {
  if (row.ytVideoId) return false;
  if (row.ytCheckCount >= MAX_HIGHLIGHTS_CHECKS) return false;
  if (row.ytLastCheckedAt && now - new Date(row.ytLastCheckedAt).getTime() < YT_MIN_CHECK_SPACING_MS) return false;

  if (row.ytCheckCount === 0) {
    const anchor = row.finalAt ?? row.tipoffUtc;
    const sinceFinalMs = now - new Date(anchor).getTime();
    return sinceFinalMs >= YT_FIRST_CHECK_DELAY_MS;
  }

  // Second attempt: row.ytLastCheckedAt is always set once ytCheckCount > 0
  // (markHighlightsChecked sets both together), so this is never null here.
  const sinceFirstCheckMs = now - new Date(row.ytLastCheckedAt!).getTime();
  return sinceFirstCheckMs >= YT_SECOND_CHECK_DELAY_MS;
}

/**
 * Searches for and records the result of one game's highlights check -
 * shared by the poller and the demand-driven trigger below so there's
 * exactly one place this happens. Guarded by the persisted daily search
 * budget (gameStore.canSpendSearchQuota) as a hard ceiling independent of
 * the per-game scheduling above - if the budget's spent, this deliberately
 * does NOT call markHighlightsChecked, so the game stays "due" and gets its
 * real attempt once the budget resets, rather than silently losing one of
 * its 2 allotted checks to a skip that was never really tried.
 */
async function checkGameHighlights(row: GameRow): Promise<void> {
  if (!canSpendSearchQuota()) {
    console.warn(`checkGameHighlights: daily search budget spent, deferring ${row.away} @ ${row.home}`);
    return;
  }

  const highlightsLeague: HighlightsLeague = row.league === "wnba" ? "wnba" : "nba";
  console.log(`checkGameHighlights: searching for ${row.away} @ ${row.home} (${row.eventId}, ${highlightsLeague})`);
  recordSearchQuotaSpend();
  const match = await searchHighlightsVideo(highlightsLeague, row.away, row.home, row.tipoffUtc);
  markHighlightsChecked(row.eventId, new Date().toISOString());
  if (match) {
    setHighlights(row.eventId, match.videoId, match.publishedAt);
    console.log(`checkGameHighlights: matched "${match.title}" (${match.videoId})`);
  } else {
    console.log(`checkGameHighlights: no match for ${row.away} @ ${row.home} (${row.eventId})`);
  }
}

// Defense in depth alongside gameStore's recent-games-only filter and the
// daily search budget: even a legitimate backlog (e.g. after downtime)
// shouldn't be able to spend a big chunk of the daily budget in one poller
// tick. Bounded per tick, not per day - a real backlog just spreads across
// a few extra ticks instead.
const MAX_CHECKS_PER_POLL = 20;

/**
 * Checks final games still missing a highlights match that could actually
 * be shown one (gameStore.getFinalGamesMissingHighlights already excludes
 * historical/backfill games entirely) and searches for whichever are due
 * per isDueForHighlightsCheck, up to MAX_CHECKS_PER_POLL. Called by
 * highlightsPoller.ts on a timer, as a backstop for games nobody's actively
 * looking at - getGamesForDate below also triggers this inline for
 * whatever a live request happens to touch, so someone actively viewing a
 * game doesn't have to wait for the next poller tick.
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
  let checksThisPoll = 0;
  for (const row of getFinalGamesMissingHighlights()) {
    if (checksThisPoll >= MAX_CHECKS_PER_POLL) break;
    if (isDueForHighlightsCheck(row, now)) {
      await checkGameHighlights(row);
      checksThisPoll++;
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
export async function getGamesForDate(date: string, leagueGroup: BasketballLeagueGroup = "nba"): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const leagueEvents = await fetchAllEvents(espnDate, leagueGroup);

  const results: GameJson[] = [];

  for (const { league, event } of leagueEvents) {
    const competition = event.competitions[0];
    const away = competition.competitors.find((c) => c.homeAway === "away")!;
    const home = competition.competitors.find((c) => c.homeAway === "home")!;
    // Summer League's bracket rounds get scheduled on ESPN before the
    // earlier group-stage games that decide the actual matchup finish -
    // those slots carry literal "TBD" team names until ESPN fills them in.
    // Skipped entirely (not even upserted into gameStore) rather than shown
    // as a placeholder tile, since upsertBaseEntry's insert-once behavior
    // would otherwise freeze "TBD" as this game's permanent team names, and
    // there's nothing real to show anyway - once ESPN resolves the real
    // matchup, this same event naturally starts coming through normally on
    // a later request for this date.
    if (away.team.displayName === "TBD" || home.team.displayName === "TBD") continue;
    const status = mapEspnState(competition.status.type.state);

    upsertBaseEntry({
      eventId: event.id,
      league,
      leagueGroup,
      away: away.team.displayName,
      home: home.team.displayName,
      awayLogo: preferDarkLogoVariant(away.team.logo),
      homeLogo: preferDarkLogoVariant(home.team.logo),
      tipoffUtc: event.date,
      status,
    });
    updateStatus(event.id, status);

    let row = getGame(event.id)!;
    // Summer League keeps its own separate static label client-side.
    const cl = league.startsWith("nba-summer") ? undefined : deriveCompetitionLabel(event, league === "wnba" ? "wnba" : "nba");
    // Persisted once (gameStore's IS NULL guard) so this same label is still
    // available once the game graduates into History, long after the raw
    // ESPN event/notes data used to derive it is gone.
    if (cl) setSeasonStageLabel(event.id, cl);

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
      const score = computeWatchabilityScore(mapped.rubric, row.stakes ?? undefined, league).total;
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
        standoutPerformers: mapped.rubric.standoutPerformers ?? [],
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
      sop: row.standoutPerformers,
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

/**
 * The next date, strictly after [afterDate], that [leagueGroup] actually has
 * a real (non-TBD) game - backs the Games tab's "jump to next game" action
 * for when a swipe lands on an empty day at a stage boundary (e.g. Summer
 * League ending) and the next real slate could be days or months away, in
 * a league within the group the viewer isn't even necessarily thinking
 * about (e.g. NBA's Summer League variants).
 *
 * Each underlying league's ESPN scoreboard calendar lists every date that
 * league's current season has a game, in one request (fetchCalendarDates) -
 * so this never scans day by day. If the calendar's earliest post-[afterDate]
 * date turns out to be a TBD-only slate (the same bracket-scheduling quirk
 * getGamesForDate filters out), it's skipped in favor of the next
 * candidate - a real check, not just trusting the calendar blindly. Returns
 * undefined if none of the group's leagues have a known future date at all
 * (e.g. a season boundary where the next season's schedule isn't announced
 * yet - a real, not hypothetical, case for NBA between Summer League ending
 * and the following season's schedule release).
 */
export async function getNextScheduledDate(afterDate: string, leagueGroup: BasketballLeagueGroup): Promise<string | undefined> {
  const anchorEspnDate = toEspnDate(new Date(`${afterDate}T12:00:00Z`));
  const calendars = await Promise.all(
    LEAGUE_GROUPS[leagueGroup].map((league) => fetchCalendarDates(anchorEspnDate, league))
  );
  const candidateDates = Array.from(new Set(calendars.flat()))
    .filter((d) => d > afterDate)
    .sort();

  for (const date of candidateDates) {
    const games = await getGamesForDate(date, leagueGroup);
    if (games.length > 0) return date;
  }
  return undefined;
}
