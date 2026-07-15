// Serves the "which old games are actually worth going back to watch" query
// for the mobile app's History tab, straight from gameStore - the same
// durable table every live game graduates into once it's "complete"
// (rated + highlights found). There's no separate historical dataset
// anymore; a game finished a year ago and a game that finished five
// minutes ago are the same kind of row.
import { earliestGameDate, getSeasonLabels, getWatchableHistory, seasonLabelForTipoff } from "./gameStore";
import { teamLogoUrl } from "./teamLogos";
import { GameJson, LeagueGroup } from "./types";

export interface HistoryResult {
  earliestDate: string;
  seasons: string[];
  games: GameJson[];
}

export async function getHistory(start: string, end: string, leagueGroup: LeagueGroup): Promise<HistoryResult> {
  const rows = getWatchableHistory(start, end, leagueGroup);

  const games: GameJson[] = rows.map((row) => ({
    a: row.away,
    h: row.home,
    // Historical rows migrated from the pre-gameStore backfill never had
    // logos (that backfill only kept team display names) - fall back to
    // the static name->abbreviation map for those; live-collected rows
    // always have the real ESPN logo URL already.
    al: row.awayLogo ?? teamLogoUrl(row.away, leagueGroup),
    hl: row.homeLogo ?? teamLogoUrl(row.home, leagueGroup),
    stt: "final",
    utc: row.tipoffUtc,
    lg: row.league === "nba" ? "nba" : row.league === "wnba" ? "wnba" : "summer",
    // Historical rows have no stored ESPN season/tournament data to derive
    // deriveCompetitionLabel()'s finer REGULAR SEASON/CUP/PLAYOFFS label
    // (that's only ever computed for live games, from the live ESPN event) -
    // the season-year label already used for the History season chips is
    // the best available substitute. Summer League rows keep their own
    // separate client-side static label (GameCard checks isSummerLeague
    // first) and ignore this field.
    cl: `${leagueGroup.toUpperCase()} · ${seasonLabelForTipoff(row.tipoffUtc, leagueGroup)}`,
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
