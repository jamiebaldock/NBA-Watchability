import { CachedDay, loadDay, saveDay } from "./cache";
import { ALL_LEAGUES, EspnEvent, League, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { mapEspnState, mapEventToGame } from "./gameMapper";
import { fetchTeamInjuries, injuryNotesToText } from "./injuries";
import { generateHookAndStakes } from "./llm";
import { computeWatchabilityScore } from "./rubric";
import { GameJson } from "./types";
import { hasLocalFiveAmPassed, venueTimeZone } from "./venueTimezone";

function overallRecord(competitor: EspnEvent["competitions"][number]["competitors"][number]): string | undefined {
  return competitor.records?.find((r) => r.type === "total")?.summary;
}

interface LeagueEvent {
  league: League;
  event: EspnEvent;
}

/** Each league is its own ESPN "sport", so a day's full slate is the union of separate per-league fetches. */
async function fetchAllEvents(espnDate: string): Promise<LeagueEvent[]> {
  const perLeague = await Promise.all(
    ALL_LEAGUES.map(async (league) => {
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
 * not the first time the event is seen, but at/after 5am local time at the
 * venue, so it can reflect same-day news (starter ruled out, etc.) instead of
 * being locked in as soon as ESPN publishes the schedule. Before that gate,
 * or if generation already happened, this is a no-op.
 */
async function ensurePregamePreview(day: CachedDay, league: League, event: EspnEvent): Promise<void> {
  const cached = day.games[event.id];
  if (cached.hook !== undefined) return; // already generated

  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const zone = venueTimeZone(event, home.team.displayName);
  if (!hasLocalFiveAmPassed(event.date, zone)) return; // not time yet

  const [awayInjuries, homeInjuries] = await Promise.all([
    fetchTeamInjuries(away.team.id),
    fetchTeamInjuries(home.team.id),
  ]);
  const injuryContext = [
    injuryNotesToText(away.team.displayName, awayInjuries),
    injuryNotesToText(home.team.displayName, homeInjuries),
  ]
    .filter((n): n is string => n !== null)
    .join(" | ");

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: away.team.displayName,
    home: home.team.displayName,
    awayRecord: overallRecord(away),
    homeRecord: overallRecord(home),
    notes:
      league !== "nba"
        ? "NBA Summer League exhibition game — rookies/prospects, not regular-season standings, no playoff implications"
        : undefined,
    injuryContext: injuryContext || undefined,
  });

  cached.hook = hook;
  cached.stakes = stakes;
  cached.pitch = pitch;
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
export async function getGamesForDate(date: string): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const leagueEvents = await fetchAllEvents(espnDate);
  const day = loadDay(date);

  const results: GameJson[] = [];

  for (const { league, event } of leagueEvents) {
    await ensureBaseEntry(day, league, event);
    await ensurePregamePreview(day, league, event);
    const cached = day.games[event.id];
    // Older cache entries predate Summer League support and have no stored league.
    const eventLeague: League = cached.league ?? league;
    const lg: GameJson["lg"] = eventLeague === "nba" ? "nba" : "summer";
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
      const mapped = mapEventToGame(event, summary);
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

    results.push({
      a: cached.away,
      h: cached.home,
      al: cached.awayLogo,
      hl: cached.homeLogo,
      stt: "final",
      utc: cached.tipoffUtc,
      lg,
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
    });
  }

  saveDay(day);
  return results;
}
