// Real per-league team rosters, backing the mobile app's favorite-teams
// search/browse screen. Unlike every other data source in this app, no
// existing pipeline hands us "every team in a league" - game data only ever
// carries whichever two teams happened to play, so this hits ESPN's own
// teams-list endpoint directly (espnClient.ts's fetchTeams / soccerEspnClient.ts's
// fetchSoccerTeams), cached once per day per leagueGroup like standings/stats.
import { fetchTeams } from "./espnClient";
import { fetchSoccerTeams } from "./soccerEspnClient";
import { isSoccerLeagueGroup, SOCCER_LEAGUE_FOR_GROUP } from "./soccerGamesService";
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

async function getSoccerTeams(leagueGroup: "epl" | "la-liga"): Promise<TeamJson[]> {
  const league = SOCCER_LEAGUE_FOR_GROUP[leagueGroup];
  const teams = await fetchSoccerTeams(league);
  return teams
    .map((t): TeamJson => ({ id: t.id, name: t.displayName, logo: t.logo }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export async function getTeams(leagueGroup: LeagueGroup): Promise<TeamsResponseJson> {
  const dateKey = todayKey(new Date());
  const cached = loadLeagueCache<TeamsResponseJson>("teams", leagueGroup, dateKey);
  if (cached) return cached;

  let teams: TeamJson[];
  if (isSoccerLeagueGroup(leagueGroup)) {
    teams = await getSoccerTeams(leagueGroup);
  } else if (leagueGroup === "nba" || leagueGroup === "wnba") {
    teams = await getBasketballTeams(leagueGroup);
  } else {
    // Every other LeagueGroup (NBL, UFC, and the rest of the placeholder
    // batch) has no real backend route at all yet - an empty list here is
    // the same "nothing to show, not an error" behavior every other tab
    // gives these leagues.
    teams = [];
  }

  const response: TeamsResponseJson = { teams };
  saveLeagueCache("teams", leagueGroup, dateKey, response);
  return response;
}
