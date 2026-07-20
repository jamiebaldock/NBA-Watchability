// Extracts NhlRubricInputs (nhlRubric.ts) from ESPN's raw NHL event +
// summary data - the NHL analogue of nflGameMapper.ts/mlbGameMapper.ts. The
// lead-change/comeback/decisive-late-goal/power-play/hat-trick derivation
// below is a direct port of the same logic already validated in
// backfillRawStatsNhl.ts's calibration pass - safe to trust here rather than
// a fresh guess.
import { EspnNhlEvent, EspnNhlHeaderCompetitor, EspnNhlPlay, EspnNhlPlayerBoxscore, EspnNhlSummary } from "./nhlEspnClient";
import { NhlRubricInputs } from "./nhlRubric";
import { GameStatus, StandoutPerformerJson } from "./types";

export function mapNhlEspnState(state: "pre" | "in" | "post"): GameStatus {
  if (state === "post") return "final";
  if (state === "in") return "live";
  return "upcoming";
}

function clockDisplayToSeconds(display: string): number {
  const [mm, ss] = display.split(":").map((n) => parseInt(n, 10) || 0);
  return mm * 60 + ss;
}

/** Best single-goalie save total in the game, straight off the goalies player-stat block. */
function maxGoalieSaves(players: EspnNhlPlayerBoxscore[]): number {
  let max = 0;
  for (const team of players) {
    const block = team.statistics.find((s) => s.name === "goalies");
    if (!block) continue;
    const savesIdx = block.keys.indexOf("saves");
    if (savesIdx === -1) continue;
    for (const athlete of block.athletes) {
      const saves = parseInt(athlete.stats[savesIdx] ?? "0", 10) || 0;
      max = Math.max(max, saves);
    }
  }
  return max;
}

/**
 * Walks the chronological goal-play timeline once, deriving lead changes,
 * largest-deficit-overcome, decisive-late-goal, combined power-play goals,
 * and per-player goal counts (with the scoring team resolved to a real
 * display name, for the favorite-player standout callout) at the same time -
 * direct port of backfillRawStatsNhl.ts's deriveTimelineFacts, already
 * validated against real ESPN data.
 */
function deriveTimelineFacts(
  goalPlays: EspnNhlPlay[],
  awayWon: boolean,
  homeWon: boolean,
  regulationPeriods: number,
  awayTeamId: string,
  homeTeamId: string,
  awayDisplayName: string,
  homeDisplayName: string
): {
  leadChanges: number;
  largestDeficitOvercome: number;
  decisiveScoreLate: boolean;
  combinedPowerPlayGoals: number;
  maxGoalsByPlayer: number;
  standoutPerformers: StandoutPerformerJson[];
} {
  let lastRealLeader: "away" | "home" | null = null;
  let leadChanges = 0;
  let maxWinnerDeficit = 0;
  let lastFlip: { period: number; clockDisplay: string } | null = null;
  let combinedPowerPlayGoals = 0;
  const goalsByPlayer = new Map<string, { count: number; team: string }>();

  for (const play of goalPlays) {
    const { awayScore: a, homeScore: h } = play;

    if (awayWon && h > a) maxWinnerDeficit = Math.max(maxWinnerDeficit, h - a);
    if (homeWon && a > h) maxWinnerDeficit = Math.max(maxWinnerDeficit, a - h);

    const currentLeader: "away" | "home" | null = a > h ? "away" : h > a ? "home" : null;
    if (currentLeader !== null && currentLeader !== lastRealLeader) {
      if (lastRealLeader !== null) leadChanges++;
      lastRealLeader = currentLeader;
      lastFlip = { period: play.period.number, clockDisplay: play.clock.displayValue };
    }

    if (play.strength?.abbreviation === "power-play") combinedPowerPlayGoals++;

    const scorer = play.participants?.find((p) => p.type === "scorer");
    const teamId = play.team?.id;
    if (scorer && teamId) {
      const teamName = teamId === awayTeamId ? awayDisplayName : teamId === homeTeamId ? homeDisplayName : undefined;
      if (teamName) {
        const existing = goalsByPlayer.get(scorer.athlete.displayName);
        goalsByPlayer.set(scorer.athlete.displayName, { count: (existing?.count ?? 0) + 1, team: teamName });
      }
    }
  }

  const decisiveScoreLate =
    lastFlip !== null &&
    (lastFlip.period > regulationPeriods || (lastFlip.period === regulationPeriods && clockDisplayToSeconds(lastFlip.clockDisplay) <= 120));

  const maxGoalsByPlayer = Array.from(goalsByPlayer.values()).reduce((max, v) => Math.max(max, v.count), 0);

  // Every skater whose goal count in this game cleared the rubric's own
  // "good"-or-better star bar (2+ goals, nhlRubric.ts's starPoints lowest
  // skater bracket) - matches mlbGameMapper.ts's/nflGameMapper.ts's own
  // "reuse the rubric's threshold" approach for the favorite-player callout.
  const standoutPerformers: StandoutPerformerJson[] = Array.from(goalsByPlayer.entries())
    .filter(([, v]) => v.count >= 2)
    .map(([name, v]) => ({ name, line: `${v.count} goal${v.count > 1 ? "s" : ""}`, team: v.team }));

  return { leadChanges, largestDeficitOvercome: maxWinnerDeficit, decisiveScoreLate, combinedPowerPlayGoals, maxGoalsByPlayer, standoutPerformers };
}

