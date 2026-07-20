// Extracts NflRubricInputs (nflRubric.ts) from ESPN's raw NFL event +
// summary data - the NFL analogue of mlbGameMapper.ts. The lead-change/
// comeback/decisive-late-score derivation below is a direct port of the
// same logic already validated in nflRubric's calibration pass
// (backfillRawStatsNfl.ts, run against the full 286-game 2025 season) -
// safe to trust here rather than a fresh guess.
import {
  EspnNflEvent,
  EspnNflHeaderCompetitor,
  EspnNflPlayerBoxscore,
  EspnNflScoringPlay,
  EspnNflSummary,
  EspnNflTeamBoxscore
} from "./nflEspnClient";
import { NflRubricInputs } from "./nflRubric";
import { GameStatus, StandoutPerformerJson } from "./types";

export function mapNflEspnState(state: "pre" | "in" | "post"): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

// Always reads displayValue (parsed), never the parallel `value` field -
// confirmed directly against a real response that `value` is the literal
// string "-" for several simple single-number team stats (turnovers
// included), not just for the compound "X-Y" fields where that'd be
// expected. Same "don't trust the field shape at face value" lesson as
// MLB's stats-leader displayValue bug.
function teamStatValue(team: EspnNflTeamBoxscore, statName: string): number {
  const stat = team.statistics.find((s) => s.name === statName);
  if (!stat) return 0;
  return parseInt(stat.displayValue, 10) || 0;
}

/** Sum of a single stat key across every athlete in one team's stat block (e.g. every rushing back's touchdowns). */
function sumPlayerStat(block: { keys: string[]; athletes: Array<{ athlete: { displayName: string }; stats: string[] }> } | undefined, keyName: string): Record<string, number> {
  const result: Record<string, number> = {};
  if (!block) return result;
  const idx = block.keys.indexOf(keyName);
  if (idx === -1) return result;
  for (const athlete of block.athletes) {
    const n = parseInt(athlete.stats[idx] ?? "0", 10) || 0;
    if (n > 0) result[athlete.athlete.displayName] = (result[athlete.athlete.displayName] ?? 0) + n;
  }
  return result;
}

function maxYardsByPlayer(players: EspnNflPlayerBoxscore[], blockName: "passing" | "rushing", yardsKey: string): number {
  let max = 0;
  for (const team of players) {
    const block = team.statistics.find((s) => s.name === blockName);
    if (!block) continue;
    const idx = block.keys.indexOf(yardsKey);
    if (idx === -1) continue;
    for (const athlete of block.athletes) {
      const yards = parseInt(athlete.stats[idx] ?? "0", 10) || 0;
      max = Math.max(max, yards);
    }
  }
  return max;
}

