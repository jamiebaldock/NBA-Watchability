import {
  ContentLeague,
  EspnLeaderEntry,
  fetchAthleteName,
  fetchLeaders,
  fetchLeadersForSport,
  fetchTeamInfo,
} from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { resolveSeasonYear } from "./seasonYear";
import { preferDarkLogoVariant } from "./teamLogos";
import { LeagueGroup, SPORT_FOR_LEAGUE_GROUP, StatCategoryJson, StatLeaderJson, StatsResponseJson } from "./types";

interface ShownCategory {
  key: string;
  label: string;
  abbr: string;
  // Basketball's leaders' own displayValue is already a clean per-category
  // number (e.g. "32.1" for PPG) - safe to show as-is, no formatter needed.
  // MLB's is NOT (see EspnLeaderEntry's displayValue comment) - its shown
  // categories below each supply a formatter that builds a clean value from
  // the entry's raw numeric `value` instead.
  format?: (entry: EspnLeaderEntry) => string;
}

// The classic box-score categories - matches what other sports apps lead
// with, out of the ~16 categories ESPN exposes.
const SHOWN_CATEGORIES: ShownCategory[] = [
  { key: "pointsPerGame", label: "Points Per Game", abbr: "PPG" },
  { key: "reboundsPerGame", label: "Rebounds Per Game", abbr: "REB" },
  { key: "assistsPerGame", label: "Assists Per Game", abbr: "AST" },
  { key: "stealsPerGame", label: "Steals Per Game", abbr: "STL" },
  { key: "blocksPerGame", label: "Blocks Per Game", abbr: "BLK" },
];

// Baseball convention: batting average is shown without its leading zero
// (".335", never "0.335"); ERA keeps it ("1.62"). Falls back to the raw
// displayValue on the rare entry missing a numeric value rather than
// showing nothing.
function formatAvg(entry: EspnLeaderEntry): string {
  return entry.value !== undefined ? entry.value.toFixed(3).replace(/^0/, "") : entry.displayValue;
}
function formatEra(entry: EspnLeaderEntry): string {
  return entry.value !== undefined ? entry.value.toFixed(2) : entry.displayValue;
}
// Home runs/RBIs/strikeouts/wins are all whole-number counting stats.
function formatCount(entry: EspnLeaderEntry): string {
  return entry.value !== undefined ? String(Math.round(entry.value)) : entry.displayValue;
}

// MLB's classic leaderboard, split evenly across batting and pitching (the
// two fundamentally different stat families baseball has, unlike
// basketball's single all-around box score) - confirmed directly against a
// real leaders response's full category list (avg/homeRuns/RBIs/runs/OPS/
// onBasePct/slugAvg/ERA/wins/strikeouts/saves/WHIP/qualityStarts/
// opponentAvg/stolenBases/hits/holds/MLBRating/avgGameScore/WARBR - 19
// categories total), not guessed. Each supplies its own [format] - directly
// using displayValue here (as basketball does) put the player's entire
// season batting/pitching line next to their name instead of one number,
// confirmed directly against a real response before switching to [value].
const MLB_SHOWN_CATEGORIES: ShownCategory[] = [
  { key: "avg", label: "Batting Average", abbr: "AVG", format: formatAvg },
  { key: "homeRuns", label: "Home Runs", abbr: "HR", format: formatCount },
  { key: "RBIs", label: "Runs Batted In", abbr: "RBI", format: formatCount },
  { key: "ERA", label: "Earned Run Average", abbr: "ERA", format: formatEra },
  { key: "strikeouts", label: "Strikeouts", abbr: "K", format: formatCount },
  { key: "wins", label: "Wins", abbr: "W", format: formatCount },
];

const LEADERS_PER_CATEGORY = 10;

async function resolveLeader(
  entry: EspnLeaderEntry,
  athleteNameCache: Map<string, string>,
  teamInfoCache: Map<string, { abbreviation: string; logo?: string }>,
  formatValue: (entry: EspnLeaderEntry) => string = (e) => e.displayValue
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

  return { name, team: team.abbreviation, teamLogo: preferDarkLogoVariant(team.logo), value: formatValue(entry) };
}

/** Cached once per calendar day per league group - same reasoning as standingsService. */
export async function getStats(leagueGroup: LeagueGroup): Promise<StatsResponseJson> {
  const sport = SPORT_FOR_LEAGUE_GROUP[leagueGroup];
  // No Stats route built for nfl/nhl yet (no fetchLeadersForSport branch to
  // point at) - empty here rather than attempting a football/hockey leaders
  // fetch that hasn't been verified against real data, same "not wired yet"
  // contract every other not-yet-built route in this app already returns.
  if (sport !== "basketball" && sport !== "baseball") {
    return { season: "", categories: [] };
  }

  const now = new Date();
  const dateKey = todayKey(now);

  const cached = loadLeagueCache<StatsResponseJson>("stats", leagueGroup, dateKey);
  if (cached) return cached;

  const shownCategories = sport === "baseball" ? MLB_SHOWN_CATEGORIES : SHOWN_CATEGORIES;
  const resolved =
    sport === "baseball"
      ? await resolveSeasonYear("mlb", now, (year) => fetchLeadersForSport("baseball", "mlb", year))
      : await resolveSeasonYear(leagueGroup as ContentLeague, now, (year) => fetchLeaders(leagueGroup as ContentLeague, year));

  if (!resolved) {
    const empty: StatsResponseJson = { season: "", categories: [] };
    saveLeagueCache("stats", leagueGroup, dateKey, empty);
    return empty;
  }

  const athleteNameCache = new Map<string, string>();
  const teamInfoCache = new Map<string, { abbreviation: string; logo?: string }>();

  const categories: StatCategoryJson[] = [];
  for (const shown of shownCategories) {
    const category = resolved.data.categories.find((c) => c.name === shown.key);
    if (!category) continue;
    const leaders = await Promise.all(
      category.leaders
        .slice(0, LEADERS_PER_CATEGORY)
        .map((entry) => resolveLeader(entry, athleteNameCache, teamInfoCache, shown.format))
    );
    categories.push({ key: shown.key, label: shown.label, abbr: shown.abbr, leaders });
  }

  const response: StatsResponseJson = { season: String(resolved.year), categories };
  saveLeagueCache("stats", leagueGroup, dateKey, response);
  return response;
}