export interface MappedNhlGame {
  away: string;
  home: string;
  awayScore: number;
  homeScore: number;
  rubricInputs: NhlRubricInputs;
  standoutPerformers: StandoutPerformerJson[];
}

/** Builds NhlRubricInputs (nhlRubric.ts) straight from ESPN's raw event + summary - only meaningful once the game has actually gone final. */
export function mapNhlEventToGame(event: EspnNhlEvent, summary: EspnNhlSummary): MappedNhlGame {
  const competition = event.competitions[0];
  const away = competition.competitors.find((c) => c.homeAway === "away")!;
  const home = competition.competitors.find((c) => c.homeAway === "home")!;

  const headerCompetition = summary.header.competitions[0];
  const headerAway: EspnNhlHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "away");
  const headerHome: EspnNhlHeaderCompetitor | undefined = headerCompetition?.competitors.find((c) => c.homeAway === "home");
  const awayScore = parseFloat(headerAway?.score ?? away.score) || 0;
  const homeScore = parseFloat(headerHome?.score ?? home.score) || 0;
  const awayWon = headerAway?.winner === true;
  const homeWon = headerHome?.winner === true;
  const regulationPeriods = summary.format?.regulation?.periods ?? 3;
  const overtimePeriods = Math.max(0, (headerAway?.linescores?.length ?? regulationPeriods) - regulationPeriods);

  const allPlays = summary.plays ?? [];
  const goalPlays = allPlays.filter((p) => p.type?.text === "Goal");
  const wentToShootout = allPlays.some((p) => p.period?.displayValue === "SO");

  const { leadChanges, largestDeficitOvercome, decisiveScoreLate, combinedPowerPlayGoals, maxGoalsByPlayer, standoutPerformers } =
    deriveTimelineFacts(
      goalPlays,
      awayWon,
      homeWon,
      regulationPeriods,
      away.team.id,
      home.team.id,
      away.team.displayName,
      home.team.displayName
    );

  const players = summary.boxscore?.players ?? [];

  return {
    away: away.team.displayName,
    home: home.team.displayName,
    awayScore,
    homeScore,
    standoutPerformers,
    rubricInputs: {
      finalMargin: Math.abs(homeScore - awayScore),
      totalGoals: awayScore + homeScore,
      largestDeficitOvercome,
      leadChanges,
      overtimePeriods,
      wentToShootout,
      decisiveScoreLate,
      combinedPowerPlayGoals,
      maxGoalsByPlayer,
      maxGoalieSaves: maxGoalieSaves(players),
      teamShutout: awayScore === 0 || homeScore === 0
    }
  };
}
