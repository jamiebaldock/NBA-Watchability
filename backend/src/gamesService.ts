import { CachedDay, loadDay, saveDay } from "./cache";
import { EspnEvent, League, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { deriveCompetitionLabel, mapEspnState, mapEventToGame } from "./gameMapper";
import { generateHookAndStakes } from "./llm";
import { computeWatchabilityScore } from "./rubric";
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
// variants are within the "nba" group. Cache entries are keyed by eventId
// (globally unique across ESPN sports), so both groups safely share the same
// per-date cache file with no risk of collision.
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
function fallbackHook(cached: Pick<CachedDay["games"][string], "away" | "home">): string {
  return `${cached.away} at ${cached.home}.`;
}

async function ensureBaseEntry(day: CachedDay, league: League, event: EspnEvent): Promise<void> {
  if (day.games[event.id]) return;

  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  day.games[event.id] = {
    eventId: event.id,
    away: away.team.displayName,
    home: home.team.displayName,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    tipoffUtc: event.date,
    league,
  };
}

/**
 * Generates the pregame preview (hook/stakes/pitch) exactly once per game -
 * not the first time the event is seen, but starting 24 hours before its own
 * tipoff, so games populate on a staggered schedule instead of every game at
 * a venue clustering around the same wall-clock hour. Before that gate, or if
 * generation already happened, this is a no-op.
 */
async function ensurePregamePreview(day: CachedDay, league: League, event: EspnEvent): Promise<void> {
  const cached = day.games[event.id];
  if (cached.hook !== undefined) return; // already generated
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

  cached.hook = hook;
  cached.stakes = stakes;
  cached.pitch = pitch;
}

// The official channel rarely has a game's highlights video up within
// minutes of it going final (observed same-day, but not immediately), so a
// miss is retried on this cadence rather than being permanent - not on
// every request, since search.list has its own hard 100-calls/day quota
// bucket (youtubeClient.ts) and this app's low traffic means "every
// request" and "every 30 minutes" are similar in practice anyway.
const YT_RETRY_INTERVAL_MS = 30 * 60 * 1000;
// Bounds quota spend on games that never get an official upload (rare, but
// not impossible for Summer League) - by 48h after tipoff, the video is
// either up or it isn't coming.
const YT_GIVE_UP_AFTER_MS = 48 * 60 * 60 * 1000;

/**
 * Looks up the official full-game-highlights video for a final game,
 * retrying a miss periodically (see YT_RETRY_INTERVAL_MS) until found or
 * until YT_GIVE_UP_AFTER_MS has elapsed since tipoff. NBA (and its Summer
 * League variants) search the @NBA channel; WNBA searches @WNBA.
 */
async function ensureHighlightsVideo(day: CachedDay, league: League, event: EspnEvent): Promise<void> {
  if (!isYoutubeSearchConfigured()) return; // no key yet - leave unchecked so it's retried once one's added

  const cached = day.games[event.id];
  if (cached.yt) return;

  const now = Date.now();
  if (cached.ytCheckedAt) {
    const sinceTipoffMs = now - new Date(cached.tipoffUtc).getTime();
    if (sinceTipoffMs > YT_GIVE_UP_AFTER_MS) return;

    const sinceLastCheckMs = now - new Date(cached.ytCheckedAt).getTime();
    if (sinceLastCheckMs < YT_RETRY_INTERVAL_MS) return;
  }

  const highlightsLeague: HighlightsLeague = league === "wnba" ? "wnba" : "nba";
  const match = await searchHighlightsVideo(highlightsLeague, cached.away, cached.home, cached.tipoffUtc);
  cached.ytCheckedAt = new Date(now).toISOString();
  if (match) cached.yt = match.videoId;
}

/**
 * Fetches, scores, and caches one day's games, matching the mobile client's
 * JSON contract (nba-watchability-spec.md section 5).
 *
 * Bandwidth note: the one expensive ESPN call is fetchSummary (full
 * play-by-play + box score) - it's only ever made once per game, the first
 * time that game is seen as "final", and cached forever after via
 * finalRubric. While a game is live, period/clock come for free from the
 * same lightweight scoreboard listing already being fetched, so live games
 * show LIVE + quarter + clock but no watchability score - the score only
 * appears once, all at once, when the game ends (spec change: previously it
 * appeared progressively from Q4 onward, which required re-fetching the full
 * play-by-play on every single poll for as long as the game stayed live).
 */
export async function getGamesForDate(date: string, leagueGroup: LeagueGroup = "nba"): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const leagueEvents = await fetchAllEvents(espnDate, leagueGroup);
  const day = loadDay(date);

  const results: GameJson[] = [];

  for (const { league, event } of leagueEvents) {
    await ensureBaseEntry(day, league, event);
    await ensurePregamePreview(day, league, event);
    const cached = day.games[event.id];
    // Older cache entries predate Summer League support and have no stored league.
    const eventLeague: League = cached.league ?? league;
    const lg: GameJson["lg"] = eventLeague === "nba" ? "nba" : eventLeague === "wnba" ? "wnba" : "summer";
    // Summer League keeps its own separate static label client-side (lg === "summer").
    const cl = lg === "summer" ? undefined : deriveCompetitionLabel(event, lg);
    const status = mapEspnState(event.competitions[0].status.type.state);
    const hook = cached.hook ?? fallbackHook(cached);
    const stakes = cached.stakes ?? 0;

    if (status === "upcoming") {
      results.push({
        a: cached.away,
        h: cached.home,
        al: cached.awayLogo,
        hl: cached.homeLogo,
        stt: "upcoming",
        utc: cached.tipoffUtc,
        lg,
        cl,
        ot: 0,
        c5: false,
        lcf: false,
        fp: false,
        bz: false,
        st: null,
        sk: cached.stakes,
        hook,
        pitch: cached.pitch,
        score_visible: false,
      });
      continue;
    }

    if (status === "live") {
      // Quarter/clock are on the scoreboard event itself - no play-by-play needed.
      const period = event.competitions[0].status.period;
      const clock = event.competitions[0].status.displayClock;
      results.push({
        a: cached.away,
        h: cached.home,
        al: cached.awayLogo,
        hl: cached.homeLogo,
        stt: "live",
        utc: cached.tipoffUtc,
        lg,
        cl,
        q: period,
        clk: clock,
        ot: 0,
        c5: false,
        lcf: false,
        fp: false,
        bz: false,
        st: null,
        sk: cached.stakes,
        hook,
        pitch: cached.pitch,
        score_visible: false,
      });
      continue;
    }

    // final: need play-by-play, unless we already locked in a result for it.
    let rubric = cached.finalRubric;

    if (!rubric) {
      const summary = await fetchSummary(event.id, eventLeague);
      const mapped = mapEventToGame(event, eventLeague, summary);
      const score = computeWatchabilityScore(mapped.rubric, stakes).total;
      cached.finalRubric = {
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
      };
      rubric = cached.finalRubric;
    }

    await ensureHighlightsVideo(day, eventLeague, event);

    results.push({
      a: cached.away,
      h: cached.home,
      al: cached.awayLogo,
      hl: cached.homeLogo,
      stt: "final",
      utc: cached.tipoffUtc,
      lg,
      cl,
      ot: rubric.overtimePeriods,
      m: rubric.finalMargin,
      cb: rubric.largestDeficitOvercome,
      lc: rubric.leadChanges,
      c5: rubric.closeInFinalTwoMin,
      lcf: rubric.leadChangeInFinalMin,
      fp: rubric.decidedOnFinalPossession,
      bz: rubric.buzzerBeater,
      st: rubric.starPerformance,
      sk: cached.stakes,
      hook,
      pitch: cached.pitch,
      score: rubric.score,
      score_visible: true,
      yt: cached.yt,
    });
  }

  saveDay(day);
  return results;
}
