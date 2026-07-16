// Serves the "which old games are actually worth going back to watch" query
// for the mobile app's History tab, straight from gameStore - the same
// durable table every live game graduates into once it's "complete"
// (rated + highlights found). There's no separate historical dataset
// anymore; a game finished a year ago and a game that finished five
// minutes ago are the same kind of row.
import { earliestGameDate, getMostRecentFinalsEnd, getSeasonLabels, getWatchableHistory, seasonLabelForTipoff } from "./gameStore";
import { isSoccerLeagueGroup, LEAGUE_DISPLAY_NAME } from "./soccerGamesService";
import { teamLogoUrl } from "./teamLogos";
import { GameJson, LeagueGroup } from "./types";

export interface HistoryResult {
  earliestDate: string;
  seasons: string[];
  games: GameJson[];
}

export async function getHistory(start: string, end: string, leagueGroup: LeagueGroup): Promise<HistoryResult> {
  const rows = getWatchableHistory(start, end, leagueGroup);
  const finalsEnd = getMostRecentFinalsEnd(leagueGroup);

  const games: GameJson[] = rows.map((row) => ({
    a: row.away,
    h: row.home,
    // Historical rows migrated from the pre-gameStore backfill never had
    // logos (that backfill only kept team display names) - fall back to
    // the static name->abbreviation map for those; live-collected rows
    // (basketball or soccer) always have the real ESPN logo URL already.
    al: row.awayLogo ?? teamLogoUrl(row.away, leagueGroup),
    hl: row.homeLogo ?? teamLogoUrl(row.home, leagueGroup),
    stt: "final",
    utc: row.tipoffUtc,
    // A soccer leagueGroup's own `league` column holds "eng.1"/"esp.1", not
    // "nba"/"wnba"/a summer-league name - checking sport first avoids
    // silently mislabeling every EPL/La Liga History row as "summer".
    lg: isSoccerLeagueGroup(leagueGroup) ? "soccer" : row.league === "nba" ? "nba" : row.league === "wnba" ? "wnba" : "summer",
    // row.seasonStageLabel ("NBA - Playoffs: Conference Semifinals",
    // "EPL - Regular Season") is captured once per game (gamesService.ts /
    // soccerGamesService.ts) from ESPN's own season/notes data and
    // persisted in gameStore, so it survives long after that raw ESPN data
    // is gone. Only missing for games seen before that column existed -
    // migrateStageLabels.ts backfills the basketball ones; the plain
    // season-year label is a last-resort fallback for anything that script
    // still couldn't resolve. Summer League rows keep their own separate
    // client-side static label (GameCard checks isSummerLeague first) and
    // ignore this field either way. Soccer's fallback uses its own display
    // name (LEAGUE_DISPLAY_NAME) rather than `leagueGroup.toUpperCase()`,
    // since that would read "LA-LIGA" instead of "La Liga".
    cl:
      row.seasonStageLabel ??
      (isSoccerLeagueGroup(leagueGroup)
        ? `${LEAGUE_DISPLAY_NAME[leagueGroup]} - ${seasonLabelForTipoff(row.tipoffUtc, leagueGroup, finalsEnd)}`
        : `${leagueGroup.toUpperCase()} - ${seasonLabelForTipoff(row.tipoffUtc, leagueGroup, finalsEnd)}`),
    m: row.finalMargin ?? undefined,
    as: row.awayScore ?? undefined,
    hs: row.homeScore ?? undefined,
    cb: row.largestDeficitOvercome ?? undefined,
    lc: row.leadChanges ?? undefined,
    ot: row.overtimePeriods ?? 0,
    c5: Boolean(row.closeInFinalTwoMin),
    lcf: Boolean(row.leadChangeInFinalMin),
    fp: Boolean(row.decidedOnFinalPossession),
    bz: Boolean(row.buzzerBeater),
    st: row.starPerformance,
    hook: `${row.away} at ${row.home}.`,
    score: row.score ?? undefined,
    score_visible: true,
    yt: row.ytVideoId ?? undefined,
  }));

  return { earliestDate: earliestGameDate(leagueGroup) ?? start, seasons: getSeasonLabels(leagueGroup), games };
}
