// Backs the mobile app's game-detail popup (tap a finished tile) - the
// "top performers" and "context" (standings/head-to-head) sections. The
// rubric-breakdown tab needs none of this: every fact it shows was already
// exposed on GameJson back in Phase E, so the client recomputes that tab's
// per-category breakdown locally instead of a second trip here.
//
// Fetched fresh on every request (no caching) - this is a rare per-tap
// action, not something every tile needs computed up front, unlike the
// score/tier/highlights pipeline that runs for every game once, ever.
import { EspnBoxscoreTeamPlayers, fetchSummary, League } from "./espnClient";
import { getGame, getHeadToHead } from "./gameStore";
import { getStandings } from "./standingsService";
import { GameDetailResponseJson, LeagueGroup, TeamStandingsContextJson, TopPerformerJson } from "./types";

const BASKETBALL_STAT_CATEGORIES = ["PTS", "REB", "AST"] as const;

/** Top 3 combined (both teams) by points - "leaders," not a full box score. */
function basketballTopPerformers(playersBoxscore: EspnBoxscoreTeamPlayers[] | undefined): TopPerformerJson[] {
  if (!playersBoxscore) return [];
  const lines: Array<{ name: string; team: string; pts: number; line: string }> = [];

  for (const teamBlock of playersBoxscore) {
    for (const statBlock of teamBlock.statistics) {
      const indices = BASKETBALL_STAT_CATEGORIES.map((cat) => statBlock.labels.indexOf(cat));
      for (const athlete of statBlock.athletes) {
        const values = indices.map((i) => (i >= 0 ? parseFloat(athlete.stats[i]) || 0 : 0));
        const [pts, reb, ast] = values;
        lines.push({
          name: athlete.athlete.displayName,
          team: teamBlock.team.displayName,
          pts,
          line: `${pts} PTS, ${reb} REB, ${ast} AST`,
        });
      }
    }
  }

  return lines
    .sort((a, b) => b.pts - a.pts)
    .slice(0, 3)
    .map(({ name, team, line }) => ({ name, team, line }));
}

/** Best-effort rank/record lookup - matches Game's full team display name against standings' shortDisplayName by suffix (e.g. "Boston Celtics" ends with "Celtics"), since those are the two name shapes this app already has for the same team. */
async function standingsContextFor(leagueGroup: LeagueGroup, teamName: string): Promise<TeamStandingsContextJson> {
  const standings = await getStandings(leagueGroup);
  for (const group of standings.groups) {
    const index = group.teams.findIndex((t) => teamName.endsWith(t.n));
    if (index >= 0) {
      const team = group.teams[index];
      return { rank: index + 1, record: `${team.w}-${team.l}`, groupName: group.name };
    }
  }
  return {};
}

export async function getGameDetail(eventId: string): Promise<GameDetailResponseJson> {
  const row = getGame(eventId);
  if (!row || row.status !== "final") {
    return { topPerformers: [], headToHead: [], awayStandings: {}, homeStandings: {} };
  }

  const [topPerformers, awayStandings, homeStandings] = await Promise.all([
    row.leagueGroup === "mlb" || row.leagueGroup === "nfl"
      // No top-performers breakdown built for MLB/NFL yet (Games-tab-only
      // first pass) - fetchSummary's URL is basketball-namespaced and would
      // 404 for an MLB/NFL eventId, so this short-circuits to the same empty
      // result basketballTopPerformers already returns for a missing
      // boxscore.
      ? Promise.resolve([])
      : fetchSummary(eventId, row.league as League).then((s) => basketballTopPerformers(s.boxscore?.players)),
    standingsContextFor(row.leagueGroup, row.away),
    standingsContextFor(row.leagueGroup, row.home),
  ]);

  const headToHead = getHeadToHead(row.leagueGroup, row.away, row.home, eventId).map((g) => ({
    eventId: g.eventId,
    utc: g.tipoffUtc,
    away: g.away,
    home: g.home,
    awayScore: g.awayScore,
    homeScore: g.homeScore,
  }));

  return { topPerformers, headToHead, awayStandings, homeStandings };
}
