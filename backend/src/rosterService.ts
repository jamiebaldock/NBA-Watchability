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
    const athletes = await fetchRosterForSport("baseball", "mlb", teamId);
    players = athletes.map((a) => ({ id: a.id, name: a.displayName, headshot: a.headshot?.href }));
  } else if (leagueGroup === "nfl") {
    // Same grouped-by-position shape as MLB's roster endpoint (confirmed
    // directly against a real response, not assumed from MLB's precedent) -
    // fetchRosterForSport's isRosterGroup handling already covers it with no
    // NFL-specific code needed. Real headshot photos too (87/90 on a real
    // team roster checked directly).
    const athletes = await fetchRosterForSport("football", "nfl", teamId);
    players = athletes.map((a) => ({ id: a.id, name: a.displayName, headshot: a.headshot?.href }));
  } else {
    // NHL rosters are still out of scope (no favorite-players route built
    // for it yet) - same "no real backend route yet" placeholder-league
    // behavior as teamsService.ts's getTeams.
    players = [];
  }

  const response: RosterResponseJson = { players };
  saveLeagueCache("roster", cacheKey, dateKey, response);
  return response;
}