/** Most combined TDs (passing+rushing+receiving) by one single player, matching by display name across all three stat blocks. */
function maxTotalTdsByPlayer(players: EspnNflPlayerBoxscore[]): number {
  let max = 0;
  for (const team of players) {
    const passingTds = sumPlayerStat(team.statistics.find((s) => s.name === "passing"), "passingTouchdowns");
    const rushingTds = sumPlayerStat(team.statistics.find((s) => s.name === "rushing"), "rushingTouchdowns");
    const receivingTds = sumPlayerStat(team.statistics.find((s) => s.name === "receiving"), "receivingTouchdowns");
    const byPlayer: Record<string, number> = {};
    for (const [name, n] of Object.entries(passingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const [name, n] of Object.entries(rushingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const [name, n] of Object.entries(receivingTds)) byPlayer[name] = (byPlayer[name] ?? 0) + n;
    for (const n of Object.values(byPlayer)) {
      if (n > max) max = n;
    }
  }
  return max;
}

/**
 * Every player whose passing or rushing yardage cleared the rubric's own
 * "good"-tier star bar (300+ passing, 125+ rushing - nflRubric.ts's
 * starPoints lowest bracket) - the favorite-player callout needs to check
 * "did *this specific person* have a rubric-recognized standout game,"
 * matching mlbGameMapper.ts's findStandoutHitters' same "reuse the rubric's
 * own threshold" approach rather than inventing a separate bar. Unlike
 * MLB's player boxscore (team only carries an id), NFL's carries the real
 * team.displayName directly - no id->name lookup table needed here.
 */
function findStandoutPerformers(players: EspnNflPlayerBoxscore[]): StandoutPerformerJson[] {
  const performers: StandoutPerformerJson[] = [];
  for (const team of players) {
    const passing = team.statistics.find((s) => s.name === "passing");
    if (passing) {
      const yardsIdx = passing.keys.indexOf("passingYards");
      if (yardsIdx !== -1) {
        for (const athlete of passing.athletes) {
          const yards = parseInt(athlete.stats[yardsIdx] ?? "0", 10) || 0;
          if (yards >= 300) performers.push({ name: athlete.athlete.displayName, line: `${yards} pass yds`, team: team.team.displayName });
        }
      }
    }
    const rushing = team.statistics.find((s) => s.name === "rushing");
    if (rushing) {
      const yardsIdx = rushing.keys.indexOf("rushingYards");
      if (yardsIdx !== -1) {
        for (const athlete of rushing.athletes) {
          const yards = parseInt(athlete.stats[yardsIdx] ?? "0", 10) || 0;
          if (yards >= 125) performers.push({ name: athlete.athlete.displayName, line: `${yards} rush yds`, team: team.team.displayName });
        }
      }
    }
  }
  return performers;
}

/**
 * Walks the chronological scoringPlays timeline once, deriving three facts
 * at the same time - direct port of backfillRawStatsNfl.ts's
 * deriveTimelineFacts, already validated against the real 286-game 2025
 * season. See that file's own comment for the full reasoning behind each of
 * the three derived facts.
 */
function deriveTimelineFacts(
  scoringPlays: EspnNflScoringPlay[],
  awayWon: boolean,
  homeWon: boolean
): { leadChanges: number; largestDeficitOvercome: number; decisiveScoreLate: boolean } {
  let lastRealLeader: "away" | "home" | null = null;
  let leadChanges = 0;
  let maxWinnerDeficit = 0;
  let lastFlip: { period: number; clockValue: number } | null = null;

  for (const play of scoringPlays) {
    const { awayScore: a, homeScore: h } = play;

    if (awayWon && h > a) maxWinnerDeficit = Math.max(maxWinnerDeficit, h - a);
    if (homeWon && a > h) maxWinnerDeficit = Math.max(maxWinnerDeficit, a - h);

    const currentLeader: "away" | "home" | null = a > h ? "away" : h > a ? "home" : null;
    if (currentLeader !== null && currentLeader !== lastRealLeader) {
      if (lastRealLeader !== null) leadChanges++;
      lastRealLeader = currentLeader;
      lastFlip = { period: play.period.number, clockValue: play.clock.value };
    }
  }

  const decisiveScoreLate = lastFlip !== null && (lastFlip.period > 4 || (lastFlip.period === 4 && lastFlip.clockValue <= 120));

  return { leadChanges, largestDeficitOvercome: maxWinnerDeficit, decisiveScoreLate };
}

export interface MappedNflGame {
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  rubricInputs: NflRubricInputs;
  standoutPerformers: StandoutPerformerJson[];
}

/** Builds NflRubricInputs (nflRubric.ts) straight from ESPN's raw event + summary - only meaningful once the game has actually gone final (scoringPlays/box-score stats are only complete then). */
export function mapNflEventToGame(event: EspnNflEvent, summary: EspnNflSummary): MappedNflGame {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const headerCompetition = summary.header.competitions[0];
  const headerAway: EspnNflHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "away");
  const headerHome: EspnNflHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "home");
  const awayScore = parseFloat(headerAway?.score ?? away.score) || 0;
  const homeScore = parseFloat(headerHome?.score ?? home.score) || 0;
  const awayWon = headerAway?.winner === true;
  const homeWon = headerHome?.winner === true;
  const overtimePeriods = Math.max(0, (headerAway?.linescores?.length ?? 4) - 4);

  const scoringPlays = summary.scoringPlays ?? [];
  const { leadChanges, largestDeficitOvercome, decisiveScoreLate } = deriveTimelineFacts(scoringPlays, awayWon, homeWon);

  const boxTeams = summary.boxscore?.teams ?? [];
  const awayBox = boxTeams.find((t) => t.team.displayName === away.team.displayName);
  const homeBox = boxTeams.find((t) => t.team.displayName === home.team.displayName);
  const combinedTurnovers = (awayBox ? teamStatValue(awayBox, "turnovers") : 0) + (homeBox ? teamStatValue(homeBox, "turnovers") : 0);
  const defensiveOrSpecialTeamsTd =
    (awayBox ? teamStatValue(awayBox, "defensiveTouchdowns") : 0) > 0 || (homeBox ? teamStatValue(homeBox, "defensiveTouchdowns") : 0) > 0;

  const players = summary.boxscore?.players ?? [];

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    standoutPerformers: findStandoutPerformers(players),
    rubricInputs: {
      finalMargin: Math.abs(homeScore - awayScore),
      largestDeficitOvercome,
      leadChanges,
      overtimePeriods,
      decisiveScoreLate,
      combinedTurnovers,
      defensiveOrSpecialTeamsTd,
      maxPassingYards: maxYardsByPlayer(players, "passing", "passingYards"),
      maxRushingYards: maxYardsByPlayer(players, "rushing", "rushingYards"),
      maxTotalTdsByPlayer: maxTotalTdsByPlayer(players),
      totalPoints: awayScore + homeScore
    }
  };
}
