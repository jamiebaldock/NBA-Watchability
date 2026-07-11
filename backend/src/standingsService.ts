import { ContentLeague, EspnStandingsEntry, EspnStandingsGroup, fetchStandings } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { resolveSeasonYear } from "./seasonYear";
import { LeagueGroup, StandingsGroupJson, StandingsResponseJson, StandingsTeamJson } from "./types";

function statValueByName(entry: EspnStandingsEntry, name: string): string {
  return entry.stats.find((s) => s.name === name)?.displayValue ?? "-";
}

function flattenGroups(groups: EspnStandingsGroup[]): StandingsGroupJson[] {
  const result: StandingsGroupJson[] = [];
  for (const group of groups) {
    if (group.standings?.entries?.length) {
      result.push({
        name: group.name,
        teams: group.standings.entries.map(
          (entry): StandingsTeamJson => ({
            id: entry.team.id,
            n: entry.team.shortDisplayName,
            ab: entry.team.abbreviation,
            lg: entry.team.logos?.[0]?.href,
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
  const league = leagueGroup as ContentLeague;
  const now = new Date();
  const dateKey = todayKey(now);

  const cached = loadLeagueCache<StandingsResponseJson>("standings", leagueGroup, dateKey);
  if (cached) return cached;

  const resolved = await resolveSeasonYear(league, now, (year) => fetchStandings(league, year));
  const response: StandingsResponseJson = resolved
    ? {
        season: resolved.data.children[0]?.standings?.seasonDisplayName ?? String(resolved.year),
        groups: flattenGroups(resolved.data.children),
      }
    : { season: "", groups: [] };

  saveLeagueCache("standings", leagueGroup, dateKey, response);
  return response;
}
