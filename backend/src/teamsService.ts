// Real per-league team rosters, backing the mobile app's favorite-teams
// search/browse screen. Unlike every other data source in this app, no
// existing pipeline hands us "every team in a league" - game data only ever
// carries whichever two teams happened to play, so this hits ESPN's own
// teams-list endpoint directly (espnClient.ts's fetchTeams), cached once
// per day per leagueGroup like standings/stats.
import { fetchTeams, fetchTeamsForSport } from "./espnClient";
import { preferDarkLogoVariant, teamLogoUrl } from "./teamLogos";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, TeamJson, TeamsResponseJson } from "./types";

async function getBasketballTeams(leagueGroup: "nba" | "wnba"): Promise<TeamJson[]> {
  const teams = await fetchTeams(leagueGroup);
  return teams
    .map((t): TeamJson => ({
      id: t.id,
      name: t.displayName,
      // teamLogoUrl's static per-team maps are what every other tab's tile
      // already uses (e.g. the "scoreboard" NBA logo variant) - reused here
      // rather than ESPN's own default logo field, so a favorited team's
      // logo looks identical everywhere it appears in the app.
      logo: preferDarkLogoVariant(teamLogoUrl(t.displayName, leagueGroup))
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

// sportSlug/league pairs for the non-basketball leagues that have real
// team-list data - ESPN's own site-API sport namespace + league slug, not
// this app's LeagueGroup naming (which happen to match here, but aren't
// guaranteed to in general - see espnClient.ts's fetchTeamsForSport).
const SPORT_SLUG_FOR_LEAGUE_GROUP: Record<"mlb" | "nfl" | "nhl", string> = {
  mlb: "baseball",
  nfl: "football",
  nhl: "hockey"
};

async function getSportTeams(leagueGroup: "mlb" | "nfl" | "nhl"): Promise<TeamJson[]> {
  const teams = await fetchTeamsForSport(SPORT_SLUG_FOR_LEAGUE_GROUP[leagueGroup], leagueGroup);
  return teams
    .map((t): TeamJson => ({
      id: t.id,
      name: t.displayName,
      // No static per-team logo map exists for these sports yet (teamLogos.ts
      // is basketball-only) - ESPN's own logo field is the only source here.
      logo: t.logo
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export async function getTeams(leagueGroup: LeagueGroup): Promise<TeamsResponseJson> {
  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<TeamsResponseJson>("teams", leagueGroup, dateKey);
  if (cached) return cached;

  let teams: TeamJson[];
  if (leagueGroup === "nba" || leagueGroup === "wnba") {
    teams = await getBasketballTeams(leagueGroup);
  } else if (leagueGroup === "mlb" || leagueGroup === "nfl" || leagueGroup === "nhl") {
    teams = await getSportTeams(leagueGroup);
  } else {
    // Every other LeagueGroup (NBL, UFC, and the rest of the placeholder
    // batch) has no favorite-teams route built for it yet - an empty list
    // here is the same "nothing to show, not an error" behavior every other
    // tab gives these leagues.
    teams = [];
  }

  const response: TeamsResponseJson = { teams };
  saveLeagueCache("teams", leagueGroup, dateKey, response);
  return response;
}
