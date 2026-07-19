import { ContentLeague, EspnStandingsEntry, EspnStandingsGroup, fetchStandings, fetchStandingsForSport } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { resolveSeasonYear } from "./seasonYear";
import { preferDarkLogoVariant } from "./teamLogos";
import { LeagueGroup, SPORT_FOR_LEAGUE_GROUP, StandingsGroupJson, StandingsResponseJson, StandingsTeamJson } from "./types";

function statValueByName(entry: EspnStandingsEntry, name: string): string {
  return entry.stats.find((s) => s.name === name)?.displayValue ?? "-";
}

function numericStatValueByName(entry: EspnStandingsEntry, name: string): number {
  return entry.stats.find((s) => s.name === name)?.value ?? -Infinity;
}

/**
 * ESPN's own entries array is NOT returned in rank order (verified against
 * the live endpoint - e.g. the #1 team can be last in the raw array), and
 * the "playoffSeed" stat some entries carry is a BPI power-index *projected*
 * seed, not the actual current standings rank, so neither can be trusted
 * directly. Sorting by the full-precision winPercent value (not the rounded
 * 3-decimal displayValue, which isn't precise enough to break ties) via a
 * stable sort reproduces the real standings order exactly, including every
 * observed win%-tied pair (e.g. POR/LAC both .512) - ESPN's raw array
 * already preserves the correct relative order for those, even though the
 * array as a whole isn't sorted.
 */
function sortByWinPercentDescending(entries: EspnStandingsEntry[]): EspnStandingsEntry[] {
  return [...entries].sort(
    (a, b) => numericStatValueByName(b, "winPercent") - numericStatValueByName(a, "winPercent")
  );
}

function flattenGroups(groups: EspnStandingsGroup[]): StandingsGroupJson[] {
  const result: StandingsGroupJson[] = [];
  for (const group of groups) {
    if (group.standings?.entries?.length) {
      result.push({
        name: group.name,
        teams: sortByWinPercentDescending(group.standings.entries).map(
          (entry): StandingsTeamJson => ({
            id: entry.team.id,
            n: entry.team.shortDisplayName,
            ab: entry.team.abbreviation,
            lg: preferDarkLogoVariant(entry.team.logos?.[0]?.href),
            w: Number(statValueByName(entry, "wins")) || 0,
            l: Number(statValueByName(entry, "losses")) || 0,
            pct: statValueByName(entry, "winPercent"),
            gb: statValueByName(entry, "gamesBehind"),
            strk: statValueByName(entry, "streak"),
          })
        ),
      });
    }
    if (group.children?.length) {
      result.push(...flattenGroups(group.children));
    }
  }
  return result;
}

/** Cached once per calendar day per league group - standings don't change fast enough to justify a fresh ESPN fetch on every app open. */
export async function getStandings(leagueGroup: LeagueGroup): Promise<StandingsResponseJson> {
  // Only basketball (native fetchStandings, ContentLeague-typed) and MLB
  // (fetchStandingsForSport, its own baseball/mlb branch below) have a
  // Standings route built - fetchStandings' URL is basketball-namespaced and
  // 404s/throws for any other sport's slug (unlike ESPN's teams/roster
  // endpoints, which tolerate an unrecognized league and just come back
  // empty), so nfl/nhl still have to short-circuit before ever building that
  // request, same reasoning as newsService.ts's own early return.
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];
  if (sport !== "basketball" && sport !== "baseball") {
    return { season: "", groups: [] };
  }

  const now = new Date();
  const dateKey = todayKey(now);

  // Cache kind bumped to "standings2" to bypass any pre-existing cached
  // response from before entries were sorted by win% - those files have the
  // correct team data but the wrong (unsorted) order baked in, and would
  // otherwise keep being served as-is until the dateKey rolls over.
  const cached = loadLeagueCache<StandingsResponseJson>("standings2", leagueGroup, dateKey);
  if (cached) return cached;

  // MLB's season is a single calendar year (guessSeasonYear's "mlb" branch,
  // same as WNBA's) rather than basketball's "ends in" convention, so it
  // gets its own fetchFn here instead of reusing the ContentLeague-typed
  // fetchStandings - resolveSeasonYear itself is still shared (its league
  // param already accepts "mlb" alongside ContentLeague, see
  // seasonYear.ts), since the guess-then-fallback-a-year logic is identical
  // either way.
  const resolved =
    sport === "baseball"
      ? await resolveSeasonYear("mlb", now, (year) => fetchStandingsForSport("baseball", "mlb", year))
      : await resolveSeasonYear(leagueGroup as ContentLeague, now, (year) => fetchStandings(leagueGroup as ContentLeague, year));

  const response: StandingsResponseJson = resolved
    ? {
        // MLB's level=3 groups only carry seasonDisplayName at the
        // division-level (children[0].children[0]), not the AL/NL-level
        // group flattenGroups' top of the tree - resolved.year (the season
        // fetchStandingsForSport actually resolved against) is a reliable
        // fallback either way.
        season: resolved.data.children[0]?.standings?.seasonDisplayName ?? String(resolved.year),
        groups: flattenGroups(resolved.data.children),
      }
    : { season: "", groups: [] };

  saveLeagueCache("standings2", leagueGroup, dateKey, response);
  return response;
}
