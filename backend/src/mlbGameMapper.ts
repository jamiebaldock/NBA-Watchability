// Extracts MlbRubricInputs (mlbRubric.ts) from ESPN's raw MLB event +
// summary data - the MLB analogue of gameMapper.ts/soccerGameMapper.ts. The
// deficit/walk-off derivation below is a direct port of the same logic
// already validated in mlbRubric's calibration pass (backfillRawStatsMlb.ts,
// run against the full 2,478-game 2025 season) - safe to trust here rather
// than a fresh guess.
import {
  EspnMlbEvent,
  EspnMlbHeaderCompetitor,
  EspnMlbPlayerBoxscore,
  EspnMlbSummary,
  EspnMlbTeamBoxscore
} from "./mlbEspnClient";
import { MlbRubricInputs } from "./mlbRubric";
import { GameStatus, StandoutPerformerJson } from "./types";

export function mapMlbEspnState(state: "pre" | "in" | "post"): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

function statValue(team: EspnMlbTeamBoxscore, groupName: string, statName: string): number {
  const group = team.statistics.find((s) => s.name === groupName);
  const stat = group?.stats.find((s) => s.name === statName);
  return stat?.value ?? 0;
}

/** Most home runs hit by a single player - own team boxscore.players' batting block, keyed by name like backfillRawStatsMlb.ts's maxHomeRunsByPlayer. */
function homeRunsByPlayer(players: EspnMlbPlayerBoxscore[]): Record<string, { count: number; team: string }> {
  const counts: Record<string, { count: number; team: string }> = {};
  for (const team of players) {
    const battingBlock = team.statistics.find((s) => s.keys?.includes("homeRuns"));
    if (!battingBlock) continue;
    const hrIndex = battingBlock.keys.indexOf("homeRuns");
    for (const athlete of battingBlock.athletes) {
      const hr = parseInt(athlete.stats[hrIndex] ?? "0", 10) || 0;
      if (hr > 0) counts[athlete.athlete.displayName] = { count: hr, team: team.team.id };
    }
  }
  return counts;
}

/** Every hitter with 2+ home runs - MLB's analogue of soccerGameMapper.ts's findStandoutScorers, using the same "star" threshold mlbRubric.ts's starHomeRunPoints already scores a multi-homer game against. */
function findStandoutHitters(players: EspnMlbPlayerBoxscore[], teamNameById: Record<string, string>): StandoutPerformerJson[] {
  const counts = homeRunsByPlayer(players);
  return Object.entries(counts)
    .filter(([, v]) => v.count >= 2)
    .map(([name, v]) => ({
      name,
      line: `${v.count} HR`,
      team: teamNameById[v.team]
    }));
}

/** Largest deficit the eventual winner ever faced, reconstructed from both teams' per-inning linescores. Direct port of backfillRawStatsMlb.ts's validated logic. */
function largestDeficitOvercome(awayInnings: number[], homeInnings: number[], awayWon: boolean): number {
  const maxLen = Math.max(awayInnings.length, homeInnings.length);
  let awayCum = 0;
  let homeCum = 0;
  let maxDeficit = 0;
  for (let i = 0; i < maxLen; i++) {
    awayCum += awayInnings[i] ?? 0;
    homeCum += homeInnings[i] ?? 0;
    const winnerDeficit = awayWon ? homeCum - awayCum : awayCum - homeCum;
    if (winnerDeficit > maxDeficit) maxDeficit = winnerDeficit;
  }
  return maxDeficit;
}

/** True when the home team was tied or trailing entering their final plate-appearance inning and won during it. Direct port of backfillRawStatsMlb.ts's validated logic. */
function isWalkOff(homeInnings: number[], homeWon: boolean, awayFinal: number): boolean {
  if (!homeWon || homeInnings.length === 0) return false;
  const homeCumBeforeLastInning = homeInnings.slice(0, -1).reduce((a, b) => a + b, 0);
  return homeCumBeforeLastInning <= awayFinal;
}

export interface MappedMlbGame {
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  awayLogo?: string;
  homeLogo?: string;
  rubricInputs: MlbRubricInputs;
  standoutPerformers: StandoutPerformerJson[];
}

/** Builds MlbRubricInputs (mlbRubric.ts) straight from ESPN's raw event + summary - only meaningful once the game has actually gone final (linescores/box-score stats are only complete then). */
export function mapMlbEventToGame(event: EspnMlbEvent, summary: EspnMlbSummary): MappedMlbGame {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;
  const awayScore = parseFloat(away.score) || 0;
  const homeScore = parseFloat(home.score) || 0;

  const headerCompetition = summary.header.competitions[0];
  const headerAway: EspnMlbHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "away");
  const headerHome: EspnMlbHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "home");
  const awayInnings = (headerAway?.linescores ?? []).map((l) => parseInt(l.displayValue, 10) || 0);
  const homeInnings = (headerHome?.linescores ?? []).map((l) => parseInt(l.displayValue, 10) || 0);
  const awayWon = headerAway?.winner === true;
  const homeWon = headerHome?.winner === true;

  const boxTeams = summary.boxscore?.teams ?? [];
  const awayBox = boxTeams.find((t) => t.homeAway === "away");
  const homeBox = boxTeams.find((t) => t.homeAway === "home");
  const combinedHomeRuns = (awayBox ? statValue(awayBox, "batting", "homeRuns") : 0) + (homeBox ? statValue(homeBox, "batting", "homeRuns") : 0);
  const noHitter = (awayBox ? statValue(awayBox, "pitching", "hits") === 0 : false) || (homeBox ? statValue(homeBox, "pitching", "hits") === 0 : false);
  const perfectGame =
    (awayBox ? statValue(awayBox, "pitching", "perfectGames") > 0 : false) || (homeBox ? statValue(homeBox, "pitching", "perfectGames") > 0 : false);
  const teamBlanked =
    (awayBox ? statValue(awayBox, "pitching", "shutouts") > 0 : false) || (homeBox ? statValue(homeBox, "pitching", "shutouts") > 0 : false);
  const blownSave =
    (awayBox ? statValue(awayBox, "pitching", "blownSaves") > 0 : false) || (homeBox ? statValue(homeBox, "pitching", "blownSaves") > 0 : false);
  const combinedErrors = (awayBox ? statValue(awayBox, "fielding", "errors") : 0) + (homeBox ? statValue(homeBox, "fielding", "errors") : 0);

  const players = summary.boxscore?.players ?? [];
  const teamNameById: Record<string, string> = {
    [away.team.id]: away.team.displayName,
    [home.team.id]: home.team.displayName
  };
  const maxHomeRunsByPlayer = Math.max(0, ...Object.values(homeRunsByPlayer(players)).map((v) => v.count));

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    awayLogo: away.team.logo,
    homeLogo: home.team.logo,
    standoutPerformers: findStandoutHitters(players, teamNameById),
    rubricInputs: {
      finalMargin: Math.abs(homeScore - awayScore),
      totalRuns: awayScore + homeScore,
      largestDeficitOvercome: largestDeficitOvercome(awayInnings, homeInnings, awayWon),
      walkOff: isWalkOff(homeInnings, homeWon, awayScore),
      extraInningsCount: Math.max(0, Math.max(awayInnings.length, homeInnings.length) - 9),
      combinedHomeRuns,
      maxHomeRunsByPlayer,
      teamBlanked,
      noHitter,
      perfectGame,
      blownSave,
      combinedErrors
    }
  };
}
