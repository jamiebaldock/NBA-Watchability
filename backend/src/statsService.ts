import {
  ContentLeague,
  EspnLeaderEntry,
  fetchAthleteName,
  fetchLeaders,
  fetchTeamInfo,
} from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { resolveSeasonYear } from "./seasonYear";
import { preferDarkLogoVariant } from "./teamLogos";
import { LeagueGroup, StatCategoryJson, StatLeaderJson, StatsResponseJson } from "./types";

// The classic box-score categories - matches what other sports apps lead
// with, out of the ~16 categories ESPN exposes.
const SHOWN_CATEGORIES: Array<{ key: string; label: string; abbr: string }> = [
  { key: "pointsPerGame", label: "Points Per Game", abbr: "PPG" },
  { key: "reboundsPerGame", label: "Rebounds Per Game", abbr: "REB" },
  { key: "assistsPerGame", label: "Assists Per Game", abbr: "AST" },
  { key: "stealsPerGame", label: "Steals Per Game", abbr: "STL" },
  { key: "blocksPerGame", label: "Blocks Per Game", abbr: "BLK" },
];
const LEADERS_PER_CATEGORY = 10;

async function resolveLeader(
  entry: EspnLeaderEntry,
  athleteNameCache: Map<string, string>,
  teamInfoCache: Map<string, { abbreviation: string; logo?: string }>
): Promise<StatLeaderJson> {
  let name = athleteNameCache.get(entry.athlete.$ref);
  if (!name) {
    name = await fetchAthleteName(entry.athlete.$ref);
    athleteNameCache.set(entry.athlete.$ref, name);
  }

  let team = teamInfoCache.get(entry.team.$ref);
  if (!team) {
    team = await fetchTeamInfo(entry.team.$ref);
    teamInfoCache.set(entry.team.$ref, team);
  }

  return { name, team: team.abbreviation, teamLogo: preferDarkLogoVariant(team.logo), value: entry.displayValue };
}

/** Cached once per calendar day per league group - same reasoning as standingsService. */
export async function getStats(leagueGroup: LeagueGroup): Promise<StatsResponseJson> {
  const league = leagueGroup as ContentLeague;
  const now = new Date();
  const dateKey = todayKey(now);

  const cached = loadLeagueCache<StatsResponseJson>("stats", leagueGroup, dateKey);
  if (cached) return cached;

  const resolved = await resolveSeasonYear(league, now, (year) => fetchLeaders(league, year));
  if (!resolved) {
    const empty: StatsResponseJson = { season: "", categories: [] };
    saveLeagueCache("stats", leagueGroup, dateKey, empty);
    return empty;
  }

  const athleteNameCache = new Map<string, string>();
  const teamInfoCache = new Map<string, { abbreviation: string; logo?: string }>();

  const categories: StatCategoryJson[] = [];
  for (const shown of SHOWN_CATEGORIES) {
    const category = resolved.data.categories.find((c) => c.name === shown.key);
    if (!category) continue;
    const leaders = await Promise.all(
      category.leaders
        .slice(0, LEADERS_PER_CATEGORY)
        .map((entry) => resolveLeader(entry, athleteNameCache, teamInfoCache))
    );
    categories.push({ key: shown.key, label: shown.label, abbr: shown.abbr, leaders });
  }

  const response: StatsResponseJson = { season: String(resolved.year), categories };
  saveLeagueCache("stats", leagueGroup, dateKey, response);
  return response;
}
