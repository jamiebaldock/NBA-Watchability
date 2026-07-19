// Real per-team player rosters, backing the mobile app's favorite-players
// search/browse screen (league -> team -> player drill-down). Same reasoning
// as teamsService.ts: no existing pipeline hands us "every player on a team,"
// only whichever players happened to log a stat line in a specific game.
import { fetchRoster, fetchRosterForSport, League } from "./espnClient";
import { loadLeagueCache, saveLeagueCache, todayKey } from "./leagueCache";
import { LeagueGroup, RosterResponseJson } from "./types";

const BASKETBALL_LEAGUE_FOR_GROUP: Record<"nba" | "wnba", League> = {
  nba: "nba",
  wnba: "wnba"
};

export async function getRoster(leagueGroup: LeagueGroup, teamId: string): Promise<RosterResponseJson> {
  const dateKey = todayKey(new Date());
  const cacheKey = `${leagueGroup}-${teamId}`;
  const cached = loadLeagueCache<RosterResponseJson>("roster", cacheKey, dateKey);
  if (cached) return cached;

  let players: RosterResponseJson["players"];
  if (leagueGroup === "nba" || leagueGroup === "wnba") {
    const league = BASKETBALL_LEAGUE_FOR_GROUP[leagueGroup];
    const athletes = await fetchRoster(teamId, league);
    players = athletes.map((a) => ({ id: a.id, name: a.displayName, headshot: a.headshot?.href }));
  } else if (leagueGroup === "mlb") {
    // MLB only - NFL/NHL rosters are explicitly out of scope for this pass
    // (no favorite-players route built for them yet), same "empty list"
    // placeholder behavior as before.
    const athletes = await fetchRosterForSport("baseball", "mlb", teamId);
    players = athletes.map((a) => ({ id: a.id, name: a.displayName, headshot: a.headshot?.href }));
  } else {
    // Same "no real backend route yet" placeholder-league behavior as
    // teamsService.ts's getTeams.
    players = [];
  }

  const response: RosterResponseJson = { players };
  saveLeagueCache("roster", cacheKey, dateKey, response);
  return response;
}
