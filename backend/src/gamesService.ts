import { CachedDay, loadDay, saveDay } from "./cache";
import { ALL_LEAGUES, EspnEvent, League, fetchScoreboard, fetchSummary, toEspnDate } from "./espnClient";
import { mapEspnState, mapEventToGame } from "./gameMapper";
import { generateHookAndStakes } from "./llm";
import { computeWatchabilityScore, isScoreVisible } from "./rubric";
import { GameJson } from "./types";

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

async function ensureMatchupContext(day: CachedDay, league: League, event: EspnEvent): Promise<void> {
  if (day.games[event.id]) return;

  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const { hook, stakes, pitch } = await generateHookAndStakes({
    away: away.team.displayName,
    home: home.team.displayName,
    awayRecord: overallRecord(away),
    homeRecord: overallRecord(home),
    notes:
      league !== "nba"
        ? "NBA Summer League exhibition game — rookies/prospects, not regular-season standings, no playoff implications"
        : undefined,
  });

  day.games[event.id] = {
    eventId: event.id,
    away: away.team.displayName,
    home: home.team.displayName,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    tipoffUtc: event.date,
    league,
    stakes,
    hook,
    pitch,
  };
}

/**
 * Fetches, scores, and caches one day's games, matching the mobile client's
 * JSON contract (nba-watchability-spec.md section 5). Cheap to call
 * repeatedly: the sports-data fetch always happens (needed for live
 * period/clock), but each game's LLM call happens at most once ever (the
 * hook+stakes are a fixed matchup synopsis, generated once and never
 * regenerated based on the result) and is skipped entirely once cached.
 */
export async function getGamesForDate(date: string): Promise<GameJson[]> {
  const espnDate = toEspnDate(new Date(`${date}T12:00:00Z`));
  const leagueEvents = await fetchAllEvents(espnDate);
  const day = loadDay(date);

  const results: GameJson[] = [];

  for (const { league, event } of leagueEvents) {
    await ensureMatchupContext(day, league, event);
    const cached = day.games[event.id];
    // Older cache entries predate Summer League support and have no stored league.
    const eventLeague: League = cached.league ?? league;
    const lg: GameJson["lg"] = eventLeague === "nba" ? "nba" : "summer";
    const status = mapEspnState(event.competitions[0].status.type.state);

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
        hook: cached.hook,
        pitch: cached.pitch,
        score_visible: false,
      });
      continue;
    }

    // live or final: need play-by-play, unless we already locked in a final result.
    let rubric = cached.finalRubric;

    if (!rubric) {
      const summary = await fetchSummary(event.id, eventLeague);
      const mapped = mapEventToGame(event, summary);
      const period = mapped.rubric.period;
      const clock = mapped.rubric.clock;
      const scoreVisible = isScoreVisible(status, period);

      if (status === "final") {
        const score = computeWatchabilityScore(mapped.rubric, cached.stakes).total;
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
      } else {
        // live, still in-progress: build a GameJson straight from this poll's rubric.
        results.push(buildLiveGameJson(cached, lg, status, period, clock, mapped.rubric, cached.stakes, scoreVisible));
        continue;
      }
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
      hook: cached.hook,
      pitch: cached.pitch,
      score: rubric.score,
      score_visible: true,
    });
  }

  saveDay(day);
  return results;
}

function buildLiveGameJson(
  cached: CachedDay["games"][string],
  lg: GameJson["lg"],
  status: "live",
  period: number | undefined,
  clock: string | undefined,
  rubric: ReturnType<typeof mapEventToGame>["rubric"],
  stakes: number,
  scoreVisible: boolean
): GameJson {
  const base: GameJson = {
    a: cached.away,
    h: cached.home,
    al: cached.awayLogo,
    hl: cached.homeLogo,
    stt: status,
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
    sk: stakes,
    hook: cached.hook,
    pitch: cached.pitch,
    score_visible: false,
  };

  if (!scoreVisible) return base;

  const score = computeWatchabilityScore(rubric, stakes).total;
  return {
    ...base,
    ot: rubric.overtimePeriods,
    m: rubric.finalMargin,
    cb: rubric.largestDeficitOvercome,
    lc: rubric.leadChanges,
    c5: rubric.closeInFinalTwoMin,
    lcf: rubric.leadChangeInFinalMin,
    fp: rubric.decidedOnFinalPossession,
    bz: rubric.buzzerBeater,
    st: rubric.starPerformance,
    score,
    score_visible: true,
  };
}
